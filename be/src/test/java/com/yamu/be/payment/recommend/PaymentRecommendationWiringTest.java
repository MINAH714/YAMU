package com.yamu.be.payment.recommend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.yamu.be.payment.lookup.PaymentCardLookup;
import com.yamu.be.recommendation.RecommendationService;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PaymentRecommendationWiringTest {
    @Test void realPortIsRegisteredAndConditionalMockDoesNotCoexist() {
        new ApplicationContextRunner()
                .withUserConfiguration(DatabaseCardRecommender.class,PaymentRecommendConfig.class)
                .withBean(RecommendationService.class,()->mock(RecommendationService.class))
                .withBean(PaymentCardLookup.class,()->mock(PaymentCardLookup.class))
                .withBean(Clock.class,Clock::systemUTC)
                .run(context->{
                    assertThat(context).hasSingleBean(CardRecommender.class);
                    assertThat(context.getBean(CardRecommender.class)).isInstanceOf(DatabaseCardRecommender.class);
                });
    }
}
