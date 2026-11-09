package com.yamu.be.report;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 월간 혜택 리포트(API 명세 V2 #28 「카드별 혜택 리포트」). 금액은 모두 원이고 승인 결제만 본다(취소·거절 제외).
 *
 * <p>명세와 다른 점(S15P31E103-372, 앱 DTO에 맞춤): 명세가 가정한 {@code PAYMENT_BENEFIT.expected_amount}·{@code actual_amount}
 * 칸이 DB에 없다. 승인 때 기록한 혜택이 청구 확정 때도 그대로라 "예상과 확정의 차이"는 생기지 않으므로, {@code differences}는
 * 추천 1순위 대비 놓친 혜택이다. {@code summary.byType}·{@code cards}·{@code insights}는 아직 없고 {@code byCategory}·
 * {@code yamuPickExtra}·{@code missedBenefit}을 더했다.
 *
 * @param yearMonth   한국 기준 월(YYYY-MM)
 * @param status      CONFIRMED: 지난 달이고 그 달 혜택이 모두 확정됨 · PARTIAL: 이번 달이거나 확정 전 혜택이 있음
 * @param summary     혜택 합계
 * @param differences 놓친 혜택 결제 목록(승인 시각 순)
 * @param byCategory  업종별 받은 혜택(확정 전 포함). 금액이 큰 순, 0원 업종은 없음
 */
public record BenefitReportResponse(
        String yearMonth,
        String status,
        Summary summary,
        List<Difference> differences,
        List<MonthlyReportResponse.CategoryAmount> byCategory) {

    /**
     * @param expectedBenefit  승인 결제의 혜택 원화 합계(확정 전 포함)
     * @param confirmedBenefit 그중 확정된 혜택 합계
     * @param yamuPickExtra    추천 1순위로 승인한 결제에서 1순위가 2순위보다 더 준 혜택의 합(원 단위 내림). 2순위가 없는 결제는 빼고 센다
     * @param missedBenefit    {@code differences}의 놓친 금액 합계
     */
    public record Summary(long expectedBenefit, long confirmedBenefit, long yamuPickExtra, long missedBenefit) {}

    /**
     * 놓친 혜택 한 건. 야무로 결제했고 저장된 추천 결과의 1순위 순혜택이 실제 받은 혜택보다 큰 결제다.
     *
     * @param expectedAmount 추천 1순위 순혜택(원 단위 내림)
     * @param actualAmount   실제 받은 혜택 원화 합계
     * @param reason         USER_SELECTED: 1순위가 아닌 카드로 승인 · LIMIT_PARTIAL: 1순위 카드였지만 승인 때 혜택이 줄어듦
     */
    public record Difference(long paymentId, LocalDateTime approvedAt, String merchantName, Long userCardId,
            long expectedAmount, long actualAmount, String reason) {}
}
