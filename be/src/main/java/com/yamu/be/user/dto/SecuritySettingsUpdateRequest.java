package com.yamu.be.user.dto;

/**
 * 보안 설정 수정(노션 API 명세 V2 「보안 설정 수정」). 보낸 칸만 바꾼다.
 *
 * @param autoLockSeconds   앱을 떠난 뒤 다시 잠그기까지(초). 0(즉시) · 60(기본) · 300
 * @param barcodeAuthMethod 결제 코드를 열 때 인증 방식. BIOMETRIC_OR_PIN(기본) · PIN_ONLY
 */
public record SecuritySettingsUpdateRequest(Integer autoLockSeconds, String barcodeAuthMethod) {
}
