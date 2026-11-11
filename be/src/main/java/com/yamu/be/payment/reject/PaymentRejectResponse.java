package com.yamu.be.payment.reject;

import com.yamu.be.payment.domain.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/** 결제 요청 거절 응답(S15P31E103-360). 거절했거나 이미 거절한 결제면 status는 REJECTED다. */
public record PaymentRejectResponse(
        @Schema(description = "결제 번호", example = "1024") Long paymentId,
        @Schema(description = "거절 뒤 결제 상태", example = "REJECTED") PaymentStatus status) {
}
