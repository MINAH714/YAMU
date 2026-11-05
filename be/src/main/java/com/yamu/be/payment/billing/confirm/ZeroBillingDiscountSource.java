package com.yamu.be.payment.billing.confirm;

import com.yamu.be.payment.billing.BillItem;
import com.yamu.be.payment.billing.CardBill;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/** 임시 청구할인 포트(BE3 혜택 원장 Task 5.2.5 전): 청구할인을 보지 않는다. 확정액 = 회차 청구액 합계. */
@Slf4j
public class ZeroBillingDiscountSource implements BillingDiscountSource {

    @Override
    public long discount(CardBill bill, List<BillItem> items) {
        log.debug("청구할인 계산 생략(임시) cardBillId={} items={}", bill.getId(), items.size());
        return 0;
    }
}
