package com.yamu.be.payment.billing.confirm;

import com.yamu.be.payment.billing.BillItem;
import com.yamu.be.payment.billing.CardBill;
import java.util.List;

/**
 * 청구할인 포트(Task 5.4.2, API 명세 「청구서 상세」 {@code discounts}). 청구 확정이 청구서 하나의 청구할인 합계를 물어본다.
 * 구현은 BE3 혜택 원장(Task 5.2.5)이 맡는다: 회차의 원결제({@link BillItem#getPaymentTransactionId()})에 저장된
 * {@code BILLING_DISCOUNT} 혜택({@code payment_benefit})을 모은다. 구현이 없으면 {@link ZeroBillingDiscountSource}(0원)가 쓰인다.
 *
 * <p>임시(2026-11-05 결정): 회차별 할인 배분을 저장할 칸이 아직 없어, 0원이 아닌 값이 오면 청구 확정이 그 청구서를 확정하지 않고
 * 실패로 남긴다. 할인이 생기면 회차별 배분 칸(스키마 변경)과 함께 이 포트가 회차별 배분을 돌려주도록 바꾼다
 * ({@code payment.cancel.BillShares}가 그 값으로 환급 몫을 정한다).
 */
public interface BillingDiscountSource {

    /**
     * @param bill  확정하려는 청구서(잠근 상태)
     * @param items 확정액에 들어갈 청구 전(PENDING) 회차
     * @return 청구서 전체 청구할인(원). 0 이상이고 회차 청구액 합계를 넘지 않는다
     */
    long discount(CardBill bill, List<BillItem> items);
}
