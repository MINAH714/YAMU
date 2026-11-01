package com.yamu.be.payment.pos;

import com.yamu.be.payment.domain.PaymentStatus;
import java.time.LocalDateTime;

/**
 * POS 결제 요청 응답(201). 카드·추천 정보는 넣지 않는다. 사용자 앱이 바코드 조회로 본다.
 *
 * @param confirmDeadline 사용자가 이 시각(UTC)까지 승인하지 않으면 EXPIRED
 */
public record PosPaymentRequestResponse(Long paymentId, String orderNo, PaymentStatus status,
        LocalDateTime confirmDeadline) {}
