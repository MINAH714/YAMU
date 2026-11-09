package com.yamu.be.payment.domain;

/**
 * 결제가 전체 취소됐다(CANCELED). 신용카드·체크카드 취소가 모두 이 사건을 남긴다.
 * 결제 행을 저장하는 트랜잭션 안에서 발행하므로 받는 쪽은 {@code @TransactionalEventListener(phase = AFTER_COMMIT)}로 받는다.
 */
public record PaymentCanceledEvent(Long paymentId, Long userId) {
}
