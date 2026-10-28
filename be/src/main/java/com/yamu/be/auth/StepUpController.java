package com.yamu.be.auth;

import com.yamu.be.auth.dto.StepUpChallengeRequest;
import com.yamu.be.auth.dto.StepUpChallengeResponse;
import com.yamu.be.auth.dto.StepUpVerifyRequest;
import com.yamu.be.auth.dto.StepUpVerifyResponse;
import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.security.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 본인 인증(step-up). 로그인한 사용자만 부를 수 있다(Bearer). */
@RestController
@RequestMapping("/api/auth/step-up")
@RequiredArgsConstructor
public class StepUpController {

    private final StepUpService stepUpService;

    @PostMapping("/challenge")
    public ApiResponse<StepUpChallengeResponse> challenge(@AuthenticationPrincipal AuthUser me,
            @Valid @RequestBody StepUpChallengeRequest request) {
        return ApiResponse.ok(stepUpService.issueChallenge(me, request.purpose()));
    }

    @PostMapping("/verify")
    public ApiResponse<StepUpVerifyResponse> verify(@AuthenticationPrincipal AuthUser me,
            @Valid @RequestBody StepUpVerifyRequest request, HttpServletRequest http) {
        return ApiResponse.ok(stepUpService.verify(me, request, ClientInfo.from(http)));
    }
}
