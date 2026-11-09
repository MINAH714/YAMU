package com.yamu.be.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

/**
 * 생활비 예측 상세(명세 「생활비 예측 상세」). 잔고예보의 보수적 선에 쓴 예상 생활비의 계산 근거다.
 *
 * @param projectedAmount    예보 기간(내일부터) 예상 생활비(원, AI 또는 TSB). 한 계좌라도 자료가 없으면 null
 * @param dailyAverage       지난 3개월 실적 요약의 하루 평균(원). 예측 값이 아니다
 * @param monthlyAverage     지난 3개월 실적 요약의 한 달 평균(원). 예측 값이 아니다
 * @param method             예상 생활비 계산 방식. ML_V1(AI)·TSB, 계좌마다 다르면 MIXED. 자료 있는 계좌가 없으면 null
 * @param dataOrigin         예측 입력(어제까지 90일)에 모의 소비(SEED)가 있으면 SEED
 * @param confidence         기록 길이: 60일 이상 HIGH, 14~59일 MEDIUM, 1~13일 LOW, 없으면 INSUFFICIENT(가장 낮은 계좌)
 * @param sources            지난 3개월 실적 요약에 쓴 달별 합계(계좌 합)
 * @param excluded           중복 예측을 막으려고 생활비에서 빼는 소비 종류 안내
 * @param period             예상 생활비를 계산한 기간. 같은 until·days로 부른 잔고예보와 같다
 * @param dailyTarget        잔고예보 설정의 하루 목표 생활비(dailyLivingCostTarget). 없으면 null. 예측 값에는 섞지 않는다
 * @param paceCheck          이번 달 소비 속도. 계산할 수 없으면 null
 * @param dataSyncedAt       생활비 원천을 읽은 UTC 시각
 * @param accountLivingCosts 계좌별 생활비. 자세히 보기(includeAccountDetails)가 아니면 null
 */
public record LivingCostForecast(List<Long> accountIds, Long projectedAmount, Long dailyAverage, Long monthlyAverage,
        String method, LivingCostEstimator.DataOrigin dataOrigin, LivingCostEstimator.Confidence confidence,
        Period period, List<Source> sources, List<Exclusion> excluded, Long dailyTarget, PaceCheck paceCheck,
        LocalDateTime dataSyncedAt, List<AccountLivingCost> accountLivingCosts) {

    /** 예상 생활비를 계산한 예보 기간. basis는 실제로 쓴 기간 기준(다음 월급일이 없으면 NEXT_INCOME이어도 DAYS). */
    public record Period(LocalDate from, LocalDate to, int days, BalanceForecast.Until basis) {}

    public record Source(YearMonth yearMonth, long amount) {}

    public record Exclusion(String type, String note) {}

    /**
     * @param overRatio 이번 달 하루 평균이 평소보다 몇 배 더 많은지에서 1을 뺀 값(0.3이면 30% 초과)
     * @param flagged   평소의 1.3배를 넘었는지
     * @param checkedAt 계산한 UTC 시각
     */
    public record PaceCheck(long thisMonthDaily, long averageDaily, BigDecimal overRatio, boolean flagged,
            LocalDateTime checkedAt) {}

    /**
     * @param dailyAverage    지난 3개월 실적 요약의 하루 평균
     * @param projectedAmount 예보 기간 예상 생활비. 기록이 없으면 null
     * @param method          ML_V1 또는 TSB. 기록이 없으면 null
     */
    public record AccountLivingCost(Long accountId, Long dailyAverage, Long monthlyAverage, Long projectedAmount,
            String method, LivingCostEstimator.DataOrigin dataOrigin) {}
}
