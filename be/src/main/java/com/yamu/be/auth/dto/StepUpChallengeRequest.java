package com.yamu.be.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** 용도는 문자열로 받아 서비스에서 확인한다. 틀린 값을 명세대로 400 INVALID_PURPOSE로 돌려주기 위해서다. */
public record StepUpChallengeRequest(
        @NotBlank(message = "본인 인증 용도가 필요합니다.")
        String purpose) {
}
