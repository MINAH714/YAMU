package com.yamu.be.payment.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yamu.be.payment.billing.BillingCycle.Cycle;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

/** 청구 월 귀속 규칙(V2 3장, POL-005): 이용기간 마감 = 결제일 − N일, 승인일이 마감 이전(포함)이면 그달 청구서. */
class BillingCycleTest {

    final BillingCycle cycle = new BillingCycle(15, 14);

    @Test
    void 마감_전_승인은_그달_청구서에_들어간다() {
        // 결제일 15·N 14: 11월 청구서는 10/2 ~ 11/1 사용분, 11/15 결제
        Cycle november = cycle.cycleFor(LocalDate.of(2026, 10, 22));

        assertThat(november.billingMonth()).isEqualTo(YearMonth.of(2026, 11));
        assertThat(november.periodStart()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(november.periodEnd()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(november.paymentDate()).isEqualTo(LocalDate.of(2026, 11, 15));
    }

    @Test
    void 마감일_당일은_그달이고_다음_날부터_다음_달이다() {
        assertThat(cycle.cycleFor(LocalDate.of(2026, 11, 1)).billingMonth()).isEqualTo(YearMonth.of(2026, 11));
        assertThat(cycle.cycleFor(LocalDate.of(2026, 11, 2)).billingMonth()).isEqualTo(YearMonth.of(2026, 12));
    }

    @Test
    void 마감이_전월로_넘어가는_결제일도_달력으로_역산한다() {
        // 결제일 5·N 14: 11월 청구서 마감 = 10/22, 이용기간 9/22 ~ 10/22
        BillingCycle early = new BillingCycle(5, 14);

        Cycle november = early.cycleFor(LocalDate.of(2026, 10, 22));
        assertThat(november.billingMonth()).isEqualTo(YearMonth.of(2026, 11));
        assertThat(november.periodStart()).isEqualTo(LocalDate.of(2026, 9, 22));
        assertThat(november.periodEnd()).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(november.paymentDate()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(early.cycleFor(LocalDate.of(2026, 10, 23)).billingMonth()).isEqualTo(YearMonth.of(2026, 12));
    }

    @Test
    void 연말에서_새해로_넘어간다() {
        Cycle january = cycle.cycleFor(LocalDate.of(2026, 12, 20));

        assertThat(january.billingMonth()).isEqualTo(YearMonth.of(2027, 1));
        assertThat(january.periodStart()).isEqualTo(LocalDate.of(2026, 12, 2));
        assertThat(january.periodEnd()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(january.paymentDate()).isEqualTo(LocalDate.of(2027, 1, 15));
    }

    @Test
    void 특정_청구_월의_주기를_바로_구한다() {
        Cycle december = cycle.cycleOf(YearMonth.of(2026, 12));

        assertThat(december.periodStart()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(december.periodEnd()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(december.paymentDate()).isEqualTo(LocalDate.of(2026, 12, 15));
    }

    @Test
    void 상품에_마감_일수가_없으면_14일이다() {
        assertThat(BillingCycle.of(15, null).cutoffDays()).isEqualTo(14);
        assertThat(BillingCycle.of(15, 20).cutoffDays()).isEqualTo(20);
    }

    @Test
    void 결제일과_마감_일수의_범위를_검사한다() {
        assertThatThrownBy(() -> new BillingCycle(0, 14)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BillingCycle(28, 14)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BillingCycle(15, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BillingCycle(15, 32)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void supports는_of가_예외_없이_만들_수_있는_값만_참이다() {
        assertThat(BillingCycle.supports(1, 1)).isTrue();
        assertThat(BillingCycle.supports(27, 31)).isTrue();
        assertThat(BillingCycle.supports(15, null)).isTrue();
        assertThat(BillingCycle.supports(0, 14)).isFalse();
        assertThat(BillingCycle.supports(28, 14)).isFalse();
        assertThat(BillingCycle.supports(31, null)).isFalse();
        assertThat(BillingCycle.supports(15, 0)).isFalse();
        assertThat(BillingCycle.supports(15, 32)).isFalse();
    }
}
