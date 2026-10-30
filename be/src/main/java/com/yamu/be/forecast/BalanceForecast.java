package com.yamu.be.forecast;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 잔고예보 결과. 명세 「잔고예보 조회」 응답의 확정·예상·보수적 선, 위험 요약·구간, 예상 생활비, 계좌별 결과를 담는다.
 * {@code beyondHorizon}(기간 밖 청구 미리보기)은 아직 없다.
 *
 * @param accountIds          중복을 뺀 선택 계좌(요청 순서)
 * @param currentTotalBalance 선택 계좌 현재 잔액 합계(원)
 * @param alertThreshold      안전 잔액(원). 통합·계좌별 위험 판단에 같이 쓴다
 * @param series              통합 날짜 선(기간의 모든 날짜)
 * @param riskWindows         통합 선의 위험 구간
 * @param livingCost          예상 생활비. 자료가 없으면 금액은 null이고 dataOrigin이 INSUFFICIENT
 * @param accountForecasts    계좌별 결과. 자세히 보기(includeAccountDetails)가 아니면 null
 * @param excluded            계산에서 뺀 예정 회차(명세에 없는 필드)
 * @param calculatedAt        계산한 UTC 시각
 */
public record BalanceForecast(List<Long> accountIds, int selectionCount, long currentTotalBalance,
        Horizon horizon, long alertThreshold, Summary summary, List<SeriesPoint> series,
        List<RiskDetector.RiskWindow> riskWindows, LivingCost livingCost, List<AccountForecast> accountForecasts,
        List<ExcludedItem> excluded, boolean isEstimate, LocalDateTime calculatedAt) {

    /** 예보 기간 기준. NEXT_INCOME(다음 지정 수입일 전날까지)·DAYS(고정 일수). */
    public enum Until { NEXT_INCOME, DAYS }

    /**
     * 실제로 계산한 기간. 다음 수입일을 정할 설정이 없으면 NEXT_INCOME도 30일 DAYS로 계산한다.
     *
     * @param from 한국 날짜 오늘
     * @param to   from + days
     */
    public record Horizon(Until type, LocalDate from, LocalDate to, int days) {}

    /**
     * 그날 입출금을 모두 반영한 일말 잔액(원).
     *
     * @param conservative 예상 선에서 추정 생활비를 뺀 잔액. 생활비 자료가 없으면 null
     */
    public record SeriesPoint(LocalDate date, long confirmed, long expected, Long conservative, List<Event> events) {}

    /** 그날 반영한 입출금 하나. amount는 입금 +, 출금 −. */
    public record Event(FlowEvent.Type type, String title, long amount, FlowEvent.Confidence confidence) {}

    /**
     * 통합 위험 요약.
     *
     * @param status             화면에 표시할 최종 수준. 통합·계좌별 수준 중 가장 나쁜 것(계좌 위험이 있으면 최소 WARNING)
     * @param aggregateStatus    통합 선만 본 수준
     * @param minBalance         가장 나쁜 선(보수적, 없으면 예상)의 최저 잔액
     * @param hasAccountRisk     통합이 안전해도 위험한 계좌가 있는지
     * @param atRiskAccountCount 위험한 계좌 수
     * @param largestOutflow     통합 출금 합이 가장 큰 날. 출금이 없으면 null
     */
    public record Summary(RiskDetector.Level status, RiskDetector.Level aggregateStatus, long minBalance,
            LocalDate minBalanceDate, long endBalance, boolean hasAccountRisk, int atRiskAccountCount,
            Outflow largestOutflow) {}

    /** 하루 출금 합계(원, 음수). */
    public record Outflow(LocalDate date, String title, long amount) {}

    /**
     * 예보 기간의 예상 생활비.
     *
     * @param projectedAmount 일평균 × 예보 일수
     * @param method          계산 방식(최근 완료 3개월 평균)
     */
    public record LivingCost(Long projectedAmount, Long dailyAverage, Long monthlyAverage, String method,
            LivingCostEstimator.DataOrigin dataOrigin, LivingCostEstimator.Confidence confidence) {}

    /**
     * 계좌 하나의 결과. 모든 계좌의 같은 날짜 수치를 더하면 통합 선과 같다.
     *
     * @param balance  현재 잔액(원)
     * @param syncedAt 잔액을 금융망에서 읽은 UTC 시각
     */
    public record AccountForecast(Long accountId, long balance, LocalDateTime syncedAt, AccountSummary summary,
            List<SeriesPoint> series, List<RiskDetector.RiskWindow> riskWindows) {}

    /** 계좌 하나의 위험 요약. 금액은 가장 나쁜 선 기준이다. */
    public record AccountSummary(RiskDetector.Level status, long minBalance, LocalDate minBalanceDate,
            long endBalance) {}
}
