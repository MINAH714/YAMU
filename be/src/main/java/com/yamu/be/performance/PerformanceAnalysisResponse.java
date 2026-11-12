package com.yamu.be.performance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * GET /api/user-cards/{userCardId}/performance/analysis 응답(S15P31E103-375). 칸 이름은 API 명세서 V2 「실적 달성 유불리 분석」과
 * 앱 {@code PerformanceAnalysisDto}를 따른다.
 *
 * @param asOf             이 분석을 계산한 UTC 시각. 판단 기록의 analysisVersion으로 그대로 보낸다
 * @param shortfall        예정 지출을 다 옮겨도 더 써야 채우는 돈. 이미 달성·평소대로 달성이면 0
 * @param extraBenefitIfMet 채우면 더 받는 혜택(원화 월 한도 기준 최대치, 추정). 셀 수 없으면 null
 * @param lossIfForced     필요 없는 소비로 채울 때 손해 = shortfall − 더 받는 혜택. 추가 소비 비추천일 때만 0보다 크다
 * @param decision         이번 달 사용자가 기록한 판단. 없거나 되돌렸으면 null
 * @param scenario         scenarioMoveItemIds를 줬을 때 가정한 항목과 결과. 안 줬으면 null
 */
public record PerformanceAnalysisResponse(
        long userCardId,
        String yearMonth,
        LocalDateTime asOf,
        long targetAmount,
        long recognizedAmount,
        long remainingAmount,
        int daysLeft,
        Projection projection,
        PerformanceGuide guide,
        String headline,
        String message,
        List<BenefitAtStake> benefitsAtStake,
        @JsonProperty("isEstimate") boolean isEstimate,
        List<Step> steps,
        List<MovableItem> movableItems,
        Long shortfall,
        Long extraBenefitIfMet,
        Long lossIfForced,
        Decision decision,
        Scenario scenario) {

    /**
     * @param scheduledRecognizable 이 카드로 낼 남은 예정 지출 중 실적 인정 대상 금액
     * @param method               SCHEDULED_PLUS_AVG_3M(예정 지출 + 최근 3개월 같은 기간 평균)
     */
    public record Projection(long scheduledRecognizable, long naturalRecognizable, long averageDailyRecognizable,
            long projectedRecognizedAmount, String likelihood, long shortfallIfTrend, int historyMonths,
            String method) {
    }

    /** typicalMonthlyValue는 혜택마다 셀 수 없어 null(합계는 extraBenefitIfMet). */
    public record BenefitAtStake(String name, Long typicalMonthlyValue) {
    }

    public record Step(int no, String label, Long amount, String note) {
    }

    /**
     * @param currentMethod    ACCOUNT(계좌) · CARD(다른 카드)
     * @param recognizedAmount 옮기면 이 카드 실적에 더해질 금액. 옮길 수 없으면 null
     * @param deadline         이 날까지 결제처에서 카드를 바꿔야 첫 회차부터 반영된다
     */
    public record MovableItem(long cashflowItemId, String title, LocalDate date, long amount, String currentMethod,
            boolean eligible, Long recognizedAmount, LocalDate deadline, String note, String reason) {
    }

    public record Decision(PerformanceDecision.Choice decision, LocalDateTime decidedAt, String analysisVersion,
            boolean notificationsMuted) {
    }

    public record Scenario(List<Long> moveItemIds, long movedAmount, long projectedRecognizedAmount,
            PerformanceGuide guide) {
    }
}
