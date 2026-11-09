package com.yamu.be.global.security;

import org.springframework.security.authentication.BadCredentialsException;

/**
 * 토큰은 맞지만 그 기기가 해제됐다(다른 폰에서 재등록 등). {@link JsonAuthenticationEntryPoint}가 401 DEVICE_NOT_REGISTERED로
 * 응답해 앱이 기기 재등록으로 안내하게 한다(S15P31E103-351).
 */
class RevokedDeviceException extends BadCredentialsException {

    RevokedDeviceException() {
        super("해제된 기기의 토큰입니다.");
    }
}
