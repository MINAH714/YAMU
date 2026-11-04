package com.yamu.be.payment.billing;

/** 청구서 상태(DB {@code card_bill.status}). 값 이름은 API 명세 V2 2장 {@code BillStatus}와 같다. */
public enum BillStatus {
    /** 이용기간 진행 중. 승인된 회차가 쌓인다 */
    ACCUMULATING,
    /** 청구 확정(출금 대기) */
    CONFIRMED,
    /** 출금 완료 */
    PAID,
    /** 출금 실패·미납 */
    UNPAID
}
