package com.yamu.be.payment.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.recommendation.RecommendationSelector;
import com.yamu.be.recommendation.CardBenefitCalculator;
import com.yamu.be.recommendation.RecommendationService;
import com.yamu.be.recommendation.RecommendationDataIssue;
import com.yamu.be.payment.recommend.DatabaseCardRecommender;
import com.yamu.be.payment.recommend.RecommendationRequest;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.Mockito;
import org.mockito.ArgumentMatchers;

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

    @Test void confirmedOnlyNoticeSurvivesSecondRankAndJson() {
        var result = new RecommendationResult("rec_partial", LocalDateTime.of(2026,11,17,3,0),false,
                List.of(candidate(1,3L,"880",SelectionReason.FIXED_ORDER),candidate(2,4L,"880",null)),
                List.of(),List.of(),1,List.of(new RecommendationDataIssue(4L,1L,RecommendationDataIssue.IBK_CONFIRMED_ONLY,false)));
        var view = PaymentRequestViewReader.recommendation(result,PaymentTerms.lumpSum(8800));
        assertThat(view.status()).isEqualTo(RecommendationResult.Status.PARTIAL_READY);
        assertThat(view.candidates().get(0).benefitNotice()).isNull();
        assertThat(view.candidates().get(1).benefitNotice()).isEqualTo(RecommendationDataIssue.IBK_CONFIRMED_NOTICE);
        assertThat(new ObjectMapper().findAndRegisterModules().valueToTree(view)
                .path("candidates").get(1).path("benefitNotice").asText()).isEqualTo(RecommendationDataIssue.IBK_CONFIRMED_NOTICE);
    }

    @Test
    void 엔진의_미비교_사유와_재시도_안내가_결제_요청_JSON까지_전달된다() throws Exception {
        var unavailable = new RecommendationSelector.CardEvaluation(10L,
                RecommendationSelector.CardStatus.NOT_COMPARED, "PAYMENT_AVAILABILITY_UNKNOWN", null);
        var incomplete = new CardBenefitCalculator.Calculation(false, null,
                List.of(), List.of(), List.of(new CardBenefitCalculator.Problem(1L, "CONDITION_UNKNOWN")));
        var benefitUnknown = new RecommendationSelector.CardEvaluation(20L,
                RecommendationSelector.CardStatus.NOT_COMPARED, "BENEFIT_DATA_INSUFFICIENT", incomplete);
        var retry = new RecommendationSelector.CardEvaluation(30L,
                RecommendationSelector.CardStatus.NOT_COMPARED, "PAYMENT_AVAILABILITY_UNKNOWN", null);
        var selection = new RecommendationSelector.Result(1024L,
                RecommendationSelector.Status.DATA_INSUFFICIENT, null, null,
                "추천에 필요한 정보를 확인하지 못했습니다.", false, false, List.of(), List.of(unavailable, benefitUnknown, retry));
        var contexts = new HashMap<Long, RecommendationService.CardContext>();
        for (long id : List.of(10L, 20L, 30L)) {
            contexts.put(id, new RecommendationService.CardContext(100L + id,
                    "상품", null, "1234", CardType.CREDIT, "TEST", false, Map.of()));
        }
        var service = Mockito.mock(RecommendationService.class);
        Mockito.when(service.recommend(ArgumentMatchers.any())).thenReturn(
                new RecommendationService.Result(selection,
                        List.of(new RecommendationDataIssue(30L, null, "BALANCE_READ_RETRYABLE", true)), contexts));
        var now = Instant.parse("2026-11-04T03:00:00Z");
        var adapter = new DatabaseCardRecommender(service,
                Clock.fixed(now, ZoneOffset.UTC));
        var result = adapter.recommend(new RecommendationRequest(1024L, 7L,
                PaymentTerms.lumpSum(10000), new RecommendationRequest.MerchantContext(
                        301L, "COFFEE_SHOP", "OFFLINE", "GENERAL"), LocalDateTime.ofInstant(now, ZoneOffset.UTC)));
        var mapper = new ObjectMapper().findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        var codec = new com.yamu.be.payment.recommend.RecommendationResultJson(mapper);
        var restored = codec.decode(codec.encode(result));
        var view = PaymentRequestViewReader.recommendation(restored, PaymentTerms.lumpSum(10000));
        assertThat(view.status()).isEqualTo(RecommendationResult.Status.DATA_INSUFFICIENT);
        assertThat(view.candidates()).isEmpty();
        assertThat(view.uncomparedCards()).extracting(PaymentRequestView.UncomparedCard::userCardId)
                .containsExactly(10L, 20L, 30L);
        assertThat(view.uncomparedCards()).extracting(PaymentRequestView.UncomparedCard::reason)
                .containsExactly(RecommendationResult.UncomparedReason.PAYMENT_AVAILABILITY_UNKNOWN,
                        RecommendationResult.UncomparedReason.BENEFIT_DATA_INSUFFICIENT,
                        RecommendationResult.UncomparedReason.PAYMENT_AVAILABILITY_UNKNOWN);
        assertThat(view.uncomparedCards()).extracting(PaymentRequestView.UncomparedCard::directPaymentAllowed)
                .containsExactly(false, true, false);
        var json = mapper.valueToTree(view).path("uncomparedCards");
        assertThat(json.get(0).path("message").asText()).isEqualTo("결제 가능 여부를 확인하지 못해 비교에서 제외했어요.");
        assertThat(json.get(1).path("message").asText()).isEqualTo("혜택 정보를 확인하지 못해 비교에서 제외했어요.");
        assertThat(json.get(2).path("message").asText()).isEqualTo(
                RecommendationDataIssue.RETRY_MESSAGE);
    }

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
