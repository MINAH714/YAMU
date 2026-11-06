package com.yamu.be.card;

import com.yamu.be.payment.billing.BillStatus;
import com.yamu.be.payment.billing.BillingCycle;
import com.yamu.be.payment.billing.query.BillListResponse;
import com.yamu.be.payment.billing.query.BillQueryService;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 신용카드의 진행 중 이용기간과 그동안 쌓인 금액(카드 목록 {@code billingSummary.accumulatingAmount}, 카드 상세
 * {@code billing.currentCycle}). 금액은 BE 2 청구서 목록({@link BillQueryService})의 예상 청구액을 그대로 쓴다.
 * 같은 값을 SQL로 다시 세지 않아, BE 2가 예상 청구할인 등 계산을 바꾸면 카드 화면도 따라간다.
 *
 * <p>진행 중(ACCUMULATING) 청구서는 한 카드에 여러 장일 수 있다. 할부는 다음 달 이후 회차의 청구서를 미리 만들고, 마감이 지난
 * 청구서도 확정 배치가 돌기 전까지는 진행 중이다. 그래서 이용기간에 오늘(한국 날짜)이 들어가는 청구서 하나만 고른다.
 * 그런 청구서가 없으면 이번 기간에 쓴 돈이 없는 것이라 0원이고, 이용기간은 카드의 지금 결제일로 계산한다({@link BillingCycle}).
 * 결제일을 기간 중간에 바꿨으면 이미 만든 청구서의 기간을 따른다.
 *
 * <p>확정일({@code confirmAt})은 이용기간 마감 다음 날이다(청구 확정 배치가 마감 다음 날 00:30에 돈다).
 */
@Component
@RequiredArgsConstructor
class CurrentCycleReader {

    private final BillQueryService bills;

    /**
     * @param userCardId 한 카드만 볼 때(상세). null이면 사용자의 모든 카드(목록)
     * @param cards      진행 중 이용기간을 붙일 신용카드
     * @return 카드 번호별 진행 중 이용기간. 청구서가 없고 결제일이 없거나 청구 규칙이 받지 않는 값이면 그 카드는 빠진다
     */
    Map<Long, CurrentCycle> currentCycles(long userId, Long userCardId, Collection<CreditTerms> cards,
            LocalDate today) {
        if (cards.isEmpty()) {
            return Map.of();
        }
        Map<Long, BillListResponse.Item> current = bills.list(userId, userCardId, null, BillStatus.ACCUMULATING)
                .items().stream()
                .filter(bill -> contains(bill.usagePeriod(), today))
                .collect(Collectors.toMap(BillListResponse.Item::userCardId, Function.identity(),
                        (a, b) -> Comparator.comparing(BillListResponse.Item::billingMonth).compare(a, b) <= 0 ? a : b));
        Map<Long, CurrentCycle> result = new HashMap<>();
        for (CreditTerms card : cards) {
            BillListResponse.Item bill = current.get(card.userCardId());
            if (bill != null) {
                Long expected = bill.expectedAmount();
                result.put(card.userCardId(), CurrentCycle.of(bill.usagePeriod().from(), bill.usagePeriod().to(),
                        expected == null ? 0 : expected));
            } else if (card.paymentDay() != null && BillingCycle.supports(card.paymentDay(), card.cutoffDays())) {
                BillingCycle.Cycle cycle = BillingCycle.of(card.paymentDay(), card.cutoffDays()).cycleFor(today);
                result.put(card.userCardId(), CurrentCycle.of(cycle.periodStart(), cycle.periodEnd(), 0));
            }
        }
        return result;
    }

    private static boolean contains(BillListResponse.UsagePeriod period, LocalDate day) {
        return !day.isBefore(period.from()) && !day.isAfter(period.to());
    }

    /** @param cutoffDays 상품의 이용기간 마감 일수. null이면 청구 규칙의 기본값 */
    record CreditTerms(long userCardId, Integer paymentDay, Integer cutoffDays) {

        static List<CreditTerms> single(long userCardId, Integer paymentDay, Integer cutoffDays) {
            return List.of(new CreditTerms(userCardId, paymentDay, cutoffDays));
        }
    }

    /**
     * @param start              이용기간 시작(포함, 한국 날짜)
     * @param end                이용기간 마감(포함, 한국 날짜)
     * @param accumulatingAmount 지금까지 쌓인 금액(원). BE 2 예상 청구액. 쓴 돈이 없으면 0
     * @param confirmAt          청구가 확정되는 날 = 마감 다음 날
     */
    record CurrentCycle(LocalDate start, LocalDate end, long accumulatingAmount, LocalDate confirmAt) {

        static CurrentCycle of(LocalDate start, LocalDate end, long accumulatingAmount) {
            return new CurrentCycle(start, end, accumulatingAmount, end.plusDays(1));
        }
    }
}
