package com.yamu.be.payment.billing;

/** 청구 회차 상태(DB {@code bill_item.status}). 값 이름은 API 명세 V2 2장 {@code BillItemStatus}와 같다. */
public enum BillItemStatus {
    /** 청구 전 */
    PENDING,
    /** 청구 확정 */
    BILLED,
    /** 출금 완료 */
    PAID,
    /** 확정 전 취소로 청구 제외 */
    EXCLUDED,
    /** 출금 후 취소, 환급 대기 */
    REFUND_PENDING,
    /** 환급 완료 */
    REFUNDED
}
