package com.yamu.be.card;

import com.yamu.be.auth.StepUpService;
import com.yamu.be.card.dto.CardDetailResponse;
import com.yamu.be.card.dto.CardListResponse;
import com.yamu.be.card.dto.CardLookupRequest;
import com.yamu.be.card.dto.CardLookupResponse;
import com.yamu.be.card.dto.CardOrderRequest;
import com.yamu.be.card.dto.CardOrderResponse;
import com.yamu.be.card.dto.CardRegisterRequest;
import com.yamu.be.card.dto.CardRegisterResponse;
import com.yamu.be.card.dto.CardUpdateRequest;
import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.security.AuthUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 카드 확인·등록·관리 API. 명세: 노션 API 명세서 V2 「카드 확인 (카드사·상품 식별)」「카드 등록」「보유 카드 목록 조회」
 * 「카드 상세 조회」「카드 정보 수정」「카드 고정 정렬 순서 저장」「카드 등록 해제」. 로그인 후(Access Token)에만 쓴다.
 */
@RestController
@RequestMapping("/api/cards")
@RequiredArgsConstructor
public class CardController {

    private final CardRegistrationService registrationService;
    private final CardManagementService managementService;

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

    @GetMapping
    public ApiResponse<CardListResponse> list(@AuthenticationPrincipal AuthUser me,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(managementService.list(me, status));
    }

    @GetMapping("/{userCardId}")
    public ApiResponse<CardDetailResponse> detail(@AuthenticationPrincipal AuthUser me,
            @PathVariable Long userCardId) {
        return ApiResponse.ok(managementService.detail(me, userCardId));
    }

    @PatchMapping("/{userCardId}")
    public ApiResponse<CardDetailResponse> update(@AuthenticationPrincipal AuthUser me,
            @PathVariable Long userCardId, @Valid @RequestBody CardUpdateRequest request) {
        return ApiResponse.ok(managementService.update(me, userCardId, request));
    }

    @PutMapping("/order")
    public ApiResponse<CardOrderResponse> saveOrder(@AuthenticationPrincipal AuthUser me,
            @Valid @RequestBody CardOrderRequest request) {
        return ApiResponse.ok(managementService.saveOrder(me, request));
    }

    /** 204 No Content(명세). 본인 인증 토큰(용도 CARD_DELETE)이 필요하다. */
    @DeleteMapping("/{userCardId}")
    public ResponseEntity<Void> unregister(@AuthenticationPrincipal AuthUser me, @PathVariable Long userCardId,
            @RequestHeader(value = StepUpService.HEADER, required = false) String stepUpToken) {
        managementService.unregister(me, userCardId, stepUpToken);
        return ResponseEntity.noContent().build();
    }
}
