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
import com.yamu.be.global.finapi.FinApiOutcome;
import com.yamu.be.global.redis.RedisLocks;
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
    private static final Pattern IDEMPOTENCY_KEY_FORMAT = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    /** 같은 사용자·은행·계좌번호 UNIQUE(V20261026_1). */
    private static final String ACCOUNT_IDENTITY_KEY = "uk_account_identity";

    private final AccountVerificationStore verificationStore;
    private final OneWonAttempts attempts;
    private final SsafyAccountApi ssafy;
    private final LinkedAccountRepository accountRepository;
    private final AppUserRepository userRepository;
    private final StepUpService stepUpService;
    private final PersonalDataCipher cipher;
    private final LookupHasher lookupHasher;
    private final StringRedisTemplate redis;
    private final RedisLocks redisLocks;
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
     * 2단계. 1원을 보낸다. 보내면 시도 횟수를 처음부터 세고 verificationToken을 10분으로 늘린다.
     *
     * <ul>
     *   <li>오늘 이 계좌로 코드를 10번 틀렸으면 429 AUTH_CODE_LOCKED, 같은 계좌로 1분 안이면 429 AUTH_REQUEST_TOO_FREQUENT,
     *       오늘 5번 보냈으면 429 ONE_WON_DAILY_LIMIT_EXCEEDED. 재요청 자리(SET NX)가 같은 계좌의 동시 발송도 하나로 막는다</li>
     *   <li>금융망이 처리하지 않은 게 확실하면(요청 안 감·거절) 1분 자리와 하루 횟수를 돌려줘 바로 다시 요청할 수 있다</li>
     *   <li>결과 불명(시간 초과 등)이면 1원이 실제로 나갔을 수 있다. 보낸 것으로 기록해(1분 자리·하루 횟수 유지, 보낸 시각 저장)
     *       받은 코드로 바로 연결할 수 있게 하고, 504 등 오류는 그대로 알린다. 돌려주면 앱이 곧장 다시 보내 1원이 두 번 나간다</li>
     * </ul>
     */
    public OneWonAuthResponse requestOneWon(AccountActor actor, String verificationToken) {
        VerifiedAccount account = requireVerified(actor.userId(), verificationToken);
        AppUser user = requireUser(actor);
        String accountKey = account.accountKey();
        attempts.requireNotLocked(accountKey);
        attempts.claimResendSlot(accountKey);
        try {
            attempts.claimDailySend(accountKey);
        } catch (BusinessException e) {
            attempts.releaseResendSlot(accountKey);
            throw e;
        }
        try {
            ssafy.sendOneWon(userKeyOf(user), accountNoOf(account), properties.authText());
        } catch (FinApiException e) {
            if (e.getOutcome() == FinApiOutcome.UNKNOWN) {
                log.warn("1원 송금 결과 불명({}) → 보낸 것으로 기록", e.getApiName());
                markSent(verificationToken, account);
                throw e;
            }
            giveBack(accountKey);
            throw e;
        } catch (RuntimeException e) {
            // SSAFY가 거절(계좌 오류 등)했거나 보내기 전에 실패했다.
            giveBack(accountKey);
            throw e;
        }
        LocalDateTime requestedAt = markSent(verificationToken, account)
                .orElseThrow(() -> new BusinessException(ErrorCode.VERIFICATION_TOKEN_EXPIRED));
        return new OneWonAuthResponse(verificationToken, requestedAt, requestedAt.plus(properties.authCodeValidity()),
                properties.authText(), AUTH_CODE_LENGTH,
                "계좌 거래내역에서 '" + properties.authText() + "' 뒤 숫자 " + AUTH_CODE_LENGTH + "자리를 확인하세요");
    }

    /**
     * 3단계. 인증코드를 확인하고 계좌를 연결한다(201).
     *
     * <ul>
     *   <li>Idempotency-Key가 같으면 다시 연결하지 않고 처음 결과를 돌려준다. 같은 키로 다른 계좌를 보내면 409 IDEMPOTENCY_KEY_REUSED</li>
     *   <li>같은 키의 첫 요청이 아직 처리 중이면 409 IDEMPOTENCY_IN_PROGRESS. 끝난 뒤 다시 부르면 처음 결과를 받는다
     *       (처리 중 표시가 없으면 두 요청이 모두 진행해, 늦은 쪽이 이미 쓴 본인 인증 토큰 때문에 403을 받았다)</li>
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
        requireIdempotencyKey(idempotencyKey);
        String tokenHash = AccountVerificationStore.hash(request.verificationToken());
        Optional<LinkResult> previous = findPrevious(actor.userId(), idempotencyKey);
        if (previous.isPresent()) {
            return replay(actor.userId(), previous.get(), tokenHash);
        }
        Optional<RedisLocks.Lock> lock = redisLocks.tryLock(idempotencyKey(actor.userId(), idempotencyKey) + ":lock",
                properties.idempotencyLockTtl());
        if (lock.isEmpty()) {
            // 앞 요청이 그 사이 끝났으면 그 결과를, 아직 처리 중이면 409.
            return findPrevious(actor.userId(), idempotencyKey)
                    .map(done -> replay(actor.userId(), done, tokenHash))
                    .orElseThrow(() -> new BusinessException(ErrorCode.IDEMPOTENCY_IN_PROGRESS));
        }
        try (RedisLocks.Lock held = lock.get()) {
            Optional<LinkResult> done = findPrevious(actor.userId(), idempotencyKey);
            if (done.isPresent()) {
                return replay(actor.userId(), done.get(), tokenHash);
            }
            return linkOnce(actor, request, idempotencyKey, stepUpToken, tokenHash);
        }
    }

    private AccountLinkResponse linkOnce(AccountActor actor, AccountLinkRequest request, String idempotencyKey,
            String stepUpToken, String tokenHash) {
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
        // 결과를 먼저 기억하고 토큰을 지운다. 반대 순서면 기억이 실패했을 때 연결은 됐는데 재시도가 "토큰 만료"가 된다.
        remember(actor.userId(), idempotencyKey,
                new LinkResult(tokenHash, saved.getId(), account.bankName(), grant == null ? null : grant.method()));
        verificationStore.delete(request.verificationToken());
        attempts.clear(account.accountKey());

        VerifiedBy verifiedBy = grant == null ? null : new VerifiedBy(grant.method(), grant.auditLogId(), linkedAt);
        return response(saved, account.bankName(), userKeyOf(user), verifiedBy, true);
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
        attempts.requireNotLocked(account.accountKey());
        int remaining = attempts.consumeTry(account.accountKey());
        AppUser user = userRepository.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        try {
            ssafy.checkCode(userKeyOf(user), accountNoOf(account), properties.authText(), authCode, remaining);
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.AUTH_CODE_MISMATCH) {
                // 하루 한도에 닿으면 여기서 429 AUTH_CODE_LOCKED로 바뀐다.
                attempts.recordFailure(account.accountKey());
            }
            throw e;
        }
        VerifiedAccount verified = account.withCodeVerified();
        verificationStore.update(verificationToken, verified, false);
        return verified;
    }

    /**
     * 처음 연결이면 새 행, 끊었던 계좌면 그 행을 다시 연결한다. 같은 계좌를 동시에 두 번 연결하면 늦은 쪽은 409.
     * 409로 바꾸는 것은 같은 계좌 UNIQUE(uk_account_identity) 위반과 다시 연결 경합(@Version)뿐이다. 다른 DB 오류(FK 등)를
     * "이미 연결"로 가리지 않게 그대로 던진다.
     */
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
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new BusinessException(ErrorCode.ACCOUNT_ALREADY_LINKED);
        } catch (DataIntegrityViolationException e) {
            if (isSameAccountDuplicate(e)) {
                throw new BusinessException(ErrorCode.ACCOUNT_ALREADY_LINKED);
            }
            throw e;
        }
    }

    private static boolean isSameAccountDuplicate(DataIntegrityViolationException e) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.contains(ACCOUNT_IDENTITY_KEY);
    }

    /** 처음 연결의 결과를 다시 보여 준다. 금융망은 다시 부르지 않는다(잔액은 비움). 재시도마다 SSAFY를 부를 이유가 없다. */
    private AccountLinkResponse replay(Long userId, LinkResult previous, String tokenHash) {
        if (!previous.tokenHash().equals(tokenHash)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        LinkedAccount account = accountRepository.findByIdAndUserId(previous.accountId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        // 본인 인증 토큰은 처음 요청에서 이미 썼다. 같은 기록을 다시 보여 준다.
        VerifiedBy verifiedBy = account.getStepUpAuditLogId() == null ? null
                : new VerifiedBy(previous.method(), account.getStepUpAuditLogId(), account.getConnectedAt());
        return response(account, previous.bankName(), null, verifiedBy, false);
    }

    /**
     * 잔액은 응답에만 넣고 저장하지 않는다. 연결은 이미 끝났으니 잔액 조회가 실패해도 연결을 되돌리지 않고 null로 준다.
     *
     * @param fetchBalance false면 금융망을 부르지 않고 잔액을 비운다(재응답)
     */
    private AccountLinkResponse response(LinkedAccount account, String bankName, String userKey, VerifiedBy verifiedBy,
            boolean fetchBalance) {
        Long balance = null;
        LocalDateTime balanceAsOf = null;
        if (fetchBalance) {
            try {
                balance = ssafy.balance(userKey, cipher.decrypt(account.getAccountNoEnc(), CipherField.LINKED_ACCOUNT_NO));
                balanceAsOf = now();
            } catch (FinApiException e) {
                log.warn("계좌 {} 연결 뒤 잔액 조회 실패({}) → 잔액 없이 응답", account.getId(), e.getOutcome());
            }
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

    /** 영문·숫자·_·- 8~64자. 형식을 보지 않으면 아주 긴 값이나 ':' 같은 글자가 그대로 Redis 키에 들어간다. */
    private static void requireIdempotencyKey(String idempotencyKey) {
        String message = idempotencyKey == null || idempotencyKey.isBlank()
                ? "Idempotency-Key 헤더가 필요합니다."
                : IDEMPOTENCY_KEY_FORMAT.matcher(idempotencyKey).matches() ? null
                : "Idempotency-Key는 영문·숫자·_·- 8~64자입니다.";
        if (message != null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, Map.of("fieldErrors",
                    List.of(new FieldErrorResponse("Idempotency-Key", message))));
        }
    }

    /** 1원을 보낸 것으로 기록한다(보낸 시각, 시도 횟수 처음부터). 그 사이 토큰이 만료됐으면 empty. */
    private Optional<LocalDateTime> markSent(String verificationToken, VerifiedAccount account) {
        LocalDateTime requestedAt = now();
        boolean updated = verificationStore.update(verificationToken,
                account.withAuthRequested(requestedAt.toInstant(ZoneOffset.UTC).toEpochMilli()), true);
        attempts.resetTries(account.accountKey());
        return updated ? Optional.of(requestedAt) : Optional.empty();
    }

    /** 1원이 나가지 않은 게 확실할 때 1분 자리와 하루 횟수를 돌려준다. */
    private void giveBack(String accountKey) {
        attempts.releaseResendSlot(accountKey);
        attempts.refundDailySend(accountKey);
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
