package com.yamu.be.payment.cancel;

import com.yamu.be.payment.domain.PaymentStatus;
import java.time.LocalDateTime;

/**
 * POS 결제 취소 응답(S15P31E103-424). POS 취소 영수증에 필요한 값만 담는다.
 * 앱 취소 응답의 실적 복구·청구 회차·환급·입금 뒤 잔액은 고객 정보라 넣지 않는다.
 *
 * @param canceledAt    취소 시각(UTC)
 * @param amount        POS가 요청했던 결제 금액
 * @param chargedAmount 승인액(즉시할인을 뺀 영수증 금액). 체크카드는 이 금액이 고객 계좌로 돌아간다
 */
public record PosPaymentCancelResult(
        Long paymentId,
        String orderNo,
        PaymentStatus status,
        LocalDateTime canceledAt,
        long amount,
        Long chargedAmount) {}
