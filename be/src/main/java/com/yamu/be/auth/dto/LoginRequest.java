package com.yamu.be.auth.dto;

import com.yamu.be.auth.DeviceCredentialProcessor.CredentialCommand;
import com.yamu.be.auth.domain.AuthMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * @param signature challenge 서명(Base64). PIN은 기기 키, BIOMETRIC은 생체 키로 서명한다.
 * @param pin       method가 PIN일 때 숫자 6자리. 형식 검사는 Service에서 한다(method에 따라 필수 여부가 달라서).
 */
public record LoginRequest(
        @NotNull(message = "기기 번호가 필요합니다.")
        @Positive(message = "기기 번호가 올바르지 않습니다.")
        Long userDeviceId,
        @NotBlank(message = "challengeId가 필요합니다.")
        String challengeId,
        @NotNull(message = "로그인 방식(PIN·BIOMETRIC)이 필요합니다.")
        AuthMethod method,
        @NotBlank(message = "서명이 필요합니다.")
        String signature,
        String pin) {

    public CredentialCommand toCommand() {
        return new CredentialCommand(userDeviceId, challengeId, method, signature, pin);
    }
}
