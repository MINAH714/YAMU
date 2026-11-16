package com.yamu.be.recommendation;

import static org.assertj.core.api.Assertions.*;
import static com.yamu.be.recommendation.DeepDreamPolicySupport.*;
import static com.yamu.be.recommendation.DeepDreamTestFixtures.*;
import static com.yamu.be.recommendation.DeepDreamDeferredSettlement.*;
import java.util.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class DeepDreamDeferredSettlementTest {
    @Test void interimIsUnresolvedRatherThanZeroAndVirtualSettlementDoesNotMutateState() {
        var run=DeepDreamCaseStudySupport.run(Origin.SYNTHETIC_TEST_ONLY,DeepDreamCaseStudySupport.Strategy.RECORDED_ACTUAL,
                List.of(purchase("T1",1,10_000,Area.RETAIL_STORE,Kind.CREDIT)),rules(),MONTH,opening(),Kind.CREDIT,List.of());
        var state=run.finalState().get(Kind.CREDIT);var before=state;
        var interim=evaluate(Mode.INTERIM,terms().get(Kind.CREDIT),rules(),state);
        assertThat(interim.points()).isNull();assertThat(interim.status()).isEqualTo("UNRESOLVED_MONTH_NOT_CLOSED");
        var virtual=evaluate(Mode.VIRTUAL_OBSERVATION_END,terms().get(Kind.CREDIT),rules(),state);
        assertThat(virtual.status()).isEqualTo("VIRTUAL_ONLY");assertThat(virtual.points()).isEqualByComparingTo("140");
        assertThat(virtual.remainingBefore()).isEqualTo(49_860);assertThat(virtual.remainingAfter()).isEqualTo(49_720);
        assertThat(state).isEqualTo(before);assertThat(evaluate(Mode.VIRTUAL_OBSERVATION_END,terms().get(Kind.CREDIT),rules(),state)).isEqualTo(virtual);
    }
    @Test void differentPathsHaveDifferentAreaTotalsAndNeverCopyActualDeferredValue() {
        var purchases=List.of(purchase("T1",1,10_000,Area.RETAIL_STORE,Kind.CHECK),purchase("T2",2,20_000,Area.ENJOY_STORE,Kind.CREDIT));
        var actual=DeepDreamCaseStudySupport.run(Origin.SYNTHETIC_TEST_ONLY,DeepDreamCaseStudySupport.Strategy.RECORDED_ACTUAL,purchases,rules(),MONTH,opening(),Kind.CREDIT,List.of());
        var yamu=DeepDreamCaseStudySupport.run(Origin.SYNTHETIC_TEST_ONLY,DeepDreamCaseStudySupport.Strategy.YAMU_KNOWN_TRANSACTION_BENEFIT,purchases,rules(),MONTH,opening(),Kind.CREDIT,List.of());
        assertThat(actual.finalState().get(Kind.CHECK).areaSpending().get(Area.RETAIL_STORE)).isEqualTo(10_000);
        assertThat(yamu.finalState().get(Kind.CHECK).areaSpending().get(Area.RETAIL_STORE)).isZero();
        assertThat(evaluate(Mode.VIRTUAL_OBSERVATION_END,terms().get(Kind.CHECK),rules(),actual.finalState().get(Kind.CHECK)).points()).isEqualByComparingTo("40");
        assertThat(evaluate(Mode.VIRTUAL_OBSERVATION_END,terms().get(Kind.CHECK),rules(),yamu.finalState().get(Kind.CHECK)).points()).isEqualByComparingTo("0");
    }
    @Test void unresolvedTieAndRoundingAreBlockedAndSharedRemainderCapsVirtualPoints() {
        var areas=zeroAreas();areas.put(Area.RETAIL_STORE,10_000L);areas.put(Area.ENJOY_STORE,10_000L);
        var state=new DeepDreamReplayState.CardSnapshot(2_000_000L,Map.of(MONTH,0L),1_000_000L,49_999L,true,areas,areas,0,BigDecimal.ZERO);
        var r=rules();var unknownTie=new Rules(r.origin(),r.evidence(),r.from(),r.until(),r.componentRounding(),r.order(),r.bonusStacksWithDream(),r.deferredRounding(),null,true);
        var blocked=evaluate(Mode.VIRTUAL_OBSERVATION_END,terms().get(Kind.CREDIT),unknownTie,state);
        assertThat(blocked.status()).isEqualTo("BLOCKED");assertThat(blocked.points()).isNull();assertThat(blocked.issues()).contains("TOP_AREA_TIE_UNCONFIRMED");
        assertThat(evaluate(Mode.VIRTUAL_OBSERVATION_END,terms().get(Kind.CREDIT),researchRules(),state).points()).isNull();
        assertThat(evaluate(Mode.VIRTUAL_OBSERVATION_END,terms().get(Kind.CREDIT),rules(),state).points()).isEqualByComparingTo("1");
    }
}
