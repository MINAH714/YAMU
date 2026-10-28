package com.yamu.be.auth;

import com.yamu.be.auth.dto.LoginChallengeRequest;
import com.yamu.be.auth.dto.LoginChallengeResponse;
import com.yamu.be.auth.dto.LoginRequest;
import com.yamu.be.auth.dto.LoginResponse;
import com.yamu.be.auth.dto.RefreshTokenRequest;
import com.yamu.be.auth.dto.TokenResponse;
import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.security.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 로그인·토큰 API. 명세: 노션 API 명세서 V2 「로그인 준비」「로그인」「토큰 재발급」「로그아웃」. */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login/challenge")
    public ApiResponse<LoginChallengeResponse> challenge(@Valid @RequestBody LoginChallengeRequest request) {
        return ApiResponse.ok(authService.issueChallenge(request.userDeviceId()));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return ApiResponse.ok(authService.login(request.toCommand(), ClientInfo.from(http)));
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ApiResponse.ok(authService.refresh(request.refreshToken()));
    }

    /** 204 No Content(명세). */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(me);
        return ResponseEntity.noContent().build();
    }
}
