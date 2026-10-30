package com.yamu.be.auth;

import com.yamu.be.auth.dto.PhoneVerificationConfirmRequest;
import com.yamu.be.auth.dto.PhoneVerificationConfirmResponse;
import com.yamu.be.auth.dto.PhoneVerificationRequest;
import com.yamu.be.auth.dto.PhoneVerificationResponse;
import com.yamu.be.global.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 휴대폰 본인인증(연출) API. 명세: 노션 API 명세서 V2 「휴대폰 본인인증 요청 (연출)」「휴대폰 본인인증 확인」.
 * 로그인 전에 부르므로 SecurityConfig에서 공개 경로로 둔다.
 *
 * <p>「회원가입」(POST /api/auth/signup)은 10/30 결정으로 없앴다. 시드 사용자만 가입하므로 본인인증 확인이 그 사용자를 바로 이어받는다.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class SignupController {

    private final SignupService signupService;

    @PostMapping("/phone-verifications")
    public ApiResponse<PhoneVerificationResponse> requestVerification(
            @Valid @RequestBody PhoneVerificationRequest request, HttpServletRequest http) {
        return ApiResponse.ok(signupService.requestVerification(request, ClientInfo.from(http)));
    }

    @PostMapping("/phone-verifications/{verificationId}/confirm")
    public ApiResponse<PhoneVerificationConfirmResponse> confirmVerification(@PathVariable String verificationId,
            @Valid @RequestBody PhoneVerificationConfirmRequest request, HttpServletRequest http) {
        return ApiResponse.ok(signupService.confirmVerification(verificationId, request.code(), ClientInfo.from(http)));
    }
}
