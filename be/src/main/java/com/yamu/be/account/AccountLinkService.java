package com.yamu.be.account;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.account.AccountVerificationStore.VerifiedAccount;
import com.yamu.be.account.domain.LinkedAccount;
import com.yamu.be.account.domain.LinkedAccountRepository;
import com.yamu.be.account.domain.LinkedAccountStatus;
import com.yamu.be.account.dto.AccountLinkRequest;
import com.yamu.be.account.dto.AccountLinkResponse;
import com.yamu.be.account.dto.AccountLinkResponse.VerifiedBy;
import com.yamu.be.account.dto.AccountVerifyResponse;
import com.yamu.be.account.dto.BankResponse;
import com.yamu.be.account.dto.OneWonAuthResponse;
import com.yamu.be.auth.StepUpService;
import com.yamu.be.auth.StepUpTokenStore.StepUpGrant;
import com.yamu.be.auth.domain.AuthMethod;
import com.yamu.be.auth.domain.StepUpPurpose;
import com.yamu.be.global.common.ApiResponse.FieldErrorResponse;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.LookupHasher;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.finapi.FinApiException;
import com.yamu.be.user.domain.AppUser;
import com.yamu.be.user.domain.AppUserRepository;
import com.yamu.be.user.domain.AppUserStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * 계좌 연결 3단계: 계좌 확인(예금주) → 1원 인증 요청 → 계좌 연결. 명세: 노션 API 명세서 V2 「계좌 확인」「1원 인증 요청」「계좌 연결」.
 *
 * <pre>
 * 가입 중   : Bearer signupToken            → 연결(본인 인증 기록 없음. 휴대폰 본인인증·1원 인증이 대신함)
 * 가입 후   : Bearer accessToken + X-Step-Up-Token(ACCOUNT_LINK) → 연결(본인 인증 기록 저장)
 * 기기 재등록: Bearer signupToken            → 이미 연결된 계좌만 확인·1원 인증. 연결 대신 기기 등록 API가 {@link #confirmOneWon}을 쓴다
 * </pre>
 *
 * 금융망 호출은 DB 트랜잭션 밖에서 한다. 응답을 기다리는 동안 DB 연결을 잡고 있지 않도록.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountLinkService {

    private static final Pattern ACCOUNT_NO = Pattern.compile("\\d{16}");
    private static final int AUTH_CODE_LENGTH = 4;
    private static final String IDEMPOTENCY_PREFIX = "account:link:idem:";

    private final AccountVerificationStore verificationStore;
    private final OneWonAttempts attempts;
    private final SsafyAccountApi ssafy;
    private final LinkedAccountRepository accountRepository;
    private final AppUserRepository userRepository;
    private final StepUpService stepUpService;
    private final PersonalDataCipher cipher;
    private final LookupHasher lookupHasher;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AccountLinkProperties properties;
    private final Clock clock;

    /**
     * 1단계. 예금주가 이 사용자의 SSAFY 계정인지 확인하고 verificationToken(10분)을 준다.
     * SSAFY 예금주명(userName)은 실제 이름이 아니라 SSAFY 계정 ID의 {@code @} 앞부분이라, 이름이 아니라 그것으로 비교한다.
     */
    public AccountVerifyResponse verify(AccountActor actor, String accountNo) {
        if (accountNo == null || !ACCOUNT_NO.matcher(accountNo).matches()) {
            throw new BusinessException(ErrorCode.INVALID_ACCOUNT_NUMBER);
        }
        AppUser user = requireUser(actor);
        SsafyAccountApi.Holder holder = ssafy.holder(userKeyOf(user), accountNo);
        if (!holder.userName().equals(memberLocalPart(user))) {
            throw new BusinessException(ErrorCode.HOLDER_MISMATCH);
        }
        byte[] accountHash = lookupHasher.account(holder.bankCode(), accountNo);
        boolean linked = findConnected(user.getId(), holder.bankCode(), accountHash).isPresent();
        if (actor.isDeviceRecovery() && !linked) {
            // 기기 재등록은 원래 쓰던 계좌로만 본인을 확인한다. 새 계좌로는 남이 내 계정을 가져갈 수 있다.
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_LINKED);
        }
        if (!actor.isDeviceRecovery() && linked) {
            throw new BusinessException(ErrorCode.ACCOUNT_ALREADY_LINKED);
        }
        String last4 = accountNo.substring(accountNo.length() - 4);
        String token = verificationStore.issue(new VerifiedAccount(user.getId(), holder.bankCode(), holder.bankName(),
                cipher.encrypt(accountNo, CipherField.LINKED_ACCOUNT_NO), accountHash, last4, null, false));
        return new AccountVerifyResponse(token, now().plus(properties.verificationTtl()),
                bank(holder.bankCode(), holder.bankName()), mask(last4), maskName(user.getName()), true, linked);
    }

    /**
     * 2단계. 1원을 보낸다. 같은 계좌로 1분 안에 다시 부르면 429. 보내면 시도 횟수를 처음부터 세고 verificationToken을 10분으로 늘린다.
     * 금융망 실패는 502·504 그대로이며, 그때는 1분을 기다리지 않고 바로 다시 요청할 수 있다.
     */
    public OneWonAuthResponse requestOneWon(AccountActor actor, String verificationToken) {
        VerifiedAccount account = requireVerified(actor.userId(), verificationToken);
        AppUser user = requireUser(actor);
        attempts.claimResendSlot(account.accountKey());
        try {
            ssafy.sendOneWon(userKeyOf(user), accountNoOf(account), properties.authText());
        } catch (RuntimeException e) {
            attempts.releaseResendSlot(account.accountKey());
            throw e;
        }
        LocalDateTime requestedAt = now();
        if (!verificationStore.update(verificationToken,
                account.withAuthRequested(requestedAt.toInstant(ZoneOffset.UTC).toEpochMilli()), true)) {
            throw new BusinessException(ErrorCode.VERIFICATION_TOKEN_EXPIRED);
        }
        attempts.resetTries(account.accountKey());
        return new OneWonAuthResponse(verificationToken, requestedAt, requestedAt.plus(properties.authCodeValidity()),
                properties.authText(), AUTH_CODE_LENGTH,
                "계좌 거래내역에서 '" + properties.authText() + "' 뒤 숫자 " + AUTH_CODE_LENGTH + "자리를 확인하세요");
    }

    /**
     * 3단계. 인증코드를 확인하고 계좌를 연결한다(201).
     *
     * <ul>
     *   <li>Idempotency-Key가 같으면 다시 연결하지 않고 처음 결과를 돌려준다. 같은 키로 다른 계좌를 보내면 409 IDEMPOTENCY_KEY_REUSED</li>
     *   <li>가입 후에는 X-Step-Up-Token(ACCOUNT_LINK)이 필요하다. 인증코드가 맞은 뒤에 쓴다(틀린 코드로 본인 인증을 버리지 않게)</li>
     *   <li>본인 인증 토큰이 없어 실패해도 맞힌 인증코드는 기억해 두어, 본인 인증만 하고 다시 부르면 된다</li>
     * </ul>
     */
    public AccountLinkResponse link(AccountActor actor, AccountLinkRequest request, String idempotencyKey,
            String stepUpToken) {
        if (actor.isDeviceRecovery()) {
            // 기기 재등록은 이미 연결된 계좌로만 오고, 인증코드는 기기 등록 API로 보낸다.
            throw new BusinessException(ErrorCode.ACCOUNT_ALREADY_LINKED);
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, Map.of("fieldErrors",
                    List.of(new FieldErrorResponse("Idempotency-Key", "Idempotency-Key 헤더가 필요합니다."))));
        }
        String tokenHash = AccountVerificationStore.hash(request.verificationToken());
        Optional<LinkResult> previous = findPrevious(actor.userId(), idempotencyKey);
        if (previous.isPresent()) {
            return replay(actor.userId(), previous.get(), tokenHash);
        }
        if (actor.me() != null && (stepUpToken == null || stepUpToken.isBlank())) {
            throw new BusinessException(ErrorCode.STEP_UP_REQUIRED);
        }

        AppUser user = requireUser(actor);
        VerifiedAccount account = confirmOneWon(actor.userId(), request.verificationToken(), request.authCode());
        Optional<LinkedAccount> existing = accountRepository.findByUserIdAndBankCodeAndAccountNoHash(
                user.getId(), account.bankCode(), account.accountNoHash());
        if (existing.filter(LinkedAccount::isConnected).isPresent()) {
            throw new BusinessException(ErrorCode.ACCOUNT_ALREADY_LINKED);
        }
        StepUpGrant grant = actor.me() == null ? null
                : stepUpService.requireStepUp(actor.me(), StepUpPurpose.ACCOUNT_LINK, stepUpToken);
        LocalDateTime linkedAt = now();
        String alias = request.accountName() == null || request.accountName().isBlank() ? null : request.accountName().strip();
        Long auditLogId = grant == null ? null : grant.auditLogId();

        LinkedAccount saved = save(existing, user.getId(), account, alias, auditLogId, linkedAt);
        verificationStore.delete(request.verificationToken());
        attempts.clear(account.accountKey());
        remember(actor.userId(), idempotencyKey,
                new LinkResult(tokenHash, saved.getId(), account.bankName(), grant == null ? null : grant.method()));

        VerifiedBy verifiedBy = grant == null ? null : new VerifiedBy(grant.method(), grant.auditLogId(), linkedAt);
        return response(saved, account.bankName(), userKeyOf(user), verifiedBy);
    }

    /**
     * 1원 인증코드를 확인한다. 계좌 연결과 기기 재등록(기기 등록 API)이 같이 쓴다. 맞으면 확인한 계좌를 돌려주고, 토큰은 지우지 않는다
     * (호출한 쪽이 할 일을 마친 뒤 {@link AccountVerificationStore#delete}).
     *
     * <ul>
     *   <li>토큰이 없거나 만료·남의 것이면 400 VERIFICATION_TOKEN_EXPIRED</li>
     *   <li>1원을 아직 안 보냈거나 시도를 다 썼거나 SSAFY가 만료라고 하면 400 AUTH_CODE_EXPIRED(1원 인증부터 다시)</li>
     *   <li>틀리면 400 AUTH_CODE_MISMATCH(details.remainingAttempts)</li>
     * </ul>
     */
    public VerifiedAccount confirmOneWon(Long userId, String verificationToken, String authCode) {
        VerifiedAccount account = requireVerified(userId, verificationToken);
        if (account.codeVerified()) {
            return account;
        }
        if (account.authRequestedAt() == null) {
            throw new BusinessException(ErrorCode.AUTH_CODE_EXPIRED);
        }
        int remaining = attempts.consumeTry(account.accountKey());
        AppUser user = userRepository.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        ssafy.checkCode(userKeyOf(user), accountNoOf(account), properties.authText(), authCode, remaining);
        VerifiedAccount verified = account.withCodeVerified();
        verificationStore.update(verificationToken, verified, false);
        return verified;
    }

    /** 처음 연결이면 새 행, 끊었던 계좌면 그 행을 다시 연결한다. 같은 계좌를 동시에 두 번 연결하면 늦은 쪽은 409. */
    private LinkedAccount save(Optional<LinkedAccount> existing, Long userId, VerifiedAccount account, String alias,
            Long auditLogId, LocalDateTime now) {
        try {
            if (existing.isPresent()) {
                LinkedAccount reconnected = existing.get();
                reconnected.reconnect(alias, auditLogId, now);
                return accountRepository.saveAndFlush(reconnected);
            }
            return accountRepository.saveAndFlush(LinkedAccount.connect(userId, account.bankCode(),
                    account.accountNoEnc(), account.accountNoHash(), account.last4(), alias, auditLogId, now));
        } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException e) {
            throw new BusinessException(ErrorCode.ACCOUNT_ALREADY_LINKED);
        }
    }

    private AccountLinkResponse replay(Long userId, LinkResult previous, String tokenHash) {
        if (!previous.tokenHash().equals(tokenHash)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        LinkedAccount account = accountRepository.findByIdAndUserId(previous.accountId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        AppUser user = userRepository.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        // 본인 인증 토큰은 처음 요청에서 이미 썼다. 같은 기록을 다시 보여 준다.
        VerifiedBy verifiedBy = account.getStepUpAuditLogId() == null ? null
                : new VerifiedBy(previous.method(), account.getStepUpAuditLogId(), account.getConnectedAt());
        return response(account, previous.bankName(), userKeyOf(user), verifiedBy);
    }

    /** 잔액은 응답에만 넣고 저장하지 않는다. 연결은 이미 끝났으니 잔액 조회가 실패해도 연결을 되돌리지 않고 null로 준다. */
    private AccountLinkResponse response(LinkedAccount account, String bankName, String userKey, VerifiedBy verifiedBy) {
        Long balance = null;
        LocalDateTime balanceAsOf = null;
        try {
            balance = ssafy.balance(userKey, cipher.decrypt(account.getAccountNoEnc(), CipherField.LINKED_ACCOUNT_NO));
            balanceAsOf = now();
        } catch (FinApiException e) {
            log.warn("계좌 {} 연결 뒤 잔액 조회 실패({}) → 잔액 없이 응답", account.getId(), e.getOutcome());
        }
        boolean primary = accountRepository.findFirstByUserIdAndStatusOrderByIdAsc(account.getUserId(),
                        LinkedAccountStatus.CONNECTED)
                .map(first -> first.getId().equals(account.getId()))
                .orElse(false);
        return new AccountLinkResponse(account.getId(), bank(account.getBankCode(), bankName), account.getAccountAlias(),
                mask(account.getAccountLast4()), balance, balanceAsOf, primary, verifiedBy,
                account.getConnectedAt().truncatedTo(ChronoUnit.SECONDS));
    }

    private Optional<LinkResult> findPrevious(Long userId, String idempotencyKey) {
        String json = redis.opsForValue().get(idempotencyKey(userId, idempotencyKey));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, LinkResult.class));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    private void remember(Long userId, String idempotencyKey, LinkResult result) {
        try {
            redis.opsForValue().set(idempotencyKey(userId, idempotencyKey), objectMapper.writeValueAsString(result),
                    properties.idempotencyTtl());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 키에 사용자를 넣어 다른 사람이 같은 키를 써도 섞이지 않게 한다. */
    private static String idempotencyKey(Long userId, String key) {
        return IDEMPOTENCY_PREFIX + userId + ":" + key;
    }

    private VerifiedAccount requireVerified(Long userId, String verificationToken) {
        return verificationStore.find(verificationToken, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VERIFICATION_TOKEN_EXPIRED));
    }

    private AppUser requireUser(AccountActor actor) {
        return userRepository.findById(actor.userId())
                .filter(user -> user.getStatus() != AppUserStatus.WITHDRAWN)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private Optional<LinkedAccount> findConnected(Long userId, String bankCode, byte[] accountHash) {
        return accountRepository.findByUserIdAndBankCodeAndAccountNoHash(userId, bankCode, accountHash)
                .filter(LinkedAccount::isConnected);
    }

    /** SSAFY 계정이 없는 사용자(시드로 넣은 가입 중 사용자 등)는 금융망을 부를 수 없다. */
    private String userKeyOf(AppUser user) {
        if (user.getSsafyUserKey() == null || user.getSsafyMemberId() == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        return cipher.decrypt(user.getSsafyUserKey(), CipherField.APP_USER_SSAFY_USER_KEY);
    }

    private String accountNoOf(VerifiedAccount account) {
        return cipher.decrypt(account.accountNoEnc(), CipherField.LINKED_ACCOUNT_NO);
    }

    private static String memberLocalPart(AppUser user) {
        String memberId = user.getSsafyMemberId();
        int at = memberId.indexOf('@');
        return at < 0 ? memberId : memberId.substring(0, at);
    }

    private static BankResponse bank(String code, String name) {
        return new BankResponse(code, name == null || name.isBlank() ? code : name);
    }

    private static String mask(String last4) {
        return "****-****-****-" + (last4 == null ? "****" : last4);
    }

    /** 가운데 글자를 가린다. 예: 김야무 → 김*무, 김무 → 김*, 남궁야무 → 남**무 */
    static String maskName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        if (name.length() <= 2) {
            return name.charAt(0) + "*";
        }
        return name.charAt(0) + "*".repeat(name.length() - 2) + name.charAt(name.length() - 1);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    }

    /** Idempotency-Key로 기억해 두는 첫 연결 결과. 토큰 원문 대신 해시로 같은 요청인지 본다. */
    record LinkResult(String tokenHash, Long accountId, String bankName, AuthMethod method) {
    }
}
