package com.yamu.be.recommendation;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BasicPurchaseSimulationTest {
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
