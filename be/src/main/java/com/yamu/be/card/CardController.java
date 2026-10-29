package com.yamu.be.card;

import com.yamu.be.auth.StepUpService;
import com.yamu.be.card.dto.CardLookupRequest;
import com.yamu.be.card.dto.CardLookupResponse;
import com.yamu.be.card.dto.CardRegisterRequest;
import com.yamu.be.card.dto.CardRegisterResponse;
import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.security.AuthUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 카드 확인·등록 API. 명세: 노션 API 명세서 V2 「카드 확인 (카드사·상품 식별)」「카드 등록」. 로그인 후(Access Token)에만 쓴다. */
@RestController
@RequestMapping("/api/cards")
@RequiredArgsConstructor
public class CardController {

    private final CardRegistrationService registrationService;

    @PostMapping("/lookup")
    public ApiResponse<CardLookupResponse> lookup(@AuthenticationPrincipal AuthUser me,
            @Valid @RequestBody CardLookupRequest request) {
        return ApiResponse.ok(registrationService.lookup(me, request));
    }

    /** 201 Created(명세). 본인 인증 토큰(용도 CARD_REGISTER)이 필요하다. */
    @PostMapping
    public ResponseEntity<ApiResponse<CardRegisterResponse>> register(@AuthenticationPrincipal AuthUser me,
            @RequestHeader(value = StepUpService.HEADER, required = false) String stepUpToken,
            @Valid @RequestBody CardRegisterRequest request) {
        CardRegisterResponse registered = registrationService.register(me, request, stepUpToken);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(registered));
    }
}
