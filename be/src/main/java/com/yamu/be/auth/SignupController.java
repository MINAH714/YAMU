package com.yamu.be.auth;

import com.yamu.be.auth.dto.PhoneVerificationConfirmRequest;
import com.yamu.be.auth.dto.PhoneVerificationConfirmResponse;
import com.yamu.be.auth.dto.PhoneVerificationRequest;
import com.yamu.be.auth.dto.PhoneVerificationResponse;
import com.yamu.be.auth.dto.SignupResponse;
import com.yamu.be.global.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 휴대폰 본인인증(연출)·회원가입 API. 명세: 노션 API 명세서 V2 「휴대폰 본인인증 요청 (연출)」「휴대폰 본인인증 확인」「회원가입」.
 *
 * <p>회원가입은 Access Token이 아니라 가입 진행 토큰(signupToken)을 {@code Authorization: Bearer}로 받는다.
 * JWT가 아니므로 SecurityConfig에서 공개 경로로 두고(JWT 검사 안 함) 여기서 직접 확인한다.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class SignupController {

    private static final String BEARER = "Bearer ";

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

    /** 201 Created(명세). 본문 없음. */
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<SignupResponse>> signup(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest http) {
        String token = authorization != null && authorization.startsWith(BEARER)
                ? authorization.substring(BEARER.length()).strip() : null;
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(signupService.signup(token, ClientInfo.from(http))));
    }
}
