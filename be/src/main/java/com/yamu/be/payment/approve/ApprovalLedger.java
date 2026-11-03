package com.yamu.be.payment.approve;

import com.yamu.be.payment.domain.PaymentTransaction;

/**
 * 승인 원장 반영 포트(Task 5.2.5). 승인이 확정된 결제 행과 같은 트랜잭션 안에서 불린다.
 *
 * <p>구현이 맡는 일(명세 「결제 승인 요청」 처리 순서 5): {@code payment_benefit}, {@code payment_limit_usage}, {@code monthly_limit_usage},
 * {@code card_monthly_performance} 반영. 예외를 던지면 승인까지 함께 롤백된다.
 * 신용카드의 {@code bill_item}·{@code card_bill}은 여기가 아니라 {@code CreditBillingWriter}(Task 5.2.4)가 같은 트랜잭션에서 적는다.
 *
 * <p>구현이 없으면 {@link NoopApprovalLedger}가 쓰인다.
 */
public interface ApprovalLedger {

    void recordApproval(PaymentTransaction payment, ApprovalOutcome.Approved outcome);
}
