package com.yamu.be.payment.memo;

import com.fasterxml.jackson.databind.JsonNode;
import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 결제 메모. API 명세 「결제 메모」. */
@Tag(name = "결제 내역")
@RestController
@RequestMapping("/api/payments")
class PaymentMemoController {

    private final PaymentMemoService service;

    PaymentMemoController(PaymentMemoService service) {
        this.service = service;
    }

    @Operation(summary = "결제 메모",
            description = "결제 건에 메모를 남기거나 지운다. 최대 100자(앞뒤 공백 제외, 이모지도 1자)이고 넘으면 400 MEMO_TOO_LONG. "
                    + "memo가 null·빈 문자열·공백뿐이면 메모를 지운다. memo 칸이 없거나 문자열이 아니면 400 INVALID_REQUEST. "
                    + "취소된 결제에도 남길 수 있다. 없는 결제나 다른 사용자의 결제는 404 RESOURCE_NOT_FOUND.")
    @PatchMapping("/{paymentId}/memo")
    ApiResponse<PaymentMemoResponse> memo(
            @AuthenticationPrincipal AuthUser me,
            @PathVariable Long paymentId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(schema = @Schema(implementation = PaymentMemoRequest.class)))
            @RequestBody(required = false) JsonNode body) {
        return ApiResponse.ok(service.save(me, paymentId, PaymentMemoRequest.parse(body)));
    }
}
