package com.yamu.be.recommendation;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.yamu.be.recommendation.DeepDreamPolicySupport.*;
import static com.yamu.be.recommendation.DeepDreamTestFixtures.*;
import static com.yamu.be.recommendation.DeepDreamAssumptionSupport.*;
import static com.yamu.be.recommendation.DeepDreamSensitivitySupport.*;
import static com.yamu.be.recommendation.DeepDreamCaseStudySupport.*;

/** Fabricated transactions only; never imports HTML or opens a real-case execution path. */
class DeepDreamAssumptionTest {
    private List<Purchase> input() {
        return List.of(purchase("FEE",5,10280,Area.ABROAD,Kind.CREDIT),
                purchase("COFFEE_ONE",6,100,Area.NONE,Kind.CHECK),purchase("COFFEE_TWO",7,100,Area.NONE,Kind.CHECK));
    }
    private Axis axis(String id) {
        return new Axis(id,List.of(new Alternative("GENERAL",Map.of(Kind.CREDIT,facts(100,Area.NONE),Kind.CHECK,facts(100,Area.NONE)),"FABRICATED_GENERAL"),
                new Alternative("COFFEE",Map.of(Kind.CREDIT,facts(100,Area.ENJOY_STORE),Kind.CHECK,facts(100,Area.ENJOY_STORE)),"FABRICATED_COFFEE_NOT_ISSUER_EVIDENCE")));
    }
    private List<PolicyAlternative> policies() {
        var r=rules();
        var other=new Rules(r.origin(),"FABRICATED_ALTERNATIVE_ORDER",r.from(),r.until(),r.componentRounding(),BenefitPolicy.CalculationOrder.ROUND_THEN_LIMIT,
                r.bonusStacksWithDream(),r.deferredRounding(),r.tieRule(),r.immediateBeforeDeferred());
        return List.of(new PolicyAlternative("LIMIT_FIRST",r,"TEST_ONLY"),new PolicyAlternative("ROUND_FIRST",other,"TEST_ONLY"));
    }
    private Map<Kind,AreaAmounts> areas() {
        return Map.of(Kind.CREDIT,new AreaAmounts(10280,10280,"SYNTHETIC_INDEPENDENT_AREA_BASE"),
                Kind.CHECK,new AreaAmounts(10280,10280,"SYNTHETIC_INDEPENDENT_AREA_BASE"));
    }
    private Report report(List<Axis> axes) {
        return evaluate(Origin.SYNTHETIC_TEST_ONLY,input(),"FEE",axes,policies(),areas(),MONTH,opening(),Kind.CHECK);
    }
    @Test void crossesJointAlternativesWithBothFeesAndPoliciesRatherThanOneAtATime() {
        var r=report(List.of(axis("COFFEE_ONE"),axis("COFFEE_TWO")));
        assertThat(r.cells()).hasSize(16);
        assertThat(r.advantage().minimum()).isEqualByComparingTo("0");
        assertThat(r.advantage().maximum()).isEqualByComparingTo("2");
        assertThat(r.advantageSigns()).containsExactlyInAnyOrder(0,1);
        assertThat(r.selectionSensitiveIds()).containsExactlyInAnyOrder("COFFEE_ONE","COFFEE_TWO");
        assertThat(report(List.of(axis("COFFEE_ONE"))).advantage().maximum()).isEqualByComparingTo("1");
        assertThat(report(List.of(axis("COFFEE_TWO"))).advantage().maximum()).isEqualByComparingTo("1");
        assertThat(r.cells()).anySatisfy(c->{assertThat(c.assumptions()).containsEntry("COFFEE_ONE","COFFEE").containsEntry("COFFEE_TWO","COFFEE");});
    }
    @Test void preservesFullTracesIndependentStateAndInputs() {
        var original=input();var r=report(List.of(axis("COFFEE_ONE"),axis("COFFEE_TWO")));
        assertThat(input()).isEqualTo(original);
        assertThat(report(List.of(axis("COFFEE_ONE"),axis("COFFEE_TWO")))).isEqualTo(r);
        for(var c:r.cells()) {
            assertThat(c.comparison().yamu().trades()).hasSize(3);
            assertThat(c.comparison().yamu().trades().get(1).candidates()).hasSize(2);
            assertThat(c.comparison().yamu().trades().get(1).before()).hasSize(2);
            assertThat(c.comparison().yamu().trades().get(1).after()).hasSize(2);
            assertThat(c.comparison().yamu().deferredStatus()).isEqualTo("UNRESOLVED_MONTH_NOT_CLOSED");
        }
        assertThat(r.coverage()).isEqualTo("DECLARED_CELLS_ONLY_NOT_UNIVERSAL_ROBUSTNESS");
    }
    @Test void realInputsAndRealPolicyCannotEnterMatrix() {
        assertThatThrownBy(()->evaluate(Origin.REAL_CASE,input(),"FEE",List.of(),policies(),areas(),MONTH,opening(),Kind.CREDIT))
                .hasMessageContaining("REAL_CASE_EXECUTION_NOT_APPROVED");
        var p=input().get(0);var real=new Purchase(p.id(),Origin.REAL_CASE,p.at(),p.amount(),p.actualCard(),p.facts());
        assertThatThrownBy(()->evaluate(Origin.SYNTHETIC_TEST_ONLY,List.of(real),"FEE",List.of(),policies(),areas(),MONTH,opening(),Kind.CREDIT))
                .hasMessageContaining("REAL_CASE_EXECUTION_NOT_APPROVED");
        var r=rules();var realRules=new Rules(Origin.REAL_CASE,r.evidence(),r.from(),r.until(),r.componentRounding(),r.order(),r.bonusStacksWithDream(),r.deferredRounding(),r.tieRule(),true);
        assertThatThrownBy(()->evaluate(Origin.SYNTHETIC_TEST_ONLY,input(),"FEE",List.of(),List.of(new PolicyAlternative("REAL",realRules,"NOT_EXECUTABLE")),areas(),MONTH,opening(),Kind.CREDIT))
                .hasMessageContaining("REAL_CASE_EXECUTION_NOT_APPROVED");
        assertThat(resource("card-selection-exp-005-assumptions.json").get("mainExecutionApproved").asBoolean()).isFalse();
    }
    @Test void rejectsConflictingOrMissingDimensionsAndNeverSamplesSilently() {
        assertThatThrownBy(()->report(List.of(axis("MISSING")))).hasMessage("UNKNOWN_OR_OVERLAPPING_AXIS");
        assertThatThrownBy(()->report(List.of(axis("COFFEE_ONE"),axis("COFFEE_ONE")))).hasMessage("UNKNOWN_OR_OVERLAPPING_AXIS");
        var options=new ArrayList<Alternative>();
        for(int i=0;i<65;i++)options.add(new Alternative("V"+i,Map.of(Kind.CREDIT,facts(100,Area.NONE),Kind.CHECK,facts(100,Area.NONE)),"TEST"));
        assertThatThrownBy(()->report(List.of(new Axis("COFFEE_ONE",options)))).hasMessage("DECLARED_MATRIX_TOO_LARGE_NO_SAMPLING");
        assertThatThrownBy(()->new Alternative("BAD",Map.of(Kind.CREDIT,facts(100,Area.NONE)),"TEST")).hasMessage("BOTH_CARD_FACTS_REQUIRED");
    }
    @Test void unresolvedAlternativeAbortsInsteadOfDroppingUnfavorableOrUnknownCells() {
        var unknown=new Facts(null,Area.NONE,false,true,100L,100L,MONTH,100L,BigDecimal.ZERO,0L,0L,"UNCONFIRMED");
        var a=new Axis("COFFEE_ONE",List.of(new Alternative("KNOWN",axis("COFFEE_ONE").alternatives().get(0).facts(),"TEST"),
                new Alternative("UNKNOWN",Map.of(Kind.CREDIT,unknown,Kind.CHECK,facts(100,Area.NONE)),"TEST")));
        assertThatThrownBy(()->report(List.of(a))).isInstanceOf(Unresolved.class);
    }
    @Test void reportsNegativeZeroAndPositiveWithoutSmallAmountFiltering() {
        var cells=new ArrayList<Cell>();
        for(int delta:new int[]{-1,0,1}) {
            var recorded=new Run(Strategy.RECORDED_ACTUAL,BigDecimal.ONE,"UNRESOLVED_MONTH_NOT_CLOSED",List.of(),Map.of());
            var yamu=new Run(Strategy.YAMU_KNOWN_TRANSACTION_BENEFIT,BigDecimal.valueOf(1+delta),"UNRESOLVED_MONTH_NOT_CLOSED",List.of(),Map.of());
            cells.add(new Cell(Map.of("FABRICATED_SUMMARY_INPUT",String.valueOf(delta)),"TEST",new Comparison(Scenario.A,recorded,yamu,BigDecimal.valueOf(delta))));
        }
        // This is a reporting-unit test, not a negative result computed by the Deep Dream policy.
        var r=summarize(cells);
        assertThat(r.advantageSigns()).containsExactlyInAnyOrder(-1,0,1);
        assertThat(r.advantage().minimum()).isEqualByComparingTo("-1");
        assertThat(r.advantage().maximum()).isEqualByComparingTo("1");
    }
    @Test void coupledCapAndCategoryBoundariesAreKeptInEachCell() {
        var limited=new EnumMap<Kind,DeepDreamReplayState.Opening>(Kind.class);limited.putAll(opening());
        limited.put(Kind.CREDIT,new DeepDreamReplayState.Opening(2000000L,0L,0L,49999L,true,zeroAreas(),zeroAreas()));
        var r=evaluate(Origin.SYNTHETIC_TEST_ONLY,input(),"FEE",List.of(axis("COFFEE_ONE"),axis("COFFEE_TWO")),policies(),areas(),MONTH,limited,Kind.CHECK);
        assertThat(r.cells()).hasSize(16);
        for(var c:r.cells()) {
            assertThat(c.comparison().yamu().trades().get(0).after().get(Kind.CREDIT).usedExtra()).isEqualTo(50000);
            assertThat(c.comparison().yamu().finalState().get(Kind.CREDIT).usedExtra()).isEqualTo(50000);
        }
        // Stress fixture only: approved real-case model starts at zero, not 49,999.
    }
}
