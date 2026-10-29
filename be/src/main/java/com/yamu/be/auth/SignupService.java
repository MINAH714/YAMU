package com.yamu.be.auth;

import com.yamu.be.account.domain.LinkedAccountRepository;
import com.yamu.be.account.domain.LinkedAccountStatus;
import com.yamu.be.auth.PhoneVerificationStore.IssuedVerification;
import com.yamu.be.auth.SignupTokenStore.IssuedSignupToken;
import com.yamu.be.auth.SignupTokenStore.SignupSession;
import com.yamu.be.auth.SsafyMemberRegistrar.RegisteredMember;
import com.yamu.be.auth.domain.SignupMode;
import com.yamu.be.auth.domain.SignupStep;
import com.yamu.be.auth.dto.PhoneVerificationConfirmResponse;
import com.yamu.be.auth.dto.PhoneVerificationRequest;
import com.yamu.be.auth.dto.PhoneVerificationResponse;
import com.yamu.be.auth.dto.SignupResponse;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.LookupHasher;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.crypto.PersonalDataNormalizer;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.redis.RedisLocks;
import com.yamu.be.user.domain.AppUser;
import com.yamu.be.user.domain.AppUserRepository;
import com.yamu.be.user.domain.AppUserStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 가입 앞 단계: 휴대폰 본인인증(연출) 요청 → 확인(가입 진행 토큰) → 회원가입(PENDING 사용자 + SSAFY 계정). DEC-001.
 *
 * <pre>
 * 본인인증 요청 → 본인인증 확인(signupToken) → 회원가입 → 계좌 연결(1원) → PIN 설정 → 기기 등록(ACTIVE)
 * </pre>
 *
 * 같은 사람은 이름 + 생년월일 + 휴대폰 번호로 본다(명세 「휴대폰 본인인증 확인」). 찾기는 UK(phone_hash, birth_date)로 하고,
 * 이름이 다르면 다른 사람으로 보고 그 계정을 이어받지 않는다. 실제 은행의 본인확인 고유값(CI)을 대신하는 연출이다.
 *
 * <p>임시 결정: 문자를 보내지 않아 인증번호를 응답(demoCode)으로 주는 동안은 휴대폰 소유를 증명하지 않는다.
 * 그래서 이름·생년월일·번호를 아는 사람은 가입 진행 토큰을 받을 수 있고, 모드(SIGNUP·DEVICE_RECOVERY)로 가입 여부도 알 수 있다.
 * 실제 확인은 뒤 단계의 1원 인증(본인 명의 계좌)이 맡는다. 무작위 대량 시도는 세 API를 합친 IP별 호출 제한으로 막는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignupService {

    /** signup:lock:{phoneHash hex} → 같은 사람의 회원가입을 한 번에 하나만(TTL lockTtl). */
    private static final String LOCK_PREFIX = "signup:lock:";

    private final PhoneVerificationStore verificationStore;
    private final SignupTokenStore tokenStore;
    private final SsafyMemberRegistrar memberRegistrar;
    private final AppUserRepository userRepository;
    private final LinkedAccountRepository accountRepository;
    private final PersonalDataCipher cipher;
    private final LookupHasher lookupHasher;
    private final SignupProperties properties;
    private final LoginRateLimiter rateLimiter;
    private final RedisLocks redisLocks;
    private final Clock clock;

    /** 인증번호를 만든다(문자는 보내지 않음). 휴대폰 번호는 여기서 DB에 저장하지 않고 암호문으로 Redis에만 둔다. */
    public PhoneVerificationResponse requestVerification(PhoneVerificationRequest request, ClientInfo client) {
        rateLimiter.checkSignup(client.ipAddress(), properties.perIp());
        String phone = PersonalDataNormalizer.phone(request.phoneNumber());
        byte[] phoneHash = lookupHasher.phone(phone);
        verificationStore.claimResendSlot(phoneHash);
        VerifiedIdentity identity = new VerifiedIdentity(request.name(), request.birthDate(),
                cipher.encrypt(phone, CipherField.APP_USER_PHONE), phoneHash);
        IssuedVerification issued = verificationStore.issue(identity);
        LocalDateTime expiresAt = LocalDateTime.now(clock).plus(properties.verificationTtl()).truncatedTo(ChronoUnit.SECONDS);
        return new PhoneVerificationResponse(issued.verificationId(), expiresAt,
                properties.exposeDemoCode() ? issued.code() : null);
    }

    /**
     * 인증번호가 맞으면 가입 진행 토큰을 준다. 기존 사용자에 따라 모드와 다음 단계가 달라진다.
     * <ul>
     *   <li>없음 → SIGNUP, 회원가입부터</li>
     *   <li>가입 중(PENDING) → SIGNUP, 멈춘 단계부터(이어하기)</li>
     *   <li>가입 완료(ACTIVE) → DEVICE_RECOVERY, 연결된 계좌로 1원 인증부터</li>
     *   <li>번호·생년월일은 같은데 이름이 다름 → 409 IDENTITY_MISMATCH(그 계정의 정보는 알려주지 않음)</li>
     *   <li>탈퇴(WITHDRAWN) → 409 REJOIN_NOT_AVAILABLE</li>
     * </ul>
     */
    public PhoneVerificationConfirmResponse confirmVerification(String verificationId, String code, ClientInfo client) {
        rateLimiter.checkSignup(client.ipAddress(), properties.perIp());
        VerifiedIdentity identity = verificationStore.confirm(verificationId, code);
        LocalDateTime verifiedAt = LocalDateTime.now(clock);
        Optional<AppUser> existing = findSamePerson(identity);

        SignupMode mode = SignupMode.SIGNUP;
        SignupStep nextStep = SignupStep.SIGNUP;
        Long userId = null;
        if (existing.isPresent()) {
            AppUser user = existing.get();
            userId = user.getId();
            if (user.isActive()) {
                mode = SignupMode.DEVICE_RECOVERY;
                nextStep = SignupStep.ACCOUNT_LINK;
            } else {
                nextStep = nextStepOf(user);
            }
        }
        IssuedSignupToken token = tokenStore.issue(new SignupSession(mode, identity, verifiedAt, userId));
        return new PhoneVerificationConfirmResponse(token.token(), token.expiresIn(), mode, nextStep);
    }

    /**
     * PENDING 사용자를 만들고 SSAFY 계정을 연결한다. SSAFY 계정 생성이 실패하면 사용자도 만들지 않는다(502·504 그대로).
     * 이미 가입 중인 사람이 다시 부르면 새로 만들지 않고 그 사용자를 돌려준다(앱 재시도·연타).
     *
     * <ul>
     *   <li>탈퇴한 사람은 SSAFY를 부르기 전에 409 REJOIN_NOT_AVAILABLE. 행이 남아 있어 저장할 수 없는데 SSAFY 계정만 버려지기 때문이다</li>
     *   <li>같은 사람의 가입이 동시에 오면 하나만 SSAFY를 부르고 나머지는 409 SIGNUP_IN_PROGRESS(잠시 뒤 다시 부르면 이어받음)</li>
     * </ul>
     */
    public SignupResponse signup(String signupToken, ClientInfo client) {
        rateLimiter.checkSignup(client.ipAddress(), properties.perIp());
        SignupSession session = tokenStore.find(signupToken)
                .orElseThrow(() -> new BusinessException(ErrorCode.SIGNUP_TOKEN_INVALID));
        if (session.mode() == SignupMode.DEVICE_RECOVERY) {
            throw new BusinessException(ErrorCode.ALREADY_REGISTERED);
        }
        VerifiedIdentity identity = session.identity();
        Optional<AppUser> existing = findSamePerson(identity);
        if (existing.isPresent()) {
            return resume(signupToken, existing.get(), identity);
        }

        // 같은 사람의 두 요청이 동시에 "아직 사용자 없음"을 보고 둘 다 SSAFY 계정을 만들지 않게 잠근다.
        try (RedisLocks.Lock lock = redisLocks.tryLock(LOCK_PREFIX + HexFormat.of().formatHex(identity.phoneHash()),
                properties.lockTtl()).orElseThrow(() -> new BusinessException(ErrorCode.SIGNUP_IN_PROGRESS))) {
            // 잠그는 사이 다른 요청이 저장을 끝냈을 수 있다.
            existing = findSamePerson(identity);
            if (existing.isPresent()) {
                return resume(signupToken, existing.get(), identity);
            }
            return create(signupToken, session);
        }
    }

    private SignupResponse create(String signupToken, SignupSession session) {
        VerifiedIdentity identity = session.identity();
        // 금융망 호출은 DB 트랜잭션 밖에서 한다. 응답을 기다리는 동안 DB 연결을 잡고 있지 않도록.
        RegisteredMember member = memberRegistrar.register(session.pendingMemberId(),
                memberId -> tokenStore.rememberPendingMember(signupToken, memberId));
        AppUser user = AppUser.pending(identity.name(), identity.birthDate(), identity.phoneEnc(), identity.phoneHash(),
                session.verifiedAt());
        user.linkFinMember(member.memberId(), cipher.encrypt(member.userKey(), CipherField.APP_USER_SSAFY_USER_KEY));
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // 잠금 시간이 지난 뒤 같은 사람의 다른 요청이 먼저 저장한 경우. 이 예외의 메시지에는 중복된 키 값(생년월일 등)이
            // 들어 있어 로그·응답에 남기지 않는다.
            AppUser winner = findSamePerson(identity).orElseThrow(() -> new IllegalStateException(
                    "회원가입 저장 실패: 같은 사람의 사용자를 찾지 못함 (SSAFY 계정 " + member.memberId() + ")"));
            log.warn("동시 가입 요청으로 SSAFY 계정 {}는 사용하지 않음 (userId={})", member.memberId(), winner.getId());
            return resume(signupToken, winner, identity);
        }
        tokenStore.attachUser(signupToken, user.getId());
        return new SignupResponse(user.getId(), identity.name(), user.getStatus(), true,
                SignupStep.ACCOUNT_LINK.andAfter(), user.getCreatedAt());
    }

    /** 응답 이름은 DB 값이 아니라 본인인증 때 받은 값이다. 이름이 같은 사람만 여기에 오지만 DB 값을 내보낼 이유가 없다. */
    private SignupResponse resume(String signupToken, AppUser user, VerifiedIdentity identity) {
        if (user.getStatus() != AppUserStatus.PENDING) {
            throw new BusinessException(ErrorCode.ALREADY_REGISTERED);
        }
        tokenStore.attachUser(signupToken, user.getId());
        return new SignupResponse(user.getId(), identity.name(), user.getStatus(), user.getSsafyUserKey() != null,
                nextStepOf(user).andAfter(), user.getCreatedAt());
    }

    /**
     * 이름·생년월일·번호가 모두 같은 사용자. 없으면 empty.
     * 이름이 다르면 409 IDENTITY_MISMATCH, 탈퇴한 사람이면 409 REJOIN_NOT_AVAILABLE이다.
     * 탈퇴 행이 UK(phone_hash, birth_date)를 쥐고 있어 새로 저장할 수 없으므로, SSAFY를 부르기 전에 여기서 멈춘다.
     */
    private Optional<AppUser> findSamePerson(VerifiedIdentity identity) {
        Optional<AppUser> found = userRepository.findByPhoneHashAndBirthDate(identity.phoneHash(), identity.birthDate());
        if (found.isEmpty()) {
            return found;
        }
        AppUser user = found.get();
        if (user.getStatus() == AppUserStatus.WITHDRAWN) {
            throw new BusinessException(ErrorCode.REJOIN_NOT_AVAILABLE);
        }
        if (!identity.sameNameAs(user.getName())) {
            throw new BusinessException(ErrorCode.IDENTITY_MISMATCH);
        }
        return found;
    }

    /** 가입 중인 사용자가 멈춘 단계. 진행 상황을 따로 저장하지 않고 DB에 있는 것으로 판단한다. */
    private SignupStep nextStepOf(AppUser user) {
        if (!accountRepository.existsByUserIdAndStatus(user.getId(), LinkedAccountStatus.CONNECTED)) {
            return SignupStep.ACCOUNT_LINK;
        }
        return user.getPinHash() == null ? SignupStep.PIN_SETUP : SignupStep.DEVICE_REGISTER;
    }
}
