package com.yamu.be.payment.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yamu.be.card.RecommendationPerformanceAssembler.Recognition;
import com.yamu.be.payment.PaymentTerms;
import com.yamu.be.payment.recommend.RecommendationRequest.MerchantContext;
import com.yamu.be.payment.recommend.RecommendationResult.PerformanceRecognition;
import com.yamu.be.recommendation.RecommendationCommand;
import com.yamu.be.recommendation.RecommendationSelector;
import com.yamu.be.recommendation.RecommendationService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** BE3가 계산한 카드별 실적 인정 값을 결제 쪽 결과로 그대로 옮기고, POS 거래 사실을 계산 입력에 싣는다(Task 5.2.11). */
class DatabaseCardRecommenderRecognitionTest {

    static final Instant NOW = Instant.parse("2026-11-06T03:00:00Z");
    static final MerchantContext CAFE = new MerchantContext(301L, "COFFEE_SHOP", "OFFLINE", "GENERAL");

    @Test
    void 카드별_인정월과_인정액을_그대로_옮기고_최소_실적만은_옮기지_않는다() {
        RecommendationService service = mock(RecommendationService.class);
        when(service.recommend(any())).thenReturn(result(Map.of(
                10L, new Recognition(10L, YearMonth.of(2026, 11), 300_000L, 10_000L),
                20L, new Recognition(20L, null, 0L, null),
                30L, new Recognition(30L, YearMonth.of(2026, 11), 300_000L, 0L))));
        DatabaseCardRecommender recommender = new DatabaseCardRecommender(service, Clock.fixed(NOW, ZoneOffset.UTC));

        RecommendationResult result = recommender.recommend(RecommendationRequest.posPurchase(1024L, 7L,
                PaymentTerms.lumpSum(10_000), CAFE, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)));

        assertThat(result.recognition(10L)).contains(new PerformanceRecognition(10L, YearMonth.of(2026, 11), 10_000L));
        assertThat(result.recognition(20L)).contains(new PerformanceRecognition(20L, null, null));
        assertThat(result.recognition(30L)).contains(new PerformanceRecognition(30L, YearMonth.of(2026, 11), 0L));
        assertThat(result.recognition(40L)).isEmpty();
    }

    @Test
    void POS_구매_요청은_결제가_확인한_거래_사실을_계산_입력에_싣는다() {
        RecommendationService service = mock(RecommendationService.class);
        when(service.recommend(any())).thenReturn(result(Map.of()));
        DatabaseCardRecommender recommender = new DatabaseCardRecommender(service, Clock.fixed(NOW, ZoneOffset.UTC));

        recommender.recommend(RecommendationRequest.posPurchase(1024L, 7L, PaymentTerms.lumpSum(10_000), CAFE,
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)));

        ArgumentCaptor<RecommendationCommand> command = ArgumentCaptor.forClass(RecommendationCommand.class);
        verify(service).recommend(command.capture());
        assertThat(command.getValue().transaction().facts()).containsAllEntriesOf(PosPurchaseFacts.verified());
    }

    private static RecommendationService.Result result(Map<Long, Recognition> recognitions) {
        var selection = new RecommendationSelector.Result(1024L, RecommendationSelector.Status.NO_ELIGIBLE_CARD, null, null,
                "결제할 수 있는 카드가 없어요", false, false, List.of(), List.of());
        return new RecommendationService.Result(selection, List.of(), Map.of(), List.of(), recognitions);
    }
}
