package com.yamu.be.payment.approve;

import com.yamu.be.card.domain.CardType;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
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
        try {
            return credit.approve(command);
        } catch (ApprovalNotSentException e) {
            throw e;
        } catch (BusinessException e) {
            // 신용 승인은 금융망·계좌에 아무것도 보내지 않으므로 실행 중 실패는 결과 불명이 아니다.
            // 결제를 되돌리고(PROCESSING에 남기지 않음) 그 오류로 답한다. 예: 사전 검사 뒤 카드 해지 → 404
            throw new ApprovalNotSentException("신용 승인 실행 실패(외부로 보낸 것 없음): " + e.getErrorCode(), e, e);
        } catch (RuntimeException e) {
            throw new ApprovalNotSentException("신용 승인 실행 실패(외부로 보낸 것 없음): " + e, e,
                    new BusinessException(ErrorCode.INTERNAL_ERROR));
        }
    }
}
