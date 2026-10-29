package com.yamu.be.account;

import com.yamu.be.account.dto.AccountLinkRequest;
import com.yamu.be.account.dto.AccountLinkResponse;
import com.yamu.be.account.dto.AccountVerifyRequest;
import com.yamu.be.account.dto.AccountVerifyResponse;
import com.yamu.be.account.dto.OneWonAuthRequest;
import com.yamu.be.account.dto.OneWonAuthResponse;
import com.yamu.be.auth.StepUpService;
import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.security.AuthUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계좌 연결 API. 명세: 노션 API 명세서 V2 「계좌 확인 (은행·예금주)」「1원 인증 요청」「계좌 연결」.
 *
 * <p>가입 후에는 Access Token, 가입·기기 재등록 중에는 가입 진행 토큰을 {@code Authorization: Bearer}로 받는다.
 * 두 가지를 다 받으려고 SecurityConfig에서 이 경로는 인증 없이 열고, 가입 진행 토큰은 JWT 검사에서 뺀다.
 * Access Token이 맞으면 {@code me}가 들어오고, 아니면 {@link AccountActorResolver}가 가입 진행 토큰을 확인한다.
 */
@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountLinkController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final AccountLinkService accountLinkService;
    private final AccountActorResolver actorResolver;

    @PostMapping("/verify")
    public ApiResponse<AccountVerifyResponse> verify(@AuthenticationPrincipal AuthUser me,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody AccountVerifyRequest request) {
        return ApiResponse.ok(accountLinkService.verify(actorResolver.resolve(me, authorization), request.accountNo()));
    }

    @PostMapping("/one-won-auth")
    public ApiResponse<OneWonAuthResponse> requestOneWon(@AuthenticationPrincipal AuthUser me,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody OneWonAuthRequest request) {
        return ApiResponse.ok(accountLinkService.requestOneWon(actorResolver.resolve(me, authorization),
                request.verificationToken()));
    }

    /** 201 Created(명세). */
    @PostMapping
    public ResponseEntity<ApiResponse<AccountLinkResponse>> link(@AuthenticationPrincipal AuthUser me,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestHeader(value = StepUpService.HEADER, required = false) String stepUpToken,
            @Valid @RequestBody AccountLinkRequest request) {
        AccountLinkResponse linked = accountLinkService.link(actorResolver.resolve(me, authorization), request,
                idempotencyKey, stepUpToken);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(linked));
    }
}
