package com.yamu.be.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import com.yamu.be.payment.recommend.RecommendationResult;
import com.yamu.be.support.OwnershipFixtures;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CheoeumStorePurchaseDatabaseTest extends SimulationSeedRecommendationDatabaseTest {
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    RecommendationInputAssembler assembler;

    @ParameterizedTest
    @CsvSource({"0,0", "299999,0", "300000,500", "1000000,500"})
    void coupangOfficialApp21600(long previous, long expected) {
        long user = new OwnershipFixtures(jdbc).user();
        long card = card(user, product("신한카드 처음"), previous, 0);
        var result = port.recommend(request(user, List.of(card), "COUPANG", 21600, null, Map.of()));
        assertThat(result.status()).isEqualTo(RecommendationResult.Status.READY);
        assertThat(result.topCandidate().orElseThrow().netBenefit()).isEqualByComparingTo(Long.toString(expected));
        assertThat(result.simulationRules()).anyMatch(r -> r.id().equals("YAMU_CHEOEUM_COUPANG_PURCHASE"));
    }

    @ParameterizedTest
    @CsvSource({"현대카드 ZERO Edition3 (할인형),80", "현대카드ZERO Edition3(포인트형),120"})
    void zeroDomesticAndOverseasCardForm(String productName, long expected) {
        long user = new OwnershipFixtures(jdbc).user();
        long card = card(user, product(productName), 0, 0);
        for (var facts : List.of(Map.<String, Boolean>of(), Map.of("CARD_FORM:GLOBAL", false),
                Map.of("REGION:OVERSEAS", true, "CARD_FORM:GLOBAL", true))) {
            var result = port.recommend(request(user, List.of(card), "GS25", 10000, null, facts));
            assertThat(result.status()).isEqualTo(RecommendationResult.Status.READY);
            assertThat(result.topCandidate().orElseThrow().netBenefit()).isEqualByComparingTo(Long.toString(expected));
        }
        var unknown = port.recommend(request(user, List.of(card), "GS25", 10000, null, Map.of("REGION:OVERSEAS", true)));
        assertThat(unknown.status()).isEqualTo(RecommendationResult.Status.DATA_INSUFFICIENT);
        var ineligible = port.recommend(request(user, List.of(card), "GS25", 10000, null,
                Map.of("REGION:OVERSEAS", true, "CARD_FORM:GLOBAL", false)));
        assertThat(ineligible.topCandidate().orElseThrow().netBenefit()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"0,0", "299999,0", "300000,500", "400000,500"})
    void starbucks21600UsesApprovedStorePolicy(long previous, long expected) {
        long user = new OwnershipFixtures(jdbc).user();
        long card = card(user, product("신한카드 처음"), previous, 0);
        var originalPolicy = new java.util.concurrent.atomic.AtomicReference<BenefitPolicy>();
        org.mockito.Mockito.doAnswer(invocation -> {
            var policy = (BenefitPolicy) invocation.callRealMethod();
            originalPolicy.set(policy);
            return policy;
        }).when(assembler).policy(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.nullable(Long.class),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("STARBUCKS"));
        var result = port.recommend(request(user, List.of(card), "STARBUCKS", 21600, null, Map.of()));
        var cafe = originalPolicy.get().benefits().stream().filter(b -> b.name().contains("카페")).findFirst().orElseThrow();
        assertThat(cafe.conditions().requiredFacts()).contains("RULE:CHEOEUM_CAFE_ROUTE", "RULE:EXCLUDE_REDEEMED_POINTS_FROM_BASE");
        assertThat(cafe.conditions().excludedFacts()).contains("TRANSACTION_KIND:DELIVERY_APP", "TRANSACTION_KIND:GIFT_CERTIFICATE_PURCHASE");
        assertThat(originalPolicy.get().simulationRules()).noneMatch(r -> r.id().equals("YAMU_CHEOEUM_STORE_PURCHASE"));
        assertThat(result.status()).isEqualTo(RecommendationResult.Status.READY);
        assertThat(result.topCandidate().orElseThrow().netBenefit()).isEqualByComparingTo(Long.toString(expected));
        assertThat(result.uncomparedCards()).isEmpty();
        assertThat(result.simulationRules()).anyMatch(r -> r.id().equals("YAMU_CHEOEUM_STORE_PURCHASE")
                && r.appliedFields().contains("RULE:CHEOEUM_CAFE_ROUTE=true")
                && r.appliedFields().contains("RULE:EXCLUDE_REDEEMED_POINTS_FROM_BASE=true")
                && r.appliedFields().contains("TRANSACTION_KIND:DELIVERY_APP=false"));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"RULE:CHEOEUM_CAFE_ROUTE", "RULE:EXCLUDE_REDEEMED_POINTS_FROM_BASE"})
    void explicitUnmetConditionStillProducesZero(String condition) {
        long user = new OwnershipFixtures(jdbc).user();
        long card = card(user, product("신한카드 처음"), 400000, 0);
        var result = port.recommend(request(user, List.of(card), "STARBUCKS", 21600, null, Map.of(condition, false)));
        assertThat(result.status()).isEqualTo(RecommendationResult.Status.READY);
        assertThat(result.topCandidate().orElseThrow().netBenefit()).isZero();
    }
}
