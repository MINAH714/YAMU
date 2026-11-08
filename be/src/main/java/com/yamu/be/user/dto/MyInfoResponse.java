package com.yamu.be.user.dto;

import java.time.LocalDateTime;

/**
 * 내 정보(명세 V2 「내 정보 조회」 + v0.3.1 설정 요약).
 *
 * @param userId           사용자 ID
 * @param maskedPhone      가운데를 가린 휴대폰 번호(010-****-5678)
 * @param name             본인인증 이름
 * @param onboarding       초기 설정 완료 여부
 * @param securitySettings 로그인·보안 설정 요약. 시간 규칙과 결제 코드 인증 방식은 임시 기본값
 * @param paymentSettings  결제 설정 요약(자세한 값은 「결제 설정 조회」)
 * @param createdAt        가입 시각(UTC)
 */
public record MyInfoResponse(Long userId, String maskedPhone, String name, Onboarding onboarding,
        SecuritySettings securitySettings, PaymentSettings paymentSettings, LocalDateTime createdAt) {

    /**
     * @param cardRegistered    등록된 카드(ACTIVE·INACTIVE)가 1장 이상
     * @param primaryAccountSet 연결된 기준 계좌가 있음
     * @param completed         둘 다. false면 앱이 초기 설정 화면으로 보낸다
     */
    public record Onboarding(boolean cardRegistered, boolean primaryAccountSet, boolean completed) {}

    /**
     * @param autoLockSeconds         앱을 떠난 뒤 다시 잠그기까지(임시 60)
     * @param setupGraceSeconds       카드 등록·초기 설정 중 잠금 유예(임시 300)
     * @param barcodeAuthGraceSeconds 잠금 해제 뒤 결제 코드 열 때 다시 묻지 않는 시간(임시 60)
     * @param barcodeAuthMethod       BIOMETRIC_OR_PIN · PIN_ONLY(임시 BIOMETRIC_OR_PIN)
     * @param biometricEnabled        지금 기기에서 생체인증을 켰는지
     * @param pinSet                  간편 비밀번호를 정했는지
     * @param currentDevice           지금 요청한 기기. 찾지 못하면 null
     */
    public record SecuritySettings(int autoLockSeconds, int setupGraceSeconds, int barcodeAuthGraceSeconds,
            String barcodeAuthMethod, boolean biometricEnabled, boolean pinSet, CurrentDevice currentDevice) {}

    /** @param deviceName 기기 이름. 없으면 null */
    public record CurrentDevice(Long userDeviceId, String deviceName) {}

    /**
     * @param autoSelect        결제 카드 자동 선택. 끄면 기본 카드로 결제(「결제 설정 수정」으로 바꿈)
     * @param defaultUserCardId 기본 카드. 없으면 null
     */
    public record PaymentSettings(boolean autoSelect, Long defaultUserCardId) {}
}
