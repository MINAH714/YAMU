package com.yamu.be.forecast;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * 가상 구매 전후 비교 결과. 명세 「가상 구매 시뮬레이션」 응답의 schedule·comparison·series·outsideSelectionImpact·
 * accountComparisons를 담는다. 혜택·추천·실적·한도(card·benefit·alternatives·performanceImpact)는 아직 없다.
 * 계산만 한 결과라 실제 결제·청구·실적·한도는 바뀌지 않는다.
 *
 * @param schedule               가상 구매의 모든 출금 회차(기간 밖 포함)
 * @param comparison             선택 계좌 통합 기준 전후 비교
 * @param series                 통합 선의 날짜별 전후 잔액(기간의 모든 날짜)
 * @param outsideSelectionImpact 카드 출금 계좌가 선택 밖이면 그 영향. 선택 안이면 null
 * @param accountComparisons     선택 계좌별 전후 비교(선택 순서). 응답에 넣을지는 includeAccountDetails로 정한다
 */
public record PurchaseSimulation(List<ScheduledWithdrawal> schedule, Comparison comparison, List<SeriesPoint> series,
        OutsideSelectionImpact outsideSelectionImpact, List<AccountComparison> accountComparisons) {

    /**
     * 가상 구매 출금 한 회차.
     *
     * @param date         실제 출금일(한국 날짜)
     * @param amount       출금액(원, 음수)
     * @param label        화면 이름(예: "1/3회차 (11월 청구)")
     * @param billingMonth 청구 월. 체크카드는 null
     * @param accountId    카드의 실제 출금 계좌
     * @param withinPeriod 선택 계좌에서 비교 기간 안에 빠지면 true. 선택 밖 계좌나 기간 밖이면 false
     */
    public record ScheduledWithdrawal(LocalDate date, long amount, String label, int installmentNo,
            int installmentMonths, YearMonth billingMonth, Long accountId, boolean withinPeriod) {}

    /**
     * 통합 전후 비교.
     *
     * @param withinPeriodImpact 선택 계좌에서 비교 기간 안에 빠지는 금액(원, 음수 또는 0)
     * @param beyondPeriodImpact 선택 계좌에서 비교 기간 뒤로 밀린 금액(원, 음수 또는 0)
     */
    public record Comparison(Snapshot before, Snapshot after, long withinPeriodImpact, long beyondPeriodImpact) {}

    /**
     * 한쪽(구매 전 또는 후)의 요약. 금액은 가장 나쁜 선(보수적, 없으면 예상) 기준이다.
     *
     * @param status 통합 비교는 통합·계좌별 중 가장 나쁜 수준, 계좌 비교는 그 계좌의 수준
     */
    public record Snapshot(long minBalance, LocalDate minBalanceDate, long endBalance, RiskDetector.Level status) {}

    /** 그날 일말 잔액(원). 가장 나쁜 선 기준이다. */
    public record SeriesPoint(LocalDate date, long before, long after) {}

    /**
     * 선택하지 않은 계좌에서 빠지는 가상 구매. 통합 비교에는 넣지 않는다.
     *
     * @param amount 그 계좌에서 빠지는 전체 금액(원, <b>양수</b>). 다른 영향액(음수)과 부호가 다르다
     */
    public record OutsideSelectionImpact(Long accountId, long amount, String note) {}

    /** 계좌 하나의 전후 비교. */
    public record AccountComparison(Long accountId, Snapshot before, Snapshot after) {}
}
