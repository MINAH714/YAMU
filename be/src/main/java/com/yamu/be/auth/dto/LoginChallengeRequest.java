package com.yamu.be.auth.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record LoginChallengeRequest(
        @NotNull(message = "기기 번호가 필요합니다.")
        @Positive(message = "기기 번호가 올바르지 않습니다.")
        Long userDeviceId) {
}
