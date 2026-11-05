package com.yamu.be.forecast;

import com.yamu.be.cashflow.CashflowService;
import java.time.LocalDate;

/**
 * 잔고예보 계산에서 뺀 예정 회차·청구. 예보가 완전한 확정 잔액처럼 보이지 않도록 응답에 함께 알린다(명세에 없는 필드).
 *
 * @param cashflowItemId 가계부 항목 ID. 청구면 null
 * @param cardBillId     카드 청구 ID. 가계부 항목이면 null
 * @param occurrenceDate 원래 회차. 청구면 null
 * @param date           실제 예정일(미납 청구는 원래 출금일)
 * @param amount         금액(원). 모르면 null
 * @param reason         뺀 이유
 */
public record ExcludedItem(Long cashflowItemId, Long cardBillId, LocalDate occurrenceDate, LocalDate date,
        String title, Long amount, Reason reason) {

    public enum Reason {
        /** 금액 미정. 입력하면 반영한다. */
        AMOUNT_UNKNOWN,
        /** 계좌·카드 출금 계좌가 없어 어느 계좌에서 나갈지 모른다. */
        NO_WITHDRAWAL_ACCOUNT,
        /** 지정한 카드가 없거나 등록 해제됐다. */
        CARD_UNAVAILABLE,
        /** 지정한 카드를 카드사가 정지했거나 유효기간이 끝나 결제가 일어날 수 없다. 결제 카드를 바꾸면 반영한다. */
        CARD_SUSPENDED_OR_EXPIRED,
        /** 신용카드의 결제일이 없거나 청구 주기를 만들 수 없는 값이라 출금일을 정할 수 없다. 카드 결제일을 고치면 반영한다. */
        BILLING_CYCLE_UNKNOWN,
        /** 오늘 예정 수입. 현재 잔액에 이미 들어왔는지 알 수 없어 두 번 더하지 않도록 뺐다. */
        TODAY_INCOME_UNVERIFIED,
        /** 출금에 실패한 미납 청구. 재출금 일정이 정해지지 않아 차감하지 않았다(FR-069 미정). */
        UNPAID_BILL
    }

    static ExcludedItem of(CashflowService.Entry entry, Reason reason) {
        return new ExcludedItem(entry.item().id(), null, entry.occurrenceDate(), entry.date(), entry.item().title(),
                entry.item().amount(), reason);
    }

    static ExcludedItem unpaid(CardBillReader.OutstandingBill bill) {
        return new ExcludedItem(null, bill.cardBillId(), null, bill.withdrawalDate(), "카드 청구 미납", bill.amount(),
                Reason.UNPAID_BILL);
    }
}
