package com.yamu.be.payment.result;

import com.yamu.be.card.CardIssuers;
import com.yamu.be.payment.PaymentPlan;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.lookup.PaymentCardLookup;
import java.util.Optional;
import java.util.function.Function;

/** POS 영수증에 보일 카드. 결과 조회({@link PosPaymentResultService})와 내역 조회({@link PosPaymentHistoryService})가 같이 쓴다. */
final class PosReceiptCards {

    private PosReceiptCards() {}

    /**
     * 승인·거절로 정해진 카드. 승인 전(REQUESTED)·처리 중(PROCESSING)의 카드는 아직 결과가 아니라 보이지 않는다.
     * 승인 되돌리기({@code releaseApproval}) 뒤의 REQUESTED는 행에 카드가 남아 있어도 숨긴다.
     *
     * 카드사는 코드가 아니라 이름(예: 신한카드)으로 내린다. POS 영수증이 그대로 보여 준다.
     *
     * @param receiptCard 보유 카드 번호로 카드사·종류·끝 4자리를 읽는다. 내역 조회는 같은 카드를 한 번만 읽게 캐시를 넘긴다
     */
    static PosPaymentResult.Card of(PaymentTransaction payment,
            Function<Long, Optional<PaymentCardLookup.ReceiptCard>> receiptCard) {
        PaymentStatus status = payment.getStatus();
        if (payment.getUserCardId() == null || status == PaymentStatus.REQUESTED
                || status == PaymentStatus.PROCESSING) {
            return null;
        }
        PaymentPlan plan = payment.isInstallment() ? PaymentPlan.INSTALLMENT : PaymentPlan.LUMP_SUM;
        Integer months = payment.isInstallment() ? payment.getInstallmentMonths() : null;
        return receiptCard.apply(payment.getUserCardId())
                .map(card -> new PosPaymentResult.Card(CardIssuers.of(card.issuerCode()).name(), card.cardType(),
                        "****-****-****-" + card.last4(), plan, months))
                .orElse(null);
    }
}
