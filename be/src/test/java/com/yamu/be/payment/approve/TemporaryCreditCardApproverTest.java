package com.yamu.be.payment.approve;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.card.domain.CardType;
import com.yamu.be.payment.PaymentTerms;
import com.yamu.be.payment.recommend.RecommendationResult.CardSummary;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class TemporaryCreditCardApproverTest {

    final TemporaryCreditCardApprover approver = new TemporaryCreditCardApprover();
    final CardSummary card = new CardSummary(15L, 3L, CardType.CREDIT, true, null, "1234", "TEST", "테스트 카드");

    @Test
    void 요청_금액_그대로_혜택_없이_승인하고_실적_칸은_0과_한국_월이며_출금_번호는_없다() {
        // UTC 10/31 15:30 = 한국 11/1 00:30 → 실적 월은 11월
        ApprovalOutcome outcome = approver.approve(new ApprovalCommand(1024L, 7L,
                PaymentTerms.installment(90_000, 3), card, null, LocalDateTime.of(2026, 10, 31, 15, 29),
                LocalDateTime.of(2026, 10, 31, 15, 30), 1));

        assertThat(outcome).isInstanceOf(ApprovalOutcome.Approved.class);
        ApprovalOutcome.Approved approved = (ApprovalOutcome.Approved) outcome;
        assertThat(approved.approvedAmount()).isEqualTo(90_000);
        assertThat(approved.chargedAmount()).isEqualTo(90_000);
        assertThat(approved.recognizedPerformanceAmount()).isZero();
        assertThat(approved.performanceMonth()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(approved.benefits()).isEmpty();
        assertThat(approved.finRequestNo()).isNull();
        assertThat(approved.finTransactionNo()).isNull();
    }
}
