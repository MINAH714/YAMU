package com.yamu.be.payment.approve;

import com.yamu.be.payment.PaymentTerms;
import com.yamu.be.payment.recommend.RecommendationResult.CardSummary;

/**
 * 신용카드 승인 실행 포트(Task 5.2.4). 계좌는 건드리지 않고 가용 한도 확인 뒤 승인하며, 청구 회차 계획을 돌려준다.
 * 기본 구현은 {@link YamuCreditCardApprover}다.
 */
public interface CreditCardApprover {

    /** 승인 전 사전 검사. {@link PaymentApprover#precheck}와 같다. */
    default void precheck(Long userId, CardSummary card, PaymentTerms terms) {}

    ApprovalOutcome approve(ApprovalCommand command);
}
