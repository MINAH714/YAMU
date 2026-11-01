package com.yamu.be.payment.pos;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.payment.PaymentExpiry;
import com.yamu.be.payment.PaymentPlan;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import com.yamu.be.payment.recommend.RecommendationResult;
import com.yamu.be.payment.recommend.RecommendationResult.TieBreak;
import com.yamu.be.payment.recommend.RecommendationSnapshotStore;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 바코드 조회에 넣을 결제 요청 화면 값을 만든다. 결제 조건은 저장된 스냅샷(merchant_context_snapshot)에서 읽는다. */
@Component
public class PaymentRequestViewReader {

    static final int CANDIDATES_SHOWN = 2;

    private final PaymentTransactionRepository payments;
    private final RecommendationSnapshotStore snapshots;
    private final PaymentExpiry expiry;
    private final ObjectMapper objectMapper;
    private final PosProperties properties;

    public PaymentRequestViewReader(PaymentTransactionRepository payments, RecommendationSnapshotStore snapshots,
            PaymentExpiry expiry, ObjectMapper objectMapper, PosProperties properties) {
        this.payments = payments;
        this.snapshots = snapshots;
        this.expiry = expiry;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 이 사용자의 결제만 돌려준다. 남의 결제는 empty다.
     * 마감(60초)이 지난 REQUESTED·DECLINED는 조회하는 자리에서 EXPIRED로 바꿔 보여 준다(임시: 명세 「결제 상태·예외 처리」 Q4 제안).
     */
    public Optional<PaymentRequestView> find(Long paymentId, Long userId) {
        Optional<PaymentTransaction> found = payments.findByIdAndUserId(paymentId, userId);
        if (found.isPresent() && expiry.isDue(found.get())) {
            expiry.expire(paymentId);
            found = payments.findByIdAndUserId(paymentId, userId);
        }
        return found.map(this::view);
    }

    private PaymentRequestView view(PaymentTransaction payment) {
        MerchantContextSnapshot context = readContext(payment);
        PaymentPlan plan = payment.isInstallment() ? PaymentPlan.INSTALLMENT : PaymentPlan.LUMP_SUM;
        Integer months = payment.isInstallment() ? payment.getInstallmentMonths() : null;
        PaymentRequestView.Merchant merchant = new PaymentRequestView.Merchant(payment.getMerchantId(),
                context == null ? null : context.merchantName(),
                context == null ? null : context.categoryCode(),
                payment.getPaymentChannel());
        PaymentRequestView.Recommendation recommendation = snapshots.find(payment.getId())
                .map(PaymentRequestViewReader::recommendation).orElse(null);
        return new PaymentRequestView(payment.getId(), payment.getStatus(), merchant, payment.getRequestedAmount(),
                plan, months, payment.getRequestedAt().plus(properties.confirmWindow()), recommendation);
    }

    private MerchantContextSnapshot readContext(PaymentTransaction payment) {
        if (payment.getMerchantContextSnapshot() == null) {
            return null;
        }
        try {
            return objectMapper.readValue(payment.getMerchantContextSnapshot(), MerchantContextSnapshot.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    static PaymentRequestView.Recommendation recommendation(RecommendationResult result) {
        List<PaymentRequestView.Candidate> candidates = result.candidates().stream()
                .limit(CANDIDATES_SHOWN)
                .map(PaymentRequestViewReader::candidate)
                .toList();
        boolean defaultCardApplied = result.topCandidate()
                .map(top -> top.tieBreak() == TieBreak.DEFAULT_CARD).orElse(false);
        return new PaymentRequestView.Recommendation(result.recommendationId(), result.calculatedAt(), candidates,
                result.excludedCards().size(), defaultCardApplied);
    }

    private static PaymentRequestView.Candidate candidate(RecommendationResult.Candidate c) {
        RecommendationResult.CardSummary card = c.card();
        String nickname = card.alias() != null ? card.alias() : card.productName();
        return new PaymentRequestView.Candidate(c.rank(), card.userCardId(), nickname, card.cardType(),
                "****-****-****-" + card.last4(), c.netBenefit(),
                c.benefits().stream().map(b -> new PaymentRequestView.Benefit(b.benefitId(), b.name(), b.type(),
                        b.amount(), b.realization())).toList(),
                c.chargedAmount(), c.reason(), c.tieBreak());
    }
}
