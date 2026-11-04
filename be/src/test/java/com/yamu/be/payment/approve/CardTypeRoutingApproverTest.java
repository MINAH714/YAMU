package com.yamu.be.payment.approve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yamu.be.card.domain.CardType;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.PaymentTerms;
import com.yamu.be.payment.recommend.RecommendationResult.CardSummary;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
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
        ApprovalOutcome approved = new ApprovalOutcome.Approved(15_000, 15_000, 0, LocalDate.of(2026, 10, 1), List.of(),
                null, null, null);
        when(credit.approve(creditCommand)).thenReturn(approved);

        assertThat(router.approve(checkCommand)).isSameAs(declined);
        verifyNoInteractions(credit);

        assertThat(router.approve(creditCommand)).isSameAs(approved);
        verify(credit).approve(creditCommand);
    }

    @Test
    void 사전_검사는_신용카드만_신용_승인기로_보낸다() {
        CardSummary checkCard = command(CardType.CHECK).card();
        CardSummary creditCard = command(CardType.CREDIT).card();
        PaymentTerms terms = PaymentTerms.lumpSum(15_000);

        router.precheck(7L, checkCard, terms);
        verifyNoInteractions(credit);

        router.precheck(7L, creditCard, terms);
        verify(credit).precheck(7L, creditCard, terms);
        verifyNoInteractions(check);
    }

    @Test
    void 신용_승인_실행_중_업무_오류는_되돌림으로_바꾸고_그_오류를_싣는다() {
        ApprovalCommand creditCommand = command(CardType.CREDIT);
        BusinessException notFound = new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        when(credit.approve(creditCommand)).thenThrow(notFound);

        assertThatThrownBy(() -> router.approve(creditCommand))
                .isInstanceOfSatisfying(ApprovalNotSentException.class, e -> {
                    assertThat(e.reply()).containsSame(notFound);
                    assertThat(e.getCause()).isSameAs(notFound);
                });
    }

    @Test
    void 신용_승인_실행_중_다른_예외도_되돌림이고_500을_싣는다() {
        ApprovalCommand creditCommand = command(CardType.CREDIT);
        IllegalStateException failure = new IllegalStateException("db");
        when(credit.approve(creditCommand)).thenThrow(failure);

        assertThatThrownBy(() -> router.approve(creditCommand))
                .isInstanceOfSatisfying(ApprovalNotSentException.class, e -> {
                    assertThat(e.reply()).hasValueSatisfying(
                            reply -> assertThat(reply.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
                    assertThat(e.getCause()).isSameAs(failure);
                });
    }

    @Test
    void 체크카드_실행_예외는_바꾸지_않는다() {
        ApprovalCommand checkCommand = command(CardType.CHECK);
        BusinessException unknown = new BusinessException(ErrorCode.FIN_API_UNAVAILABLE);
        when(check.approve(checkCommand)).thenThrow(unknown);

        assertThatThrownBy(() -> router.approve(checkCommand)).isSameAs(unknown);
    }

    private static ApprovalCommand command(CardType type) {
        CardSummary card = new CardSummary(15L, 3L, type, false, null, "1234", "TEST", "카드");
        return new ApprovalCommand(1024L, 7L, PaymentTerms.lumpSum(15_000), card, null,
                LocalDateTime.of(2026, 10, 22, 5, 30), LocalDateTime.of(2026, 10, 22, 5, 30, 5), 1);
    }
}
