package com.yamu.be.payment.domain;

/**
 * 결제가 승인됐다(APPROVED). 결제 승인·결과 불명 복구·출금 미확인 뒤 늦은 출금 확인이 모두 이 사건을 남긴다.
 * 결제 행을 저장하는 트랜잭션 안에서 발행하므로 받는 쪽은 {@code @TransactionalEventListener(phase = AFTER_COMMIT)}로 받는다.
 * 금액·가맹점은 싣지 않는다. 받는 쪽이 결제 행에서 읽는다.
 */
public record PaymentApprovedEvent(Long paymentId, Long userId) {
}
