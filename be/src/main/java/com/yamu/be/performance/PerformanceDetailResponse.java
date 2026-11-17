package com.yamu.be.performance;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 카드 한 장의 이번 달 실적 상세(API 명세 V2 #66). 응답 모양은 앱 {@code PerformanceModels.kt}의 {@code PerformanceDetailDto}에
 * 맞춘다(S15P31E103-373).
 *
 * @param yearMonth       한국 기준 이번 달(YYYY-MM)
 * @param tiers           실적 구간. 기준이 작은 순. 실적 조건이 없으면 빈 목록
 * @param benefits        혜택마다 내용 한 줄(S15P31E103-615, 2026-11-17 임시). 필요한 실적이 낮은 순, 비율이 높은 순. 없으면 빈 목록
 * @param nextMonthImpact 이번 달 실적이 다음 달 혜택에 주는 영향. 목표가 없거나 실적 행이 없으면 null
 * @param dataSyncedAt    동기화 개념이 없어 null({@link CardPerformancesResponse} 참고)
 */
public record PerformanceDetailResponse(long userCardId, String yearMonth, Summary summary, List<Tier> tiers,
        List<Benefit> benefits, NextMonthImpact nextMonthImpact, LocalDateTime dataSyncedAt) {

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

    /**
     * 혜택 하나의 내용({@link com.yamu.be.card.CardBenefitSummaryReader.BenefitLine}과 같은 값).
     *
     * @param valueLabel           "0.8%" · "2,500원"
     * @param minPerformanceAmount 받으려면 필요한 전월 실적(원). 조건 없으면 0
     * @param minPaymentAmount     한 번에 이만큼 이상 결제해야 받는 금액(원). 조건 없으면 0
     * @param perTransactionLimit  건당 최대(원). 없거나 모르면 null
     * @param monthlyCount         한 달에 받는 횟수. 없거나 모르면 null
     * @param limited              한도에 걸려 있는지. false면 한도 없이 받는다
     */
    public record Benefit(String name, String valueLabel, long minPerformanceAmount, long minPaymentAmount,
            Long perTransactionLimit, Integer monthlyCount, boolean limited) {
    }
}
