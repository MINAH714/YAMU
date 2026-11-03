package com.yamu.be.payment.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yamu.be.payment.billing.InstallmentPlan.Installment;
import com.yamu.be.support.WeekdayCalendar;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/** 청구서 확보: 같은 달 동시 생성 충돌만 다시 읽고, 이용기간이 끝난 청구서에는 회차를 붙이지 않는다. */
class CreditBillingWriterTest {

    static final long USER = 7L;
    static final long CARD = 22L;

    final CardBillRepository bills = mock(CardBillRepository.class);
    final BillItemRepository items = mock(BillItemRepository.class);
    final CreditBillingWriter writer = new CreditBillingWriter(bills, items, mock(PlatformTransactionManager.class));

    /** 2026-10-22 승인, 결제일 15일 → 11월 청구서 하나 */
    final InstallmentPlan plan = InstallmentPlan.of(15_000, 1, new BillingCycle(15, 14), LocalDate.of(2026, 10, 22),
            new WeekdayCalendar());
    final Installment only = plan.installments().get(0);
    final LocalDate november = YearMonth.of(2026, 11).atDay(1);

    @Test
    void 두_결제가_모두_없음을_본_뒤_생성이_같은_달_UNIQUE로_충돌하면_다시_읽어_같은_청구서를_쓴다() {
        CardBill madeByOther = CardBill.accumulating(USER, CARD, only.cycle(), only.withdrawalDate());
        when(bills.findByUserCardIdAndBillingMonth(CARD, november))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(madeByOther));
        when(bills.saveAndFlush(any())).thenThrow(violation(
                "Duplicate entry '22-2026-11-01' for key 'card_bill.uk_bill_card_month'"));

        Map<YearMonth, CardBill> result = writer.ensureBills(USER, CARD, plan);

        assertThat(result).containsOnlyKeys(YearMonth.of(2026, 11));
        assertThat(result.get(YearMonth.of(2026, 11))).isSameAs(madeByOther);
        verify(bills).saveAndFlush(any());
        verify(bills, times(2)).findByUserCardIdAndBillingMonth(CARD, november);
    }

    @Test
    void 같은_달_UNIQUE가_아닌_무결성_위반은_다시_읽지_않고_그대로_던진다() {
        when(bills.findByUserCardIdAndBillingMonth(CARD, november)).thenReturn(Optional.empty());
        DataIntegrityViolationException fk = violation(
                "Cannot add or update a child row: a foreign key constraint fails (`card_bill`, CONSTRAINT `fk_bill_card_owner`)");
        when(bills.saveAndFlush(any())).thenThrow(fk);

        assertThatThrownBy(() -> writer.ensureBills(USER, CARD, plan)).isSameAs(fk);

        verify(bills, times(1)).findByUserCardIdAndBillingMonth(CARD, november);
    }

    @Test
    void 이용기간이_끝난_청구서에는_회차를_붙이지_않는다() {
        CardBill confirmed = CardBill.accumulating(USER, CARD, only.cycle(), only.withdrawalDate());
        ReflectionTestUtils.setField(confirmed, "status", BillStatus.CONFIRMED);
        when(bills.findByUserCardIdAndBillingMonth(CARD, november)).thenReturn(Optional.of(confirmed));

        assertThatThrownBy(() -> writer.ensureBills(USER, CARD, plan))
                .isInstanceOf(IllegalStateException.class);

        verify(bills, never()).saveAndFlush(any());
    }

    @Test
    void 충돌_뒤_다시_읽은_청구서가_이용기간이_끝났어도_붙이지_않는다() {
        CardBill paid = CardBill.accumulating(USER, CARD, only.cycle(), only.withdrawalDate());
        ReflectionTestUtils.setField(paid, "status", BillStatus.PAID);
        when(bills.findByUserCardIdAndBillingMonth(CARD, november))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(paid));
        when(bills.saveAndFlush(any())).thenThrow(violation(
                "Duplicate entry '22-2026-11-01' for key 'card_bill.uk_bill_card_month'"));

        assertThatThrownBy(() -> writer.ensureBills(USER, CARD, plan))
                .isInstanceOf(IllegalStateException.class);
    }

    private static DataIntegrityViolationException violation(String message) {
        return new DataIntegrityViolationException("could not execute statement",
                new SQLIntegrityConstraintViolationException(message));
    }
}
