package com.yamu.be.payment.request;

import com.yamu.be.card.domain.CardType;
import com.yamu.be.payment.PaymentPlan;
import com.yamu.be.payment.domain.PaymentChannel;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.recommend.RecommendationResult;
import com.yamu.be.payment.recommend.RecommendationResult.SelectionReason;
import com.yamu.be.payment.recommend.RecommendationResult.Status;
import com.yamu.be.payment.recommend.RecommendationResult.UncomparedReason;
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
     * 임시(명세 반영 전, 11/2 정책 변경): {@code status}·{@code noBenefit}·{@code uncomparedCards}·{@code noBenefitNotice}는
     * 명세 「바코드 상태·결제 요청 조회」에 아직 없는 칸이다.
     *
     * @param status             추천 상태. NO_ELIGIBLE_CARD와 DATA_INSUFFICIENT를 앱이 구분할 수 있다
     * @param candidates         순혜택 내림차순 상위 2장. 혜택을 확인하지 못한 카드는 여기 섞지 않는다
     * @param noBenefit          확인된 후보의 순혜택이 모두 0원인지. 미비교 카드는 0원으로 세지 않는다
     * @param excludedCount      결제 불가로 뺀 카드 수
     * @param uncomparedCards    비교하지 못한 카드와 사유. 순위가 없다
     * @param noBenefitNotice    혜택 없이 직접 결제할 수 있는 미비교 카드가 있으면, 그 카드를 고를 때 보여 주고 확인받을 문구. 없으면 null
     * @param defaultCardApplied 1순위가 동률 규칙 중 기본 카드로 정해졌는지
     */
    public record Recommendation(String recommendationId, LocalDateTime calculatedAt, Status status,
            List<Candidate> candidates, boolean noBenefit, int excludedCount, List<UncomparedCard> uncomparedCards,
            String noBenefitNotice, boolean defaultCardApplied) {}

    /**
     * @param nickname        사용자 별칭. 없으면 상품명
     * @param maskedNumber    끝 4자리만 보이는 카드번호
     * @param netBenefit      예상 순혜택(원). 엔진 값 그대로라 포인트 환산율에 소수가 있으면 {@code 1.5}처럼 소수가 나온다
     *                        (S15P31E103-283). 정수면 소수점 없이 나간다. {@code reason} 문구의 금액과 같은 값이다
     * @param chargedAmount   지금 빠지거나 청구에 올라갈 금액(즉시할인만 차감). 결제 조건으로 서버가 계산한다
     * @param selectionReason 1순위를 고른 기준. 1순위에만 있다
     */
    public record Candidate(int rank, Long userCardId, String nickname, CardType cardType, String maskedNumber,
            java.math.BigDecimal netBenefit, List<Benefit> benefits, long chargedAmount, String reason,
            SelectionReason selectionReason, List<RecommendationResult.UnallocatedBenefitGroup> unallocatedBenefitGroups) {
        public Candidate(int rank,Long userCardId,String nickname,CardType cardType,String maskedNumber,java.math.BigDecimal netBenefit,
                List<Benefit> benefits,long chargedAmount,String reason,SelectionReason selectionReason) {
            this(rank,userCardId,nickname,cardType,maskedNumber,netBenefit,benefits,chargedAmount,reason,selectionReason,List.of());
        }
        public Candidate { unallocatedBenefitGroups=unallocatedBenefitGroups==null ? List.of() : List.copyOf(unallocatedBenefitGroups); }
    }

    public record Benefit(Long benefitId, String name, String type, Long amount, String realization) {}

    /**
     * 비교하지 못한 카드.
     *
     * @param directPaymentAllowed 사용자가 직접 골라 혜택 없이 원금으로 결제할 수 있는지(결제 가능 확인·혜택만 미확인)
     */
    public record UncomparedCard(Long userCardId, String nickname, CardType cardType, String maskedNumber,
            UncomparedReason reason, String message, boolean directPaymentAllowed) {}
}
