package com.yamu.be.payment.approve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yamu.be.card.CardQueryService;
import com.yamu.be.card.UserCardRef;
import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.card.domain.CardRegistrationStatus;
import com.yamu.be.card.domain.CardType;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.PaymentTerms;
import com.yamu.be.payment.approve.ApprovalOutcome.Approved;
import com.yamu.be.payment.approve.ApprovalOutcome.Declined;
import com.yamu.be.payment.approve.CreditLimitSource.LimitCheck;
import com.yamu.be.payment.billing.InstallmentPlan.Installment;
import com.yamu.be.payment.recommend.RecommendationResult.CardSummary;
import com.yamu.be.support.WeekdayCalendar;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** 신용카드 모의 승인(Task 5.2.4): 한도 판정과 청구 계획. 계좌·금융망은 쓰지 않는다. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class YamuCreditCardApproverTest {

    static final CardSummary CARD = new CardSummary(15L, 3L, CardType.CREDIT, true, "메인 카드", "1234", "TEST",
            "테스트 신용");
    static final CardSummary CHECK = new CardSummary(16L, 4L, CardType.CHECK, false, null, "5678", "TEST",
            "테스트 체크");
    /** 2026-10-22 05:30:05 UTC = 한국 14:30:05(목) → 결제일 15·N 14면 11월 청구서 */
    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 22, 5, 30, 5);

    @Mock CardQueryService cardQuery;
    @Mock CreditLimitSource limits;

    YamuCreditCardApprover approver;

    @BeforeEach
    void setUp() {
        approver = new YamuCreditCardApprover(cardQuery, limits, new WeekdayCalendar());
        when(cardQuery.findRegisteredCard(7L, 15L)).thenReturn(Optional.of(ref(15, 14)));
        when(limits.check(anyLong(), anyLong(), anyLong())).thenReturn(LimitCheck.skipped());
    }

    @Test
    void 한도_검사를_생략하면_승인하고_청구_계획을_돌려준다() {
        Approved approved = (Approved) approver.approve(command(PaymentTerms.installment(100_000, 3)));

        assertThat(approved.approvedAmount()).isEqualTo(100_000);
        assertThat(approved.chargedAmount()).isEqualTo(100_000);
        assertThat(approved.recognizedPerformanceAmount()).isZero();
        assertThat(approved.performanceMonth()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(approved.benefits()).isEmpty();
        assertThat(approved.finRequestNo()).isNull();
        assertThat(approved.finTransactionNo()).isNull();
        assertThat(approved.hasInstallments()).isTrue();
        assertThat(approved.installments().total()).isEqualTo(100_000);
        assertThat(approved.installments().installments()).extracting(Installment::principal)
                .containsExactly(33_333L, 33_333L, 33_334L);
        assertThat(approved.installments().installments()).extracting(Installment::billingMonth)
                .containsExactly(YearMonth.of(2026, 11), YearMonth.of(2026, 12), YearMonth.of(2027, 1));
        assertThat(approved.installments().installments().get(0).withdrawalDate()).isEqualTo(LocalDate.of(2026, 11, 16));
    }

    @Test
    void 일시불은_회차_하나다() {
        Approved approved = (Approved) approver.approve(command(PaymentTerms.lumpSum(15_000)));

        assertThat(approved.installments().months()).isEqualTo(1);
        assertThat(approved.installments().installments().get(0).principal()).isEqualTo(15_000);
    }

    @Test
    void 한도가_충분하면_승인한다() {
        when(limits.check(7L, 15L, 15_000)).thenReturn(LimitCheck.sufficient(500_000));

        assertThat(approver.approve(command(PaymentTerms.lumpSum(15_000)))).isInstanceOf(Approved.class);
    }

    @Test
    void 전체_원금이_가용_한도를_넘으면_CREDIT_LIMIT_EXCEEDED로_거절한다() {
        when(limits.check(7L, 15L, 100_000)).thenReturn(LimitCheck.exceeded(80_000));

        Declined declined = (Declined) approver.approve(command(PaymentTerms.installment(100_000, 3)));

        assertThat(declined.code()).isEqualTo(FailureCode.CREDIT_LIMIT_EXCEEDED);
        assertThat(declined.details()).containsEntry("availableLimit", 80_000L);
    }

    @Test
    void 상품에_마감_일수가_없으면_14일로_계산한다() {
        when(cardQuery.findRegisteredCard(7L, 15L)).thenReturn(Optional.of(ref(15, null)));

        Approved approved = (Approved) approver.approve(command(PaymentTerms.lumpSum(15_000)));

        Installment only = approved.installments().installments().get(0);
        assertThat(only.billingMonth()).isEqualTo(YearMonth.of(2026, 11));
        assertThat(only.cycle().periodEnd()).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    void 등록된_카드가_아니면_RESOURCE_NOT_FOUND다() {
        when(cardQuery.findRegisteredCard(7L, 15L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> approver.approve(command(PaymentTerms.lumpSum(15_000))))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ───────── 사전 검사 ─────────

    @Test
    void 사전_검사는_결제일이_없으면_DATA_INSUFFICIENT다() {
        when(cardQuery.findRegisteredCard(7L, 15L)).thenReturn(Optional.of(ref(null, 14)));

        assertThatThrownBy(() -> approver.precheck(7L, CARD, PaymentTerms.lumpSum(15_000)))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.DATA_INSUFFICIENT);
    }

    @Test
    void 사전_검사는_한도를_모르면_DATA_INSUFFICIENT다() {
        when(limits.check(7L, 15L, 15_000)).thenReturn(LimitCheck.unknown());

        assertThatThrownBy(() -> approver.precheck(7L, CARD, PaymentTerms.lumpSum(15_000)))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.DATA_INSUFFICIENT);
    }

    @Test
    void 사전_검사는_결제일과_한도가_있으면_통과한다() {
        when(limits.check(7L, 15L, 15_000)).thenReturn(LimitCheck.exceeded(1_000));

        // 한도 초과는 사전 검사가 아니라 승인 실행의 거절(DECLINED)이다
        approver.precheck(7L, CARD, PaymentTerms.lumpSum(15_000));
    }

    @Test
    void 사전_검사는_체크카드를_보지_않는다() {
        approver.precheck(7L, CHECK, PaymentTerms.lumpSum(15_000));

        verifyNoInteractions(cardQuery, limits);
    }

    private static ApprovalCommand command(PaymentTerms terms) {
        return new ApprovalCommand(1024L, 7L, terms, CARD, null, NOW.minusSeconds(5), NOW, 1);
    }

    private static UserCardRef ref(Integer paymentDay, Integer billingCutoffDays) {
        return new UserCardRef(15L, 3L, 9L, "1234", CardRegistrationStatus.ACTIVE, CardIssuerStatus.NORMAL,
                CardType.CREDIT, paymentDay, billingCutoffDays);
    }
}
