package com.yamu.be.performance;

import java.time.LocalDateTime;

/**
 * 판단 기록 응답. API 명세서 V2 「실적 판단 결정 기록」. 명세서의 effects.homeTodoRemoved는 홈 할 일(S15P31E103-378)이 이
 * 기록을 읽기 전이라 넣지 않았다.
 *
 * @param guide     기록할 때 서버 판정. 판정할 수 없었으면 null
 * @param canRevert 되돌릴 판단이 있는지
 */
public record PerformanceDecisionResponse(long userCardId, String yearMonth, PerformanceDecision.Choice decision,
        PerformanceGuide guide, LocalDateTime decidedAt, String analysisVersion, Effects effects,
        boolean canRevert) {

    public record Effects(boolean notificationsMuted) {
    }
}
