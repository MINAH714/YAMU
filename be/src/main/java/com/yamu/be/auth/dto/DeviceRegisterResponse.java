package com.yamu.be.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.yamu.be.auth.domain.DeviceStatus;
import java.time.LocalDateTime;

/**
 * 토큰 세 칸은 가입 진행 토큰으로 불렀을 때(가입 완료·기기 재등록)만 들어간다. 로그인 후 생체를 켤 때는 빠진다(명세).
 *
 * @param registeredAt 이번 등록(또는 키 교체) 시각(UTC)
 * @param expiresIn    Access Token 유효 시간(초)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeviceRegisterResponse(Long userDeviceId, String deviceName, boolean biometricEnabled,
        DeviceStatus status, LocalDateTime registeredAt, String accessToken, String refreshToken, Long expiresIn) {
}
