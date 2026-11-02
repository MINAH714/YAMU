package com.yamu.be.auth;

import com.yamu.be.auth.PhoneVerificationStore.IssuedVerification;
import com.yamu.be.auth.SignupTokenStore.IssuedSignupToken;
import com.yamu.be.auth.SignupTokenStore.SignupSession;
import com.yamu.be.auth.domain.SignupMode;
import com.yamu.be.auth.domain.SignupStep;
import com.yamu.be.auth.dto.PhoneVerificationConfirmResponse;
import com.yamu.be.auth.dto.PhoneVerificationRequest;
import com.yamu.be.auth.dto.PhoneVerificationResponse;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.LookupHasher;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.crypto.PersonalDataNormalizer;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.user.domain.AppUser;
import com.yamu.be.user.domain.AppUserRepository;
import com.yamu.be.user.RejoinService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 가입 앞 단계: 휴대폰 본인인증(연출) 요청 → 확인(가입 진행 토큰). DEC-001.
 *
 * <pre>
 * 본인인증 요청 → 본인인증 확인(signupToken) → 계좌 연결(1원) → PIN 설정 → 기기 등록(ACTIVE)
 * </pre>
 *
 * <p>시연 전용(10/30 결정): 시드가 미리 넣어 둔 사람(PENDING + SSAFY 회원·계좌)만 가입할 수 있다. 회원가입 때 SSAFY 회원을
 * 새로 만들지 않는다. 새로 만든 SSAFY 회원에는 계좌가 없어 계좌 연결 단계를 넘을 수 없기 때문이다.
 *
 * <p>같은 사람은 이름 + 생년월일 + 휴대폰 번호로 본다(명세 「휴대폰 본인인증 확인」). 찾기는 UK(phone_hash, birth_date)로 하고,
 * 이름이 다르면 다른 사람으로 보고 그 계정을 이어받지 않는다. 실제 은행의 본인확인 고유값(CI)을 대신하는 연출이다.
 *
 * <p>무엇이 드러나는가: 이름이 다르면 시드에 없는 사람과 똑같은 404 DEMO_USER_NOT_FOUND를 준다. 그래서 번호·생년월일만으로는
 * 가입·탈퇴 여부를 알 수 없다. 이름·생년월일·번호 세 값을 모두 아는 사람에게만 가입 여부(모드 SIGNUP·DEVICE_RECOVERY)가 드러난다.
 *
 * <p>탈퇴한 사람(11번, 10/30 결정 (B)): 탈퇴 때 이름 원문은 지우고 이름 해시만 남긴다(임시, !55 리뷰 반영). 번호·생년월일·이름이
 * 모두 맞는 탈퇴 행이면 새 PENDING 사용자를 만들어 다시 가입시킨다({@link RejoinService}). 예전 이력은 옛 계정에 남는다.
 * 이름을 보지 않으면 번호·생년월일만 아는 남이 재가입을 먼저 확정해(되돌릴 수 없음) 본인이 영구히 막히기 때문이다.
 *
 * <p>임시 결정: 문자를 보내지 않아 인증번호를 응답(demoCode)으로 주는 동안은 휴대폰 소유를 증명하지 않는다.
 * 그래서 세 값을 아는 사람은 가입 진행 토큰을 받을 수 있다. 실제 확인은 뒤 단계의 1원 인증(본인 명의 계좌)이 맡는다.
 * 무작위 대량 시도는 두 API를 합친 IP별 호출 제한으로 막는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignupService {

    private final PhoneVerificationStore verificationStore;
    private final SignupTokenStore tokenStore;
    private final AppUserRepository userRepository;
    private final RejoinService rejoinService;
    private final PersonalDataCipher cipher;
    private final LookupHasher lookupHasher;
    private final SignupProperties properties;
    private final LoginRateLimiter rateLimiter;
    private final TransactionTemplate transaction;
    private final Clock clock;

    /**
     * 인증번호를 만든다(문자는 보내지 않음). 휴대폰 번호는 여기서 DB에 저장하지 않고 암호문으로 Redis에만 둔다.
     * 시드에 있는 사람인지는 여기서 보지 않는다. 요청만으로 등록 여부를 알 수 없게, 인증번호를 확인한 뒤에 본다.
     */
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
     * 인증번호가 맞으면 시드 사용자를 찾아 가입 진행 토큰을 준다. 토큰은 그 사용자에 묶인다.
     * <ul>
     *   <li>가입 중(PENDING, SSAFY 회원 있음) → SIGNUP, 항상 계좌 연결(1원 인증)부터. 본인인증 시각을 기록한다.
     *       전에 계좌를 연결해 두고 멈췄어도 새 토큰으로는 1원 인증을 다시 한다(본인인증이 연출이라, 남이 이어받아
     *       PIN·기기를 등록하지 못하게. {@link SignupProgress})</li>
     *   <li>가입 완료(ACTIVE) → DEVICE_RECOVERY, 연결된 계좌로 1원 인증부터</li>
     *   <li>탈퇴(WITHDRAWN) → 새 PENDING 사용자로 재가입, SIGNUP(계좌 연결부터)</li>
     *   <li>없음, 이름이 다름, 시드가 덜 들어감(SSAFY 회원 없음) → 모두 같은 404 DEMO_USER_NOT_FOUND</li>
     * </ul>
     * 같은 인증번호로 동시에 확인하면 Redis에서 꺼내면서 지우므로 하나만 성공한다.
     */
    public PhoneVerificationConfirmResponse confirmVerification(String verificationId, String code, ClientInfo client) {
        rateLimiter.checkSignup(client.ipAddress(), properties.perIp());
        VerifiedIdentity identity = verificationStore.confirm(verificationId, code);
        LocalDateTime verifiedAt = LocalDateTime.now(clock);
        AppUser found = findSeededUser(identity, verifiedAt);
        Decision decision = decide(found.getId(), verifiedAt);

        IssuedSignupToken token = tokenStore.issue(new SignupSession(decision.mode(), identity, verifiedAt, found.getId()));
        return new PhoneVerificationConfirmResponse(token.token(), token.expiresIn(), decision.mode(), decision.nextStep());
    }

    /**
     * 이름·생년월일·번호가 모두 같은 시드 사용자. 세 값이 모두 맞는 탈퇴 행(이름은 남긴 해시로 비교)이면 새 사용자로 재가입시킨다.
     * 이름이 다른 탈퇴 행은 없는 사람과 같은 404다. 같은 탈퇴자를 다른 요청이 먼저 옮겼으면(동시 재가입) 다시 찾아 그 새 사용자로
     * 이어 간다(그 사용자도 같은 이름이라 아래 이름 비교를 통과한다).
     */
    private AppUser findSeededUser(VerifiedIdentity identity, LocalDateTime verifiedAt) {
        Optional<AppUser> match = userRepository.findByPhoneHashAndBirthDate(identity.phoneHash(), identity.birthDate());
        byte[] nameHash = lookupHasher.name(identity.name());
        if (match.isPresent() && match.get().isWithdrawnWithName(nameHash)) {
            Optional<Long> rejoined = rejoinService.rejoin(match.get().getId(), identity.name(), nameHash,
                    identity.birthDate(), identity.phoneEnc(), identity.phoneHash(), verifiedAt);
            if (rejoined.isPresent()) {
                return userRepository.findById(rejoined.get())
                        .orElseThrow(() -> new BusinessException(ErrorCode.DEMO_USER_NOT_FOUND));
            }
            match = userRepository.findByPhoneHashAndBirthDate(identity.phoneHash(), identity.birthDate());
        }
        AppUser user = match.filter(found -> !found.isWithdrawn() && identity.sameNameAs(found.getName()))
                .orElseThrow(() -> new BusinessException(ErrorCode.DEMO_USER_NOT_FOUND));
        if (user.getSsafyUserKey() == null) {
            // 시드가 SSAFY 회원·계좌를 만들기 전에 멈춘 행. 계좌를 연결할 수 없으니 가입을 시작시키지 않는다.
            log.warn("SSAFY 회원이 없는 시드 사용자라 가입을 막음 (userId={})", user.getId());
            throw new BusinessException(ErrorCode.DEMO_USER_NOT_FOUND);
        }
        return user;
    }

    /**
     * 사용자 행을 잠근 채 지금 상태로 모드를 정한다. 찾은 뒤 그 사이에 가입을 마쳤으면(PENDING → ACTIVE) 기기 재등록으로 보내고,
     * ACTIVE 사용자의 본인인증 시각을 덮어쓰지 않는다. 본인인증 시각은 가입 중(PENDING)일 때만 기록한다.
     */
    private Decision decide(Long userId, LocalDateTime verifiedAt) {
        return transaction.execute(status -> {
            AppUser user = userRepository.findByIdForUpdate(userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.DEMO_USER_NOT_FOUND));
            return switch (user.getStatus()) {
                case ACTIVE -> new Decision(SignupMode.DEVICE_RECOVERY, SignupStep.ACCOUNT_LINK);
                // 찾은 뒤 그 사이에 탈퇴했다. 다시 본인인증부터 하면 재가입으로 간다
                case WITHDRAWN -> throw new BusinessException(ErrorCode.DEMO_USER_NOT_FOUND);
                case PENDING -> {
                    // 계좌를 연결해 두고 멈췄어도 새 토큰은 1원 인증부터(SignupProgress). 남이 이어받아 PIN·기기를 등록하지 못하게.
                    user.markIdentityVerified(verifiedAt);
                    yield new Decision(SignupMode.SIGNUP, SignupStep.ACCOUNT_LINK);
                }
            };
        });
    }

    private record Decision(SignupMode mode, SignupStep nextStep) {}
}
