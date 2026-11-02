package com.yamu.be.auth;

import com.yamu.be.auth.domain.AuthMethod;
import com.yamu.be.auth.domain.DeviceEventType;
import com.yamu.be.auth.domain.DeviceStatus;
import com.yamu.be.auth.domain.StepUpPurpose;
import com.yamu.be.auth.domain.UserDevice;
import com.yamu.be.auth.domain.UserDeviceEvent;
import com.yamu.be.auth.domain.UserDeviceEventRepository;
import com.yamu.be.auth.domain.UserDeviceRepository;
import com.yamu.be.auth.dto.DeviceListResponse;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.security.AuthUser;
import com.yamu.be.user.domain.AppUserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 등록 기기 목록·해제(명세 「등록 기기 목록 조회」「기기 해제」, FR-071).
 *
 * <ul>
 *   <li>해제는 행을 지우지 않고 REVOKED로 바꾸고 이력(REVOKED)을 남긴다. 그 기기의 Refresh Token도 지워 재발급을 막는다</li>
 *   <li>지금 쓰는 기기도 해제할 수 있다. 그 폰은 다시 로그인할 수 없고 기기 재등록(1원 인증)을 해야 한다.
 *       명세의 "현재 기기를 해제하면 PIN만 남는다"는 PIN 로그인도 기기 서명을 하게 바뀌어(10/28) 맞지 않는다</li>
 *   <li>사용자 행을 잠근 채 바꾼다. 기기 등록이 같은 잠금 안에서 대수를 세므로 해제와 등록이 겹쳐도 대수가 어긋나지 않는다</li>
 *   <li>이미 해제한 기기를 다시 해제하면 아무것도 바꾸지 않고 204(본인 인증 토큰도 쓰지 않음)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class DeviceManagementService {

    private static final Comparator<UserDevice> LAST_USED_FIRST = Comparator
            .comparing(UserDevice::getLastVerifiedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(UserDevice::getId, Comparator.reverseOrder());

    private final AppUserRepository userRepository;
    private final UserDeviceRepository deviceRepository;
    private final UserDeviceEventRepository eventRepository;
    private final StepUpService stepUpService;
    private final RefreshTokenStore refreshTokenStore;
    private final TransactionTemplate transaction;
    private final Clock clock;

    @Transactional(readOnly = true)
    public DeviceListResponse list(AuthUser me) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<DeviceListResponse.Item> items = deviceRepository.findAllByUserId(me.userId()).stream()
                .sorted(Comparator.comparing((UserDevice d) -> !d.getId().equals(me.userDeviceId()))
                        .thenComparing(UserDevice::isRevoked)
                        .thenComparing(LAST_USED_FIRST))
                .map(d -> item(d, me, now))
                .toList();
        return new DeviceListResponse(items, DeviceRegistrationService.MAX_DEVICES);
    }

    /**
     * 기기를 해제한다. 남의 기기·없는 기기는 404. 본인 인증(APP_UNLOCK, PIN 허용)은 대상을 확인한 뒤, 바꾸기 직전에 쓴다.
     */
    public void revoke(AuthUser me, Long userDeviceId, String stepUpToken) {
        UserDevice target = deviceRepository.findById(userDeviceId)
                .filter(d -> d.getUser().getId().equals(me.userId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (target.isRevoked()) {
            return;
        }
        stepUpService.requireStepUp(me, StepUpPurpose.APP_UNLOCK, stepUpToken);
        boolean revoked = Boolean.TRUE.equals(transaction.execute(status -> {
            userRepository.findByIdForUpdate(me.userId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            UserDevice device = deviceRepository.findByIdForUpdate(userDeviceId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            if (device.isRevoked()) {
                return false;
            }
            device.revoke();
            eventRepository.save(new UserDeviceEvent(device.getId(), DeviceEventType.REVOKED,
                    device.getId().equals(me.userDeviceId()) ? "사용자가 지금 기기를 해제" : "사용자가 다른 기기를 해제"));
            return true;
        }));
        if (revoked) {
            refreshTokenStore.revoke(userDeviceId);
        }
    }

    private static DeviceListResponse.Item item(UserDevice device, AuthUser me, LocalDateTime now) {
        DeviceStatus status = device.isRevoked() ? DeviceStatus.REVOKED
                : device.isLockedAt(now) ? DeviceStatus.LOCKED : DeviceStatus.ACTIVE;
        List<AuthMethod> methods = device.isRevoked() ? List.of()
                : device.isBiometricEnabled() ? List.of(AuthMethod.BIOMETRIC, AuthMethod.PIN) : List.of(AuthMethod.PIN);
        return new DeviceListResponse.Item(device.getId(), device.getDeviceName(), device.getPlatform(),
                device.getId().equals(me.userDeviceId()), status, methods, null,
                seconds(device.getCreatedAt()), seconds(device.getLastVerifiedAt()),
                status == DeviceStatus.LOCKED ? seconds(device.getLockedUntil()) : null);
    }

    private static LocalDateTime seconds(LocalDateTime time) {
        return time == null ? null : time.truncatedTo(ChronoUnit.SECONDS);
    }
}
