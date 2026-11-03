package com.yamu.be.payment.approve;

/**
 * 신용카드 승인 실행 포트(Task 5.2.4). 계좌는 건드리지 않고 가용 한도 확인 뒤 승인하며, 청구 회차는 원장 반영({@link ApprovalLedger})에서 만든다.
 * 구현이 없으면 {@link TemporaryCreditCardApprover}가 쓰인다.
 */
public interface CreditCardApprover {

    ApprovalOutcome approve(ApprovalCommand command);
}
