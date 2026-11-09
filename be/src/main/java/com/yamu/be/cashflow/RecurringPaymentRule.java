package com.yamu.be.cashflow;

import java.time.YearMonth;
import java.util.Map;

/**
 * 같은 카드·가맹점·승인 금액의 결제가 매달 한 번 반복되는지 가르는 규칙(S15P31E103-295). 생활비 원천은 이 규칙에 걸린 결제를
 * 생활비에서 빼고, 반복 지출 탐지(S15P31E103-349)는 같은 규칙에 걸린 키를 예정 지출 후보로 만든다. 두 곳이 같은 규칙을 써야
 * 생활비에서 빠진 돈이 후보로 채워지고, 생활비와 후보에서 두 번 빠지는 결제가 생기지 않는다.
 */
public final class RecurringPaymentRule {
    /** 반복 지출을 셀 기간(그 달 포함, 달 수). */
    public static final int WINDOW_MONTHS = 6;
    /** 이 수 이상의 서로 다른 달에 나오면 반복 지출이다. */
    public static final int MIN_MONTHS = 3;

    private RecurringPaymentRule() {
    }

    /**
     * 그 달과 앞 5개 달 중 같은 키가 나온 서로 다른 달이 3개 이상이고, 그중 두 번 이상 나온 달이 없는지.
     *
     * @param countsByMonth 같은 키의 달별 승인 결제 수(취소 결제는 세지 않음)
     * @param month         판정할 달
     * @return 매달 한 번 반복되는 지출이면 true
     */
    public static boolean recurring(Map<YearMonth, Integer> countsByMonth, YearMonth month) {
        return monthsIn(countsByMonth, month) >= MIN_MONTHS;
    }

    /**
     * 그 달과 앞 5개 달 중 같은 키가 나온 서로 다른 달 수. 두 번 이상 나온 달이 있으면 0이다(매달 한 번이 아님).
     *
     * @param countsByMonth 같은 키의 달별 승인 결제 수
     * @param month         기준 달
     * @return 나온 달 수
     */
    public static int monthsIn(Map<YearMonth, Integer> countsByMonth, YearMonth month) {
        YearMonth first = month.minusMonths(WINDOW_MONTHS - 1);
        int months = 0;
        for (Map.Entry<YearMonth, Integer> entry : countsByMonth.entrySet()) {
            if (entry.getKey().isBefore(first) || entry.getKey().isAfter(month)) {
                continue;
            }
            if (entry.getValue() > 1) {
                return 0;
            }
            months++;
        }
        return months;
    }
}
