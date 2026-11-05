package com.yamu.be.payment.billing;

import com.yamu.be.global.calendar.BusinessDayCalendar;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * 신용카드 결제 한 건의 청구 계획(POL-013): 회차별 원금과 청구 월·출금 예정일. 승인 실행이 계산하고 결과 저장이 회차로 적는다.
 *
 * <ul>
 *   <li>원금(결제 금액 − 즉시할인)을 개월 수로 균등 분할하고 원 단위 나머지는 마지막 회차에 더한다. 수수료 0원</li>
 *   <li>첫 회차는 승인일이 들어가는 청구 월({@link BillingCycle#cycleFor}), k번째는 그 달 + (k−1)</li>
 *   <li>출금 예정일 = 그 달 결제일을 영업일로 보정. 옮겨도 청구 월은 그대로</li>
 * </ul>
 *
 * @param total        원금 합계(= 승인 원금)
 * @param installments 회차 순서대로
 */
public record InstallmentPlan(long total, List<Installment> installments) {

    static final int MAX_MONTHS = 12;

    public InstallmentPlan {
        installments = List.copyOf(installments);
        long sum = installments.stream().mapToLong(Installment::principal).sum();
        if (installments.isEmpty() || sum != total) {
            throw new IllegalArgumentException("회차 원금 합계(" + sum + ")가 원금(" + total + ")과 다릅니다");
        }
    }

    /**
     * @param amount    승인 원금(원, 즉시할인 뒤). 1원 이상. 개월 수보다 작으면(즉시할인으로 원금이 크게 준 할부) 앞 회차는
     *                  0원이고 원금은 마지막 회차에 붙는다. 0원 회차도 POS가 정한 개월 수를 지키기 위해 만든다
     * @param months    일시불 1, 할부 2~12
     * @param usageDate 승인일(한국 날짜)
     */
    public static InstallmentPlan of(long amount, int months, BillingCycle cycle, LocalDate usageDate,
            BusinessDayCalendar calendar) {
        if (months < 1 || months > MAX_MONTHS) {
            throw new IllegalArgumentException("할부 개월 수는 1(일시불)~12입니다: " + months);
        }
        if (amount < 1) {
            // 0원 승인(전액 즉시할인)은 청구 계획을 만들지 않는다
            throw new IllegalArgumentException("원금은 1원 이상이어야 합니다: " + amount);
        }
        BillingCycle.Cycle first = cycle.cycleFor(usageDate);
        long base = amount / months;
        List<Installment> installments = new ArrayList<>(months);
        for (int no = 1; no <= months; no++) {
            BillingCycle.Cycle month = no == 1 ? first : cycle.cycleOf(first.billingMonth().plusMonths(no - 1L));
            long principal = no < months ? base : amount - base * (months - 1);
            installments.add(new Installment(no, month.billingMonth(), principal, month,
                    calendar.businessDayOnOrAfter(month.paymentDate())));
        }
        return new InstallmentPlan(amount, installments);
    }

    public int months() {
        return installments.size();
    }

    /**
     * 회차 하나.
     *
     * @param cycle          그 청구 월의 이용기간·결제일(청구서를 새로 만들 때 쓴다)
     * @param withdrawalDate 영업일로 보정한 출금 예정일
     */
    public record Installment(int no, YearMonth billingMonth, long principal, BillingCycle.Cycle cycle,
            LocalDate withdrawalDate) {}
}
