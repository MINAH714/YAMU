package com.yamu.be.performance;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Size;

/**
 * POST /api/user-cards/{userCardId}/performance/decisions 요청. API 명세서 V2 「실적 판단 결정 기록」.
 *
 * @param decision          FILL · KEEP · SKIP. null이면 되돌리기. 칸이 없으면 400(빈 본문이 되돌리기로 읽히지 않게).
 *                          null과 빠진 칸을 가르려고 JsonNode로 받는다
 * @param analysisVersion   판단 근거로 본 분석의 asOf
 * @param muteNotifications 이번 달 이 카드의 실적 부족 알림을 멈출지. 기본 true
 * @param yearMonth         기본 이번 달. 이번 달만 받는다
 */
public record PerformanceDecisionRequest(
        JsonNode decision,
        @Size(max = 40, message = "analysisVersion은 40자 이하입니다.") String analysisVersion,
        Boolean muteNotifications,
        String yearMonth) {
}
