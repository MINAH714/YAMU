package com.yamu.be.recommendation;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.yamu.be.recommendation.DeepDreamPolicySupport.*;
import static com.yamu.be.recommendation.DeepDreamTestFixtures.*;
import static com.yamu.be.recommendation.DeepDreamSensitivitySupport.*;

/** All trades below are fabricated; no statement import, environment access, DB or real-case run. */
class DeepDreamSensitivityTest {
    private Purchase target() { return purchase("SYNTHETIC_TARGET",5,10_280,Area.ABROAD,Kind.CHECK); }
    private Map<Kind,AreaAmounts> areas() {
        return Map.of(Kind.CREDIT,new AreaAmounts(10_280,10_280,"SYNTHETIC_SEPARATE_AREA_RULE"),
                Kind.CHECK,new AreaAmounts(10_280,10_280,"SYNTHETIC_SEPARATE_AREA_RULE"));
    }
    private Comparison evaluate(List<Purchase> input,Scenario scenario,Map<Kind,DeepDreamReplayState.Opening> initial) {
        return compare(Origin.SYNTHETIC_TEST_ONLY,input,"SYNTHETIC_TARGET",scenario,areas(),rules(),MONTH,initial,Kind.CREDIT);
    }
    @Test void overlaysOnlyEligibleBaseAndNeverMutatesSourceOrIndependentAmounts() {
        var p=target(); var original=p.facts().get(Kind.CHECK);
        var b=overlay(List.of(p),p.id(),Scenario.B,areas()).get(0);
        assertThat(p.facts().get(Kind.CHECK)).isEqualTo(original);
        assertThat(b.amount()).isEqualTo(10_280);
        for(var kind:Kind.values()) {
            var f=b.facts().get(kind);
            assertThat(f.benefitBaseAmount()).isEqualTo(10_040);
            assertThat(f.debitAmount()).isEqualTo(10_280);
            assertThat(f.recognizedAmount()).isEqualTo(10_280);
            assertThat(f.areaSelectionAmount()).isEqualTo(10_280);
            assertThat(f.deferredEligibleAmount()).isEqualTo(10_280);
        }
        assertThatThrownBy(()->overlay(List.of(p),p.id(),Scenario.B,Map.of())).isInstanceOf(Unresolved.class);
    }
    @Test void independentArithmeticAndPointDeltasAreNotARealCaseResult() {
        var input=List.of(target()); var a=evaluate(input,Scenario.A,opening()); var b=evaluate(input,Scenario.B,opening());
        // Synthetic per-component floor: credit A=71+143, B=70+140; check A=20+41, B=20+40.
        assertThat(a.recorded().knownNetBenefit()).isEqualByComparingTo("61");
        assertThat(a.yamu().knownNetBenefit()).isEqualByComparingTo("214");
        assertThat(b.recorded().knownNetBenefit()).isEqualByComparingTo("60");
        assertThat(b.yamu().knownNetBenefit()).isEqualByComparingTo("210");
        var pair=pair(a,b);
        assertThat(pair.recordedBMinusA()).isEqualByComparingTo("-1");
        assertThat(pair.yamuBMinusA()).isEqualByComparingTo("-4");
        assertThat(pair.advantageBMinusA()).isEqualByComparingTo("-3");
        assertThat(pair.yamuSelectionChangedIds()).isEmpty();
        assertThat(a.yamu().deferredStatus()).isEqualTo("UNRESOLVED_MONTH_NOT_CLOSED");
        assertThat(evaluate(input,Scenario.A,opening())).isEqualTo(a);
    }
    @Test void partialCapDifferencePropagatesToLaterTradeAndCanCancelInTotal() {
        var initial=new EnumMap<Kind,DeepDreamReplayState.Opening>(Kind.class);initial.putAll(opening());
        initial.put(Kind.CHECK,new DeepDreamReplayState.Opening(250_000L,0L,0L,4900L,true,zeroAreas(),zeroAreas()));
        var input=List.of(target(),purchase("LATER",6,20_000,Area.ABROAD,Kind.CHECK));
        var a=evaluate(input,Scenario.A,initial);var b=evaluate(input,Scenario.B,initial);
        assertThat(a.recorded().trades().get(0).after().get(Kind.CHECK).usedExtra()).isEqualTo(4941);
        assertThat(b.recorded().trades().get(0).after().get(Kind.CHECK).usedExtra()).isEqualTo(4940);
        assertThat(a.recorded().trades().get(1).selectedCalculation().netBenefit()).isEqualByComparingTo("99");
        assertThat(b.recorded().trades().get(1).selectedCalculation().netBenefit()).isEqualByComparingTo("100");
        assertThat(pair(a,b).recordedBMinusA()).isEqualByComparingTo("0");
        assertThat(a.recorded().finalState().get(Kind.CHECK).usedExtra()).isEqualTo(5000);
    }
    @Test void approvedAvailabilityAndCostScopeAreExplicitNotHistoricalAssertions() {
        var p=target();
        for(var k:Kind.values()) {var f=p.facts().get(k);p=withFacts(p,k,new Facts(f.pointEligible(),f.area(),f.bonusEligible(),f.specialBenefitsAbsent(),
                f.benefitBaseAmount(),f.recognizedAmount(),f.recognitionMonth(),null,null,f.areaSelectionAmount(),f.deferredEligibleAmount(),f.evidence()));}
        var initial=new EnumMap<Kind,DeepDreamReplayState.Opening>(Kind.class);
        for(var k:Kind.values()) {var o=opening().get(k);initial.put(k,new DeepDreamReplayState.Opening(o.previous(),o.currentPerformance(),null,o.usedExtra(),null,o.areaSpending(),o.deferredBases()));}
        var result=evaluate(List.of(p),Scenario.A,initial);
        assertThat(result.yamu().knownNetBenefit()).isEqualByComparingTo("214");
        assertThat(result.yamu().trades().get(0).before().get(Kind.CREDIT).available()).isEqualTo(10_280);
        assertThat(p.facts().get(Kind.CREDIT).additionalCost()).isNull();
        var o=initial.get(Kind.CREDIT);initial.put(Kind.CREDIT,new DeepDreamReplayState.Opening(o.previous(),o.currentPerformance(),null,null,null,o.areaSpending(),o.deferredBases()));
        var finalP=p;
        assertThatThrownBy(()->evaluate(List.of(finalP),Scenario.A,initial)).isInstanceOf(Unresolved.class);
    }
    @Test void missingEligibilityOrEvidenceCannotBecomeKnownZero() {
        var p=target();var f=p.facts().get(Kind.CREDIT);
        var bad=withFacts(p,Kind.CREDIT,new Facts(null,f.area(),f.bonusEligible(),true,f.benefitBaseAmount(),f.recognizedAmount(),MONTH,
                f.debitAmount(),BigDecimal.ZERO,f.areaSelectionAmount(),f.deferredEligibleAmount()," "));
        assertThatThrownBy(()->evaluate(List.of(bad),Scenario.B,opening())).isInstanceOf(Unresolved.class);
    }
    @Test void realCaseAndTargetErrorsAreRejected() {
        var p=target();var real=new Purchase(p.id(),Origin.REAL_CASE,p.at(),p.amount(),p.actualCard(),p.facts());
        assertThatThrownBy(()->evaluate(List.of(real),Scenario.A,opening())).hasMessageContaining("REAL_CASE_EXECUTION_NOT_APPROVED");
        assertThatThrownBy(()->compare(Origin.REAL_CASE,List.of(p),p.id(),Scenario.A,areas(),rules(),MONTH,opening(),Kind.CREDIT)).hasMessageContaining("REAL_CASE_EXECUTION_NOT_APPROVED");
        assertThatThrownBy(()->overlay(List.of(p,p),p.id(),Scenario.A,areas())).hasMessage("TARGET_MUST_BE_UNIQUE");
        assertThatThrownBy(()->overlay(List.of(p),"MISSING",Scenario.A,areas())).hasMessage("TARGET_MUST_BE_UNIQUE");
        assertThat(resource("card-selection-exp-005-sensitivity-plan.json").get("mainExecutionApproved").asBoolean()).isFalse();
    }
    @Test void areaSelectionIsIndependentAndVirtualSettlementRemainsOptional() {
        var changed=Map.of(Kind.CREDIT,new AreaAmounts(10_040,10_040,"SYNTHETIC_ALTERNATIVE_NOT_OFFICIAL"),
                Kind.CHECK,new AreaAmounts(10_040,10_040,"SYNTHETIC_ALTERNATIVE_NOT_OFFICIAL"));
        var a=evaluate(List.of(target()),Scenario.B,opening());
        var b=compare(Origin.SYNTHETIC_TEST_ONLY,List.of(target()),target().id(),Scenario.B,changed,rules(),MONTH,opening(),Kind.CREDIT);
        assertThat(b.yamu().knownNetBenefit()).isEqualTo(a.yamu().knownNetBenefit());
        assertThat(b.yamu().finalState().get(Kind.CREDIT).areaSpending().get(Area.ABROAD)).isEqualTo(10_040);
        assertThat(a.yamu().finalState().get(Kind.CREDIT).areaSpending().get(Area.ABROAD)).isEqualTo(10_280);
    }
    @Test void knownZeroTieUsesDeclaredDefaultAndNoFutureLookahead() {
        var p=target();
        for(var k:Kind.values()) p=withFacts(p,k,new Facts(false,Area.ABROAD,false,true,10_280L,10_280L,MONTH,
                10_280L,BigDecimal.ZERO,10_280L,0L,"SYNTHETIC_KNOWN_EXCLUSION"));
        var noDeferred=Map.of(Kind.CREDIT,new AreaAmounts(10_280,0,"SYNTHETIC_EXCLUSION"),
                Kind.CHECK,new AreaAmounts(10_280,0,"SYNTHETIC_EXCLUSION"));
        for(var s:Scenario.values()) {
            var result=compare(Origin.SYNTHETIC_TEST_ONLY,List.of(p),p.id(),s,noDeferred,rules(),MONTH,opening(),Kind.CHECK);
            assertThat(result.yamu().trades().get(0).selectedCard()).isEqualTo(Kind.CHECK);
            assertThat(result.yamu().knownNetBenefit()).isEqualByComparingTo("0");
        }
        var shortRun=evaluate(List.of(target()),Scenario.A,opening());
        var longRun=evaluate(List.of(target(),purchase("FUTURE",8,30_000,Area.RETAIL_STORE,Kind.CREDIT)),Scenario.A,opening());
        // Modeled available funds differ with scope; selection and benefit must not.
        assertThat(longRun.yamu().trades().get(0).selectedCard()).isEqualTo(shortRun.yamu().trades().get(0).selectedCard());
        assertThat(longRun.yamu().trades().get(0).selectedCalculation()).isEqualTo(shortRun.yamu().trades().get(0).selectedCalculation());
    }
    @Test void fullCapStillKeepsBaseAndConditionalUpperBoundDoesNotNeedPrivateReplay() {
        var initial=new EnumMap<Kind,DeepDreamReplayState.Opening>(Kind.class);
        for(var k:Kind.values()) {var o=opening().get(k);initial.put(k,new DeepDreamReplayState.Opening(o.previous(),0L,0L,
                terms().get(k).cap(o.previous()),true,zeroAreas(),zeroAreas()));}
        assertThat(evaluate(List.of(target()),Scenario.A,initial).yamu().knownNetBenefit()).isEqualByComparingTo("71");
        assertThat(evaluate(List.of(target()),Scenario.B,initial).yamu().knownNetBenefit()).isEqualByComparingTo("70");
        // Algebraic upper bound, a fabricated spending bound, not a private replay. Conditional on zero opening usage,
        // no external shared usage and no special benefits; every won assumed DREAM eligible.
        for(var k:Kind.values()) {
            var t=terms().get(k);
            var bound=new BigDecimal("500000").multiply(t.extra().add(t.deferred()));
            assertThat(bound).isLessThan(BigDecimal.valueOf(t.cap(opening().get(k).previous())));
        }
    }
}
