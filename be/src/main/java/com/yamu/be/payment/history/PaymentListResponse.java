package com.yamu.be.payment.history;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.yamu.be.card.domain.CardType;
import com.yamu.be.global.common.PageResponse;
import com.yamu.be.payment.PaymentPlan;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.TransactionSource;
import java.time.LocalDateTime;

/**
 * 결제 내역 목록(API 명세 V2 「결제 내역 목록 조회」). 합계 아래에 공통 페이지 칸(items·page·size…)이 같은 높이로 펼쳐진다.
 *
 * <p>v0.3.1 보완 칸(spendingClass·memo·channel·categoryCode, summary.byClass·yamuPickCount)은 아직 넣지 않았다(별도 Task).
 */
public record PaymentListResponse(
        Summary summary,
        @JsonUnwrapped PageResponse<Item> list) {

    /**
     * 필터를 적용한 전체 합계. 취소·거절 건은 count에만 들어가고 금액·혜택 합계에서는 빠진다.
     *
     * @param amount           승인 건의 결제 금액 합계
     * @param expectedBenefit  승인 건의 혜택 원화 합계({@code payment_benefit.krw_amount})
     * @param confirmedBenefit 임시: 예상·확정 혜택을 나눠 저장하는 칸이 없어 null이다
     */
    public record Summary(long count, long amount, long expectedBenefit, Long confirmedBenefit) {}

    /**
     * 결제 한 건.
     *
     * @param requestedAt           POS 요청 시각. 목록의 기간 필터·정렬 기준이며, 승인 시각이 없는 거절 건에도 있다(명세에 더한 칸)
     * @param approvedAt            승인 시각. 거절 건은 null
     * @param amount                POS가 요청한 결제 금액(즉시할인 전)
     * @param installmentMonths     할부 개월 수. 일시불은 null
     * @param card                  승인·거절에 쓴 카드
     * @param benefitAmount         적용 혜택 원화 합계. 기록된 혜택이 없으면 0
     * @param benefitConfirmed      임시: 확정 여부를 저장하는 칸이 없어 null이다
     * @param performanceRecognized 승인 상태이고 실적 인정액이 0원보다 큰지
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
            boolean performanceRecognized) {}

    /**
     * @param name         결제 당시 거래 조건 스냅샷의 가맹점 이름. 없으면 브랜드 이름
     * @param categoryName 업종 코드표(POL-004)가 정해지기 전이라 null이다
     */
    public record Merchant(Long merchantId, String name, String categoryCode, String categoryName) {}

    /** @param nickname 사용자 별칭. 없으면 상품명 */
    public record Card(Long userCardId, String nickname, CardType cardType) {}
}
