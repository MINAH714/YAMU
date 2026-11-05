package com.yamu.be.payment.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yamu.be.card.domain.CardType;
import com.yamu.be.payment.PaymentTerms;
import com.yamu.be.payment.recommend.RecommendationRequest.MerchantContext;
import com.yamu.be.payment.recommend.RecommendationResult.Candidate;
import com.yamu.be.recommendation.BenefitPolicy.BenefitType;
import com.yamu.be.recommendation.BenefitPolicy.Unit;
import com.yamu.be.recommendation.CardBenefitCalculator.BenefitValue;
import com.yamu.be.recommendation.CardBenefitCalculator.Calculation;
import com.yamu.be.recommendation.RecommendationSelector;
import com.yamu.be.recommendation.RecommendationSelector.CardEvaluation;
import com.yamu.be.recommendation.RecommendationSelector.CardStatus;
import com.yamu.be.recommendation.RecommendationService;
import com.yamu.be.recommendation.RecommendationService.CardContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 소수 순혜택(S15P31E103-283). 포인트 환산율에 소수(0.5)가 있으면 엔진의 순혜택이 1.5원처럼 나온다.
 * 포트는 엔진 값과 순위를 그대로 후보에 담는다(명세: 공식 환산 뒤 추가 반올림 없이 비교). 결제 쪽(승인 응답의 추천 카드 목록·앱 결제
 * 요청 조회)도 같은 값을 내보내므로 정수 변환 때문에 응답이 실패하지 않는다. 표기만 정리한다(끝의 0 제거, 정수는 소수점 없이).
 */
class DatabaseCardRecommenderNetBenefitTest {

    static final Instant NOW = Instant.parse("2026-11-04T03:00:00Z");
    static final MerchantContext CAFE = new MerchantContext(301L, "F01-CAFE", "OFFLINE", "GENERAL");

    @Test
    void 소수_환산율의_순혜택은_엔진_값과_순위를_그대로_담는다() {
        // 카드 10: 3포인트 × 0.5 = 1.5원, 카드 20: 2포인트 × 0.5 = 1.0원, 카드 30: 1포인트 × 0.5 = 0.5원
        RecommendationService service = mock(RecommendationService.class);
        when(service.recommend(any())).thenReturn(result(
                calculated(10L, "1.5", 3), calculated(20L, "1.0", 2), calculated(30L, "0.5", 1)));
        DatabaseCardRecommender recommender = new DatabaseCardRecommender(service, Clock.fixed(NOW, ZoneOffset.UTC));

        RecommendationResult result = recommender.recommend(new RecommendationRequest(1024L, 7L,
                PaymentTerms.lumpSum(10_000), CAFE, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)));

        assertThat(result.candidates()).extracting(c -> c.card().userCardId()).containsExactly(10L, 20L, 30L);
        // 1.0은 값이 같은 1로 표기만 정리된다
        assertThat(result.candidates()).extracting(Candidate::netBenefit)
                .containsExactly(new BigDecimal("1.5"), new BigDecimal("1"), new BigDecimal("0.5"));
        // 0.5원도 0원이 아니므로 혜택 없음이 아니다(POL-011: 모두 0원일 때만)
        assertThat(result.noBenefit()).isFalse();
    }

    @Test
    void 음수_순혜택도_그대로_담는다() {
        // 추가 비용이 혜택보다 큰 카드: −1.5원
        RecommendationService service = mock(RecommendationService.class);
        when(service.recommend(any())).thenReturn(result(calculated(10L, "-1.5", 1)));
        DatabaseCardRecommender recommender = new DatabaseCardRecommender(service, Clock.fixed(NOW, ZoneOffset.UTC));

        RecommendationResult result = recommender.recommend(new RecommendationRequest(1024L, 7L,
                PaymentTerms.lumpSum(10_000), CAFE, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)));

        assertThat(result.topCandidate().orElseThrow().netBenefit()).isEqualTo(new BigDecimal("-1.5"));
    }

    private static RecommendationService.Result result(CardEvaluation... ranked) {
        List<CardEvaluation> cards = List.of(ranked);
        var selection = new RecommendationSelector.Result(1024L, RecommendationSelector.Status.READY,
                ranked[0].userCardId(), RecommendationSelector.SelectionReason.MAXIMUM_NET_BENEFIT, "추천",
                false, false, cards, cards);
        Map<Long, CardContext> contexts = new java.util.HashMap<>();
        for (CardEvaluation card : cards) {
            contexts.put(card.userCardId(), new CardContext(100L + card.userCardId(), "상품", null,
                    String.format("%04d", card.userCardId()), CardType.CREDIT, "TEST", false,
                    Map.of(card.userCardId() * 10, "POINT_ACCRUAL")));
        }
        return new RecommendationService.Result(selection, List.of(), contexts);
    }

    private static CardEvaluation calculated(long userCardId, String netBenefit, long points) {
        BigDecimal value = BigDecimal.valueOf(points);
        BenefitValue benefit = new BenefitValue(userCardId * 10, 1, "포인트 적립", BenefitType.POINT, value, value,
                Unit.POINT, value.multiply(new BigDecimal("0.5")), "APPLIED");
        return new CardEvaluation(userCardId, CardStatus.CALCULATED, null,
                new Calculation(true, new BigDecimal(netBenefit), List.of(benefit), List.of(), List.of()));
    }
}
