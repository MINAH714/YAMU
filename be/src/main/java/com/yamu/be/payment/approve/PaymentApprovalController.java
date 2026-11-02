package com.yamu.be.payment.approve;

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

/** 앱의 결제 승인 요청. API 명세 「결제 승인 요청 (카드 확정)」. */
@Tag(name = "결제 승인")
@RestController
@RequestMapping("/api/payments")
class PaymentApprovalController {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final PaymentApprovalService service;

    PaymentApprovalController(PaymentApprovalService service) {
        this.service = service;
    }

    @Operation(summary = "결제 승인 요청 (카드 확정)",
            description = "추천 카드 중 하나를 확정해 저장된 POS 금액·결제 방식·개월 수로 모의 승인한다. 카드를 생략하면 1순위 카드다. "
                    + "본문에 plan·installmentMonths를 보내면(null 포함) 400 INVALID_INSTALLMENT. "
                    + "확정 거절은 200 + status DECLINED이고 마감(60초) 안에 새 Idempotency-Key로 다른 카드를 다시 보낼 수 있다(최대 3회).")
    @PostMapping("/{paymentId}/approve")
    ApiResponse<PaymentApprovalResponse> approve(
            @AuthenticationPrincipal AuthUser me,
            @PathVariable Long paymentId,
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(schema = @Schema(implementation = PaymentApprovalRequest.class)))
            @RequestBody(required = false) JsonNode body) {
        return ApiResponse.ok(service.approve(me, paymentId, PaymentApprovalRequest.parse(body), idempotencyKey));
    }
}
