package com.yamu.be.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class CheoeumPurchaseSimulationTest {
    private final com.yamu.be.card.domain.CardProduct product = mock(com.yamu.be.card.domain.CardProduct.class);
    private final BenefitPolicy policy = mock(BenefitPolicy.class);

    @org.junit.jupiter.api.BeforeEach void scopedProduct() {
        when(product.getIssuerCode()).thenReturn("SHINHAN");
        when(product.getProductName()).thenReturn("신한카드 처음");
        when(policy.complete()).thenReturn(true);
        when(policy.simulationRules()).thenReturn(List.of(new SimulationRule("YAMU_SIM_CARD_POLICY", 1, "test", 1L, null, List.of())));
    }
    @ParameterizedTest
    @ValueSource(strings = {"CHANNEL:ONLINE", "TRANSACTION_KIND:DELIVERY_APP", "TRANSACTION_KIND:GIFT_CERTIFICATE_PURCHASE",
            "TRANSACTION_KIND:POINT_REDEMPTION", "BILLING:AUTO_DEBIT", "RULE:AGENCY_SITE"})
    void explicitNonOrdinaryFactsDisableAssumptions(String key) {
        var tx = transaction(Map.of(key, true));
        assertThat(CheoeumPurchaseSimulation.apply(tx, 1, product, policy)).isSameAs(tx);
    }

    @Test void explicitUnmetPointConditionAndUnknownOfficialRouteArePreserved() {
        var tx = transaction(Map.of("RULE:EXCLUDE_REDEEMED_POINTS_FROM_BASE", false));
        var result = CheoeumPurchaseSimulation.apply(tx, 1, product, policy);
        assertThat(result.facts()).containsEntry("RULE:EXCLUDE_REDEEMED_POINTS_FROM_BASE", false)
                .doesNotContainKey("RULE:OFFICIAL_PURCHASE_ROUTE");
        assertThat(tx.facts()).containsExactlyEntriesOf(Map.of("RULE:EXCLUDE_REDEEMED_POINTS_FROM_BASE", false));
    }

    @Test void onlyVerifiedCoupangOnlineGetsOfficialRouteAndExplicitFactsWin() {
        var tx = transaction(Map.of("CHANNEL:ONLINE", true, "CHANNEL:OFFLINE", false));
        assertThat(CheoeumPurchaseSimulation.apply(tx, 1, product, policy, "KURLY")).isSameAs(tx);
        var result = CheoeumPurchaseSimulation.apply(tx, 1, product, policy, "COUPANG");
        assertThat(result.facts()).containsEntry("RULE:OFFICIAL_PURCHASE_ROUTE", true)
                .containsEntry("RULE:EXCLUDE_REDEEMED_POINTS_FROM_BASE", true)
                .containsEntry("TRANSACTION_KIND:DELIVERY_APP", false)
                .doesNotContainKey("RULE:CHEOEUM_CAFE_ROUTE");
        assertThat(CheoeumPurchaseSimulation.apply(result, 1, product, policy, "COUPANG")).isSameAs(result);
        var explicit = transaction(Map.of("CHANNEL:ONLINE", true, "RULE:OFFICIAL_PURCHASE_ROUTE", false,
                "RULE:EXCLUDE_REDEEMED_POINTS_FROM_BASE", false));
        assertThat(CheoeumPurchaseSimulation.apply(explicit, 1, product, policy, "COUPANG").facts())
                .containsEntry("RULE:OFFICIAL_PURCHASE_ROUTE", false)
                .containsEntry("RULE:EXCLUDE_REDEEMED_POINTS_FROM_BASE", false);
        var delivery = transaction(Map.of("CHANNEL:ONLINE", true, "TRANSACTION_KIND:DELIVERY_APP", true));
        assertThat(CheoeumPurchaseSimulation.apply(delivery, 1, product, policy, "COUPANG")).isSameAs(delivery);
    }

    @Test void supplementsImmutableCardSpecificTransactionWithoutLeakingOrDuplicatingTrace() {
        var tx = transaction(Map.of());
        var expected = CheoeumPurchaseSimulation.apply(tx, 1, product, policy);
        assertThat(expected.facts()).containsEntry("CHANNEL:OFFLINE", true)
                .containsEntry("RULE:CHEOEUM_CAFE_ROUTE", true)
                .containsEntry("RULE:EXCLUDE_REDEEMED_POINTS_FROM_BASE", true)
                .containsEntry("TRANSACTION_KIND:DELIVERY_APP", false)
                .containsEntry("TRANSACTION_KIND:GIFT_CERTIFICATE_PURCHASE", false);
        assertThat(expected.amount()).isEqualTo(21600);
        assertThat(expected.occurredAt()).isEqualTo(tx.occurredAt());
        assertThat(CheoeumPurchaseSimulation.apply(expected, 1, product, policy)).isSameAs(expected);
        java.util.stream.IntStream.range(0, 20).parallel().forEach(i ->
                assertThat(CheoeumPurchaseSimulation.apply(tx, 1, product, policy)).isEqualTo(expected));
        when(product.getProductName()).thenReturn("신한카드 Mr.Life");
        assertThat(CheoeumPurchaseSimulation.apply(tx, 2, product, policy)).isSameAs(tx);
        assertThat(tx.facts()).isEmpty();
        assertThat(tx.simulationRules()).hasSize(1);
    }

    @Test void unapprovedPolicyAndSelectedPurchaseItemKeepOriginalFacts() {
        var tx = transaction(Map.of());
        when(policy.complete()).thenReturn(false);
        assertThat(CheoeumPurchaseSimulation.apply(tx, 1, product, policy)).isSameAs(tx);
        when(policy.complete()).thenReturn(true);
        var selected = new RecommendationInput.Transaction(tx.amount(), tx.plan(), tx.installmentMonths(), tx.occurredAt(),
                tx.merchantId(), tx.merchantCategoryCode(), tx.facts(), "SOME_ITEM", tx.simulationRules());
        assertThat(CheoeumPurchaseSimulation.apply(selected, 1, product, policy)).isSameAs(selected);
    }

    private RecommendationInput.Transaction transaction(Map<String, Boolean> facts) {
        return new RecommendationInput.Transaction(21600, RecommendationInput.Plan.LUMP_SUM, 1,
                Instant.parse("2026-11-12T03:00:00Z"), 1L, "COFFEE_SHOP", facts, null,
                List.of(new SimulationRule("YAMU_BASIC_PURCHASE", 2, "test", null, null, List.of())));
    }
}
