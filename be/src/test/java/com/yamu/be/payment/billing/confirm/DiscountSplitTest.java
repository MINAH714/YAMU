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
        assertThat(DiscountSplit.split(100, List.of(1L, 1L, 1L))).containsExactly(33L, 33L, 34L);
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
    void 합계는_언제나_할인과_같다() {
        for (long discount = 0; discount <= 50; discount++) {
            List<Long> shares = DiscountSplit.split(discount, List.of(7L, 11L, 13L));
            assertThat(shares.stream().mapToLong(Long::longValue).sum()).isEqualTo(discount);
        }
    }

    @Test
    void 음수_할인이나_원금_합계_0은_거절한다() {
        assertThatThrownBy(() -> DiscountSplit.split(-1, List.of(1L))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DiscountSplit.split(1, List.of(0L))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DiscountSplit.split(1, List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
