package com.yamu.be.report;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 월간 소비 리포트(API 명세 V2 #55 「월간 소비 리포트」). 금액은 모두 원이고 승인 결제만 더한다(취소·거절 제외).
 *
 * <p>명세와 다른 점(S15P31E103-372, 앱 DTO에 맞춤): 업종 칸 이름이 {@code categoryCode}·{@code categoryName}이다.
 * {@code livingSpent}·{@code prevSamePeriodSpent}·{@code periodTo}를 더했고, {@code byCard}·{@code recurringVsIrregular}·
 * {@code insights}·{@code changeRate}는 아직 없다.
 *
 * @param yearMonth           한국 기준 월(YYYY-MM)
 * @param isPartialMonth      아직 끝나지 않은 달(이번 달)이면 true
 * @param periodTo            집계 끝 날(한국 날짜, 포함). 이번 달이면 오늘, 지난 달이면 말일
 * @param totalSpent          승인 결제 금액 합계
 * @param livingSpent         그중 생활 소비(소비 분류 LIVING) 합계
 * @param prevSamePeriodSpent 지난달 1일부터 {@code periodTo}와 같은 날까지의 생활 소비 합계. 지난달이 짧으면 말일까지
 * @param prevMonthTotalSpent 지난달 전체 승인 결제 금액 합계
 * @param byCategory          업종별 결제 금액. 금액이 큰 순
 * @param benefitSummary      혜택 합계
 */
public record MonthlyReportResponse(
        String yearMonth,
        @JsonProperty("isPartialMonth") boolean isPartialMonth,
        LocalDate periodTo,
        long totalSpent,
        long livingSpent,
        long prevSamePeriodSpent,
        long prevMonthTotalSpent,
        List<CategoryAmount> byCategory,
        BenefitSummary benefitSummary) {

    /**
     * 업종 한 줄.
     *
     * @param categoryName 업종 표시 이름. 목록에 없는 코드면 코드 그대로
     * @param ratio        전체 대비 비율(%, 소수 첫째 자리 반올림)
     */
    public record CategoryAmount(String categoryCode, String categoryName, long amount, BigDecimal ratio) {}

    /**
     * @param expected 승인 결제의 혜택 원화 합계(확정 전 포함)
     * @param actual   그중 확정된 혜택 합계
     */
    public record BenefitSummary(long expected, long actual) {}
}
