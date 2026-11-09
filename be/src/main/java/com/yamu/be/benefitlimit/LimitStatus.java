package com.yamu.be.benefitlimit;

/** 월 혜택 한도 하나의 상태(앱 {@code LimitDto.status}). 판정 규칙은 {@link LimitJudge}. */
public enum LimitStatus {
    /** 남은 한도가 최대 한도의 20%보다 많음 */
    AVAILABLE,
    /** 0원 &lt; 남은 한도 ≤ 최대 한도의 20% */
    NEAR_END,
    /** 남은 한도 0 */
    EXHAUSTED,
    /** 지난달 실적이 모자라 이번 달 이 한도의 혜택을 받을 수 없음 */
    LOCKED
}
