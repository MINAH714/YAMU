package com.yamu.be.payment.approve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yamu.be.card.domain.CardType;
import com.yamu.be.payment.PaymentTerms;
import com.yamu.be.payment.recommend.RecommendationResult.CardSummary;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class CardTypeRoutingApproverTest {

    final CheckCardApprover check = mock(CheckCardApprover.class);
    final CreditCardApprover credit = mock(CreditCardApprover.class);
    final CardTypeRoutingApprover router = new CardTypeRoutingApprover(check, credit);

    @Test
    void 체크카드는_출금_승인으로_신용카드는_신용_승인으로_간다() {
        ApprovalOutcome declined = ApprovalOutcome.Declined.of(FailureCode.INSUFFICIENT_BALANCE);
        ApprovalCommand checkCommand = command(CardType.CHECK);
        ApprovalCommand creditCommand = command(CardType.CREDIT);
        when(check.approve(checkCommand)).thenReturn(declined);
        when(credit.approve(creditCommand)).thenReturn(new TemporaryCreditCardApprover().approve(creditCommand));

        assertThat(router.approve(checkCommand)).isSameAs(declined);
        verifyNoInteractions(credit);

        assertThat(router.approve(creditCommand)).isInstanceOf(ApprovalOutcome.Approved.class);
        verify(credit).approve(creditCommand);
    }

    private static ApprovalCommand command(CardType type) {
        CardSummary card = new CardSummary(15L, 3L, type, false, null, "1234", "TEST", "카드");
        return new ApprovalCommand(1024L, 7L, PaymentTerms.lumpSum(15_000), card, null,
                LocalDateTime.of(2026, 10, 22, 5, 30), LocalDateTime.of(2026, 10, 22, 5, 30, 5), 1);
    }
}
