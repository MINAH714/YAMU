package com.yamu.be.payment.billing.refund;

/**
 * 신용카드 취소 환급 입금을 확인했다(Task 5.4.4). 알림은 담당 모듈(BE4)이 이 이벤트를 받아 보낸다. 아직 받는 곳은 없다.
 * 환급을 완료로 바꾼 트랜잭션 안에서 발행하므로, 받는 쪽은 커밋 뒤에 처리하려면 {@code @TransactionalEventListener}를 쓴다.
 *
 * @param amount 입금한 금액(원)
 */
public record RefundCompletedEvent(Long refundId, Long userId, Long paymentTransactionId, long amount) {
}
