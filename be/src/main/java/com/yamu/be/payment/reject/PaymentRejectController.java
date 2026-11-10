package com.yamu.be.payment.reject;

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

/** 앱의 결제 요청 거절(S15P31E103-360). 임시: API 명세에 없는 API라 명세 추가 필요. */
@Tag(name = "결제 요청 거절")
@RestController
@RequestMapping("/api/payments")
class PaymentRejectController {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final PaymentRejectService service;

    PaymentRejectController(PaymentRejectService service) {
        this.service = service;
    }

    @Operation(summary = "결제 요청 거절",
            description = "승인 전(REQUESTED)이거나 거절 뒤 마감 전(DECLINED)인 결제 요청을 사용자가 거절한다. 돈은 움직이지 않고 "
                    + "결제는 REJECTED, 바코드는 다시 쓸 수 없게 닫힌다. POS 결과 조회는 바로 REJECTED를 받는다. "
                    + "이미 REJECTED면 같은 키든 다른 키든 200으로 같은 결과를 준다. "
                    + "승인 처리 중(PROCESSING)이면 409 IDEMPOTENCY_IN_PROGRESS, 승인·취소된 결제는 409 PAYMENT_ALREADY_PROCESSED, "
                    + "출금 결과를 확인하지 못해 거절된 결제는 409 PAYMENT_NOT_REJECTABLE(reason WITHDRAWAL_NOT_CONFIRMED), "
                    + "마감이 지났으면 EXPIRED로 저장하고 410 PAYMENT_EXPIRED. 없는 결제·다른 사용자의 결제는 404 RESOURCE_NOT_FOUND.")
    @PostMapping("/{paymentId}/reject")
    ApiResponse<PaymentRejectResponse> reject(
            @AuthenticationPrincipal AuthUser me,
            @PathVariable Long paymentId,
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(schema = @Schema(implementation = PaymentRejectRequest.class)))
            @RequestBody(required = false) JsonNode body) {
        return ApiResponse.ok(service.reject(me.userId(), paymentId, PaymentRejectRequest.parse(body), idempotencyKey));
    }
}
