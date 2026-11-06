package com.yamu.be.recommendation;

import static com.yamu.be.recommendation.RecommendationFixtures.transaction;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecommendationPaymentIdContractTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test void primitivePaymentAndCardIdsConstructAndRoundTripWithoutOverloadAmbiguity() throws Exception {
        long paymentId = 1024L;
        long cardId = 7L;
        var input = new RecommendationInput(paymentId, 1L, transaction(), cardId, List.of());
        var result = new RecommendationSelector.Result(paymentId, RecommendationSelector.Status.READY,
                cardId, RecommendationSelector.SelectionReason.MAXIMUM_NET_BENEFIT,
                "추천", false, false, List.of(), List.of());
        assertThat(json.readValue(json.writeValueAsString(input), RecommendationInput.class)).isEqualTo(input);
        assertThat(json.readValue(json.writeValueAsString(result), RecommendationSelector.Result.class)).isEqualTo(result);
        assertThat(json.valueToTree(input).get("paymentId").longValue()).isEqualTo(paymentId);
        assertThat(json.valueToTree(result).get("paymentId").longValue()).isEqualTo(paymentId);
    }

    @Test void nullSimulationIdRoundTripsAndActualPaymentEntryStillRequiresId() throws Exception {
        var input = new RecommendationInput(null, 1L, transaction(), null, List.of());
        var selector = new RecommendationSelector();
        var result = selector.select(input, CalculationOptions.DEFAULT);
        assertThat(result.paymentId()).isNull();
        assertThat(json.valueToTree(input).get("paymentId").isNull()).isTrue();
        assertThat(json.valueToTree(result).get("paymentId").isNull()).isTrue();
        assertThat(json.readValue(json.writeValueAsString(input), RecommendationInput.class)).isEqualTo(input);
        assertThat(json.readValue(json.writeValueAsString(result), RecommendationSelector.Result.class)).isEqualTo(result);
        assertThatNullPointerException().isThrownBy(() -> selector.select(input));
        assertThat(selector.select(new RecommendationInput(1024L, 1L, transaction(), null, List.of())).paymentId())
                .isEqualTo(1024L);
        for (long invalid : new long[]{0L, -1L}) {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    new RecommendationInput(invalid, 1L, transaction(), null, List.of()));
        }
    }
}
