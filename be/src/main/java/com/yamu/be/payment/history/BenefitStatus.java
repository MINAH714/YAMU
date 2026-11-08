package com.yamu.be.payment.history;

/**
 * 결제 한 건의 혜택 확정 상태(API 명세 「결제 내역 목록 조회」 v0.3.1 {@code benefitStatus}, 2026-11-06 결정).
 *
 * <p>혜택 줄({@code payment_benefit})마다 확정 여부를 본다. 즉시할인({@code AT_APPROVAL})은 승인 때 확정, 청구할인({@code AT_BILLING})은
 * 결제의 회차가 모두 청구 확정되면 확정, 캐시백·적립은 YAMU에 지급 기록이 없어 늘 예상이다. 승인 상태가 아니면(취소·거절) 받은 혜택이 없어 NONE이다.
 */
public enum BenefitStatus {
    /** 혜택이 없거나 승인 상태가 아님 */
    NONE,
    /** 혜택 중 확정되지 않은 줄이 있음 */
    EXPECTED,
    /** 혜택이 모두 확정됨 */
    CONFIRMED
}
