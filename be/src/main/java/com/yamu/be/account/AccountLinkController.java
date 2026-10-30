package com.yamu.be.account;

import com.yamu.be.account.dto.AccountCandidatesResponse;
import com.yamu.be.account.dto.AccountLinkRequest;
import com.yamu.be.account.dto.AccountLinkResponse;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계좌 연결 API. 명세: 노션 API 명세서 V2 「계좌 연결」 계열. 10/30 결정으로 계좌번호 입력·예금주 확인 대신
 * 계좌 목록에서 골라 연결한다(명세 갱신 필요).
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

    /** 연결할 수 있는 계좌 목록(SSAFY 계좌 목록 + candidateId). */
    @GetMapping("/candidates")
    public ApiResponse<AccountCandidatesResponse> candidates(@AuthenticationPrincipal AuthUser me,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return ApiResponse.ok(accountLinkService.candidates(actorResolver.resolve(me, authorization)));
    }

    @PostMapping("/one-won-auth")
    public ApiResponse<OneWonAuthResponse> requestOneWon(@AuthenticationPrincipal AuthUser me,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody OneWonAuthRequest request) {
        return ApiResponse.ok(accountLinkService.requestOneWon(actorResolver.resolve(me, authorization), request));
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
