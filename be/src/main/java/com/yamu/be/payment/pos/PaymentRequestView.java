package com.yamu.be.payment.pos;

import com.yamu.be.card.domain.CardType;
import com.yamu.be.payment.PaymentPlan;
import com.yamu.be.payment.domain.PaymentChannel;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.recommend.RecommendationResult.TieBreak;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 앱이 바코드 조회로 보는 결제 요청(API 명세 「바코드 상태·결제 요청 조회」 Response의 {@code payment}).
 * POS가 정한 조건과 그 조건으로 계산한 추천 상위 2장. 앱은 조건을 바꾸지 못한다.
 *
 * @param installmentMonths 할부일 때만. 일시불은 null
 * @param recommendation    추천 스냅샷이 없으면(보관 종료·장애) null. 승인 때 다시 계산한다(Q2)
 */
public record PaymentRequestView(
        Long paymentId,
        PaymentStatus status,
        Merchant merchant,
        long amount,
        PaymentPlan plan,
        Integer installmentMonths,
        LocalDateTime confirmDeadline,
        Recommendation recommendation) {

    public record Merchant(Long merchantId, String name, String categoryCode, PaymentChannel channel) {}

    /**
     * @param candidates         순혜택 내림차순 상위 2장
     * @param excludedCount      결제 불가로 뺀 카드 수
     * @param defaultCardApplied 1순위가 동률 규칙 중 기본 카드로 정해졌는지
     */
    public record Recommendation(String recommendationId, LocalDateTime calculatedAt, List<Candidate> candidates,
            int excludedCount, boolean defaultCardApplied) {}

    /**
     * @param nickname      사용자 별칭. 없으면 상품명
     * @param maskedNumber  끝 4자리만 보이는 카드번호
     * @param chargedAmount 지금 빠지거나 청구에 올라갈 금액(즉시할인만 차감)
     */
    public record Candidate(int rank, Long userCardId, String nickname, CardType cardType, String maskedNumber,
            long netBenefit, List<Benefit> benefits, long chargedAmount, String reason, TieBreak tieBreak) {}

    public record Benefit(Long benefitId, String name, String type, long amount, String realization) {}
}
