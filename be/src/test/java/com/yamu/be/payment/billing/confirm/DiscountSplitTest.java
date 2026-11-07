package com.yamu.be.payment.billing.confirm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class DiscountSplitTest {

    @Test
    void 회차_원금_비율로_나누고_원_단위_나머지는_마지막_회차에_둔다() {
        assertThat(DiscountSplit.split(9_000, List.of(30_000L, 30_000L, 30_000L))).containsExactly(3_000L, 3_000L, 3_000L);
        assertThat(DiscountSplit.split(1_000, List.of(33_333L, 33_333L, 33_334L))).containsExactly(333L, 333L, 334L);
    }

    @Test
    void 마지막_회차가_원금을_넘을_나머지는_앞_회차로_넘긴다() {
        // 나머지를 마지막에 몰면 3,332 / 3,332 / 3,335가 되어 마지막 회차 원금(3,334)을 넘는다(!141 리뷰)
        assertThat(DiscountSplit.split(9_999, List.of(3_333L, 3_333L, 3_334L))).containsExactly(3_332L, 3_333L, 3_334L);
    }

    @Test
    void 할인이_원금_합계보다_크면_원금_합계까지만_나눈다() {
        assertThat(DiscountSplit.split(100, List.of(1L, 1L, 1L))).containsExactly(1L, 1L, 1L);
    }

    @Test
    void 일시불은_할인_전부가_한_회차에_간다() {
        assertThat(DiscountSplit.split(3_000, List.of(30_000L))).containsExactly(3_000L);
    }

    @Test
    void 할인이_0원이면_모두_0원이다() {
        assertThat(DiscountSplit.split(0, List.of(10_000L, 10_000L))).containsExactly(0L, 0L);
    }

    @Test
    void 합계는_할인과_원금_합계_중_작은_값이고_각_몫은_회차_원금을_넘지_않는다() {
        List<List<Long>> plans = List.of(List.of(7L, 11L, 13L), List.of(3_333L, 3_333L, 3_334L), List.of(1L, 0L, 2L),
                List.of(5L, 5L, 5L, 5L, 5L, 5L, 5L, 5L, 5L, 5L, 5L, 1L));
        for (List<Long> principals : plans) {
            long total = principals.stream().mapToLong(Long::longValue).sum();
            for (long discount = 0; discount <= total + 5; discount++) {
                List<Long> shares = DiscountSplit.split(discount, principals);
                assertThat(shares.stream().mapToLong(Long::longValue).sum()).isEqualTo(Math.min(discount, total));
                for (int i = 0; i < shares.size(); i++) {
                    assertThat(shares.get(i)).isBetween(0L, principals.get(i));
                }
            }
        }
        // 실제 금액 범위도 같은 성질(나머지가 생기는 할인)
        for (long discount = 9_990; discount <= 10_000; discount++) {
            List<Long> shares = DiscountSplit.split(discount, List.of(3_333L, 3_333L, 3_334L));
            assertThat(shares.stream().mapToLong(Long::longValue).sum()).isEqualTo(discount);
            assertThat(shares.get(2)).isLessThanOrEqualTo(3_334L);
        }
    }

    @Test
    void 음수_할인이나_원금_합계_0은_거절한다() {
        assertThatThrownBy(() -> DiscountSplit.split(-1, List.of(1L))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DiscountSplit.split(1, List.of(0L))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DiscountSplit.split(1, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DiscountSplit.split(1, List.of(5L, -1L))).isInstanceOf(IllegalArgumentException.class);
    }
}
