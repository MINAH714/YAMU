package com.yamu.be.payment.billing.confirm;

import java.util.List;

/** 구현({@link JdbcBillingDiscountSource})이 없을 때만 쓰는 포트: 청구할인을 보지 않는다. 확정액 = 회차 청구액 합계. */
public class ZeroBillingDiscountSource implements BillingDiscountSource {

    @Override
    public List<Allocation> allocate(Long cardBillId) {
        return List.of();
    }
}
