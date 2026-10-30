package com.yamu.be.auth.dto;

/**
 * @param pin 숫자 6자리. 비었거나 형식이 틀리면 Service가 400 INVALID_PIN_FORMAT(명세 코드)으로 돌려준다
 */
public record PinSetupRequest(String pin) {
}
