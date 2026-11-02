package com.yamu.be.payment.approve;

import com.yamu.be.card.domain.CardType;
import com.yamu.be.payment.PaymentTerms;
import com.yamu.be.payment.recommend.RecommendationResult.CardSummary;

/**
 * 카드 종류별 승인 실행. 체크카드는 연결 계좌에서 바로 출금하고(Task 5.2.3), 신용카드는 계좌를 건드리지 않고 청구 회차를 만든다(Task 5.2.4).
 */
public class CardTypeRoutingApprover implements PaymentApprover {

    private final CheckCardApprover check;
    private final CreditCardApprover credit;

    public CardTypeRoutingApprover(CheckCardApprover check, CreditCardApprover credit) {
        this.check = check;
        this.credit = credit;
    }

    @Override
    public void precheck(Long userId, CardSummary card, PaymentTerms terms) {
        if (card.cardType() != CardType.CHECK) {
            credit.precheck(userId, card, terms);
        }
    }

    @Override
    public ApprovalOutcome approve(ApprovalCommand command) {
        if (command.card().cardType() == CardType.CHECK) {
            return check.approve(command);
        }
        return credit.approve(command);
    }
}
