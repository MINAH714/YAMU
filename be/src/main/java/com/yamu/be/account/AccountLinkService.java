package com.yamu.be.account;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.account.AccountCandidateStore.AccountCandidate;
import com.yamu.be.account.AccountCandidateStore.CandidateScope;
import com.yamu.be.account.AccountVerificationStore.VerifiedAccount;
import com.yamu.be.account.domain.LinkedAccount;
import com.yamu.be.account.domain.LinkedAccountRepository;
import com.yamu.be.account.domain.LinkedAccountStatus;
import com.yamu.be.account.dto.AccountCandidatesResponse;
import com.yamu.be.account.dto.AccountLinkRequest;
import com.yamu.be.account.dto.AccountLinkResponse;
import com.yamu.be.account.dto.AccountLinkResponse.LinkedAccountItem;
import com.yamu.be.account.dto.AccountLinkResponse.SkippedAccount;
import com.yamu.be.account.dto.AccountLinkResponse.VerifiedBy;
import com.yamu.be.account.dto.BankResponse;
import com.yamu.be.account.dto.OneWonAuthRequest;
import com.yamu.be.account.dto.OneWonAuthResponse;
import com.yamu.be.auth.SignupTokenStore;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 계좌 연결: 계좌 목록 → 1원 인증 요청 → 계좌 연결. 명세: 노션 API 명세서 V2 「계좌 연결」 계열(10/30 목록 방식으로 변경, 명세 갱신 필요).
 *
 * <pre>
 * 가입 중   : Bearer signupToken  목록(전체) → 고른 계좌 중 하나로 1원 → 코드 확인 → 고른 계좌를 한꺼번에 연결
 * 가입 후   : Bearer accessToken  목록(아직 연결 안 한 것) → 고른 계좌를 X-Step-Up-Token(ACCOUNT_LINK)으로 연결. 1원 없음
 * 기기 재등록: Bearer signupToken  목록(연결된 것) → 하나로 1원 → 기기 등록 API가 {@link #confirmOneWon}으로 확인. 연결 API는 409
 * </pre>
 *
 * 목록은 이 사용자 userKey의 SSAFY 계좌 목록이라 남의 계좌가 나오지 않는다. 그래서 예금주 확인을 하지 않는다.
 * 1원 인증은 계좌 주인 확인이 아니라 "이 사람이 맞는지"(휴대폰 본인인증이 연출이라)를 확인하는 단계라 가입 때 한 번만 한다.
 * 금융망 호출은 DB 트랜잭션 밖에서 한다. 응답을 기다리는 동안 DB 연결을 잡고 있지 않도록.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountLinkService {

    private static final int AUTH_CODE_LENGTH = 4;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String IDEMPOTENCY_PREFIX = "account:link:idem:";
    private static final Pattern IDEMPOTENCY_KEY_FORMAT = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    /** 같은 사용자·은행·계좌번호 UNIQUE(V20261026_1). */
    private static final String ACCOUNT_IDENTITY_KEY = "uk_account_identity";
    private static final String ALREADY_LINKED = "ALREADY_LINKED";

    private final AccountCandidateStore candidateStore;
    private final AccountVerificationStore verificationStore;
    private final OneWonAttempts attempts;
    private final SsafyAccountApi ssafy;
    private final LinkedAccountRepository accountRepository;
    private final AppUserRepository userRepository;
    private final StepUpService stepUpService;
    private final SignupTokenStore signupTokenStore;
    private final PersonalDataCipher cipher;
    private final LookupHasher lookupHasher;
    private final StringRedisTemplate redis;
    private final RedisLocks redisLocks;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;
    private final AccountLinkProperties properties;
    private final Clock clock;

    /**
     * 연결할 수 있는 계좌 목록. SSAFY 계좌 목록을 읽어 계좌마다 candidateId(10분)를 만든다.
     * 1원 인증 전이라 잔액은 보여 주지 않는다(번호·생년월일만 아는 사람도 가입 진행 토큰을 받을 수 있다).
     */
    public AccountCandidatesResponse candidates(AccountActor actor) {
        AppUser user = requireUser(actor);
        CandidateScope scope = scopeOf(actor);
        List<SsafyAccountApi.Account> accounts = ssafy.accounts(userKeyOf(user));
        Set<String> connected = accountRepository.findByUserIdAndStatus(user.getId(), LinkedAccountStatus.CONNECTED)
                .stream().map(a -> identity(a.getBankCode(), a.getAccountNoHash())).collect(Collectors.toSet());

        List<AccountCandidate> shown = new ArrayList<>();
        List<Boolean> linkedFlags = new ArrayList<>();
        for (SsafyAccountApi.Account account : accounts) {
            byte[] hash = lookupHasher.account(account.bankCode(), account.accountNo());
            boolean linked = connected.contains(identity(account.bankCode(), hash));
            // 가입 후 추가는 아직 연결 안 한 계좌만, 기기 재등록은 연결해 둔 계좌만(새 계좌로는 남이 내 계정을 가져갈 수 있다).
            if ((scope == CandidateScope.MEMBER && linked) || (scope == CandidateScope.DEVICE_RECOVERY && !linked)) {
                continue;
            }
            shown.add(new AccountCandidate(user.getId(), scope, account.bankCode(), account.bankName(),
                    cipher.encrypt(account.accountNo(), CipherField.LINKED_ACCOUNT_NO), hash, last4(account.accountNo()),
                    account.accountName()));
            linkedFlags.add(linked);
        }
        List<String> ids = candidateStore.issue(shown);
        List<AccountCandidatesResponse.Candidate> items = new ArrayList<>(shown.size());
        for (int i = 0; i < shown.size(); i++) {
            AccountCandidate c = shown.get(i);
            items.add(new AccountCandidatesResponse.Candidate(ids.get(i), bank(c.bankCode(), c.bankName()),
                    mask(c.last4()), c.accountName(), linkedFlags.get(i)));
        }
        return new AccountCandidatesResponse(items, now().plus(properties.verificationTtl()));
    }

    /**
     * 고른 계좌 중 primaryCandidateId로 1원을 보내고 verificationToken(10분)을 준다. 가입 중·기기 재등록에서만 쓴다.
     *
     * <ul>
     *   <li>오늘 이 계좌로 코드를 10번 틀렸으면 429 AUTH_CODE_LOCKED, 같은 계좌로 1분 안이면 429 AUTH_REQUEST_TOO_FREQUENT,
     *       오늘 5번 보냈으면 429 ONE_WON_DAILY_LIMIT_EXCEEDED. 재요청 자리(SET NX)가 같은 계좌의 동시 발송도 하나로 막는다</li>
     *   <li>금융망이 처리하지 않은 게 확실하면(요청 안 감·거절) 1분 자리와 하루 횟수를 돌려줘 바로 다시 요청할 수 있다</li>
     *   <li>결과 불명(시간 초과 등)이면 1원이 실제로 나갔을 수 있다. 보낸 것으로 기록하고(1분 자리·하루 횟수 유지)
     *       {@code deliveryUncertain=true}로 토큰을 준다. 오류로 끝내면 앱이 토큰을 몰라 받은 코드를 쓸 수 없고, 곧장 다시 보내 1원이 두 번 나간다</li>
     * </ul>
     */
    public OneWonAuthResponse requestOneWon(AccountActor actor, OneWonAuthRequest request) {
        if (actor.me() != null) {
            // 가입 후 추가 연결은 1원 없이 본인 인증으로 한다.
            throw fieldError("candidateIds", "가입 후 계좌 추가는 1원 인증 없이 본인 인증으로 연결합니다.");
        }
        CandidateScope scope = scopeOf(actor);
        List<String> ids = distinct(request.candidateIds());
        if (!ids.contains(request.primaryCandidateId())) {
            throw fieldError("primaryCandidateId", "1원을 받을 계좌는 고른 계좌 중 하나여야 합니다.");
        }
        if (scope == CandidateScope.DEVICE_RECOVERY && ids.size() != 1) {
            throw fieldError("candidateIds", "기기 재등록은 연결해 둔 계좌 하나로 인증합니다.");
        }
        // 1원 받는 계좌를 맨 앞에 둔다. 처음 연결이면 먼저 저장돼 기준 계좌가 된다.
        List<String> ordered = new ArrayList<>(List.of(request.primaryCandidateId()));
        ids.stream().filter(id -> !id.equals(request.primaryCandidateId())).forEach(ordered::add);
        List<AccountCandidate> selected = resolve(actor.userId(), ordered, scope);
        AccountCandidate primary = selected.get(0);
        AppUser user = requireUser(actor);

        String accountKey = primary.accountKey();
        attempts.requireNotLocked(accountKey);
        attempts.claimResendSlot(accountKey);
        try {
            attempts.claimDailySend(accountKey, scope == CandidateScope.DEVICE_RECOVERY
                    ? properties.recoveryDailySendLimit() : properties.dailySendLimit());
        } catch (BusinessException e) {
            attempts.releaseResendSlot(accountKey);
            throw e;
        }
        VerifiedAccount pending = VerifiedAccount.of(primary, selected);
        String token = verificationStore.issue(pending);
        boolean uncertain = false;
        try {
            ssafy.sendOneWon(userKeyOf(user), accountNoOf(pending), properties.authText());
        } catch (FinApiException e) {
            if (e.getOutcome() != FinApiOutcome.UNKNOWN) {
                giveBack(token, accountKey);
                throw e;
            }
            log.warn("1원 송금 결과 불명({}) → 보낸 것으로 기록", e.getApiName());
            uncertain = true;
        } catch (RuntimeException e) {
            // SSAFY가 거절(계좌 오류 등)했거나 보내기 전에 실패했다.
            giveBack(token, accountKey);
            throw e;
        }
        LocalDateTime requestedAt = markSent(token, pending)
                .orElseThrow(() -> new BusinessException(ErrorCode.VERIFICATION_TOKEN_EXPIRED));
        String hint = uncertain
                ? "1원이 늦게 도착할 수 있습니다. 거래내역에 없으면 1분 뒤 다시 요청하세요"
                : "계좌 거래내역에서 '" + properties.authText() + "' 뒤 숫자 " + AUTH_CODE_LENGTH + "자리를 확인하세요";
        // 임시(S15P31E103-350): 시연 계좌는 사용자가 거래내역을 볼 수 없어 숫자를 응답으로 준다. 결과 불명이면 찾지 않는다.
        String demoCode = properties.exposeDemoCode() && !uncertain
                ? ssafy.findOneWonCode(userKeyOf(user), accountNoOf(pending), properties.authText(), AUTH_CODE_LENGTH,
                        LocalDate.now(clock.withZone(KST))).orElse(null)
                : null;
        return new OneWonAuthResponse(token, bank(primary.bankCode(), primary.bankName()), mask(primary.last4()),
                requestedAt, requestedAt.plus(properties.authCodeValidity()), properties.authText(), AUTH_CODE_LENGTH,
                hint, uncertain, demoCode);
    }

    /**
     * 계좌를 연결한다(201). 가입 중은 1원 인증으로 고른 계좌를 한꺼번에, 가입 후는 본인 인증으로 고른 계좌를 연결한다.
     *
     * <ul>
     *   <li>고른 것 중 이미 연결된 계좌는 건너뛰고 {@code skipped}로 알린다. 하나도 새로 연결하지 못하면 409 ACCOUNT_ALREADY_LINKED</li>
     *   <li>Idempotency-Key가 같으면 다시 연결하지 않고 처음 결과를 돌려준다. 같은 키로 다른 요청을 보내면 409 IDEMPOTENCY_KEY_REUSED</li>
     *   <li>같은 키의 첫 요청이 아직 처리 중이면 409 IDEMPOTENCY_IN_PROGRESS. 끝난 뒤 다시 부르면 처음 결과를 받는다</li>
     *   <li>가입 후 본인 인증 토큰은 입력·후보 확인을 마친 뒤, 실제 연결 직전에 쓴다</li>
     * </ul>
     */
    public AccountLinkResponse link(AccountActor actor, AccountLinkRequest request, String idempotencyKey,
            String stepUpToken) {
        if (actor.isDeviceRecovery()) {
            // 기기 재등록은 이미 연결된 계좌로만 오고, 인증코드는 기기 등록 API로 보낸다.
            throw new BusinessException(ErrorCode.ACCOUNT_ALREADY_LINKED);
        }
        requireIdempotencyKey(idempotencyKey);
        String fingerprint = fingerprint(actor, request);
        Optional<LinkResult> previous = findPrevious(actor.userId(), idempotencyKey);
        if (previous.isPresent()) {
            return replay(actor.userId(), previous.get(), fingerprint);
        }
        Optional<RedisLocks.Lock> lock = redisLocks.tryLock(idempotencyKey(actor.userId(), idempotencyKey) + ":lock",
                properties.idempotencyLockTtl());
        if (lock.isEmpty()) {
            // 앞 요청이 그 사이 끝났으면 그 결과를, 아직 처리 중이면 409.
            return findPrevious(actor.userId(), idempotencyKey)
                    .map(done -> replay(actor.userId(), done, fingerprint))
                    .orElseThrow(() -> new BusinessException(ErrorCode.IDEMPOTENCY_IN_PROGRESS));
        }
        try (RedisLocks.Lock held = lock.get()) {
            Optional<LinkResult> done = findPrevious(actor.userId(), idempotencyKey);
            if (done.isPresent()) {
                return replay(actor.userId(), done.get(), fingerprint);
            }
            return actor.me() == null
                    ? linkDuringSignup(actor, request, idempotencyKey, fingerprint)
                    : linkAsMember(actor, request, idempotencyKey, stepUpToken, fingerprint);
        }
    }

    private AccountLinkResponse linkDuringSignup(AccountActor actor, AccountLinkRequest request, String idempotencyKey,
            String fingerprint) {
        if (isBlank(request.verificationToken())) {
            throw fieldError("verificationToken", "1원 인증 결과(verificationToken)가 필요합니다.");
        }
        if (isBlank(request.authCode())) {
            throw fieldError("authCode", "인증코드가 필요합니다.");
        }
        requireUser(actor);
        VerifiedAccount verified = confirmOneWon(actor.userId(), request.verificationToken(), request.authCode());
        // 고른 계좌가 모두 이미 연결돼 있어도 실패로 끝내지 않는다. 계좌를 연결해 두고 멈춘 가입을 새 토큰으로 이어갈 때
        // 1원 인증을 다시 하는 경로라서(PIN·기기 등록은 이 세션의 1원 인증을 요구한다).
        Linked linked = linkAll(actor.userId(), verified.selected(), null, true);
        // 결과를 먼저 기억하고 토큰을 지운다. 반대 순서면 기억이 실패했을 때 연결은 됐는데 재시도가 "토큰 만료"가 된다.
        remember(actor.userId(), idempotencyKey, LinkResult.of(fingerprint, linked, null));
        finishOneWon(request.verificationToken(), verified);
        signupTokenStore.markOneWonVerified(actor.signupToken());
        return response(actor.userId(), linked, null);
    }

    private AccountLinkResponse linkAsMember(AccountActor actor, AccountLinkRequest request, String idempotencyKey,
            String stepUpToken, String fingerprint) {
        if (request.candidateIds() == null || request.candidateIds().isEmpty()) {
            throw fieldError("candidateIds", "연결할 계좌를 하나 이상 골라 주세요.");
        }
        if (isBlank(stepUpToken)) {
            throw new BusinessException(ErrorCode.STEP_UP_REQUIRED);
        }
        requireUser(actor);
        List<String> ids = distinct(request.candidateIds());
        List<AccountCandidate> selected = resolve(actor.userId(), ids, CandidateScope.MEMBER);
        // 전부 이미 연결돼 있으면 본인 인증을 쓰기 전에 끝낸다.
        boolean anyNew = selected.stream().anyMatch(c -> findConnected(actor.userId(), c).isEmpty());
        if (!anyNew) {
            throw new BusinessException(ErrorCode.ACCOUNT_ALREADY_LINKED);
        }
        StepUpGrant grant = stepUpService.requireStepUp(actor.me(), StepUpPurpose.ACCOUNT_LINK, stepUpToken);
        Linked linked = linkAll(actor.userId(), selected, grant.auditLogId(), false);
        remember(actor.userId(), idempotencyKey, LinkResult.of(fingerprint, linked, grant));
        candidateStore.delete(ids);
        return response(actor.userId(), linked, new VerifiedBy(grant.method(), grant.auditLogId(), linked.linkedAt()));
    }

    /**
     * 고른 계좌를 한 트랜잭션에서 연결한다. 사용자 행을 잠가(FOR UPDATE) 같은 사용자의 연결 요청을 한 줄로 세운다.
     * 그래서 같은 계좌를 동시에 연결해도 늦은 쪽은 "이미 연결"을 보고 건너뛴다.
     *
     * @param allowNone 하나도 새로 연결하지 못해도 성공으로 볼지. 가입 중(1원 재인증)은 true, 가입 후 추가는 false(409)
     */
    private Linked linkAll(Long userId, List<AccountCandidate> selected, Long auditLogId, boolean allowNone) {
        LocalDateTime linkedAt = now();
        return transaction.execute(status -> {
            AppUser user = userRepository.findByIdForUpdate(userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            // 요청 시작 때 토큰 검사를 통과했어도 잠금을 기다리는 사이 탈퇴했을 수 있다. 탈퇴한 사용자에게 데이터를 다시 남기지 않는다.
            if (user.isWithdrawn()) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED);
            }
            List<LinkedRow> rows = new ArrayList<>();
            List<LinkedAccount> saved = new ArrayList<>();
            List<SkippedAccount> skipped = new ArrayList<>();
            for (AccountCandidate candidate : selected) {
                Optional<LinkedAccount> existing = accountRepository.findByUserIdAndBankCodeAndAccountNoHash(userId,
                        candidate.bankCode(), candidate.accountNoHash());
                if (existing.filter(LinkedAccount::isConnected).isPresent()) {
                    skipped.add(new SkippedAccount(bank(candidate.bankCode(), candidate.bankName()),
                            mask(candidate.last4()), ALREADY_LINKED));
                    continue;
                }
                LinkedAccount account = save(existing, userId, candidate, auditLogId, linkedAt);
                saved.add(account);
                rows.add(new LinkedRow(account.getId(), candidate.bankName()));
            }
            if (rows.isEmpty() && !allowNone) {
                throw new BusinessException(ErrorCode.ACCOUNT_ALREADY_LINKED);
            }
            // 기준 계좌가 없으면 이번에 새로 연결한 첫 계좌를 기준 계좌로 둔다(FR-033). 가입 중에는 1원을 받은 계좌가 맨 앞이다.
            // 사용자 행을 잠근 채라 처음 연결이 동시에 와도 기준 계좌는 하나만 생긴다. 연결과 같은 트랜잭션이라
            // 멱등 결과를 기억하기 전에 정해지고, 재응답(replay)도 같은 값을 읽는다.
            if (!saved.isEmpty() && accountRepository.findByUserIdAndPrimaryTrue(userId).isEmpty()) {
                saved.get(0).markPrimary(true);
                accountRepository.saveAndFlush(saved.get(0));
            }
            return new Linked(rows, skipped, linkedAt);
        });
    }

    /**
     * 1원 인증코드를 확인한다. 계좌 연결과 기기 재등록(기기 등록 API)이 같이 쓴다. 맞으면 확인한 계좌를 돌려주고, 토큰은 지우지 않는다
     * (호출한 쪽이 할 일을 마친 뒤 {@link #finishOneWon}).
     *
     * <ul>
     *   <li>토큰이 없거나 만료·남의 것이면 400 VERIFICATION_TOKEN_EXPIRED</li>
     *   <li>1원을 아직 안 보냈거나 시도를 다 썼거나 SSAFY가 만료라고 하면 400 AUTH_CODE_EXPIRED(1원 인증부터 다시)</li>
     *   <li>틀리면 400 AUTH_CODE_MISMATCH(details.remainingAttempts). 하루 10번째면 429 AUTH_CODE_LOCKED</li>
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
     * 기기 재등록용. 1원 인증코드를 확인한 뒤 확인 결과를 꺼내면서 지운다(한 번만 쓴다). 같은 토큰으로 동시에 등록해도
     * 하나만 통과하고 나머지는 400 VERIFICATION_TOKEN_EXPIRED. 그 계좌가 지금도 연결 상태인지는 호출한 쪽이 트랜잭션 안에서 다시 본다.
     * 꺼낸 뒤 기기 등록이 실패하면 1원 인증부터 다시 해야 한다(토큰을 되살리면 동시 요청을 막을 수 없다).
     */
    public VerifiedAccount claimForRecovery(Long userId, String verificationToken, String authCode) {
        VerifiedAccount confirmed = confirmOneWon(userId, verificationToken, authCode);
        verificationStore.take(verificationToken, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VERIFICATION_TOKEN_EXPIRED));
        attempts.clear(confirmed.accountKey());
        return confirmed;
    }

    /** 1원 인증으로 할 일(연결·기기 등록)을 마쳤으면 토큰과 재요청 간격·시도 횟수를 지운다. 하루 횟수는 남긴다. */
    public void finishOneWon(String verificationToken, VerifiedAccount account) {
        verificationStore.delete(verificationToken);
        attempts.clear(account.accountKey());
    }

    /**
     * 처음 연결이면 새 행, 끊었던 계좌면 그 행을 다시 연결한다.
     * 409로 바꾸는 것은 같은 계좌 UNIQUE(uk_account_identity) 위반과 다시 연결 경합(@Version)뿐이다. 다른 DB 오류(FK 등)를
     * "이미 연결"로 가리지 않게 그대로 던진다.
     */
    private LinkedAccount save(Optional<LinkedAccount> existing, Long userId, AccountCandidate candidate, Long auditLogId,
            LocalDateTime now) {
        try {
            if (existing.isPresent()) {
                LinkedAccount reconnected = existing.get();
                reconnected.reconnect(null, auditLogId, now);
                return accountRepository.saveAndFlush(reconnected);
            }
            return accountRepository.saveAndFlush(LinkedAccount.connect(userId, candidate.bankCode(),
                    candidate.accountNoEnc(), candidate.accountNoHash(), candidate.last4(), null, auditLogId, now));
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

    /** 처음 연결의 결과를 다시 보여 준다. 금융망은 다시 부르지 않는다. */
    private AccountLinkResponse replay(Long userId, LinkResult previous, String fingerprint) {
        if (!previous.fingerprint().equals(fingerprint)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        List<LinkedRow> rows = previous.accounts();
        LocalDateTime linkedAt = rows.isEmpty() ? now() : accountRepository.findByIdAndUserId(rows.get(0).accountId(), userId)
                .map(LinkedAccount::getConnectedAt).orElse(now());
        Linked linked = new Linked(rows, previous.skipped(), linkedAt);
        // 본인 인증 토큰은 처음 요청에서 이미 썼다. 같은 기록을 다시 보여 준다.
        VerifiedBy verifiedBy = previous.auditLogId() == null ? null
                : new VerifiedBy(previous.method(), previous.auditLogId(), linkedAt);
        return response(userId, linked, verifiedBy);
    }

    /** 연결한 계좌를 고른 순서대로 보여 준다. 기준 계좌 여부는 저장된 칸(is_primary)을 읽는다. */
    private AccountLinkResponse response(Long userId, Linked linked, VerifiedBy verifiedBy) {
        Map<Long, LinkedAccount> byId = accountRepository.findAllById(
                linked.accounts().stream().map(LinkedRow::accountId).toList()).stream()
                .filter(account -> account.getUserId().equals(userId))
                .collect(Collectors.toMap(LinkedAccount::getId, account -> account));
        List<LinkedAccountItem> items = new ArrayList<>();
        for (LinkedRow row : linked.accounts()) {
            LinkedAccount account = byId.get(row.accountId());
            if (account != null) {
                items.add(new LinkedAccountItem(account.getId(), bank(account.getBankCode(), row.bankName()),
                        account.getAccountAlias(), mask(account.getAccountLast4()), account.isPrimary(),
                        account.getConnectedAt().truncatedTo(ChronoUnit.SECONDS)));
            }
        }
        return new AccountLinkResponse(items, linked.skipped(), verifiedBy);
    }

    /** 후보 번호들을 이 사용자·쓰임새의 후보로 바꾼다. 하나라도 없거나 남의 것이면 400 ACCOUNT_CANDIDATE_EXPIRED(목록부터 다시). */
    private List<AccountCandidate> resolve(Long userId, List<String> ids, CandidateScope scope) {
        List<AccountCandidate> resolved = new ArrayList<>(ids.size());
        for (String id : ids) {
            resolved.add(candidateStore.find(id, userId, scope)
                    .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_CANDIDATE_EXPIRED)));
        }
        return resolved;
    }

    private Optional<LinkedAccount> findConnected(Long userId, AccountCandidate candidate) {
        return accountRepository.findByUserIdAndBankCodeAndAccountNoHash(userId, candidate.bankCode(),
                candidate.accountNoHash()).filter(LinkedAccount::isConnected);
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

    /**
     * 같은 요청인지 가리는 값. 가입 중은 verificationToken, 가입 후는 고른 후보(순서 무관)의 해시.
     * 원문(토큰·후보 번호)을 Redis에 남기지 않으려고 해시로 둔다.
     */
    private static String fingerprint(AccountActor actor, AccountLinkRequest request) {
        String source = actor.me() == null
                ? "v:" + nullToEmpty(request.verificationToken())
                : "c:" + (request.candidateIds() == null ? "" : request.candidateIds().stream().sorted().distinct()
                        .collect(Collectors.joining(",")));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
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
            throw fieldError("Idempotency-Key", message);
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

    /** 1원이 나가지 않은 게 확실할 때 1분 자리와 하루 횟수를 돌려주고, 만든 토큰을 지운다. */
    private void giveBack(String verificationToken, String accountKey) {
        verificationStore.delete(verificationToken);
        attempts.releaseResendSlot(accountKey);
        attempts.refundDailySend(accountKey);
    }

    private static CandidateScope scopeOf(AccountActor actor) {
        if (actor.me() != null) {
            return CandidateScope.MEMBER;
        }
        return actor.isDeviceRecovery() ? CandidateScope.DEVICE_RECOVERY : CandidateScope.SIGNUP;
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

    /** SSAFY 계정이 없는 사용자는 금융망을 부를 수 없다. 시드 사용자는 모두 있어서, 여기 오면 시드가 덜 들어간 것이다. */
    private String userKeyOf(AppUser user) {
        if (user.getSsafyUserKey() == null || user.getSsafyMemberId() == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        return cipher.decrypt(user.getSsafyUserKey(), CipherField.APP_USER_SSAFY_USER_KEY);
    }

    private String accountNoOf(VerifiedAccount account) {
        return cipher.decrypt(account.accountNoEnc(), CipherField.LINKED_ACCOUNT_NO);
    }

    private static List<String> distinct(List<String> ids) {
        return new ArrayList<>(new LinkedHashSet<>(ids));
    }

    private static String identity(String bankCode, byte[] accountHash) {
        return bankCode + ":" + HexFormat.of().formatHex(accountHash);
    }

    private static String last4(String accountNo) {
        return accountNo.length() <= 4 ? accountNo : accountNo.substring(accountNo.length() - 4);
    }

    private static BusinessException fieldError(String field, String message) {
        return new BusinessException(ErrorCode.INVALID_REQUEST,
                Map.of("fieldErrors", List.of(new FieldErrorResponse(field, message))));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static BankResponse bank(String code, String name) {
        return new BankResponse(code, name == null || name.isBlank() ? code : name);
    }

    private static String mask(String last4) {
        return "****-****-****-" + (last4 == null ? "****" : last4);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    }

    /** 연결한 계좌 번호와 은행 이름(SSAFY 목록 값). Idempotency 결과로 Redis에 JSON으로 남는다. */
    record LinkedRow(Long accountId, String bankName) {
    }

    private record Linked(List<LinkedRow> accounts, List<SkippedAccount> skipped, LocalDateTime linkedAt) {
    }

    /** Idempotency-Key로 기억해 두는 첫 연결 결과. 요청 원문 대신 해시(fingerprint)로 같은 요청인지 본다. */
    record LinkResult(String fingerprint, List<LinkedRow> accounts, List<SkippedAccount> skipped, AuthMethod method,
            Long auditLogId) {

        static LinkResult of(String fingerprint, Linked linked, StepUpGrant grant) {
            return new LinkResult(fingerprint, linked.accounts(), linked.skipped(),
                    grant == null ? null : grant.method(), grant == null ? null : grant.auditLogId());
        }
    }
}
