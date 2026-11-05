package com.yamu.be.payment.cancel;

import com.yamu.be.payment.billing.BillItem;
import com.yamu.be.payment.billing.CardBill;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 청구서 확정액 중 회차별 몫. 환급액(실제로 출금된 몫)과 미납 제외액(아직 받지 못한 몫)을 같은 값으로 정한다.
 *
 * <p>청구할인을 회차에 나눠 저장하는 곳이 아직 없다(S15P31E103-206). 그래서 확정액이 확정에 들어간 회차 청구액의 합과 같을 때,
 * 즉 할인·조정이 없을 때만 회차 청구액을 몫으로 쓴다. 다르면 몫을 정할 근거가 없으므로 원금으로 대신하지 않고 실패시킨다.
 * 청구할인이 생기면 회차별 할인 배분액(또는 회차별 확정액)을 저장하고 이 계산을 그 값으로 바꿔야 한다.
 */
final class BillShares {

    private BillShares() {
    }

    /**
     * @param bill  잠근 청구서(확정 이후)
     * @param items 이 청구서에 배정된 모든 회차(다른 결제 포함)
     * @return 회차 번호 → 확정액 중 그 회차 몫
     */
    static Map<Long, Long> of(CardBill bill, List<BillItem> items) {
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
            shares.put(item.getId(), item.getBilledAmount());
            sum += item.getBilledAmount();
        }
        if (sum != bill.getConfirmedAmount()) {
            throw new IllegalStateException("확정액이 회차 청구액 합과 달라 회차별 몫을 정할 수 없습니다. 청구할인·조정의 회차 배분 기록이 필요합니다"
                    + " (청구서 " + bill.getId() + ", 확정액 " + bill.getConfirmedAmount() + ", 회차 합 " + sum + ")");
        }
        return shares;
    }
}
