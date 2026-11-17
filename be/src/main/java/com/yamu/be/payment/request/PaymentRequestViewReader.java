package com.yamu.be.payment.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.payment.PaymentPlan;
import com.yamu.be.payment.PaymentTerms;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import com.yamu.be.payment.merchant.MerchantCategoryNames;
import com.yamu.be.payment.recommend.RecommendationResult;
import com.yamu.be.payment.recommend.RecommendationResult.CardSummary;
import com.yamu.be.payment.recommend.RecommendationResult.SelectionReason;
import com.yamu.be.payment.recommend.RecommendationResult.UncomparedCard;
import com.yamu.be.payment.recommend.RecommendationSnapshotStore;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 바코드 조회에 넣을 결제 요청 화면 값을 만든다. 결제 조건은 저장된 스냅샷(merchant_context_snapshot)에서 읽는다.
 *
 * <p>DB 트랜잭션을 열지 않는다. 결제 행은 저장소 호출 하나로 읽고, Redis의 추천 스냅샷은 커넥션을 잡지 않은 채 읽는다.
 * 결제 확정 마감 시간은 POS 설정 값({@code yamu.pos.confirm-window})을 쓰되 pos 패키지에 기대지 않으려고 설정 키로 받는다.
 */
@Component
public class PaymentRequestViewReader {

    static final int CANDIDATES_SHOWN = 2;

    private final PaymentTransactionRepository payments;
    private final RecommendationSnapshotStore snapshots;
    private final ObjectMapper objectMapper;
    private final Duration confirmWindow;

    public PaymentRequestViewReader(PaymentTransactionRepository payments, RecommendationSnapshotStore snapshots,
            ObjectMapper objectMapper, @Value("${yamu.pos.confirm-window}") Duration confirmWindow) {
        this.payments = payments;
        this.snapshots = snapshots;
        this.objectMapper = objectMapper;
        this.confirmWindow = confirmWindow;
    }

    /** 이 사용자의 결제만 돌려준다. 남의 결제는 empty다. */
    public Optional<PaymentRequestView> find(Long paymentId, Long userId) {
        return payments.findByIdAndUserId(paymentId, userId).map(this::view);
    }

    private PaymentRequestView view(PaymentTransaction payment) {
        Optional<MerchantContextSnapshot> context = MerchantContextSnapshot.of(payment, objectMapper);
        PaymentPlan plan = payment.isInstallment() ? PaymentPlan.INSTALLMENT : PaymentPlan.LUMP_SUM;
        Integer months = payment.isInstallment() ? payment.getInstallmentMonths() : null;
        String categoryCode = context.map(MerchantContextSnapshot::categoryCode).orElse(null);
        PaymentRequestView.Merchant merchant = new PaymentRequestView.Merchant(payment.getMerchantId(),
                context.map(MerchantContextSnapshot::merchantName).orElse(null), categoryCode,
                MerchantCategoryNames.of(categoryCode), payment.getPaymentChannel());
        PaymentTerms terms = new PaymentTerms(payment.getRequestedAmount(), plan, months);
        PaymentRequestView.Recommendation recommendation = snapshots.find(payment.getId())
                .map(result -> recommendation(result, terms)).orElse(null);
        return new PaymentRequestView(payment.getId(), payment.getStatus(), merchant, payment.getRequestedAmount(),
                plan, months, payment.getRequestedAt().plus(confirmWindow), recommendation);
    }

    static PaymentRequestView.Recommendation recommendation(RecommendationResult result, PaymentTerms terms) {
        List<PaymentRequestView.Candidate> candidates = result.candidates().stream()
                .limit(CANDIDATES_SHOWN)
                .map(c -> candidate(c, terms, result.benefitNotice(c.card().userCardId())))
                .toList();
        List<PaymentRequestView.UncomparedCard> uncompared = result.uncomparedCards().stream()
                .map(PaymentRequestViewReader::uncompared)
                .toList();
        boolean directPayable = result.uncomparedCards().stream().anyMatch(UncomparedCard::directPaymentAllowed);
        boolean defaultCardApplied = result.topCandidate()
                .map(top -> top.selectionReason() == SelectionReason.DEFAULT_CARD).orElse(false);
        return new PaymentRequestView.Recommendation(result.recommendationId(), result.calculatedAt(),
                result.status(), candidates, result.noBenefit(), result.excludedCards().size(), uncompared,
                directPayable ? RecommendationResult.NO_BENEFIT_NOTICE : null, defaultCardApplied);
    }

    private static PaymentRequestView.Candidate candidate(RecommendationResult.Candidate c, PaymentTerms terms, String benefitNotice) {
        CardSummary card = c.card();
        return new PaymentRequestView.Candidate(c.rank(), card.userCardId(), nickname(card), card.cardType(),
                masked(card), c.netBenefit(),
                c.benefits().stream().map(b -> new PaymentRequestView.Benefit(b.benefitId(), b.name(), b.type(),
                        b.amount(), b.realization())).toList(),
                terms.chargedAmount(c.instantDiscount()), c.reason(), c.selectionReason(),c.unallocatedBenefitGroups(),benefitNotice);
    }

    private static PaymentRequestView.UncomparedCard uncompared(UncomparedCard u) {
        CardSummary card = u.card();
        return new PaymentRequestView.UncomparedCard(card.userCardId(), nickname(card), card.cardType(), masked(card),
                u.reason(), u.message(), u.directPaymentAllowed());
    }

    private static String nickname(CardSummary card) {
        return card.alias() != null ? card.alias() : card.productName();
    }

    private static String masked(CardSummary card) {
        return "****-****-****-" + card.last4();
    }
}
