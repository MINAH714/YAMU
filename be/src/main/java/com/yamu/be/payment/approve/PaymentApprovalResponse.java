package com.yamu.be.payment.approve;

import com.yamu.be.card.domain.CardType;
import com.yamu.be.payment.PaymentPlan;
import com.yamu.be.payment.domain.PaymentStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 승인 응답(API 명세 「결제 승인 요청 (카드 확정)」 Response). 승인과 확정 거절 모두 200이고 {@code status}로 구분한다.
 *
 * <p>{@code billItems}는 신용카드 승인의 청구 회차(Task 5.2.4). 체크카드·거절은 빈 목록이다.
 * 임시: {@code performance}·{@code limitImpacts}(Task 5.2.5), {@code accountBalanceAfter}(Task 5.2.3)는
 * 그 태스크가 채울 때까지 빈 목록·null이다. 모양은 명세대로 둔다.
 *
 * @param approvalNo        승인번호(임시 규칙 Q3). 거절이면 null
 * @param chargedAmount     체크카드는 즉시할인을 뺀 출금액, 신용카드는 승인 원금. 거절이면 null
 * @param retryable         거절일 때만. 마감 전이고 시도 횟수가 남았으면 true
 * @param remainingAttempts 거절일 때만. 남은 재승인 횟수
 * @param suggestedCards    거절일 때만. 아직 시도하지 않은 추천 후보(최대 2장)
 */
public record PaymentApprovalResponse(
        Long paymentId,
        PaymentStatus status,
        String approvalNo,
        LocalDateTime approvedAt,
        SelectedCard selectedCard,
        long amount,
        PaymentPlan plan,
        Integer installmentMonths,
        List<Benefit> benefits,
        Long chargedAmount,
        Long accountBalanceAfter,
        List<BillItem> billItems,
        Performance performance,
        List<LimitImpact> limitImpacts,
        Failure failure,
        Boolean retryable,
        Integer remainingAttempts,
        List<SuggestedCard> suggestedCards) {

    /**
     * @param nickname        사용자 별칭. 없으면 상품명
     * @param maskedNumber    끝 4자리만 보이는 카드번호
     * @param recommendedRank 추천 순위. 추천 밖의 카드를 직접 골랐으면 null
     * @param userSelected    사용자가 카드를 직접 골랐는지(기본 카드 설정은 바꾸지 않는다)
     */
    public record SelectedCard(Long userCardId, String nickname, String maskedNumber, CardType cardType,
            Integer recommendedRank, boolean userSelected) {}

    /** @param paymentBenefitId {@code payment_benefit} 행 번호. 원장 반영(Task 5.2.5) 전에는 null */
    public record Benefit(Long paymentBenefitId, Long benefitId, String name, String type, long amount,
            String realization, boolean confirmed) {}

    /** 신용카드 청구 회차(Task 5.2.4). */
    public record BillItem(Long billItemId, int seq, long amount, String billingMonth,
            LocalDate expectedWithdrawalDate, String status) {}

    /** 실적 반영(Task 5.2.5). */
    public record Performance(String yearMonth, long recognizedAmount, boolean recognized,
            Double achievementRateAfter) {}

    /** 한도 사용(Task 5.2.5). */
    public record LimitImpact(Long limitGroupId, String period, long consumedAmount, Long remainingAfter) {}

    /** 확정 거절 사유. {@code code}는 {@link FailureCode}. */
    public record Failure(String code, String message, Map<String, Object> details) {}

    /**
     * @param netBenefit 예상 순혜택(원). 추천 후보 값 그대로라 포인트 환산율에 소수가 있으면 {@code 1.5}처럼 소수가 나온다
     *                   (S15P31E103-283). 정수면 소수점 없이 나간다
     */
    public record SuggestedCard(Long userCardId, String nickname, String maskedNumber, CardType cardType,
            int recommendedRank, java.math.BigDecimal netBenefit) {}
}
