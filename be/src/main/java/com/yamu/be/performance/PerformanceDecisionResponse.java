package com.yamu.be.performance;

import java.time.LocalDateTime;

/**
 * 판단 기록 응답. API 명세서 V2 「실적 판단 결정 기록」.
 *
 * @param guide     기록할 때 서버 판정. 판정할 수 없었으면 null
 * @param canRevert 되돌릴 판단이 있는지
 */
public record PerformanceDecisionResponse(long userCardId, String yearMonth, PerformanceDecision.Choice decision,
        PerformanceGuide guide, LocalDateTime decidedAt, String analysisVersion, Effects effects,
        boolean canRevert) {

    /**
     * @param notificationsMuted 이번 달 이 카드의 실적 부족 알림을 멈췄는지
     * @param homeTodoRemoved    이번 달 홈의 실적 부족 카드·할 일(S15P31E103-378)에서 이 카드가 빠지는지. 채우지 않기(SKIP)면
     *                           true(2026-11-11 결정)
     */
    public record Effects(boolean notificationsMuted, boolean homeTodoRemoved) {
    }
}
