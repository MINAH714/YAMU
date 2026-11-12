package com.yamu.be.performance;

/**
 * 실적 유불리 판정(S15P31E103-375). 이름은 앱 {@code PerfGuide}·API 명세서 「실적 달성 유불리 분석」과 같다.
 * 판정 규칙은 {@link PerformanceAnalyzer}.
 */
public enum PerformanceGuide {
    /** 평소 소비로는 모자라지만 예정 지출을 옮기거나 더 받는 혜택보다 적게 더 쓰면 채우는 게 이득 */
    ACHIEVE_RECOMMENDED,
    /** 평소대로 쓰면 월말에 기준을 넘는다 */
    KEEP_NATURAL,
    /** 채우려면 더 받는 혜택보다 많이 더 써야 한다 */
    EXTRA_SPENDING_NOT_RECOMMENDED,
    /** 이번 달 실적을 이미 채웠다 */
    ALREADY_ACHIEVED
}
