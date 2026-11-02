package com.yamu.be.payment.domain;

/** 결제 상태(DB {@code payment_transaction.status}). 값 이름은 API 명세 V2 2장 {@code PaymentStatus}와 같다. */
public enum PaymentStatus {
    /** POS 요청 접수, 추천 계산 완료. 앱의 승인을 기다린다 */
    REQUESTED,
    /** 승인 처리 중(체크카드는 금융망 출금 중) */
    PROCESSING,
    APPROVED,
    /** 승인 거절. 확정 시간 안이면 다른 카드로 재승인할 수 있다 */
    DECLINED,
    /** 전체 취소 */
    CANCELED,
    /** 사용자가 확정 시간(60초) 안에 승인하지 않음 */
    EXPIRED
}
