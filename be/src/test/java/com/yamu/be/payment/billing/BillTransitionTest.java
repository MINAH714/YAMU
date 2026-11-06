package com.yamu.be.payment.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 청구 확정(Task 5.4.2)의 청구서·회차 상태 전이. */
class BillTransitionTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 15, 30);
    final BillingCycle.Cycle november = new BillingCycle(15, 14).cycleOf(YearMonth.of(2026, 11));

    CardBill accumulating() {
        return CardBill.accumulating(7L, 22L, november, LocalDate.of(2026, 11, 16));
    }

    @Test
    void 확정하면_금액_계좌_출금일_시각이_고정되고_CONFIRMED가_된다() {
        CardBill bill = accumulating();

        bill.confirm(30_000, 3L, LocalDate.of(2026, 11, 16), NOW);

        assertThat(bill.getStatus()).isEqualTo(BillStatus.CONFIRMED);
        assertThat(bill.getConfirmedAmount()).isEqualTo(30_000);
        assertThat(bill.getLinkedAccountId()).isEqualTo(3L);
        assertThat(bill.getConfirmedAt()).isEqualTo(NOW);
        assertThat(bill.outstandingUnpaid()).isEqualTo(30_000);
    }

    @Test
    void 이미_확정된_청구서는_다시_확정하지_않는다() {
        CardBill bill = accumulating();
        bill.confirm(30_000, 3L, LocalDate.of(2026, 11, 16), NOW);

        assertThatThrownBy(() -> bill.confirm(10_000, 3L, LocalDate.of(2026, 11, 16), NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThat(bill.getConfirmedAmount()).isEqualTo(30_000);
    }

    @Test
    void 출금_계좌_없이는_확정하지_않는다() {
        assertThatThrownBy(() -> accumulating().confirm(30_000, null, LocalDate.of(2026, 11, 16), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 확정액_0원이면_출금_없이_PAID로_닫는다() {
        CardBill bill = accumulating();
        bill.confirm(0, 3L, LocalDate.of(2026, 11, 16), NOW);

        bill.settleWithoutWithdrawal(NOW);

        assertThat(bill.getStatus()).isEqualTo(BillStatus.PAID);
        assertThat(bill.getPaidAmount()).isZero();
        assertThat(bill.isFullyPaid()).isTrue();
    }

    @Test
    void 확정액이_있으면_출금_없이_닫을_수_없다() {
        CardBill bill = accumulating();
        bill.confirm(1, 3L, LocalDate.of(2026, 11, 16), NOW);

        assertThatThrownBy(() -> bill.settleWithoutWithdrawal(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 청구_전_회차만_BILLED가_된다() {
        BillItem item = BillItem.pending(1L, 7L, 22L, 5L, 1, november.billingMonth().atDay(1), 10_000);
        item.bill();
        assertThat(item.getStatus()).isEqualTo(BillItemStatus.BILLED);

        assertThatThrownBy(item::bill).isInstanceOf(IllegalStateException.class);

        BillItem excluded = BillItem.pending(2L, 7L, 22L, 5L, 1, november.billingMonth().atDay(1), 10_000);
        excluded.exclude();
        assertThatThrownBy(excluded::bill).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 청구액이_정해지지_않은_회차는_확정하지_않는다() {
        BillItem item = BillItem.pending(1L, 7L, 22L, 5L, 1, november.billingMonth().atDay(1), 10_000);
        ReflectionTestUtils.setField(item, "billedAmount", null);

        assertThatThrownBy(item::bill).isInstanceOf(IllegalStateException.class);
    }
}
