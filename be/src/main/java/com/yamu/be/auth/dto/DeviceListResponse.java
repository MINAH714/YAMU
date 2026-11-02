package com.yamu.be.auth.dto;

import com.yamu.be.auth.domain.AuthMethod;
import com.yamu.be.auth.domain.DevicePlatform;
import com.yamu.be.auth.domain.DeviceStatus;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 등록 기기 목록(명세 「등록 기기 목록 조회」).
 *
 * @param items      지금 기기 → 쓰는 기기 → 해제한 기기 순. 같은 묶음 안에서는 마지막 사용이 최근인 순
 * @param maxDevices 해제하지 않은 기기를 둘 수 있는 대수
 */
public record DeviceListResponse(List<Item> items, int maxDevices) {

    /**
     * @param isCurrent      이 요청을 보낸 기기(Access Token의 기기)인지
     * @param status         ACTIVE · LOCKED(잠금 시각이 아직 안 지남) · REVOKED. 잠금 시각이 지난 기기는 ACTIVE로 보인다
     * @param methods        로그인·본인 인증에 쓸 수 있는 수단. 해제한 기기는 빈 목록
     * @param biometricTypes 기기가 보고한 생체 종류. 저장할 칸이 없어 지금은 null(확인 필요)
     * @param lastUsedAt     마지막 로그인·본인 인증 성공 시각. 아직 없으면 null
     * @param lockedUntil    LOCKED일 때만
     */
    public record Item(Long userDeviceId, String deviceName, DevicePlatform platform, boolean isCurrent,
            DeviceStatus status, List<AuthMethod> methods, List<String> biometricTypes, LocalDateTime registeredAt,
            LocalDateTime lastUsedAt, LocalDateTime lockedUntil) {
    }
}
