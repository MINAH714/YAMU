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

    // ───────── 회원 탈퇴로 닫기(S15P31E103-443) ─────────

    @Test
    void 진행_중_확정_미납_청구서는_탈퇴로_닫고_금액은_그대로_둔다() {
        CardBill accumulating = accumulating();
        accumulating.closeForWithdrawal();
        assertThat(accumulating.getStatus()).isEqualTo(BillStatus.CLOSED_BY_WITHDRAWAL);

        CardBill confirmed = accumulating();
        confirmed.confirm(30_000, 3L, LocalDate.of(2026, 11, 16), NOW);
        confirmed.closeForWithdrawal();
        assertThat(confirmed.getStatus()).isEqualTo(BillStatus.CLOSED_BY_WITHDRAWAL);
        assertThat(confirmed.getConfirmedAmount()).isEqualTo(30_000);

        CardBill unpaid = accumulating();
        unpaid.confirm(30_000, 3L, LocalDate.of(2026, 11, 16), NOW);
        unpaid.requestWithdrawal("20261116070000000001");
        unpaid.markUnpaid(NOW);
        assertThat(unpaid.isWithdrawalRequested()).isFalse();
        unpaid.closeForWithdrawal();
        assertThat(unpaid.getStatus()).isEqualTo(BillStatus.CLOSED_BY_WITHDRAWAL);
        assertThat(unpaid.getFailedAt()).isEqualTo(NOW);
    }

    @Test
    void 끝난_청구서와_이미_닫은_청구서는_탈퇴로_닫지_않고_닫은_뒤에는_확정_출금도_안_된다() {
        CardBill paid = accumulating();
        paid.confirm(0, 3L, LocalDate.of(2026, 11, 16), NOW);
        paid.settleWithoutWithdrawal(NOW);
        assertThatThrownBy(paid::closeForWithdrawal).isInstanceOf(IllegalStateException.class);

        CardBill closed = accumulating();
        closed.closeForWithdrawal();
        assertThatThrownBy(closed::closeForWithdrawal).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> closed.confirm(30_000, 3L, LocalDate.of(2026, 11, 16), NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 출금을_요청한_확정_청구서만_결과를_기다리는_중이다() {
        CardBill bill = accumulating();
        assertThat(bill.isWithdrawalRequested()).isFalse();
        bill.confirm(30_000, 3L, LocalDate.of(2026, 11, 16), NOW);
        assertThat(bill.isWithdrawalRequested()).isFalse();
        bill.requestWithdrawal("20261116070000000001");
        assertThat(bill.isWithdrawalRequested()).isTrue();
    }

    @Test
    void 대기_입금_실패_환급은_탈퇴로_닫고_끝난_환급은_닫지_않는다() {
        Refund awaiting = Refund.pending(1L, 2L, 7L, 3L, 10_000, null);
        awaiting.closeForWithdrawal();
        assertThat(awaiting.getStatus()).isEqualTo(RefundStatus.CLOSED_BY_WITHDRAWAL);

        Refund failed = Refund.pending(1L, 3L, 7L, 3L, 10_000, LocalDate.of(2026, 11, 19));
        failed.recordSending("20261119070000000001");
        assertThat(failed.isDepositRequested()).isTrue();
        failed.fail();
        assertThat(failed.isDepositRequested()).isFalse();
        failed.closeForWithdrawal();
        assertThat(failed.getStatus()).isEqualTo(RefundStatus.CLOSED_BY_WITHDRAWAL);

        Refund completed = Refund.pending(1L, 4L, 7L, 3L, 10_000, LocalDate.of(2026, 11, 19));
        completed.complete("D1", NOW);
        assertThatThrownBy(completed::closeForWithdrawal).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(awaiting::closeForWithdrawal).isInstanceOf(IllegalStateException.class);
    }
}
