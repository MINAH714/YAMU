package com.yamu.be.auth;

import com.yamu.be.auth.LoginChallengeStore.StoredChallenge;
import com.yamu.be.auth.domain.AuthMethod;
import com.yamu.be.auth.domain.DeviceEventType;
import com.yamu.be.auth.domain.StepUpAuditLog;
import com.yamu.be.auth.domain.StepUpAuditLogRepository;
import com.yamu.be.auth.domain.StepUpPurpose;
import com.yamu.be.auth.domain.UserDevice;
import com.yamu.be.auth.domain.UserDeviceEvent;
import com.yamu.be.auth.domain.UserDeviceEventRepository;
import com.yamu.be.auth.domain.UserDeviceRepository;
import com.yamu.be.global.crypto.PinHasher;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.user.domain.AppUser;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인 한 번의 검증을 한 트랜잭션으로 처리한다: 기기 잠금(FOR UPDATE) → 서명·PIN 확인 → 실패 횟수·잠금 갱신 → 인증 기록.
 *
 * <p>실패해도 예외를 던지지 않고 결과를 돌려준다. 예외로 끝내면 트랜잭션이 롤백돼 실패 횟수와 인증 기록이 사라지기 때문이다.
 * 커밋된 뒤 {@link AuthService}가 결과를 보고 에러 응답으로 바꾼다.
 */
@Component
@RequiredArgsConstructor
public class LoginAttemptProcessor {

    private final UserDeviceRepository deviceRepository;
    private final UserDeviceEventRepository eventRepository;
    private final StepUpAuditLogRepository auditRepository;
    private final DeviceSignatureVerifier signatureVerifier;
    private final PinHasher pinHasher;
    private final AuthProperties properties;
    private final Clock clock;

    @Transactional
    public LoginOutcome verify(LoginCommand command, StoredChallenge challenge, ClientInfo client) {
        UserDevice device = deviceRepository.findByIdForUpdate(command.userDeviceId()).orElse(null);
        if (device == null || !device.isUsable()) {
            return LoginOutcome.fail(ErrorCode.DEVICE_NOT_REGISTERED, null);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (device.unlockIfExpired(now)) {
            eventRepository.save(new UserDeviceEvent(device.getId(), DeviceEventType.UNLOCKED, "잠금 시간 경과"));
        }
        if (device.isLockedAt(now)) {
            return LoginOutcome.fail(ErrorCode.DEVICE_LOCKED, DeviceLockDetails.of(device));
        }
        if (command.method() == AuthMethod.BIOMETRIC && !device.isBiometricEnabled()) {
            return LoginOutcome.fail(ErrorCode.BIOMETRIC_NOT_ENABLED, null);
        }

        AppUser user = device.getUser();
        String publicKey = command.method() == AuthMethod.BIOMETRIC ? device.getBiometricPublicKey() : device.getPublicKey();
        if (!signatureVerifier.verify(device.getKeyAlgorithm(), publicKey, challenge.challenge(), command.signature())) {
            // 서명 실패는 잠금 횟수에 넣지 않는다. 기기 번호만 아는 사람이 엉터리 서명 5번으로 주인을 잠글 수 있기 때문이다.
            // 서명은 그 폰의 개인키 없이는 만들 수 없어 대입 공격 대상이 아니므로, 기록만 남긴다.
            audit(StepUpAuditLog.failure(user.getId(), device.getId(), command.method(), StepUpPurpose.LOGIN,
                    ErrorCode.SIGNATURE_INVALID.name(), command.challengeId(), client.ipAddress(), client.userAgent()));
            return LoginOutcome.fail(ErrorCode.SIGNATURE_INVALID, null);
        }
        // 여기부터는 "그 폰"이 확인된 요청이다. PIN 대입은 폰을 가진 사람만 할 수 있고, 그 시도를 5회로 막는다.
        if (command.method() == AuthMethod.PIN && !pinHasher.matches(command.pin(), user.getPinHash())) {
            boolean locked = device.recordFailure(now, properties.maxFailedAttempts(), properties.lockDuration());
            audit(StepUpAuditLog.failure(user.getId(), device.getId(), command.method(), StepUpPurpose.LOGIN,
                    ErrorCode.PIN_MISMATCH.name(), command.challengeId(), client.ipAddress(), client.userAgent()));
            if (locked) {
                eventRepository.save(new UserDeviceEvent(device.getId(), DeviceEventType.LOCKED,
                        "간편 비밀번호 " + properties.maxFailedAttempts() + "회 연속 실패"));
                return LoginOutcome.fail(ErrorCode.DEVICE_LOCKED, DeviceLockDetails.of(device));
            }
            return LoginOutcome.fail(ErrorCode.PIN_MISMATCH,
                    Map.of("remainingAttempts", properties.maxFailedAttempts() - device.getFailedCount()));
        }

        device.recordSuccess(now);
        audit(StepUpAuditLog.success(user.getId(), device.getId(), command.method(), StepUpPurpose.LOGIN,
                command.challengeId(), client.ipAddress(), client.userAgent()));
        return LoginOutcome.success(user.getId(), device.getId(), user.getName());
    }

    private void audit(StepUpAuditLog log) {
        auditRepository.save(log);
    }

    public record LoginCommand(Long userDeviceId, String challengeId, AuthMethod method, String signature, String pin) {
    }

    public record LoginOutcome(ErrorCode error, Map<String, Object> details, Long userId, Long userDeviceId, String name) {

        static LoginOutcome fail(ErrorCode error, Map<String, Object> details) {
            return new LoginOutcome(error, details, null, null, null);
        }

        static LoginOutcome success(Long userId, Long userDeviceId, String name) {
            return new LoginOutcome(null, null, userId, userDeviceId, name);
        }

        boolean failed() {
            return error != null;
        }
    }
}
