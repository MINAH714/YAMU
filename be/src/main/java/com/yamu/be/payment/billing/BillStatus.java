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
    UNPAID,
    /**
     * 미납이었지만 결제 취소로 남은 미납액이 0원이 됐다. 낼 돈이 없으므로 미납이 아니고, 출금도 없었으므로 PAID도 아니다.
     * 확정 원본({@code confirmed_amount})·출금 실패 이력({@code failed_at})·취소 제외액은 그대로 남는다.
     * 카드 관리·회원 탈퇴는 이미 이 값을 정산 끝난 청구서로 본다. 임시: API 명세 V2 {@code BillStatus}에는 없는 값이라 명세에 추가해야 한다
     */
    CANCELED
}
