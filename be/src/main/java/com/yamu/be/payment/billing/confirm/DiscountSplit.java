package com.yamu.be.payment.billing.confirm;

import java.util.ArrayList;
import java.util.List;

/**
 * 결제 하나의 청구할인을 회차에 나누는 규칙(2026-11-06 결정 Q1). 회차 원금 비율로 원 단위 내림해 나누고, 나머지는 마지막 회차에 둔다.
 * 원금 배분 규칙(나머지는 마지막 회차, POL-013)과 같은 방식이다. 예: 할인 9,000원 · 3개월 30,000×3 → 3,000 / 3,000 / 3,000.
 */
final class DiscountSplit {

    private DiscountSplit() {
    }

    /**
     * @param discount   결제의 할인 합계(원). 0 이상
     * @param principals 회차 원금(회차 순, 일시불은 1개). 합계가 0보다 커야 한다
     * @return 회차별 할인(같은 순서). 합계는 discount와 같다
     */
    static List<Long> split(long discount, List<Long> principals) {
        if (discount < 0) {
            throw new IllegalArgumentException("할인은 0원 이상이어야 합니다: " + discount);
        }
        long total = principals.stream().mapToLong(Long::longValue).reduce(0, Math::addExact);
        if (principals.isEmpty() || total <= 0) {
            throw new IllegalArgumentException("회차 원금 합계가 0원보다 커야 합니다: " + principals);
        }
        List<Long> shares = new ArrayList<>(principals.size());
        long given = 0;
        for (int i = 0; i < principals.size() - 1; i++) {
            long share = Math.multiplyExact(discount, principals.get(i)) / total;
            shares.add(share);
            given += share;
        }
        shares.add(discount - given);
        return shares;
    }
}
