package com.yamu.be.payment.billing;

/** 환급 상태(DB {@code refund.status}). 값 이름은 API 명세 V2 2장 {@code RefundStatus}와 같다. 신용카드 취소에만 쓴다. */
public enum RefundStatus {
    /** 대기. 원 청구서의 출금 성공을 확인하기 전도 포함한다 */
    PENDING,
    /** 결제계좌 입금 완료 */
    COMPLETED,
    /** 입금 실패(계좌 없음 등) */
    FAILED
}
