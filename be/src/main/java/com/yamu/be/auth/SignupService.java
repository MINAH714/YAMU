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
import com.yamu.be.user.domain.AppUser;
import com.yamu.be.user.domain.AppUserRepository;
import com.yamu.be.user.domain.AppUserStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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
 * 같은 사람은 휴대폰 번호 + 생년월일(UK phone_hash, birth_date)로 찾는다. 실제 은행의 본인확인 고유값(CI)을 대신하는 연출이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignupService {

    private final PhoneVerificationStore verificationStore;
    private final SignupTokenStore tokenStore;
    private final SsafyMemberRegistrar memberRegistrar;
    private final AppUserRepository userRepository;
    private final LinkedAccountRepository accountRepository;
    private final PersonalDataCipher cipher;
    private final LookupHasher lookupHasher;
    private final SignupProperties properties;
    private final Clock clock;

    /** 인증번호를 만든다(문자는 보내지 않음). 휴대폰 번호는 여기서 DB에 저장하지 않고 암호문으로 Redis에만 둔다. */
    public PhoneVerificationResponse requestVerification(PhoneVerificationRequest request) {
        String phone = PersonalDataNormalizer.phone(request.phoneNumber());
        byte[] phoneHash = lookupHasher.phone(phone);
        verificationStore.claimResendSlot(phoneHash);
        VerifiedIdentity identity = new VerifiedIdentity(request.name().strip(), request.birthDate(),
                cipher.encrypt(phone, CipherField.APP_USER_PHONE), phoneHash);
        IssuedVerification issued = verificationStore.issue(identity);
        LocalDateTime expiresAt = LocalDateTime.now(clock).plus(properties.verificationTtl()).truncatedTo(ChronoUnit.SECONDS);
        return new PhoneVerificationResponse(issued.verificationId(), expiresAt,
                properties.exposeDemoCode() ? issued.code() : null);
    }

    /**
     * 인증번호가 맞으면 가입 진행 토큰을 준다. 기존 사용자에 따라 모드와 다음 단계가 달라진다.
     * <ul>
     *   <li>없음·탈퇴 → SIGNUP, 회원가입부터</li>
     *   <li>가입 중(PENDING) → SIGNUP, 멈춘 단계부터(이어하기)</li>
     *   <li>가입 완료(ACTIVE) → DEVICE_RECOVERY, 연결된 계좌로 1원 인증부터</li>
     * </ul>
     */
    public PhoneVerificationConfirmResponse confirmVerification(String verificationId, String code) {
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
     */
    public SignupResponse signup(String signupToken) {
        SignupSession session = tokenStore.find(signupToken)
                .orElseThrow(() -> new BusinessException(ErrorCode.SIGNUP_TOKEN_INVALID));
        if (session.mode() == SignupMode.DEVICE_RECOVERY) {
            throw new BusinessException(ErrorCode.ALREADY_REGISTERED);
        }
        Optional<AppUser> existing = findSamePerson(session.identity());
        if (existing.isPresent()) {
            return resume(signupToken, existing.get());
        }

        // 금융망 호출은 DB 트랜잭션 밖에서 한다. 응답을 기다리는 동안 DB 연결을 잡고 있지 않도록.
        RegisteredMember member = memberRegistrar.register();
        VerifiedIdentity identity = session.identity();
        AppUser user = AppUser.pending(identity.name(), identity.birthDate(), identity.phoneEnc(), identity.phoneHash(),
                session.verifiedAt());
        user.linkFinMember(member.memberId(), cipher.encrypt(member.userKey(), CipherField.APP_USER_SSAFY_USER_KEY));
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // 같은 사람의 가입 요청이 동시에 두 번 와서 다른 쪽이 먼저 저장했다. 방금 만든 SSAFY 계정은 쓰이지 않고 남는다.
            AppUser winner = findSamePerson(identity).orElseThrow(() -> e);
            log.warn("동시 가입 요청으로 SSAFY 계정 {}는 사용하지 않음 (userId={})", member.memberId(), winner.getId());
            return resume(signupToken, winner);
        }
        tokenStore.attachUser(signupToken, user.getId());
        return new SignupResponse(user.getId(), user.getName(), user.getStatus(), true,
                SignupStep.ACCOUNT_LINK.andAfter(), user.getCreatedAt());
    }

    private SignupResponse resume(String signupToken, AppUser user) {
        if (user.getStatus() != AppUserStatus.PENDING) {
            throw new BusinessException(ErrorCode.ALREADY_REGISTERED);
        }
        tokenStore.attachUser(signupToken, user.getId());
        return new SignupResponse(user.getId(), user.getName(), user.getStatus(), user.getSsafyUserKey() != null,
                nextStepOf(user).andAfter(), user.getCreatedAt());
    }

    /** 탈퇴한 사용자는 없는 것으로 본다(명세: WITHDRAWN은 새 가입). */
    private Optional<AppUser> findSamePerson(VerifiedIdentity identity) {
        return userRepository.findByPhoneHashAndBirthDate(identity.phoneHash(), identity.birthDate())
                .filter(user -> user.getStatus() != AppUserStatus.WITHDRAWN);
    }

    /** 가입 중인 사용자가 멈춘 단계. 진행 상황을 따로 저장하지 않고 DB에 있는 것으로 판단한다. */
    private SignupStep nextStepOf(AppUser user) {
        if (!accountRepository.existsByUserIdAndStatus(user.getId(), LinkedAccountStatus.CONNECTED)) {
            return SignupStep.ACCOUNT_LINK;
        }
        return user.getPinHash() == null ? SignupStep.PIN_SETUP : SignupStep.DEVICE_REGISTER;
    }
}
