package com.yamu.be.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PhoneVerificationConfirmRequest(
        @NotBlank(message = "인증번호가 필요합니다.")
        @Pattern(regexp = "\\d{6}", message = "인증번호는 숫자 6자리입니다.")
        String code) {
}
