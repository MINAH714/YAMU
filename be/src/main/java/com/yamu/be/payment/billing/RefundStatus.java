package com.yamu.be.payment.billing;

/** 환급 상태(DB {@code refund.status}). 값 이름은 API 명세 V2 2장 {@code RefundStatus}와 같다. 신용카드 취소에만 쓴다. */
public enum RefundStatus {
    /**
     * 대기. 기한({@code due_date})이 없으면 원 청구서의 출금 성공을 기다리는 중이고 금액은 예상값이다.
     * 기한이 정해진 뒤에만 금액이 확정되고 입금 대상이 된다
     */
    PENDING,
    /** 결제계좌 입금 완료 */
    COMPLETED,
    /** 입금 실패(계좌 없음 등) */
    FAILED,
    /**
     * 무효. 출금 전에 취소한 회차의 청구서가 출금 실패(미납)로 끝나 돌려줄 돈이 없다. 입금하지 않는다.
     * 임시: API 명세 V2 {@code RefundStatus}에는 없는 값이라 명세에 추가해야 한다
     */
    CANCELED
}
