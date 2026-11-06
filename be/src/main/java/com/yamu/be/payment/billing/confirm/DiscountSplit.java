package com.yamu.be.payment.billing.confirm;

import java.util.ArrayList;
import java.util.List;

/**
 * 결제 하나의 청구할인을 회차에 나누는 규칙(2026-11-06 결정 Q1). 회차 원금 비율로 원 단위 내림해 나누고, 남은 원은 마지막 회차부터
 * 그 회차 원금을 넘지 않게 채운다. 보통은 남은 원이 모두 마지막 회차에 들어가 원금 배분 규칙(나머지는 마지막 회차, POL-013)과 같다.
 * 예: 할인 9,000원 · 3개월 30,000×3 → 3,000 / 3,000 / 3,000.
 *
 * <p>마지막 회차에 나머지를 무조건 몰면 그 회차 원금을 넘을 수 있다(원금 3,333 / 3,333 / 3,334에 할인 9,999원이면 마지막이 3,335).
 * 그래서 넘치는 원은 앞 회차로 넘긴다(3,332 / 3,333 / 3,334, !141 리뷰). 할인이 원금 합계보다 크면 원금 합계까지만 나눈다.
 */
final class DiscountSplit {

    private DiscountSplit() {
    }

    /**
     * @param discount   결제의 할인 합계(원). 0 이상
     * @param principals 회차 원금(회차 순, 일시불은 1개). 각각 0 이상이고 합계가 0보다 커야 한다
     * @return 회차별 할인(같은 순서). 각 몫은 그 회차 원금 이하이고, 합계는 min(discount, 원금 합계)다
     */
    static List<Long> split(long discount, List<Long> principals) {
        if (discount < 0) {
            throw new IllegalArgumentException("할인은 0원 이상이어야 합니다: " + discount);
        }
        if (principals.isEmpty() || principals.stream().anyMatch(principal -> principal < 0)) {
            throw new IllegalArgumentException("회차 원금은 하나 이상이고 0원 이상이어야 합니다: " + principals);
        }
        long total = principals.stream().mapToLong(Long::longValue).reduce(0, Math::addExact);
        if (total <= 0) {
            throw new IllegalArgumentException("회차 원금 합계가 0원보다 커야 합니다: " + principals);
        }
        long target = Math.min(discount, total);
        List<Long> shares = new ArrayList<>(principals.size());
        long given = 0;
        for (long principal : principals) {
            // target ≤ total이라 내림한 몫은 그 회차 원금을 넘지 않는다
            long share = Math.multiplyExact(target, principal) / total;
            shares.add(share);
            given += share;
        }
        long remainder = target - given;
        for (int i = principals.size() - 1; i >= 0 && remainder > 0; i--) {
            long add = Math.min(principals.get(i) - shares.get(i), remainder);
            shares.set(i, shares.get(i) + add);
            remainder -= add;
        }
        return shares;
    }
}
