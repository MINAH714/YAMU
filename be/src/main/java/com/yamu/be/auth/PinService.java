package com.yamu.be.auth;

import com.yamu.be.auth.domain.DeviceStatus;
import com.yamu.be.auth.domain.StepUpPurpose;
import com.yamu.be.auth.domain.UserDeviceRepository;
import com.yamu.be.global.crypto.PinHasher;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.user.domain.AppUser;
import com.yamu.be.user.domain.AppUserRepository;
import com.yamu.be.user.domain.AppUserStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 간편 비밀번호 설정·변경(명세 「간편 비밀번호 설정·변경」, DEC-001).
 *
 * <pre>
 * 가입 중    : Bearer signupToken                                  → 계좌 연결을 마친 PENDING 사용자에 바로 저장
 * 기기 재등록: Bearer signupToken                                  → 가입 진행 토큰에 맡겨 두고, 1원 인증을 마친 기기 등록 때 저장
 * 가입 후    : Bearer accessToken + X-Step-Up-Token(SECURITY_SETTINGS) → 바로 바꾸고, 지금 기기 말고 다른 기기의 로그인(Refresh Token)을 끊는다
 * </pre>
 *
 * <ul>
 *   <li>BCrypt는 일부러 느려서(약 0.1초) 트랜잭션 밖에서 해시를 만든다</li>
 *   <li>사용자 행을 잠가(FOR UPDATE) 바꾼다. 기기 등록(ACTIVE 전환)과 같은 잠금 순서라, 연타하거나 기기 등록과 겹쳐도
 *       @Version 충돌(500) 없이 차례로 처리된다</li>
 *   <li>가입 중에는 이 가입 진행 토큰으로 1원 인증을 마쳤어야 한다({@link SignupProgress})</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PinService {

    private final AppUserRepository userRepository;
    private final UserDeviceRepository deviceRepository;
    private final RefreshTokenStore refreshTokenStore;
    private final SignupTokenStore signupTokenStore;
    private final SignupProgress signupProgress;
    private final StepUpService stepUpService;
    private final PinHasher pinHasher;
    private final TransactionTemplate transaction;
    private final Clock clock;

    void setPin(SetupActor actor, String pin, String stepUpToken) {
        // 형식부터 본다. 틀린 PIN 때문에 본인 인증 토큰을 버리지 않게.
        PinPolicy.requireAllowed(pin);
        if (actor.isMember()) {
            stepUpService.requireStepUp(actor.me(), StepUpPurpose.SECURITY_SETTINGS, stepUpToken);
            save(actor.me().userId(), AppUserStatus.ACTIVE, pinHasher.hash(pin));
            // PIN을 바꾸는 건 보통 "남이 알았을까" 해서다. 다른 기기에 남은 로그인은 끊고, 지금 기기만 유지한다.
            deviceRepository.findIdsByUserIdAndStatusNot(actor.me().userId(), DeviceStatus.REVOKED).stream()
                    .filter(id -> !id.equals(actor.me().userDeviceId()))
                    .forEach(refreshTokenStore::revoke);
            return;
        }
        if (actor.isDeviceRecovery()) {
            // 휴대폰 본인인증(연출)만 한 상태다. 여기서 바로 바꾸면 번호·생년월일만 아는 사람이 남의 PIN을 바꿀 수 있다.
            if (!signupTokenStore.stagePin(actor.signupToken(), pinHasher.hash(pin))) {
                throw new BusinessException(ErrorCode.SIGNUP_TOKEN_INVALID);
            }
            return;
        }
        signupProgress.requireAccountLinked(actor);
        save(actor.userId(), AppUserStatus.PENDING, pinHasher.hash(pin));
    }

    /** 가입 중 PIN을 다시 정하면 덮어쓴다(앱에서 뒤로 가기). 이미 가입을 마친 사용자는 가입 진행 토큰으로 바꿀 수 없다. */
    private void save(Long userId, AppUserStatus expected, String pinHash) {
        // 기기 등록(재등록 PIN 저장)과 같이 초 단위로 자른다.
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        transaction.executeWithoutResult(status -> {
            AppUser user = userRepository.findByIdForUpdate(userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            if (user.getStatus() != expected) {
                throw new BusinessException(expected == AppUserStatus.PENDING
                        ? ErrorCode.ALREADY_REGISTERED : ErrorCode.RESOURCE_NOT_FOUND);
            }
            user.changePin(pinHash, now);
        });
    }
}
