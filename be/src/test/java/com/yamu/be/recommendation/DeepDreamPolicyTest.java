package com.yamu.be.recommendation;

import static org.assertj.core.api.Assertions.*;
import static com.yamu.be.recommendation.DeepDreamPolicySupport.*;
import static com.yamu.be.recommendation.DeepDreamTestFixtures.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class DeepDreamPolicyTest {
    private final CardBenefitCalculator calculator=new CardBenefitCalculator();
    @Test void verifiedPreviousPerformanceSelectsCorrectTiersAndBoundaryTiers() {
        assertThat(terms().get(Kind.CREDIT).cap(2_000_000)).isEqualTo(50_000);
        assertThat(terms().get(Kind.CHECK).cap(250_000)).isEqualTo(5_000);
        assertThat(terms().get(Kind.CREDIT).cap(299_999)).isZero();
        assertThat(terms().get(Kind.CREDIT).cap(300_000)).isEqualTo(10_000);
        assertThat(terms().get(Kind.CREDIT).cap(600_000)).isEqualTo(30_000);
        assertThat(terms().get(Kind.CHECK).cap(199_999)).isZero();
        assertThat(terms().get(Kind.CHECK).cap(400_000)).isEqualTo(15_000);
        assertThat(terms().get(Kind.CHECK).cap(800_000)).isEqualTo(30_000);
    }
    @Test void independentArithmeticUsesIncrementalRatesWithoutDeferredDoubleCounting() {
        var p=purchase("T1",1,10_000,Area.RETAIL_STORE,Kind.CHECK);var state=new DeepDreamReplayState(MONTH,opening(),terms());
        for(var kind:Kind.values()) {
            var policy=policy(terms().get(kind),rules(),p,state.snapshot(kind));
            var result=calculator.calculate(transaction(p),policy);
            assertThat(result.complete()).isTrue();
            assertThat(result.netBenefit()).isEqualByComparingTo(kind==Kind.CREDIT?"210":"60");
            assertThat(policy.benefits()).noneMatch(b->b.name().contains("DEFERRED"));
            assertThat(result.limitUses().get(0).consumed()).isEqualByComparingTo(kind==Kind.CREDIT?"140":"40");
        }
    }
    @Test void partialAndExhaustedExtraCapDoNotRemoveUncappedBase() {
        var p=purchase("T1",1,10_000,Area.RETAIL_STORE,Kind.CREDIT);
        for(long used:List.of(49_999L,50_000L)) {
            var openings=new EnumMap<Kind,DeepDreamReplayState.Opening>(Kind.class);openings.putAll(opening());
            openings.put(Kind.CREDIT,new DeepDreamReplayState.Opening(2_000_000L,0L,1_000_000L,used,true,zeroAreas(),zeroAreas()));
            var state=new DeepDreamReplayState(MONTH,openings,terms());
            var result=calculator.calculate(transaction(p),policy(terms().get(Kind.CREDIT),rules(),p,state.snapshot(Kind.CREDIT)));
            assertThat(result.netBenefit()).isEqualByComparingTo(used==49_999?"71":"70");
        }
    }
    @Test void missingFactsAreNotKnownZeroAndRealPoliciesStayUnresolved() {
        var p=purchase("T1",1,10_000,Area.NONE,Kind.CREDIT);var state=new DeepDreamReplayState(MONTH,opening(),terms());
        var unknown=withFacts(p,Kind.CREDIT,new Facts(null,null,null,null,null,null,null,null,null,null,null,null));
        assertThatThrownBy(()->policy(terms().get(Kind.CREDIT),rules(),unknown,state.snapshot(Kind.CREDIT))).isInstanceOf(Unresolved.class).hasMessageContaining("POINT_ELIGIBILITY_UNKNOWN");
        assertThat(researchRules().immediateIssues()).contains("COMPONENT_ROUNDING_UNCONFIRMED","SHARED_CAP_SETTLEMENT_ORDER_UNCONFIRMED");
        assertThatThrownBy(()->policy(terms().get(Kind.CREDIT),researchRules(),p,state.snapshot(Kind.CREDIT))).isInstanceOf(Unresolved.class);
        var zero=withFacts(p,Kind.CREDIT,new Facts(false,Area.NONE,false,true,10_000L,10_000L,MONTH,10_000L,BigDecimal.ZERO,0L,0L,"CONFIRMED_SYNTHETIC_EXCLUSION"));
        var result=calculator.calculate(transaction(zero),policy(terms().get(Kind.CREDIT),rules(),zero,state.snapshot(Kind.CREDIT)));
        assertThat(result.complete()).isTrue();assertThat(result.netBenefit()).isEqualByComparingTo("0");
    }
    @Test void feesAndConditionalBonusAreExplicitAndSpecialBenefitsCannotSilentlyFallBack() {
        var p=purchase("T1",1,10_000,Area.NONE,Kind.CREDIT);var state=new DeepDreamReplayState(MONTH,opening(),terms());
        var bonus=withFacts(p,Kind.CREDIT,new Facts(true,Area.NONE,true,true,10_000L,10_000L,MONTH,10_020L,new BigDecimal("20"),0L,0L,"SYNTHETIC_BONUS_AND_COST"));
        var result=calculator.calculate(transaction(bonus),policy(terms().get(Kind.CREDIT),rules(),bonus,state.snapshot(Kind.CREDIT)));
        assertThat(result.netBenefit()).isEqualByComparingTo("60"); // 70+10-20
        var special=withFacts(p,Kind.CREDIT,new Facts(true,Area.NONE,false,false,10_000L,10_000L,MONTH,10_000L,BigDecimal.ZERO,0L,0L,"SPECIAL_APPLIES"));
        assertThatThrownBy(()->policy(terms().get(Kind.CREDIT),rules(),special,state.snapshot(Kind.CREDIT))).isInstanceOf(Unresolved.class).hasMessageContaining("SPECIAL_BENEFIT");
    }
    @Test void fabricatedPerComponentRoundingIsTestedButNotPromotedToIssuerPolicy() {
        var p=purchase("T1",1,199,Area.RETAIL_STORE,Kind.CREDIT);var s=new DeepDreamReplayState(MONTH,opening(),terms());
        assertThat(calculator.calculate(transaction(p),policy(terms().get(Kind.CREDIT),rules(),p,s.snapshot(Kind.CREDIT))).netBenefit()).isEqualByComparingTo("3"); // floor(1.393)+floor(2.786)
        assertThat(resource("deep-dream-policy.json").get("componentRoundingStage").isNull()).isTrue();
    }
    @Test void belowThresholdKeepsBaseAndContradictoryOrMissingCostFactsAreRejected() {
        var p=purchase("T1",1,10_000,Area.RETAIL_STORE,Kind.CREDIT);
        var openings=new EnumMap<Kind,DeepDreamReplayState.Opening>(Kind.class);openings.putAll(opening());
        openings.put(Kind.CREDIT,new DeepDreamReplayState.Opening(299_999L,0L,1_000_000L,0L,true,zeroAreas(),zeroAreas()));
        var s=new DeepDreamReplayState(MONTH,openings,terms());
        assertThat(calculator.calculate(transaction(p),policy(terms().get(Kind.CREDIT),rules(),p,s.snapshot(Kind.CREDIT))).netBenefit()).isEqualByComparingTo("70");
        var missingCost=withFacts(p,Kind.CREDIT,new Facts(true,Area.RETAIL_STORE,false,true,10_000L,10_000L,MONTH,10_000L,null,10_000L,10_000L,"SYNTHETIC_MISSING_COST"));
        assertThatThrownBy(()->policy(terms().get(Kind.CREDIT),rules(),missingCost,s.snapshot(Kind.CREDIT))).isInstanceOf(Unresolved.class).hasMessageContaining("COST_UNCONFIRMED");
        var contradiction=new Facts(false,Area.RETAIL_STORE,false,true,10_000L,10_000L,MONTH,10_000L,BigDecimal.ZERO,10_000L,10_000L,"SYNTHETIC_CONTRADICTION");
        assertThat(contradiction.issues(Kind.CREDIT,10_000)).contains("EXCLUDED_PURCHASE_HAS_DEFERRED_BASE");
    }
}
