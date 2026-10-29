package com.yamu.be.forecast;

import com.yamu.be.cashflow.CashflowService;
import java.time.LocalDate;

/**
 * 잔고예보 계산에서 뺀 예정 회차. 예보가 완전한 확정 잔액처럼 보이지 않도록 응답에 함께 알린다(명세에 없는 필드).
 *
 * @param cashflowItemId 가계부 항목 ID
 * @param occurrenceDate 원래 회차
 * @param date           실제 예정일
 * @param reason         뺀 이유
 */
public record ExcludedItem(Long cashflowItemId, LocalDate occurrenceDate, LocalDate date, String title,
        Reason reason) {

    public enum Reason {
        /** 금액 미정. 입력하면 반영한다. */
        AMOUNT_UNKNOWN,
        /** 계좌·카드 출금 계좌가 없어 어느 계좌에서 나갈지 모른다. */
        NO_WITHDRAWAL_ACCOUNT,
        /** 지정한 카드가 없거나 등록 해제됐다. */
        CARD_UNAVAILABLE,
        /** 오늘 예정 수입. 현재 잔액에 이미 들어왔는지 알 수 없어 두 번 더하지 않도록 뺐다. */
        TODAY_INCOME_UNVERIFIED
    }

    static ExcludedItem of(CashflowService.Entry entry, Reason reason) {
        return new ExcludedItem(entry.item().id(), entry.occurrenceDate(), entry.date(), entry.item().title(), reason);
    }
}
