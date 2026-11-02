package com.yamu.be.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.yamu.be.card.domain.CardType;
import com.yamu.be.forecast.BillingSchedule.Withdrawal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BillingScheduleTest {
    /** 2027-03-01(월) 삼일절. 실제 공휴일 API 대신 쓰는 테스트용 공휴일이다. */
    private static final Set<LocalDate> HOLIDAYS = Set.of(LocalDate.of(2027, 3, 1));
    /** 주말과 HOLIDAYS를 건너뛰는 가짜 달력. */
    private static final BusinessCalendar CALENDAR = date -> {
        LocalDate day = date;
        while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY
                || HOLIDAYS.contains(day)) {
            day = day.plusDays(1);
        }
        return day;
    };

    private final BillingSchedule schedule = new BillingSchedule(CALENDAR);

    @Test
    void 명세_예시_120만_원_3개월_할부는_회차마다_원래_결제일에서_영업일로_옮긴다() {
        // 가상 구매 시뮬레이션 명세: 10/24 구매, 결제일 14일, N=14 → 11/16(11/14 토요일), 12/14, 1/14에 40만 원씩
        List<Withdrawal> result = schedule.schedule(CardType.CREDIT, 14, 14, LocalDate.of(2026, 10, 24),
                1_200_000L, 3);

        assertThat(result)
                .extracting(Withdrawal::installmentNo, Withdrawal::billingMonth, Withdrawal::paymentDate,
                        Withdrawal::withdrawalDate, Withdrawal::amount)
                .containsExactly(
                        tuple(1, YearMonth.of(2026, 11), LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 16),
                                400_000L),
                        // 1회차 실제 출금일(11/16)이 아니라 원래 결제일(12/14) 기준이라 연말에서 다음 해로 넘어가도 14일이다.
                        tuple(2, YearMonth.of(2026, 12), LocalDate.of(2026, 12, 14), LocalDate.of(2026, 12, 14),
                                400_000L),
                        tuple(3, YearMonth.of(2027, 1), LocalDate.of(2027, 1, 14), LocalDate.of(2027, 1, 14),
                                400_000L));
        assertThat(result).allMatch(withdrawal -> withdrawal.installmentMonths() == 3);
    }

    @Test
    void POL_005_마감일_당일_구매는_그달_청구이고_다음_날_구매는_다음_달_청구다() {
        // 결제일 2027-03-14·N=14 → 마감 2/28(평년), 2028-03-14 → 2/29(윤년), 결제일 25일 → 3/11
        assertThat(firstBillingMonth(14, 14, LocalDate.of(2027, 2, 28))).isEqualTo(YearMonth.of(2027, 3));
        assertThat(firstBillingMonth(14, 14, LocalDate.of(2027, 3, 1))).isEqualTo(YearMonth.of(2027, 4));
        assertThat(firstBillingMonth(14, 14, LocalDate.of(2028, 2, 29))).isEqualTo(YearMonth.of(2028, 3));
        assertThat(firstBillingMonth(14, 14, LocalDate.of(2028, 3, 1))).isEqualTo(YearMonth.of(2028, 4));
        assertThat(firstBillingMonth(25, 14, LocalDate.of(2027, 3, 11))).isEqualTo(YearMonth.of(2027, 3));
        assertThat(firstBillingMonth(25, 14, LocalDate.of(2027, 3, 12))).isEqualTo(YearMonth.of(2027, 4));
    }

    @Test
    void 결제일_27일_N_31은_하루_차이로_청구_월과_출금일이_한_달_밀린다() {
        // 2027-03-27 청구의 마감일은 3/27 − 31일 = 2/24. 3/27(토) → 3/29(월), 4/27(화)은 영업일이다.
        assertThat(schedule.schedule(CardType.CREDIT, 27, 31, LocalDate.of(2027, 2, 24), 10_000L, 1))
                .extracting(Withdrawal::billingMonth, Withdrawal::paymentDate, Withdrawal::withdrawalDate)
                .containsExactly(tuple(YearMonth.of(2027, 3), LocalDate.of(2027, 3, 27), LocalDate.of(2027, 3, 29)));
        assertThat(schedule.schedule(CardType.CREDIT, 27, 31, LocalDate.of(2027, 2, 25), 10_000L, 1))
                .extracting(Withdrawal::billingMonth, Withdrawal::paymentDate, Withdrawal::withdrawalDate)
                .containsExactly(tuple(YearMonth.of(2027, 4), LocalDate.of(2027, 4, 27), LocalDate.of(2027, 4, 27)));
    }

    @Test
    void 마감_일수가_없으면_14일로_계산한다() {
        LocalDate purchase = LocalDate.of(2027, 3, 1);

        assertThat(schedule.schedule(CardType.CREDIT, 14, null, purchase, 10_000L, 1))
                .isEqualTo(schedule.schedule(CardType.CREDIT, 14, 14, purchase, 10_000L, 1));
    }

    @Test
    void 공휴일이_겹쳐_출금일이_다음_달로_넘어가도_청구_월은_그대로다() {
        // 결제일 2/27(토) → 2/28(일) → 3/1(월, 삼일절) → 3/2(화)
        List<Withdrawal> result = schedule.schedule(CardType.CREDIT, 27, 14, LocalDate.of(2027, 2, 1),
                50_000L, 1);

        assertThat(result)
                .extracting(Withdrawal::billingMonth, Withdrawal::paymentDate, Withdrawal::withdrawalDate)
                .containsExactly(tuple(YearMonth.of(2027, 2), LocalDate.of(2027, 2, 27), LocalDate.of(2027, 3, 2)));
    }

    @Test
    void 체크카드는_구매일이_주말이어도_그날_한_번에_빠진다() {
        LocalDate saturday = LocalDate.of(2026, 10, 24);

        assertThat(schedule.schedule(CardType.CHECK, null, null, saturday, 30_000L, 1))
                .containsExactly(new Withdrawal(1, 1, null, saturday, saturday, 30_000L));
    }

    @Test
    void 할부_나머지는_마지막_회차에_붙이고_회차_합계는_원금과_같다() {
        assertThat(schedule.schedule(CardType.CREDIT, 14, 14, LocalDate.of(2026, 10, 24), 100_000L, 3))
                .extracting(Withdrawal::amount)
                .containsExactly(33_333L, 33_333L, 33_334L);

        // 아주 큰 금액도 나누기만 해서 넘치지 않고 합계가 정확히 원금이다.
        List<Withdrawal> huge = schedule.schedule(CardType.CREDIT, 14, 14, LocalDate.of(2026, 10, 24),
                Long.MAX_VALUE, 7);
        assertThat(huge.stream().mapToLong(Withdrawal::amount).reduce(0L, Math::addExact)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void 할부_5만_원_규칙은_결제_조건_검증이_맡아_여기서는_거절하지_않는다() {
        // 즉시할인을 뺀 승인 금액이 5만 원 아래로 내려가도 원래 결제 금액으로 이미 검증된 할부다.
        assertThat(schedule.schedule(CardType.CREDIT, 14, 14, LocalDate.of(2026, 10, 24), 49_000L, 2))
                .hasSize(2);
    }

    @Test
    void 계산이_성립하지_않는_값은_거절한다() {
        LocalDate purchase = LocalDate.of(2026, 10, 24);
        List<Runnable> invalid = List.of(
                () -> schedule.schedule(CardType.CREDIT, 14, 14, purchase, 0L, 1),
                // 0원 회차가 생기는 금액(2원 3개월 → 0 / 0 / 2)
                () -> schedule.schedule(CardType.CREDIT, 14, 14, purchase, 2L, 3),
                () -> schedule.schedule(CardType.CREDIT, 14, 14, purchase, 10_000L, 0),
                () -> schedule.schedule(CardType.CREDIT, 14, 14, purchase, 100_000L, 13),
                () -> schedule.schedule(CardType.CHECK, null, null, purchase, 100_000L, 3),
                () -> schedule.schedule(CardType.CREDIT, null, 14, purchase, 10_000L, 1),
                () -> schedule.schedule(CardType.CREDIT, 0, 14, purchase, 10_000L, 1),
                () -> schedule.schedule(CardType.CREDIT, 28, 14, purchase, 10_000L, 1),
                () -> schedule.schedule(CardType.CREDIT, 14, 0, purchase, 10_000L, 1),
                () -> schedule.schedule(CardType.CREDIT, 14, 32, purchase, 10_000L, 1),
                () -> schedule.schedule(null, 14, 14, purchase, 10_000L, 1),
                () -> schedule.schedule(CardType.CREDIT, 14, 14, null, 10_000L, 1));
        for (Runnable call : invalid) {
            assertThatThrownBy(call::run).isInstanceOf(IllegalArgumentException.class);
        }
        // 경계 값은 된다: 할부 2·12개월, 결제일 1·27, 마감 일수 1·31, 회차마다 1원(3원 3개월)
        assertThat(schedule.schedule(CardType.CREDIT, 1, 1, purchase, 100_000L, 2)).hasSize(2);
        assertThat(schedule.schedule(CardType.CREDIT, 27, 31, purchase, 100_000L, 12)).hasSize(12);
        assertThat(schedule.schedule(CardType.CREDIT, 14, 14, purchase, 3L, 3))
                .extracting(Withdrawal::amount).containsExactly(1L, 1L, 1L);
    }

    @Test
    void 영업일_달력이_앞선_날을_주면_계약_위반으로_실패한다() {
        BillingSchedule broken = new BillingSchedule(date -> date.minusDays(1));

        assertThatThrownBy(() -> broken.schedule(CardType.CREDIT, 14, 14, LocalDate.of(2026, 10, 24), 10_000L, 1))
                .isInstanceOf(IllegalStateException.class);
    }

    private YearMonth firstBillingMonth(int paymentDay, int cutoffDays, LocalDate purchase) {
        return schedule.schedule(CardType.CREDIT, paymentDay, cutoffDays, purchase, 10_000L, 1).get(0).billingMonth();
    }
}
