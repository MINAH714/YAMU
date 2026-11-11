package com.yamu.be.performance;

/**
 * 카드 한 장의 그달 실적 상태(S15P31E103-373, 2026-11-10 결정). 판정 순서는 위에서 아래다.
 */
public enum PerformanceStatus {
    /** 실적 기준이 없다(실적 조건 없는 카드, 오늘 유효한 혜택이 없거나 버전을 모르는 카드). */
    NO_TARGET,
    /** 그달 실적 행(card_monthly_performance)이 없다. 등록 때 0으로 만들어 두므로 보통 생기지 않는다. */
    INSUFFICIENT_DATA,
    /** 인정 실적이 목표 이상이다(기준을 모두 넘으면 목표는 가장 큰 기준). */
    ACHIEVED,
    /**
     * 목표에 못 미칠 것 같다. 이번 달이면 지금 추세(실적 ÷ 지난 일수 × 그달 일수)가 목표보다 작을 때, 지난 달이면 목표를 못 채웠을 때.
     * 이번 달 첫 5일은 결제가 적어 추세를 보지 않고 ON_TRACK으로 둔다.
     */
    AT_RISK,
    /** 지금 추세면 목표를 채운다. */
    ON_TRACK
}
