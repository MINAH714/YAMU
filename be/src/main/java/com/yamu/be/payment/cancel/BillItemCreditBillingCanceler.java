package com.yamu.be.payment.cancel;

import com.yamu.be.payment.billing.BillItem;
import com.yamu.be.payment.billing.BillItemRepository;
import com.yamu.be.payment.billing.BillItemStatus;
import com.yamu.be.payment.billing.BillStatus;
import com.yamu.be.payment.billing.CardBill;
import com.yamu.be.payment.billing.CardBillRepository;
import com.yamu.be.payment.cancel.PaymentCancelResponse.BillItemChange;
import com.yamu.be.payment.domain.PaymentTransaction;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 신용카드 취소의 청구 회차 처리. 지금은 청구 전 회차(회차 PENDING, 청구서 ACCUMULATING 또는 미배정)만 EXCLUDED로 바꾼다.
 *
 * <p>청구 확정(S15P31E103-206) 전이라 확정·출금·미납 청구서의 회차는 아직 생기지 않는다. 만나면 조용히 넘어가지 않고 실패시켜
 * 취소까지 롤백한다. 회차를 그대로 두고 결제만 CANCELED가 되면 취소한 결제가 청구되기 때문이다. 환급 대기({@code REFUND_PENDING})는
 * 청구 확정과 함께 붙인다.
 *
 * <p>잠금 순서는 청구서(번호 순) → 회차다. 청구 확정 배치도 청구서부터 잠그면 서로 엇갈리지 않는다. 같은 결제의 동시 취소는
 * 그보다 앞서 결제 행 잠금으로 줄을 서므로 회차는 한 번만 바뀐다.
 */
@Component
public class BillItemCreditBillingCanceler implements CreditBillingCanceler {

    private final BillItemRepository items;
    private final CardBillRepository bills;

    public BillItemCreditBillingCanceler(BillItemRepository items, CardBillRepository bills) {
        this.items = items;
        this.bills = bills;
    }

    @Override
    public Result cancel(PaymentTransaction payment, LocalDateTime canceledAt) {
        List<Long> billIds = items.findCardBillIdsByPaymentTransactionId(payment.getId()).stream().sorted().toList();
        Map<Long, BillStatus> billStatuses = new HashMap<>();
        if (!billIds.isEmpty()) {
            for (CardBill bill : bills.findAllForUpdate(billIds)) {
                billStatuses.put(bill.getId(), bill.getStatus());
            }
        }

        List<BillItemChange> changes = new ArrayList<>();
        for (BillItem item : items.findForCancel(payment.getId())) {
            requireBeforeBilling(payment, item, billStatuses);
            BillItemStatus before = item.getStatus();
            item.exclude();
            changes.add(new BillItemChange(item.getId(), item.getInstallmentNo(), billedAmount(item), before.name(),
                    item.getStatus().name()));
        }
        items.flush();
        return new Result(changes, List.of());
    }

    /** 청구 전 회차인지. 아니면 확정 이후 처리(-206)가 없으므로 취소를 막는다. */
    private static void requireBeforeBilling(PaymentTransaction payment, BillItem item, Map<Long, BillStatus> billStatuses) {
        BillStatus bill = item.getCardBillId() == null ? BillStatus.ACCUMULATING : billStatuses.get(item.getCardBillId());
        if (item.getStatus() != BillItemStatus.PENDING || bill != BillStatus.ACCUMULATING) {
            throw new IllegalStateException("청구 확정 이후 회차의 취소는 아직 처리하지 않습니다 (결제 " + payment.getId()
                    + ", 회차 " + item.getId() + " " + item.getStatus() + ", 청구서 " + item.getCardBillId() + " " + bill + ")");
        }
    }

    /** 무이자라 청구액 = 원금이다. 청구액 미산정(NULL) 회차는 원금으로 보여 준다. */
    private static long billedAmount(BillItem item) {
        return item.getBilledAmount() != null ? item.getBilledAmount() : item.getPrincipalAmount();
    }
}
