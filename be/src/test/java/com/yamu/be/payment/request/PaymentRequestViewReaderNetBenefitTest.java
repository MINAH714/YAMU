package com.yamu.be.payment.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.card.domain.CardType;
import com.yamu.be.payment.PaymentTerms;
import com.yamu.be.payment.recommend.RecommendationResult;
import com.yamu.be.payment.recommend.RecommendationResult.Candidate;
import com.yamu.be.payment.recommend.RecommendationResult.CardSummary;
import com.yamu.be.payment.recommend.RecommendationResult.RuleVersion;
import com.yamu.be.payment.recommend.RecommendationResult.SelectionReason;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 앱 결제 요청 조회의 소수 순혜택(S15P31E103-283). 포인트 환산율 0.5면 엔진 순혜택이 1.5원처럼 나온다.
 * 순위·0원 판정과 화면 숫자가 모두 엔진 값을 따른다. longValueExact()였으면 이 조회가 500이었다.
 */
class PaymentRequestViewReaderNetBenefitTest {

    @Test
    void 소수_순혜택은_엔진_순서와_값_그대로_내려준다() {
        RecommendationResult result = new RecommendationResult("rec_test", LocalDateTime.of(2026, 11, 4, 3, 0), false,
                List.of(candidate(1, 10L, "1.5", SelectionReason.MAXIMUM_NET_BENEFIT), candidate(2, 20L, "0.5", null)),
                List.of(), List.of());

        PaymentRequestView.Recommendation view = PaymentRequestViewReader.recommendation(result,
                PaymentTerms.lumpSum(10_000));

        assertThat(view.candidates()).extracting(PaymentRequestView.Candidate::userCardId).containsExactly(10L, 20L);
        assertThat(view.candidates()).extracting(PaymentRequestView.Candidate::netBenefit)
                .containsExactly(new BigDecimal("1.5"), new BigDecimal("0.5"));
        // 모든 후보가 1원 미만이어도 0원이 아니면 혜택 없음이 아니다(POL-011)
        assertThat(view.noBenefit()).isFalse();
    }

    private static Candidate candidate(int rank, long userCardId, String netBenefit, SelectionReason reason) {
        CardSummary card = new CardSummary(userCardId, 100L + userCardId, CardType.CREDIT, false, null, "1234", "TEST",
                "상품");
        return new Candidate(rank, card, new BigDecimal(netBenefit), List.of(), "추천", reason,
                new RuleVersion(card.cardProductId(), 1));
    }
}
