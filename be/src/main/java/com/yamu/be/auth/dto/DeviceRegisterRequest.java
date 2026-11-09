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
 * @param verificationToken  기기 재등록(DEVICE_RECOVERY)일 때만. 연결된 계좌로 받은 1원 인증 요청 응답값
 * @param authCode           기기 재등록일 때만. 1원 입금자명의 숫자 4자리
 *
 * 공개키는 EC P-256 SPKI Base64(약 124자)라 1024자면 넉넉하다. 길이 상한은 큰 값으로 서버를 괴롭히지 못하게 둔다.
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
        @Size(max = 1024, message = "기기 공개키가 너무 깁니다.")
        String devicePublicKey,
        @Size(max = 1024, message = "생체 키가 너무 깁니다.")
        String biometricPublicKey,
        @Size(max = 2, message = "생체 종류는 2개까지입니다.")
        List<@NotNull(message = "생체 종류에 빈 값이 있습니다.") BiometricType> biometricTypes,
        @Size(max = 128, message = "1원 인증 토큰 형식이 올바르지 않습니다.")
        String verificationToken,
        @Pattern(regexp = "[0-9]{4}", message = "인증코드는 숫자 4자리입니다.")
        String authCode) {
}
