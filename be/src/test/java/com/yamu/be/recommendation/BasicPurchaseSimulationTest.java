package com.yamu.be.recommendation;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BasicPurchaseSimulationTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"TAXI", "CITY_BUS", "INTERCITY_BUS", "EXPRESS_BUS",
            "SUBWAY", "RAILWAY", "ELECTRIC_UTILITY", "WATER_UTILITY", "CITY_GAS_UTILITY", "UNIVERSITY",
            "PRIMARY_SECONDARY_SCHOOL", "KINDERGARTEN", "ACADEMY", "DRIVING_SCHOOL"})
    void excludedIndustriesNeverReceiveOrdinaryDefaults(String category) {
        for (Long merchant : new Long[]{null, 1L}) {
            var original = new RecommendationInput.Transaction(10_000, RecommendationInput.Plan.LUMP_SUM, 1,
                    Instant.parse("2026-11-12T03:00:00Z"), merchant, category,
                    Map.of("TRANSACTION_KIND:ELECTRICITY_BILL", true));
            assertThat(BasicPurchaseSimulation.apply(original, "OFFLINE", "GENERAL")).isSameAs(original);
            var empty = new RecommendationInput.Transaction(original.amount(), original.plan(), original.installmentMonths(),
                    original.occurredAt(), merchant, category, Map.of());
            assertThat(BasicPurchaseSimulation.apply(empty, "OFFLINE", "GENERAL")).isSameAs(empty);
        }
    }

    @Test void ordinaryPurchaseCompletesSpecialExclusionsButKeepsProductAndRouteFactsUnknown() {
        var result = BasicPurchaseSimulation.apply(tx(Map.of()), "OFFLINE", "GENERAL");
        assertThat(result.facts()).containsEntry("TRANSACTION_KIND:ELECTRICITY_BILL", false)
                .containsEntry("TRANSACTION_KIND:SOCIAL_INSURANCE", false)
                .containsEntry("TRANSACTION_KIND:EXPRESS_BUS", false)
                .containsEntry("TRANSACTION_KIND:PREPAID_PURCHASE", false)
                .containsEntry("TRANSACTION_KIND:CANCELED_TRANSACTION", false);
        assertThat(result.facts()).doesNotContainKeys("RULE:OVERSEAS_ELIGIBILITY", "CARD_FORM:GLOBAL",
                "RULE:OFFICIAL_PURCHASE_ROUTE", "RULE:EXCLUDE_REDEEMED_POINTS_FROM_BASE", "TRANSACTION_KIND:DELIVERY_APP");
        assertThat(result.simulationRules()).singleElement().satisfies(rule -> {
            assertThat(rule.version()).isEqualTo(2);
            assertThat(rule.appliedFields()).contains("TRANSACTION_KIND:ELECTRICITY_BILL");
        });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"TRANSACTION_KIND:ELECTRICITY_BILL",
            "TRANSACTION_KIND:PREPAID_PURCHASE", "TRANSACTION_KIND:TRANSIT_RF_PAYMENT",
            "TRANSACTION_KIND:UNKNOWN_SPECIAL", "BILLING:AUTO_DEBIT", "BILLING:RECURRING_MEMBERSHIP"})
    void explicitSpecialTransactionDoesNotReceiveOrdinaryDefaults(String fact) {
        var original = tx(Map.of(fact, true));
        var result = BasicPurchaseSimulation.apply(original, "OFFLINE", "GENERAL");
        assertThat(result.facts()).containsEntry(fact, true).doesNotContainKey("TRANSACTION_KIND:TAX_OR_PUBLIC_CHARGE");
        assertThat(original.facts()).containsExactlyEntriesOf(Map.of(fact, true));
        assertThat(result.simulationRules()).allSatisfy(rule ->
                assertThat(rule.appliedFields()).doesNotContain("TRANSACTION_KIND:TAX_OR_PUBLIC_CHARGE"));
    }

    @Test void selectedPurchaseItemDoesNotReceiveOrdinaryDefaults() {
        var base = tx(Map.of());
        var selected = new RecommendationInput.Transaction(base.amount(), base.plan(), base.installmentMonths(),
                base.occurredAt(), base.merchantId(), base.merchantCategoryCode(), base.facts(), "YOUTUBE_PREMIUM_SUBSCRIPTION");
        assertThat(BasicPurchaseSimulation.apply(selected, "ONLINE", "GENERAL").facts())
                .doesNotContainKey("TRANSACTION_KIND:ELECTRICITY_BILL");
    }

    @Test void repeatedAndConcurrentCallsKeepFactsAndTraceStableWithoutChangingInput() {
        var original = tx(Map.of("TRANSACTION_KIND:ELECTRICITY_BILL", false));
        var expected = BasicPurchaseSimulation.apply(original, "OFFLINE", "GENERAL");
        assertThat(expected.simulationRules().get(0).appliedFields()).doesNotContain("TRANSACTION_KIND:ELECTRICITY_BILL");
        assertThat(BasicPurchaseSimulation.apply(expected, "OFFLINE", "GENERAL")).isEqualTo(expected);
        java.util.stream.IntStream.range(0, 50).parallel().forEach(i ->
                assertThat(BasicPurchaseSimulation.apply(original, "OFFLINE", "GENERAL")).isEqualTo(expected));
        assertThat(original.facts()).containsExactlyEntriesOf(Map.of("TRANSACTION_KIND:ELECTRICITY_BILL", false));
    }

    RecommendationInput.Transaction tx(Map<String,Boolean> facts) {
        return new RecommendationInput.Transaction(10_000,RecommendationInput.Plan.LUMP_SUM,1,
                Instant.parse("2026-11-03T03:00:00Z"),1L,"CAFE",facts);
    }
    @Test void fillsOnlyApprovedGapsAndDoesNotInventChannelOfficialRouteOrPaymentMethod() {
        var result=BasicPurchaseSimulation.apply(tx(Map.of()),"UNKNOWN",null);
        assertThat(result.facts()).containsEntry("REGION:DOMESTIC",true).containsEntry("FACILITY:GENERAL",true)
                .containsEntry("RULE:AGENCY_SITE",false).containsEntry("BILLING:AUTO_DEBIT",false);
        assertThat(result.facts()).doesNotContainKeys("CHANNEL:ONLINE","CHANNEL:OFFLINE","RULE:OFFICIAL_MERCHANT_ROUTE",
                "RULE:OWN_MERCHANT_NUMBER","PAYMENT_METHOD:KB_PAY","PERFORMANCE:INTRODUCTORY_WAIVER_ACTIVE");
        assertThat(result.simulationRules()).singleElement().satisfies(r->assertThat(r.id()).isEqualTo("YAMU_BASIC_PURCHASE"));
    }
    @Test void verifiedForeignSpecialPurchaseAndAutopayAreNeverReplacedByOrdinaryDomesticFacts() {
        var actual=tx(Map.of("REGION:OVERSEAS",true,"TRANSACTION_KIND:GIFT_CERTIFICATE_PURCHASE",true,"BILLING:AUTO_DEBIT",true));
        var result=BasicPurchaseSimulation.apply(actual,"ONLINE","DEPARTMENT_STORE");
        assertThat(result.facts()).containsEntry("REGION:OVERSEAS",true).containsEntry("REGION:DOMESTIC",false)
                .containsEntry("TRANSACTION_KIND:GIFT_CERTIFICATE_PURCHASE",true).containsEntry("BILLING:AUTO_DEBIT",true)
                .containsEntry("FACILITY:DEPARTMENT_STORE",true).containsEntry("FACILITY:GENERAL",false);
        assertThatThrownBy(()->BasicPurchaseSimulation.apply(actual,"WEIRD",null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(result.occurredAt()).isEqualTo(actual.occurredAt());
    }
    @Test void installmentFactsComeFromPlanAndCannotBeOverridden() {
        var c=RecommendationFixtures.conditions();
        c=new BenefitPolicy.Conditions(c.verified(),c.currentVersionVerified(),c.validFrom(),c.validUntil(),c.targets(),
                java.util.Set.of("INSTALLMENT:LUMP_SUM"),java.util.Set.of("INSTALLMENT:INTEREST_FREE"),c.schedule());
        assertThat(new BenefitConditionEvaluator().evaluate(c,tx(Map.of("INSTALLMENT:LUMP_SUM",false,"INSTALLMENT:INTEREST_FREE",true))))
                .isEqualTo(BenefitConditionEvaluator.Match.MATCHED);
        assertThat(new BenefitConditionEvaluator().evaluate(c,new RecommendationInput.Transaction(50_000,RecommendationInput.Plan.INSTALLMENT,2,
                tx(Map.of()).occurredAt(),1L,"CAFE",Map.of("INSTALLMENT:LUMP_SUM",true))))
                .isEqualTo(BenefitConditionEvaluator.Match.NOT_MATCHED);
    }
}
