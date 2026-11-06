package com.yamu.be.payment.billing.confirm;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 청구할인 포트(Task 5.4.7, API 명세 「청구서 상세」 {@code discounts}). 청구서 하나의 청구 전(PENDING) 회차에 나눌 청구할인을 돌려준다.
 * 청구 확정은 잠근 청구서로 불러 결과를 {@code bill_item_discount}에 저장하고, 청구서 조회·잔고예보는 진행 중 청구서의 예상 청구할인으로 부른다.
 *
 * <p>구현: {@link JdbcBillingDiscountSource}(승인 원장 {@code payment_benefit}의 청구할인). 없으면 {@link ZeroBillingDiscountSource}(할인 없음).
 */
public interface BillingDiscountSource {

    /**
     * @return 회차·혜택별 배분. 금액은 1원 이상이고 회차 청구액을 넘지 않는다. 할인이 없으면 빈 목록
     */
    List<Allocation> allocate(Long cardBillId);

    /**
     * 여러 청구서의 배분을 한 번에 계산한다. 청구서 조회·잔고예보처럼 진행 중 청구서 여러 장의 예상 청구할인이 필요한 곳에서 쓴다
     * (!141 리뷰: 청구서마다 따로 부르면 청구서 수만큼 쿼리가 늘어난다). 기본 구현은 한 장씩 부른다.
     *
     * @return 청구서 번호 → 배분. 할인이 없는 청구서는 빈 목록(키는 요청한 청구서 모두)
     */
    default Map<Long, List<Allocation>> allocateAll(Collection<Long> cardBillIds) {
        Map<Long, List<Allocation>> byBill = new LinkedHashMap<>();
        for (Long cardBillId : cardBillIds) {
            byBill.put(cardBillId, allocate(cardBillId));
        }
        return byBill;
    }

    /**
     * 회차 하나에 나눈 청구할인 하나.
     *
     * @param name 혜택 이름(청구서 상세 {@code discounts[].name})
     */
    record Allocation(Long billItemId, Long paymentId, Long paymentBenefitId, String name, long amount) {

        public static long total(List<Allocation> allocations) {
            return allocations.stream().mapToLong(Allocation::amount).reduce(0, Math::addExact);
        }
    }
}
