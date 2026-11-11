package com.yamu.be.performance;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 카드 한 장의 이번 달 실적 상세(API 명세 V2 #66). 응답 모양은 앱 {@code PerformanceModels.kt}의 {@code PerformanceDetailDto}에
 * 맞춘다(S15P31E103-373).
 *
 * @param yearMonth       한국 기준 이번 달(YYYY-MM)
 * @param tiers           실적 구간. 기준이 작은 순. 실적 조건이 없으면 빈 목록
 * @param nextMonthImpact 이번 달 실적이 다음 달 혜택에 주는 영향. 목표가 없거나 실적 행이 없으면 null
 * @param dataSyncedAt    동기화 개념이 없어 null({@link CardPerformancesResponse} 참고)
 */
public record PerformanceDetailResponse(long userCardId, String yearMonth, Summary summary, List<Tier> tiers,
        NextMonthImpact nextMonthImpact, LocalDateTime dataSyncedAt) {

    /** 목록({@link CardPerformancesResponse.Item})과 같은 값. */
    public record Summary(Long targetAmount, long recognizedAmount, long remainingAmount, PerformanceStatus status) {
    }

    /**
     * 실적 구간 하나(2026-11-10 결정, 임시).
     *
     * @param threshold      실적 기준(원, 포함)
     * @param label          예: "30만원 이상"
     * @param benefitSummary 이 기준에서 새로 열리는 혜택 이름(쉼표로 이음). 한도만 커지는 기준이면 null
     * @param benefitAmount  실적이 기준과 같을 때 받을 수 있는 원화·월 한도의 합. 포인트·횟수 한도뿐이면 null
     * @param achieved       이번 달 인정 실적이 기준 이상인지
     */
    public record Tier(long threshold, String label, String benefitSummary, Long benefitAmount, boolean achieved) {
    }

    public record NextMonthImpact(String message) {
    }
}
