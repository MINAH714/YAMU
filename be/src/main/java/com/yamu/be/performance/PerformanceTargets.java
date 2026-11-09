package com.yamu.be.performance;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 유불리 분석이 쓰는 이번 달 목표 실적과 실적 구간(S15P31E103-375).
 *
 * <p>목표·구간 규칙은 카드별 실적 현황(S15P31E103-373)이 BE1 카드 목록 임시 규칙(-308)을 열어 쓰는 것과 같아야 한다. 373이
 * develop에 머지되기 전에 시작해서(2026-11-10 결정) 지금은 {@link PendingPerformanceTargets}가 목표 없음만 돌려주고, 373 머지
 * 뒤 그 규칙을 쓰는 구현으로 바꾼다.
 */
interface PerformanceTargets {

    /**
     * @param cardProductId 카드 상품
     * @param recognized    이번 달 인정 실적(목표는 이보다 큰 가장 작은 구간)
     * @param today         한국 날짜 오늘(오늘 유효한 혜택 정책 버전)
     * @return 목표와 구간. 실적 조건이 없는 카드면 비어 있다
     */
    Optional<PerformanceAnalyzer.Target> targetFor(long cardProductId, long recognized, LocalDate today);
}
