package com.yamu.be.payment.recommend;

import com.yamu.be.payment.lookup.PaymentCardLookup;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentRecommendConfig {

    /**
     * BE3 추천 엔진이 {@link CardRecommender} 빈으로 등록되면 이 Mock은 만들지 않는다.
     * 컴포넌트 스캔이 빈 정의를 먼저 등록하고 @Bean 메서드의 조건은 그 뒤에 평가되므로 BE3 쪽은 @Component면 된다.
     * 그래도 둘 다 뜨면 BE3 구현에 @Primary를 붙이고 이 메서드를 지운다.
     */
    @Bean
    @ConditionalOnMissingBean(CardRecommender.class)
    CardRecommender mockCardRecommender(PaymentCardLookup cards, Clock clock) {
        return new MockCardRecommender(cards, clock);
    }
}
