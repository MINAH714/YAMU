package com.yamu.be.forecast;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 잔고예보 결과. 명세 「잔고예보 조회」 응답의 확정·예상·보수적 선, 위험 요약·구간, 예상 생활비, 기간 밖 청구 미리보기,
 * 계좌별 결과를 담는다.
 *
 * @param accountIds          중복을 뺀 선택 계좌(요청 순서)
 * @param currentTotalBalance 선택 계좌 현재 잔액 합계(원)
 * @param alertThreshold      안전 잔액(원). 통합·계좌별 위험 판단에 같이 쓴다
 * @param series              통합 날짜 선(기간의 모든 날짜)
 * @param riskWindows         통합 선의 위험 구간
 * @param livingCost          예상 생활비. 자료가 없으면 금액은 null이고 dataOrigin이 INSUFFICIENT
 * @param beyondHorizon       출금일이 기간 뒤인 카드 청구와 신용카드 사용 추정(출금일 순). 선·위험에는 넣지 않는다(S15P31E103-348)
 * @param accountForecasts    계좌별 결과. 자세히 보기(includeAccountDetails)가 아니면 null
 * @param excluded            계산에서 뺀 예정 회차(명세에 없는 필드)
 * @param calculatedAt        계산한 UTC 시각
 */
public record BalanceForecast(List<Long> accountIds, int selectionCount, long currentTotalBalance,
        Horizon horizon, long alertThreshold, Summary summary, List<SeriesPoint> series,
        List<RiskDetector.RiskWindow> riskWindows, LivingCost livingCost, List<BeyondHorizonItem> beyondHorizon,
        List<AccountForecast> accountForecasts,
        List<ExcludedItem> excluded, boolean isEstimate, LocalDateTime calculatedAt) {

    /** 예보 기간 기준. NEXT_INCOME(다음 지정 수입일 전날까지)·DAYS(고정 일수). */
    public enum Until { NEXT_INCOME, DAYS }

    /**
     * 실제로 계산한 기간. NEXT_INCOME은 다음 월급일 전날까지이고, 월급을 설정하지 않았거나 두 달 안에 회차가 없으면 30일 DAYS다.
     *
     * @param from 한국 날짜 오늘
     * @param to   from + days(끝 날 포함). NEXT_INCOME이면 다음 월급일 전날이라 days가 0일 수 있다(월급일이 내일)
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
     * 기간 뒤에 빠질 카드 출금 하나(명세 beyondHorizon). 선택 계좌에서 빠지는 것만 담는다.
     *
     * @param type       CARD_BILLING(확정·진행 중 청구) 또는 CARD_SPENDING_ESTIMATE(이번 이용기간 마감까지 쓸 신용카드 추정)
     * @param amount     출금(음수, 원)
     * @param title      청구는 "카드 이름 N월 청구", 추정은 추정 근거 문장
     * @param confidence 확정 청구 CONFIRMED, 진행 중 청구 EXPECTED, 사용 추정 CONSERVATIVE
     * @param accountId  출금 계좌(명세에 없음, 2026-11-09 Codex 검토로 추가)
     * @param userCardId 그 카드(명세에 없음, 2026-11-09 Codex 검토로 추가)
     */
    public record BeyondHorizonItem(LocalDate date, FlowEvent.Type type, String title, long amount,
            FlowEvent.Confidence confidence, Long accountId, Long userCardId) {}

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
     * @param projectedAmount 예보 기간(내일부터) 예상 생활비 = 보수적 선에서 날마다 빼는 값의 합. AI 또는 TSB
     * @param dailyAverage    지난 3개월 실적 요약의 하루 평균(예측 값이 아님)
     * @param monthlyAverage  지난 3개월 실적 요약의 한 달 평균(예측 값이 아님)
     * @param method          예상 생활비 계산 방식. ML_V1·TSB, 계좌마다 다르면 MIXED
     * @param dataOrigin      예측 입력에 모의 소비(SEED)가 있으면 SEED
     * @param confidence      기록 길이 기준(60일 이상 HIGH, 14~59일 MEDIUM, 1~13일 LOW)
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
