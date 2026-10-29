package com.yamu.be.forecast;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 잔고예보 결과. 명세 「잔고예보 조회」 응답 중 Task 7.1.4 범위(확정·예상 선, 계좌별 결과)만 담는다.
 * 보수적 선·위험 요약·생활비는 Task 7.1.5에서 더한다.
 *
 * @param accountIds          중복을 뺀 선택 계좌(요청 순서)
 * @param currentTotalBalance 선택 계좌 현재 잔액 합계(원)
 * @param series              통합 날짜 선
 * @param accountForecasts    계좌별 결과. 자세히 보기(includeAccountDetails)가 아니면 null
 * @param excluded            계산에서 뺀 예정 회차(명세에 없는 필드)
 * @param calculatedAt        계산한 UTC 시각
 */
public record BalanceForecast(List<Long> accountIds, int selectionCount, long currentTotalBalance,
        Horizon horizon, List<SeriesPoint> series, List<AccountForecast> accountForecasts,
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

    /** 그날 입출금을 모두 반영한 일말 잔액(원). */
    public record SeriesPoint(LocalDate date, long confirmed, long expected, List<Event> events) {}

    /** 그날 반영한 입출금 하나. amount는 입금 +, 출금 −. */
    public record Event(FlowEvent.Type type, String title, long amount, FlowEvent.Confidence confidence) {}

    /**
     * 계좌 하나의 결과. 모든 계좌의 같은 날짜 수치를 더하면 통합 선과 같다.
     *
     * @param balance  현재 잔액(원)
     * @param syncedAt 잔액을 금융망에서 읽은 UTC 시각
     */
    public record AccountForecast(Long accountId, long balance, LocalDateTime syncedAt, List<SeriesPoint> series) {}
}
