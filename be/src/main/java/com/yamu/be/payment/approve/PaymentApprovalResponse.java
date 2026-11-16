package com.yamu.be.payment.approve;

import com.yamu.be.card.CardIssuers;
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
 * {@code benefits}·{@code performance}·{@code limitImpacts}는 승인 때 다시 계산해 원장에 적은 값이다(Task 5.2.5).
 * 실적 인정액은 BE3 계산 값이다(Task 5.2.11). 임시: 제외(0)와 미확인(NULL)은 모두 {@code recognizedAmount=0, recognized=false}로
 * 나가고, 달성률과 {@code accountBalanceAfter}(Task 5.2.3)는 null이다.
 * 복구로 확정된 결제의 응답은 혜택·실적·한도를 싣지 않는다.
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
     * @param issuer          카드사 코드·이름·대표색(앱 칸, S15P31E103-389). 카드 목록 API와 같은 모양이다. 해제된 카드라
     *                        카드사를 읽지 못한 복구 응답은 null
     */
    public record SelectedCard(Long userCardId, String nickname, String maskedNumber, CardType cardType,
            Integer recommendedRank, boolean userSelected, CardIssuers.Issuer issuer) {}

    /**
     * @param paymentBenefitId {@code payment_benefit} 행 번호. 원장 저장 전에 만든 응답(저장 실패 뒤 재전송 등)은 null
     * @param amount           혜택 자기 단위의 값({@code unit}). 적립({@code POINT})이면 원이 아니라 포인트 수다
     * @param confirmed        승인과 함께 반영된 즉시할인이면 true. 청구할인·캐시백·적립은 청구·지급 때 확정되므로 false
     * @param unit             {@code amount}의 단위(KRW·POINT, {@code payment_benefit.value_unit}). 원장 저장 전에 만든 응답은 null
     * @param krwAmount        원화 금액({@code payment_benefit.krw_amount}, 결제 상세의 {@code actualAmount}와 같은 값).
     *                         포인트 환산율이 없어 원화로 바꾸지 못했거나 원장 저장 전에 만든 응답이면 null(S15P31E103-580)
     */
    public record Benefit(Long paymentBenefitId, Long benefitId, String name, String type, long amount,
            String realization, boolean confirmed, String unit, Long krwAmount) {}

    /** 신용카드 청구 회차(Task 5.2.4). */
    public record BillItem(Long billItemId, int seq, long amount, String billingMonth,
            LocalDate expectedWithdrawalDate, String status) {}

    /**
     * 실적 반영(Task 5.2.5).
     *
     * @param recognizedTotalAfter 이 결제를 반영한 그 카드·그 실적 월의 누적 실적(앱 칸, S15P31E103-390). 카드 목록 API의
     *                             이번 달 실적과 같은 값이다. 인정액 미확인이거나 읽지 못했으면 null
     * @param targetAmount         목표 실적. 카드 목록 API와 같은 규칙(누적보다 큰 기준 중 가장 작은 값, 다 넘었으면 가장 큰 기준).
     *                             실적 조건이 없는 카드·인정액 미확인은 null
     */
    public record Performance(String yearMonth, long recognizedAmount, boolean recognized,
            Double achievementRateAfter, Long recognizedTotalAfter, Long targetAmount) {}

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
