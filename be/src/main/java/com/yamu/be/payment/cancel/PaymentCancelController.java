package com.yamu.be.payment.cancel;

import com.fasterxml.jackson.databind.JsonNode;
import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 앱의 결제 전체 취소. API 명세 「결제 취소」. */
@Tag(name = "결제 취소")
@RestController
@RequestMapping("/api/payments")
class PaymentCancelController {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final PaymentCancelService service;

    PaymentCancelController(PaymentCancelService service) {
        this.service = service;
    }

    @Operation(summary = "결제 취소",
            description = "승인된 결제를 전체 취소한다. 부분 취소는 없어 본문에 취소 금액(amount)을 보내면 400 PARTIAL_CANCEL_NOT_SUPPORTED. "
                    + "APPROVED가 아니면 409 PAYMENT_NOT_CANCELABLE, 이미 취소했으면 409 ALREADY_CANCELED. "
                    + "같은 Idempotency-Key로 다시 보내면 처음 응답을 그대로 돌려준다. 현재 신용카드만 취소할 수 있다(체크카드는 Task 5.3.3).")
    @PostMapping("/{paymentId}/cancel")
    ApiResponse<PaymentCancelResponse> cancel(
            @AuthenticationPrincipal AuthUser me,
            @PathVariable Long paymentId,
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(schema = @Schema(implementation = PaymentCancelRequest.class)))
            @RequestBody(required = false) JsonNode body) {
        return ApiResponse.ok(service.cancel(me, paymentId, PaymentCancelRequest.parse(body), idempotencyKey));
    }
}
