package com.yamu.be.payment.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yamu.be.payment.billing.InstallmentPlan.Installment;
import com.yamu.be.support.WeekdayCalendar;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 청구 계획(POL-013): 균등 분할·나머지 마지막 회차, 회차 청구 월 연속, 출금 예정일 영업일 보정. */
class InstallmentPlanTest {

    final BillingCycle cycle = new BillingCycle(15, 14);
    final WeekdayCalendar calendar = new WeekdayCalendar();
    /** 2026-10-22(목) 승인 → 11월 청구서. 11/15는 일요일 */
    final LocalDate usage = LocalDate.of(2026, 10, 22);

    @Test
    void 일시불은_회차_하나이고_원금_전부다() {
        InstallmentPlan plan = InstallmentPlan.of(15_000, 1, cycle, usage, calendar);

        assertThat(plan.months()).isEqualTo(1);
        assertThat(plan.total()).isEqualTo(15_000);
        Installment only = plan.installments().get(0);
        assertThat(only.no()).isEqualTo(1);
        assertThat(only.principal()).isEqualTo(15_000);
        assertThat(only.billingMonth()).isEqualTo(YearMonth.of(2026, 11));
        assertThat(only.withdrawalDate()).isEqualTo(LocalDate.of(2026, 11, 16));
    }

    @Test
    void 균등_분할하고_원_단위_나머지는_마지막_회차에_더한다() {
        InstallmentPlan plan = InstallmentPlan.of(100_000, 3, cycle, usage, calendar);

        assertThat(plan.installments()).extracting(Installment::no).containsExactly(1, 2, 3);
        assertThat(plan.installments()).extracting(Installment::principal).containsExactly(33_333L, 33_333L, 33_334L);
        assertThat(plan.installments()).extracting(Installment::billingMonth)
                .containsExactly(YearMonth.of(2026, 11), YearMonth.of(2026, 12), YearMonth.of(2027, 1));
        assertThat(plan.installments()).extracting(Installment::withdrawalDate)
                .containsExactly(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 12, 15), LocalDate.of(2027, 1, 15));
    }

    @Test
    void 출금_예정일을_옮겨도_청구_월은_그대로다() {
        // 결제일 27·N 14: 2027-02-27은 토요일 → 3/1로 밀려도 2월 청구서
        InstallmentPlan plan = InstallmentPlan.of(15_000, 1, new BillingCycle(27, 14), LocalDate.of(2027, 2, 1),
                calendar);

        Installment only = plan.installments().get(0);
        assertThat(only.billingMonth()).isEqualTo(YearMonth.of(2027, 2));
        assertThat(only.withdrawalDate()).isEqualTo(LocalDate.of(2027, 3, 1));
    }

    @Test
    void 어떤_개월_수여도_회차_합계는_원금이다() {
        for (int months = 2; months <= 12; months++) {
            InstallmentPlan plan = InstallmentPlan.of(50_001, months, cycle, usage, calendar);

            assertThat(plan.installments()).hasSize(months);
            assertThat(plan.installments().stream().mapToLong(Installment::principal).sum()).isEqualTo(50_001);
            assertThat(plan.installments().get(months - 1).billingMonth())
                    .isEqualTo(YearMonth.of(2026, 11).plusMonths(months - 1));
        }
    }

    @Test
    void 회차의_주기는_그_청구_월의_이용기간이다() {
        InstallmentPlan plan = InstallmentPlan.of(100_000, 2, cycle, usage, calendar);

        Installment second = plan.installments().get(1);
        assertThat(second.cycle().billingMonth()).isEqualTo(YearMonth.of(2026, 12));
        assertThat(second.cycle().periodStart()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(second.cycle().periodEnd()).isEqualTo(LocalDate.of(2026, 12, 1));
    }

    @Test
    void 개월_수_범위와_회차_최소_금액을_검사한다() {
        assertThatThrownBy(() -> InstallmentPlan.of(100_000, 0, cycle, usage, calendar))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InstallmentPlan.of(100_000, 13, cycle, usage, calendar))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InstallmentPlan.of(2, 3, cycle, usage, calendar))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 회차_합계가_원금과_다르면_만들_수_없다() {
        Installment one = InstallmentPlan.of(15_000, 1, cycle, usage, calendar).installments().get(0);

        assertThatThrownBy(() -> new InstallmentPlan(16_000, List.of(one))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InstallmentPlan(0, List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
