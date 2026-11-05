package com.yamu.be.forecast;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.card.UserCardRef;
import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.card.domain.CardRegistrationStatus;
import com.yamu.be.card.domain.CardType;
import com.yamu.be.global.calendar.BusinessDayCalendar;
import com.yamu.be.support.WeekdayCalendar;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CardBillingWithdrawalScheduleTest {
    /** 2027-03-01(월) 삼일절. 실제 공휴일 자료 대신 쓰는 테스트용 공휴일이다. */
    private static final Set<LocalDate> HOLIDAYS = Set.of(LocalDate.of(2027, 3, 1));
    private static final BusinessDayCalendar CALENDAR = new WeekdayCalendar() {
        @Override
        public boolean isBusinessDay(LocalDate date) {
            return super.isBusinessDay(date) && !HOLIDAYS.contains(date);
        }
    };

    private final CardBillingWithdrawalSchedule schedule = new CardBillingWithdrawalSchedule(CALENDAR);

    @Test
    void 명세_예시처럼_결제일이_토요일이면_다음_영업일에_빠진다() {
        // 가상 구매 시뮬레이션 명세: 10/24 사용, 결제일 14일, N=14 → 11/14(토) → 11/16(월)
        assertThat(schedule.withdrawalDate(credit(14, 14), LocalDate.of(2026, 10, 24)))
                .contains(LocalDate.of(2026, 11, 16));
    }

    @Test
    void 마감일까지는_그달_결제일에_마감_다음_날부터는_다음_달_결제일에_빠진다() {
        // 결제일 14일, N=14 → 11월 청구 마감은 10/31
        assertThat(schedule.withdrawalDate(credit(14, 14), LocalDate.of(2026, 10, 31)))
                .contains(LocalDate.of(2026, 11, 16));
        assertThat(schedule.withdrawalDate(credit(14, 14), LocalDate.of(2026, 11, 1)))
                .contains(LocalDate.of(2026, 12, 14));
    }

    @Test
    void 이용기간_마감_일수가_없으면_14일로_센다() {
        assertThat(schedule.withdrawalDate(credit(14, null), LocalDate.of(2026, 10, 31)))
                .isEqualTo(schedule.withdrawalDate(credit(14, 14), LocalDate.of(2026, 10, 31)));
        assertThat(schedule.withdrawalDate(credit(14, null), LocalDate.of(2026, 11, 1)))
                .contains(LocalDate.of(2026, 12, 14));
    }

    @Test
    void 결제일이_공휴일이면_다음_영업일에_빠진다() {
        // 결제일 1일, N=14 → 3월 청구 마감은 2/15, 결제일 3/1(월, 삼일절) → 3/2
        assertThat(schedule.withdrawalDate(credit(1, 14), LocalDate.of(2027, 2, 10)))
                .contains(LocalDate.of(2027, 3, 2));
    }

    @Test
    void 결제일이_없거나_청구_주기를_만들_수_없는_값이면_empty다() {
        LocalDate spend = LocalDate.of(2026, 10, 24);

        assertThat(schedule.withdrawalDate(credit(null, 14), spend)).isEmpty();
        // DB는 결제일 1~31을 허용하지만 청구 주기는 1~27만 된다(신용 승인도 거절)
        assertThat(schedule.withdrawalDate(credit(28, 14), spend)).isEmpty();
        assertThat(schedule.withdrawalDate(credit(14, 32), spend)).isEmpty();
    }

    @Test
    void 가상_구매_청구_일정의_일시불_출금일과_같다() {
        // 같은 규칙이 BillingSchedule(7.2.1)에도 있다. 하나로 합치기 전까지 두 계산이 어긋나지 않는지 확인한다.
        BillingSchedule billingSchedule = new BillingSchedule(CALENDAR);
        for (int paymentDay : new int[] {1, 14, 27}) {
            for (Integer cutoffDays : new Integer[] {null, 1, 14, 31}) {
                for (LocalDate spend = LocalDate.of(2026, 12, 1); spend.isBefore(LocalDate.of(2027, 4, 1));
                        spend = spend.plusDays(1)) {
                    LocalDate expected = billingSchedule.schedule(CardType.CREDIT, paymentDay, cutoffDays, spend, 10_000L, 1)
                            .get(0).withdrawalDate();
                    assertThat(schedule.withdrawalDate(credit(paymentDay, cutoffDays), spend))
                            .as("결제일 %d, N=%s, 사용일 %s", paymentDay, cutoffDays, spend)
                            .isEqualTo(Optional.of(expected));
                }
            }
        }
    }

    private static UserCardRef credit(Integer paymentDay, Integer billingCutoffDays) {
        return new UserCardRef(1L, 1L, 3L, "1111", CardRegistrationStatus.ACTIVE, CardIssuerStatus.NORMAL,
                CardType.CREDIT, paymentDay, billingCutoffDays);
    }
}
