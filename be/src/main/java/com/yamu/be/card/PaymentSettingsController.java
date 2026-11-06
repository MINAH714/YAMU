package com.yamu.be.card;

import com.yamu.be.card.dto.PaymentSettingsResponse;
import com.yamu.be.card.dto.PaymentSettingsUpdateRequest;
import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결제 설정(카드 관리 화면 위 "결제 카드 자동 선택"). 명세: 노션 API 명세서 V2 「결제 설정 조회」「결제 설정 수정」.
 * 경로는 명세대로 사용자 아래(/users/me)지만, 다루는 값이 기본 카드·자동 선택 포함 카드라 카드 패키지에 둔다.
 */
@RestController
@RequestMapping("/api/users/me/payment-settings")
@RequiredArgsConstructor
public class PaymentSettingsController {

    private final PaymentSettingsService service;

    @Operation(summary = "결제 설정 조회", description = "자동 선택 여부, 기본 카드, 자동 선택 포함·제외 카드와 고정 정렬 순서.")
    @GetMapping
    public ApiResponse<PaymentSettingsResponse> get(@AuthenticationPrincipal AuthUser me) {
        return ApiResponse.ok(service.get(me));
    }

    @Operation(summary = "결제 설정 수정", description = "보낸 칸만 바꾼다. 자동 선택을 끄려면 기본 카드가 있어야 한다(409 "
            + "DEFAULT_CARD_REQUIRED). defaultUserCardId는 칸이 없으면 그대로, null이면 기본 카드 해제. 응답은 조회와 같다.")
    @PatchMapping
    public ApiResponse<PaymentSettingsResponse> update(@AuthenticationPrincipal AuthUser me,
            @RequestBody PaymentSettingsUpdateRequest request) {
        return ApiResponse.ok(service.update(me, request));
    }
}
