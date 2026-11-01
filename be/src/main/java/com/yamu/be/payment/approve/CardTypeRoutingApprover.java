package com.yamu.be.payment.approve;

import com.yamu.be.card.domain.CardType;

/**
 * 카드 종류별 승인 실행. 체크카드는 연결 계좌에서 바로 출금하고(Task 5.2.3), 신용카드는 계좌를 건드리지 않는다(Task 5.2.4).
 */
public class CardTypeRoutingApprover implements PaymentApprover {

    private final CheckCardApprover check;
    private final CreditCardApprover credit;

    public CardTypeRoutingApprover(CheckCardApprover check, CreditCardApprover credit) {
        this.check = check;
        this.credit = credit;
    }

    @Override
    public ApprovalOutcome approve(ApprovalCommand command) {
        if (command.card().cardType() == CardType.CHECK) {
            return check.approve(command);
        }
        return credit.approve(command);
    }
}
