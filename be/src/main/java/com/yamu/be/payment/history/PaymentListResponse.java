package com.yamu.be.payment.history;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.yamu.be.card.domain.CardType;
import com.yamu.be.global.common.PageResponse;
import com.yamu.be.payment.PaymentPlan;
import com.yamu.be.payment.domain.PaymentChannel;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.TransactionSource;
import java.time.LocalDateTime;

/**
 * 결제 내역 목록(API 명세 V2 「결제 내역 목록 조회」, v0.3.1 보완 포함). 합계 아래에 공통 페이지 칸(items·page·size…)이 같은 높이로 펼쳐진다.
 */
public record PaymentListResponse(
        Summary summary,
        @JsonUnwrapped PageResponse<Item> list) {

    /**
     * 필터를 적용한 전체 합계. 취소·거절 건은 count에만 들어가고 금액·혜택 합계에서는 빠진다.
     *
     * @param amount           승인 건의 결제 금액 합계
     * @param expectedBenefit  승인 건의 혜택 원화 합계({@code payment_benefit.krw_amount}). 확정된 혜택도 포함한다
     * @param confirmedBenefit 그중 확정된 혜택 원화 합계({@link BenefitStatus}의 줄 기준)
     * @param byClass          승인 건의 소비 분류별 결제 금액 합계. 분류되지 않은 결제는 어느 분류에도 넣지 않는다
     * @param yamuPickCount    승인 건 중 추천 1순위 카드로 승인한 건수. 상세의 {@code recommendedRank}가 1인 건이고, 사용자가
     *                         1순위를 직접 고른 건도 들어간다. 순위를 적기 전 결제는 저장된 추천 결과의 1순위와 승인 카드가 같으면 센다
     */
    public record Summary(long count, long amount, long expectedBenefit, Long confirmedBenefit, ByClass byClass,
            long yamuPickCount) {}

    /** 소비 분류별 금액(LIVING 생활 소비 · LARGE 큰 결제 · EXCLUDED 실적 제외). */
    public record ByClass(long LIVING, long LARGE, long EXCLUDED) {}

    /**
     * 결제 한 건.
     *
     * @param requestedAt           POS 요청 시각. 목록의 기간 필터·정렬 기준이며, 승인 시각이 없는 거절 건에도 있다(명세에 더한 칸)
     * @param approvedAt            승인 시각. 거절 건은 null
     * @param amount                POS가 요청한 결제 금액(즉시할인 전)
     * @param installmentMonths     할부 개월 수. 일시불은 null
     * @param card                  승인·거절에 쓴 카드
     * @param benefitAmount         승인 건의 적용 혜택 원화 합계. 취소·거절 건이나 기록된 혜택이 없으면 0(취소해도 혜택 기록은
     *                              남지만 받지 않은 혜택이다. 상세의 {@code benefits}에는 기록 그대로 보인다)
     * @param benefitConfirmed      혜택이 모두 확정됐으면 true, 아직 확정되지 않은 혜택이 있으면 false, 혜택이 없으면 null
     * @param performanceRecognized 승인 상태이고 실적 인정액이 0원보다 큰지
     * @param benefitStatus         혜택 확정 상태(명세에 더한 칸, 필터 {@code benefitStatus}와 같은 값)
     * @param spendingClass         소비 분류(LIVING·LARGE·EXCLUDED). 분류 전이면 null
     * @param memo                  사용자 메모. 없으면 null
     * @param channel               ONLINE · OFFLINE · UNKNOWN
     * @param categoryCode          업종 코드(가맹점 칸의 {@code categoryCode}와 같은 값)
     */
    public record Item(
            Long paymentId,
            TransactionSource source,
            PaymentStatus status,
            LocalDateTime requestedAt,
            LocalDateTime approvedAt,
            Merchant merchant,
            long amount,
            PaymentPlan plan,
            Integer installmentMonths,
            Card card,
            long benefitAmount,
            Boolean benefitConfirmed,
            boolean performanceRecognized,
            BenefitStatus benefitStatus,
            String spendingClass,
            String memo,
            PaymentChannel channel,
            String categoryCode) {}

    /**
     * @param name         결제 당시 거래 조건 스냅샷의 가맹점 이름. 없으면 브랜드 이름
     * @param categoryName 업종 표시 이름({@code GET /api/merchant-categories}와 같은 이름). 목록에 없는 코드면 null
     */
    public record Merchant(Long merchantId, String name, String categoryCode, String categoryName) {}

    /** @param nickname 사용자 별칭. 없으면 상품명 */
    public record Card(Long userCardId, String nickname, CardType cardType) {}
}
