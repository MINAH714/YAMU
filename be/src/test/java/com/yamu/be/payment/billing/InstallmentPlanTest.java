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
    void 윤년_2월을_지나는_12개월_할부도_회차_이용기간이_한_달씩_빈틈없이_이어진다() {
        // 결제일 14·N 14(이용기간 = 전월 1일~말일), 2027-12-31 승인 → 2028-01 청구서부터 12회
        BillingCycle monthly = new BillingCycle(14, 14);
        InstallmentPlan plan = InstallmentPlan.of(120_000, 12, monthly, LocalDate.of(2027, 12, 31), calendar);

        List<Installment> installments = plan.installments();
        assertThat(installments).extracting(Installment::billingMonth)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 12)
                        .mapToObj(k -> YearMonth.of(2028, 1).plusMonths(k)).toList());
        Installment march = installments.get(2);
        assertThat(march.cycle().periodStart()).isEqualTo(LocalDate.of(2028, 2, 1));
        assertThat(march.cycle().periodEnd()).isEqualTo(LocalDate.of(2028, 2, 29));
        for (int k = 0; k < installments.size(); k++) {
            Installment each = installments.get(k);
            YearMonth used = each.billingMonth().minusMonths(1);
            assertThat(each.cycle().periodStart()).isEqualTo(used.atDay(1));
            assertThat(each.cycle().periodEnd()).isEqualTo(used.atEndOfMonth());
            assertThat(each.withdrawalDate()).isEqualTo(calendar.businessDayOnOrAfter(each.billingMonth().atDay(14)));
            if (k > 0) {
                assertThat(each.cycle().periodStart())
                        .isEqualTo(installments.get(k - 1).cycle().periodEnd().plusDays(1));
            }
        }
    }

    @Test
    void 윤년_2월_29일_승인은_3월_청구서부터_회차가_붙는다() {
        // 결제일 15·N 14: 3월 청구서 이용기간 2/2 ~ 3/1
        InstallmentPlan plan = InstallmentPlan.of(90_000, 3, cycle, LocalDate.of(2028, 2, 29), calendar);

        assertThat(plan.installments()).extracting(Installment::billingMonth)
                .containsExactly(YearMonth.of(2028, 3), YearMonth.of(2028, 4), YearMonth.of(2028, 5));
        assertThat(plan.installments().get(0).cycle().periodStart()).isEqualTo(LocalDate.of(2028, 2, 2));
        assertThat(plan.installments().get(0).cycle().periodEnd()).isEqualTo(LocalDate.of(2028, 3, 1));
    }

    @Test
    void 개월_수_범위와_원금_최소_금액을_검사한다() {
        assertThatThrownBy(() -> InstallmentPlan.of(100_000, 0, cycle, usage, calendar))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InstallmentPlan.of(100_000, 13, cycle, usage, calendar))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InstallmentPlan.of(0, 1, cycle, usage, calendar))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 원금이_개월_수보다_작으면_앞_회차는_0원이고_원금은_마지막_회차에_붙는다() {
        // 즉시할인으로 원금이 5원만 남은 12개월 할부: 개월 수는 POS 조건이라 그대로 둔다
        InstallmentPlan plan = InstallmentPlan.of(5, 12, cycle, usage, calendar);

        assertThat(plan.installments()).hasSize(12);
        assertThat(plan.installments().subList(0, 11)).extracting(Installment::principal).containsOnly(0L);
        assertThat(plan.installments().get(11).principal()).isEqualTo(5);
        assertThat(plan.total()).isEqualTo(5);
    }

    @Test
    void 회차_합계가_원금과_다르면_만들_수_없다() {
        Installment one = InstallmentPlan.of(15_000, 1, cycle, usage, calendar).installments().get(0);

        assertThatThrownBy(() -> new InstallmentPlan(16_000, List.of(one))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InstallmentPlan(0, List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
