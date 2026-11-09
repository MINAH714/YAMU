package com.yamu.be.auth.dto;

import com.yamu.be.auth.domain.AuthMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 로그인과 같은 규칙이다. PIN 방식은 기기 키 서명 + PIN, 생체 방식은 생체 키 서명. 기기는 Access Token의 기기로 정한다.
 */
public record StepUpVerifyRequest(
        @NotBlank(message = "challengeId가 필요합니다.")
        String challengeId,
        @NotNull(message = "인증 방식(PIN·BIOMETRIC)이 필요합니다.")
        AuthMethod method,
        @NotBlank(message = "서명이 필요합니다.")
        String signature,
        String pin) {
}
