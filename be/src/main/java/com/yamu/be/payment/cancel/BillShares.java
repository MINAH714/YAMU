package com.yamu.be.payment.cancel;

import com.yamu.be.payment.billing.BillItem;
import com.yamu.be.payment.billing.CardBill;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 청구서 확정액 중 회차별 몫. 환급액(실제로 출금된 몫)과 미납 제외액(아직 받지 못한 몫)을 같은 값으로 정한다.
 *
 * <p>몫 = 회차 청구액 − 청구 확정 때 그 회차에 나눈 청구할인({@code bill_item_discount}, Task 5.4.7). 몫의 합은 확정액과 같아야 한다.
 * 다르면 몫을 정할 근거가 없으므로 원금으로 대신하지 않고 실패시킨다. 명세 예: 회차 30,000원 − 할인 3,000원 = 환급 27,000원.
 */
final class BillShares {

    private BillShares() {
    }

    /**
     * @param bill      잠근 청구서(확정 이후)
     * @param items     이 청구서에 배정된 모든 회차(다른 결제 포함)
     * @param discounts 회차 번호 → 그 회차에 나눈 청구할인({@code BillItemDiscountReader#amountsByItem}). 없는 회차는 0원
     * @return 회차 번호 → 확정액 중 그 회차 몫
     */
    static Map<Long, Long> of(CardBill bill, List<BillItem> items, Map<Long, Long> discounts) {
        if (bill.getConfirmedAmount() == null) {
            throw new IllegalStateException("확정액이 없는 청구서입니다 (청구서 " + bill.getId() + ", 상태 " + bill.getStatus() + ")");
        }
        Map<Long, Long> shares = new HashMap<>();
        long sum = 0;
        for (BillItem item : items) {
            if (!bill.getId().equals(item.getCardBillId()) || !item.isInConfirmedAmount()) {
                continue;
            }
            if (item.getBilledAmount() == null) {
                throw new IllegalStateException("청구액이 정해지지 않은 회차가 확정 청구서에 있습니다 (회차 " + item.getId() + ")");
            }
            long discount = discounts.getOrDefault(item.getId(), 0L);
            long share = item.getBilledAmount() - discount;
            if (share < 0) {
                throw new IllegalStateException("회차에 나눈 청구할인이 청구액보다 큽니다 (회차 " + item.getId() + ")");
            }
            shares.put(item.getId(), share);
            sum += share;
        }
        if (sum != bill.getConfirmedAmount()) {
            throw new IllegalStateException("확정액이 회차 몫(청구액 − 배분 할인)의 합과 달라 회차별 몫을 정할 수 없습니다"
                    + " (청구서 " + bill.getId() + ", 확정액 " + bill.getConfirmedAmount() + ", 몫 합 " + sum + ")");
        }
        return shares;
    }
}
