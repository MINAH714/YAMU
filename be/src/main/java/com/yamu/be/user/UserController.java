package com.yamu.be.user;

import com.yamu.be.auth.StepUpService;
import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.security.AuthUser;
import com.yamu.be.user.dto.MyInfoResponse;
import com.yamu.be.user.dto.WithdrawRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 사용자 본인 정보. 명세: 노션 API 명세서 V2 「내 정보 조회」「회원 탈퇴」. 경로에 userId 없이 토큰 기준(/users/me). */
@RestController
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
public class UserController {

    private final WithdrawalService withdrawalService;
    private final UserQueryService userQueryService;

    /** 내 정보·초기 설정 상태·보안/결제 설정 요약. */
    @GetMapping
    public ApiResponse<MyInfoResponse> me(@AuthenticationPrincipal AuthUser me) {
        return ApiResponse.ok(userQueryService.me(me));
    }

    /** 204 No Content(명세). X-Step-Up-Token(WITHDRAW_MEMBER) 필요. */
    @DeleteMapping
    public ResponseEntity<Void> withdraw(@AuthenticationPrincipal AuthUser me,
            @RequestHeader(value = StepUpService.HEADER, required = false) String stepUpToken,
            @Valid @RequestBody(required = false) WithdrawRequest request) {
        withdrawalService.withdraw(me, stepUpToken, request == null ? null : request.reason());
        return ResponseEntity.noContent().build();
    }
}
