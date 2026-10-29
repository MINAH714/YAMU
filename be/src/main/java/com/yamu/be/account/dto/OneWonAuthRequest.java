package com.yamu.be.account.dto;

import jakarta.validation.constraints.NotBlank;

public record OneWonAuthRequest(
        @NotBlank(message = "계좌 확인 결과(verificationToken)가 필요합니다.")
        String verificationToken) {
}
