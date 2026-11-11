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

    // ───────── 월말·윤년(Task 5.4.10) ─────────

    @Test
    void 결제일과_마감_일수가_같으면_이용기간은_전월_1일부터_말일까지다() {
        // 결제일 14·N 14: M월 청구서 마감 = M월 14일 − 14일 = 전월 말일. 2월은 평년 28일·윤년 29일
        BillingCycle monthly = new BillingCycle(14, 14);

        assertThat(monthly.cycleOf(YearMonth.of(2027, 3))).isEqualTo(new Cycle(YearMonth.of(2027, 3),
                LocalDate.of(2027, 2, 1), LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 14)));
        assertThat(monthly.cycleOf(YearMonth.of(2028, 3))).isEqualTo(new Cycle(YearMonth.of(2028, 3),
                LocalDate.of(2028, 2, 1), LocalDate.of(2028, 2, 29), LocalDate.of(2028, 3, 14)));
        assertThat(monthly.cycleOf(YearMonth.of(2027, 2))).isEqualTo(new Cycle(YearMonth.of(2027, 2),
                LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31), LocalDate.of(2027, 2, 14)));
        assertThat(monthly.cycleOf(YearMonth.of(2027, 1))).isEqualTo(new Cycle(YearMonth.of(2027, 1),
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 14)));
    }

    @Test
    void 말일_승인은_그달_마감이고_다음_날_승인은_다음_청구서다() {
        BillingCycle monthly = new BillingCycle(14, 14);

        assertThat(monthly.cycleFor(LocalDate.of(2027, 2, 28)).billingMonth()).isEqualTo(YearMonth.of(2027, 3));
        assertThat(monthly.cycleFor(LocalDate.of(2027, 3, 1)).billingMonth()).isEqualTo(YearMonth.of(2027, 4));
        assertThat(monthly.cycleFor(LocalDate.of(2028, 2, 28)).billingMonth()).isEqualTo(YearMonth.of(2028, 3));
        assertThat(monthly.cycleFor(LocalDate.of(2028, 2, 29)).billingMonth()).isEqualTo(YearMonth.of(2028, 3));
        assertThat(monthly.cycleFor(LocalDate.of(2028, 3, 1)).billingMonth()).isEqualTo(YearMonth.of(2028, 4));
        assertThat(monthly.cycleFor(LocalDate.of(2027, 1, 31)).billingMonth()).isEqualTo(YearMonth.of(2027, 2));
        assertThat(monthly.cycleFor(LocalDate.of(2026, 12, 31)).billingMonth()).isEqualTo(YearMonth.of(2027, 1));
        assertThat(monthly.cycleFor(LocalDate.of(2027, 1, 1)).billingMonth()).isEqualTo(YearMonth.of(2027, 2));
    }

    @Test
    void 이용기간에_2월이_끼면_평년은_28일_윤년은_29일이고_2월_29일도_그_청구서에_들어간다() {
        // 결제일 15·N 14: 3월 청구서 = 2/2 ~ 3/1
        Cycle march2027 = cycle.cycleFor(LocalDate.of(2027, 2, 28));
        Cycle march2028 = cycle.cycleFor(LocalDate.of(2028, 2, 29));

        assertThat(march2027).isEqualTo(new Cycle(YearMonth.of(2027, 3), LocalDate.of(2027, 2, 2),
                LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 15)));
        assertThat(march2028).isEqualTo(new Cycle(YearMonth.of(2028, 3), LocalDate.of(2028, 2, 2),
                LocalDate.of(2028, 3, 1), LocalDate.of(2028, 3, 15)));
        assertThat(daysOf(march2027)).isEqualTo(28);
        assertThat(daysOf(march2028)).isEqualTo(29);
        assertThat(cycle.cycleFor(LocalDate.of(2028, 3, 1)).billingMonth()).isEqualTo(YearMonth.of(2028, 3));
        assertThat(cycle.cycleFor(LocalDate.of(2028, 3, 2)).billingMonth()).isEqualTo(YearMonth.of(2028, 4));
    }

    @Test
    void 마감_역산이_2월을_건너뛰어도_결제일_27일_마감_31일의_주기가_이어진다() {
        // 결제일 27·N 31(허용 최대): 3월 청구서 마감 = 3/27 − 31일 = 2/24, 2월 청구서 마감 = 2/27 − 31일 = 1/27
        BillingCycle longest = new BillingCycle(27, 31);

        assertThat(longest.cycleOf(YearMonth.of(2027, 3))).isEqualTo(new Cycle(YearMonth.of(2027, 3),
                LocalDate.of(2027, 1, 28), LocalDate.of(2027, 2, 24), LocalDate.of(2027, 3, 27)));
        assertThat(longest.cycleOf(YearMonth.of(2028, 3))).isEqualTo(new Cycle(YearMonth.of(2028, 3),
                LocalDate.of(2028, 1, 28), LocalDate.of(2028, 2, 25), LocalDate.of(2028, 3, 27)));
        // 결제일 1·N 31: 3월 청구서 마감 = 3/1 − 31일. 평년 1/29, 윤년 1/30
        BillingCycle earliest = new BillingCycle(1, 31);
        assertThat(earliest.cycleOf(YearMonth.of(2027, 3)).periodEnd()).isEqualTo(LocalDate.of(2027, 1, 29));
        assertThat(earliest.cycleOf(YearMonth.of(2028, 3)).periodEnd()).isEqualTo(LocalDate.of(2028, 1, 30));
    }

    @Test
    void 모든_결제일_마감_일수에서_평년_윤년_내내_하루도_빠지거나_겹치지_않는다() {
        // 2026-12 ~ 2029-01: 평년 2027·윤년 2028·평년 2029 2월과 연말 두 번을 지난다
        LocalDate from = LocalDate.of(2026, 12, 1);
        LocalDate to = LocalDate.of(2029, 1, 31);
        for (int paymentDay = 1; paymentDay <= 27; paymentDay++) {
            for (int cutoffDays = 1; cutoffDays <= 31; cutoffDays++) {
                BillingCycle each = new BillingCycle(paymentDay, cutoffDays);
                Cycle previous = null;
                for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
                    Cycle current = each.cycleFor(day);
                    String where = "결제일 " + paymentDay + "·마감 " + cutoffDays + "·승인일 " + day;
                    assertThat(day).as(where).isBetween(current.periodStart(), current.periodEnd());
                    assertThat(current.paymentDate()).as(where)
                            .isEqualTo(current.periodEnd().plusDays(cutoffDays))
                            .isEqualTo(current.billingMonth().atDay(paymentDay));
                    assertThat(current).as(where).isEqualTo(each.cycleOf(current.billingMonth()));
                    if (previous != null && !previous.equals(current)) {
                        // 청구서가 바뀌는 날은 이전 마감 다음 날이고, 청구 월은 한 달씩만 넘어간다
                        assertThat(current.periodStart()).as(where).isEqualTo(previous.periodEnd().plusDays(1))
                                .isEqualTo(day);
                        assertThat(current.billingMonth()).as(where).isEqualTo(previous.billingMonth().plusMonths(1));
                    }
                    previous = current;
                }
            }
        }
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

    private static long daysOf(Cycle cycle) {
        return java.time.temporal.ChronoUnit.DAYS.between(cycle.periodStart(), cycle.periodEnd()) + 1;
    }
}
