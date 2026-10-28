package com.yamu.be.auth;

import com.yamu.be.auth.ChallengeStore.StoredChallenge;
import com.yamu.be.auth.domain.AuthMethod;
import com.yamu.be.auth.domain.DeviceEventType;
import com.yamu.be.auth.domain.StepUpAuditLog;
import com.yamu.be.auth.domain.StepUpAuditLogRepository;
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
 * 기기 서명(+PIN) 한 번의 검증을 한 트랜잭션으로 처리한다. 로그인과 본인 인증(step-up)이 같이 쓴다.
 * 기기 잠금(FOR UPDATE) → 서명·PIN 확인 → 실패 횟수·잠금 갱신 → 인증 기록(용도는 challenge의 purpose).
 *
 * <p>실패해도 예외를 던지지 않고 결과를 돌려준다. 예외로 끝내면 트랜잭션이 롤백돼 실패 횟수와 인증 기록이 사라지기 때문이다.
 * 커밋된 뒤 호출한 서비스가 결과를 보고 에러 응답으로 바꾼다. 실패 횟수는 로그인과 본인 인증이 같은 기기 카운터를 쓴다(명세 5장).
 */
@Component
@RequiredArgsConstructor
public class DeviceCredentialProcessor {

    private final UserDeviceRepository deviceRepository;
    private final UserDeviceEventRepository eventRepository;
    private final StepUpAuditLogRepository auditRepository;
    private final DeviceSignatureVerifier signatureVerifier;
    private final PinHasher pinHasher;
    private final AuthProperties properties;
    private final Clock clock;

    /**
     * @param expectedUserId 본인 인증처럼 이미 로그인한 사용자가 부를 때 그 사용자 번호. 기기 주인이 다르면 미등록으로 본다. 로그인은 null
     */
    @Transactional
    public CredentialOutcome verify(CredentialCommand command, StoredChallenge challenge, Long expectedUserId,
            ClientInfo client) {
        UserDevice device = deviceRepository.findByIdForUpdate(command.userDeviceId()).orElse(null);
        if (device == null || !device.isUsable()
                || (expectedUserId != null && !expectedUserId.equals(device.getUser().getId()))) {
            return CredentialOutcome.fail(ErrorCode.DEVICE_NOT_REGISTERED, null);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (device.unlockIfExpired(now)) {
            eventRepository.save(new UserDeviceEvent(device.getId(), DeviceEventType.UNLOCKED, "잠금 시간 경과"));
        }
        if (device.isLockedAt(now)) {
            return CredentialOutcome.fail(ErrorCode.DEVICE_LOCKED, DeviceLockDetails.of(device));
        }
        if (command.method() == AuthMethod.BIOMETRIC && !device.isBiometricEnabled()) {
            return CredentialOutcome.fail(ErrorCode.BIOMETRIC_NOT_ENABLED, null);
        }

        AppUser user = device.getUser();
        String publicKey = command.method() == AuthMethod.BIOMETRIC ? device.getBiometricPublicKey() : device.getPublicKey();
        if (!signatureVerifier.verify(device.getKeyAlgorithm(), publicKey, challenge.challenge(), command.signature())) {
            // 서명 실패는 잠금 횟수에 넣지 않는다. 기기 번호만 아는 사람이 엉터리 서명 5번으로 주인을 잠글 수 있기 때문이다.
            // 서명은 그 폰의 개인키 없이는 만들 수 없어 대입 공격 대상이 아니므로, 기록만 남긴다.
            audit(StepUpAuditLog.failure(user.getId(), device.getId(), command.method(), challenge.purpose(),
                    ErrorCode.SIGNATURE_INVALID.name(), command.challengeId(), client.ipAddress(), client.userAgent()));
            return CredentialOutcome.fail(ErrorCode.SIGNATURE_INVALID, null);
        }
        // 여기부터는 "그 폰"이 확인된 요청이다. PIN 대입은 폰을 가진 사람만 할 수 있고, 그 시도를 5회로 막는다.
        if (command.method() == AuthMethod.PIN && !pinHasher.matches(command.pin(), user.getPinHash())) {
            boolean locked = device.recordFailure(now, properties.maxFailedAttempts(), properties.lockDuration());
            audit(StepUpAuditLog.failure(user.getId(), device.getId(), command.method(), challenge.purpose(),
                    ErrorCode.PIN_MISMATCH.name(), command.challengeId(), client.ipAddress(), client.userAgent()));
            if (locked) {
                eventRepository.save(new UserDeviceEvent(device.getId(), DeviceEventType.LOCKED,
                        "간편 비밀번호 " + properties.maxFailedAttempts() + "회 연속 실패"));
                return CredentialOutcome.fail(ErrorCode.DEVICE_LOCKED, DeviceLockDetails.of(device));
            }
            return CredentialOutcome.fail(ErrorCode.PIN_MISMATCH,
                    Map.of("remainingAttempts", properties.maxFailedAttempts() - device.getFailedCount()));
        }

        device.recordSuccess(now);
        StepUpAuditLog log = audit(StepUpAuditLog.success(user.getId(), device.getId(), command.method(),
                challenge.purpose(), command.challengeId(), client.ipAddress(), client.userAgent()));
        return CredentialOutcome.success(user.getId(), device.getId(), user.getName(), log.getId());
    }

    private StepUpAuditLog audit(StepUpAuditLog log) {
        return auditRepository.save(log);
    }

    /** PIN 방식은 기기 키 서명 + PIN, 생체 방식은 생체 키 서명 하나. */
    public record CredentialCommand(Long userDeviceId, String challengeId, AuthMethod method, String signature, String pin) {
    }

    /**
     * 실패면 error·details, 성공이면 사용자·기기·인증 기록 번호.
     * error는 로그인 기준 코드(SIGNATURE_INVALID·PIN_MISMATCH 등)이고, 본인 인증은 받아서 STEP_UP_FAILED로 바꾼다.
     */
    public record CredentialOutcome(ErrorCode error, Map<String, Object> details, Long userId, Long userDeviceId,
            String name, Long auditLogId) {

        static CredentialOutcome fail(ErrorCode error, Map<String, Object> details) {
            return new CredentialOutcome(error, details, null, null, null, null);
        }

        static CredentialOutcome success(Long userId, Long userDeviceId, String name, Long auditLogId) {
            return new CredentialOutcome(null, null, userId, userDeviceId, name, auditLogId);
        }

        boolean failed() {
            return error != null;
        }
    }
}
