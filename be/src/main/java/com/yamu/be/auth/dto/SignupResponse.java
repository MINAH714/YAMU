package com.yamu.be.auth.dto;

import com.yamu.be.auth.domain.SignupStep;
import com.yamu.be.user.domain.AppUserStatus;
import java.time.LocalDateTime;
import java.util.List;

/**
 * @param finAccountLinked SSAFY 금융망 계정을 만들었는지. 실패하면 사용자도 만들지 않으므로 응답이 오면 항상 true
 * @param nextSteps        남은 가입 단계. 예: [ACCOUNT_LINK, PIN_SETUP, DEVICE_REGISTER]
 */
public record SignupResponse(Long userId, String name, AppUserStatus status, boolean finAccountLinked,
        List<SignupStep> nextSteps, LocalDateTime createdAt) {
}
