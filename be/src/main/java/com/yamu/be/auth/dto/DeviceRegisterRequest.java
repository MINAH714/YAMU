package com.yamu.be.auth.dto;

import com.yamu.be.auth.domain.BiometricType;
import com.yamu.be.auth.domain.DevicePlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 기기 등록(명세 「기기 등록」). 키 형식(EC P-256)은 Service가 확인해 400 INVALID_PUBLIC_KEY로 돌려준다.
 *
 * @param deviceId           앱이 만든 기기 식별자(UUID). 재설치하면 바뀐다
 * @param biometricPublicKey 생체 키 공개키. 없으면 생체 로그인 꺼짐
 * @param biometricTypes     biometricPublicKey가 있을 때 필수
 * @param verificationToken  기기 재등록(DEVICE_RECOVERY)일 때만. 연결된 계좌의 계좌 확인 응답값
 * @param authCode           기기 재등록일 때만. 1원 입금자명의 숫자 4자리
 */
public record DeviceRegisterRequest(
        @NotBlank(message = "deviceId가 필요합니다.")
        @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
                message = "deviceId는 UUID 형식입니다.")
        String deviceId,
        @Size(max = 100, message = "기기 이름은 100자까지입니다.")
        String deviceName,
        @NotNull(message = "플랫폼이 필요합니다.")
        DevicePlatform platform,
        @NotBlank(message = "기기 공개키가 필요합니다.")
        String devicePublicKey,
        String biometricPublicKey,
        List<BiometricType> biometricTypes,
        String verificationToken,
        String authCode) {
}
