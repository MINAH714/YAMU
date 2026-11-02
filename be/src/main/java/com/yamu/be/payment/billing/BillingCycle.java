package com.yamu.be.payment.billing;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 신용카드 한 장의 청구 주기(POL-005, API 명세 V2 3장). 결제일 P와 이용기간 마감 일수 N으로 "어느 달 청구서에 들어가는지"를 정한다.
 *
 * <pre>
 * M월 청구서: 이용기간 마감 = M월 P일 − N일(달력으로 역산), 시작 = 전월 마감 + 1일, 결제일 = M월 P일
 * 승인일 D는 D ≤ 마감인 첫 달 M의 청구서에 들어간다
 * 예: P=15, N=14 → 11월 청구서는 10/2 ~ 11/1 사용분, 11/15 결제
 * </pre>
 *
 * 결제일은 카드 등록 규칙대로 1~27이라 말일 보정이 없다. 영업일 보정(비영업일이면 다음 영업일)은 출금 예정일에만 하고 청구 월은 바꾸지 않는다.
 * BE4의 가상 구매 일정({@code forecast.BillingSchedule}, Task 7.2.1)과 같은 규칙이며, 그쪽이 이 클래스를 쓰도록 정리할 예정이다.
 */
public record BillingCycle(int paymentDay, int cutoffDays) {

    /** 카드 상품에 이용기간 마감 일수가 없을 때 쓰는 값(청구서 명세 1차 14일, 임시). */
    public static final int DEFAULT_CUTOFF_DAYS = 14;
    static final int MAX_PAYMENT_DAY = 27;
    static final int MAX_CUTOFF_DAYS = 31;

    public BillingCycle {
        if (paymentDay < 1 || paymentDay > MAX_PAYMENT_DAY) {
            throw new IllegalArgumentException("결제일은 1~27이어야 합니다: " + paymentDay);
        }
        if (cutoffDays < 1 || cutoffDays > MAX_CUTOFF_DAYS) {
            throw new IllegalArgumentException("이용기간 마감 일수는 1~31이어야 합니다: " + cutoffDays);
        }
    }

    /** @param billingCutoffDays 상품의 마감 일수. null이면 {@link #DEFAULT_CUTOFF_DAYS} */
    public static BillingCycle of(int paymentDay, Integer billingCutoffDays) {
        return new BillingCycle(paymentDay, billingCutoffDays == null ? DEFAULT_CUTOFF_DAYS : billingCutoffDays);
    }

    /** usageDate(한국 날짜)에 쓴 금액이 들어가는 청구서. */
    public Cycle cycleFor(LocalDate usageDate) {
        YearMonth month = YearMonth.from(usageDate);
        while (usageDate.isAfter(periodEnd(month))) {
            month = month.plusMonths(1);
        }
        return cycleOf(month);
    }

    /** 특정 청구 월의 주기. 할부 k번째 회차는 첫 청구 월에서 k−1달 뒤의 주기다. */
    public Cycle cycleOf(YearMonth month) {
        return new Cycle(month, periodEnd(month.minusMonths(1)).plusDays(1), periodEnd(month), month.atDay(paymentDay));
    }

    private LocalDate periodEnd(YearMonth month) {
        return month.atDay(paymentDay).minusDays(cutoffDays);
    }

    /**
     * @param billingMonth 청구 월
     * @param periodStart  이용기간 시작(포함)
     * @param periodEnd    이용기간 마감(포함)
     * @param paymentDate  결제일(영업일 보정 전)
     */
    public record Cycle(YearMonth billingMonth, LocalDate periodStart, LocalDate periodEnd, LocalDate paymentDate) {}
}
