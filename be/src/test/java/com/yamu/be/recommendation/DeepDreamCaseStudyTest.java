package com.yamu.be.recommendation;

import static org.assertj.core.api.Assertions.*;
import static com.yamu.be.recommendation.DeepDreamPolicySupport.*;
import static com.yamu.be.recommendation.DeepDreamTestFixtures.*;
import static com.yamu.be.recommendation.DeepDreamCaseStudySupport.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Synthetic infrastructure tests, NOT the November 58-transaction experiment. */
class DeepDreamCaseStudyTest {
    @Test void realCaseExecutionIsHardBlockedEvenWithSyntheticRulesOrEmptyInput() {
        assertThatThrownBy(()->run(Origin.REAL_CASE,Strategy.RECORDED_ACTUAL,List.of(),rules(),MONTH,opening(),Kind.CREDIT,List.of()))
                .isInstanceOf(Unresolved.class).hasMessageContaining("REAL_CASE_EXECUTION_NOT_APPROVED");
        var p=purchase("T1",1,10_000,Area.RETAIL_STORE,Kind.CHECK);
        var real=new Purchase(p.id(),Origin.REAL_CASE,p.at(),p.amount(),p.actualCard(),p.facts());
        assertThatThrownBy(()->run(Origin.SYNTHETIC_TEST_ONLY,Strategy.YAMU_KNOWN_TRANSACTION_BENEFIT,List.of(real),rules(),MONTH,opening(),Kind.CREDIT,List.of()))
                .hasMessageContaining("REAL_CASE_EXECUTION_NOT_APPROVED");
        assertThat(resource("card-selection-exp-005-plan.json").get("mainExecutionApproved").asBoolean()).isFalse();
    }
    @Test void futureTransactionsCannotAlterPastRecommendationsAndReplaysAreIndependent() {
        var first=purchase("T1",1,10_000,Area.RETAIL_STORE,Kind.CHECK);
        var shortRun=run(Origin.SYNTHETIC_TEST_ONLY,Strategy.YAMU_KNOWN_TRANSACTION_BENEFIT,List.of(first),rules(),MONTH,opening(),Kind.CREDIT,List.of());
        var longRun=run(Origin.SYNTHETIC_TEST_ONLY,Strategy.YAMU_KNOWN_TRANSACTION_BENEFIT,List.of(first,purchase("T2",2,50_000,Area.ENJOY_STORE,Kind.CREDIT)),rules(),MONTH,opening(),Kind.CREDIT,List.of());
        assertThat(longRun.trades().get(0)).isEqualTo(shortRun.trades().get(0));
        var actual=run(Origin.SYNTHETIC_TEST_ONLY,Strategy.RECORDED_ACTUAL,List.of(first),rules(),MONTH,opening(),Kind.CREDIT,List.of());
        assertThat(actual.knownNetBenefit()).isEqualByComparingTo("60");assertThat(shortRun.knownNetBenefit()).isEqualByComparingTo("210");
        assertThat(actual.finalState().get(Kind.CREDIT).spent()).isZero();
        assertThat(shortRun.finalState().get(Kind.CHECK).spent()).isZero();
        assertThat(run(Origin.SYNTHETIC_TEST_ONLY,Strategy.YAMU_KNOWN_TRANSACTION_BENEFIT,List.of(first),rules(),MONTH,opening(),Kind.CREDIT,List.of())).isEqualTo(shortRun);
    }
    @Test void availableFundsAreDeductedAndExplicitFundingChangesNextEligibility() {
        var inputs=new EnumMap<Kind,DeepDreamReplayState.Opening>(Kind.class);inputs.putAll(opening());
        inputs.put(Kind.CREDIT,new DeepDreamReplayState.Opening(2_000_000L,0L,10_000L,0L,true,zeroAreas(),zeroAreas()));
        var purchases=List.of(purchase("T1",1,10_000,Area.RETAIL_STORE,Kind.CHECK),purchase("T2",2,10_000,Area.RETAIL_STORE,Kind.CHECK));
        var noFunding=run(Origin.SYNTHETIC_TEST_ONLY,Strategy.YAMU_KNOWN_TRANSACTION_BENEFIT,purchases,rules(),MONTH,inputs,Kind.CREDIT,List.of());
        assertThat(noFunding.trades()).extracting(Trade::selectedCard).containsExactly(Kind.CREDIT,Kind.CHECK);
        var funded=run(Origin.SYNTHETIC_TEST_ONLY,Strategy.YAMU_KNOWN_TRANSACTION_BENEFIT,purchases,rules(),MONTH,inputs,Kind.CREDIT,List.of(new Funding("F1","T2",Kind.CREDIT,10_000)));
        assertThat(funded.trades()).extracting(Trade::selectedCard).containsExactly(Kind.CREDIT,Kind.CREDIT);
        assertThat(funded.finalState().get(Kind.CREDIT).available()).isZero();
        assertThat(funded.finalState().get(Kind.CREDIT).performance().get(MONTH)).isEqualTo(20_000);
        assertThat(funded.finalState().get(Kind.CREDIT).usedExtra()).isEqualTo(280);
    }
    @Test void allKnownZeroUsesDefaultAndUnknownFactsDoNotBecomeZero() {
        var p=purchase("T1",1,10_000,Area.NONE,Kind.CREDIT);
        for(var kind:Kind.values()) p=withFacts(p,kind,new Facts(false,Area.NONE,false,true,10_000L,10_000L,MONTH,10_000L,BigDecimal.ZERO,0L,0L,"SYNTHETIC_EXCLUSION"));
        var result=run(Origin.SYNTHETIC_TEST_ONLY,Strategy.YAMU_KNOWN_TRANSACTION_BENEFIT,List.of(p),rules(),MONTH,opening(),Kind.CHECK,List.of());
        assertThat(result.trades().get(0).selectedCard()).isEqualTo(Kind.CHECK);assertThat(result.knownNetBenefit()).isEqualByComparingTo("0");
        var unknown=withFacts(p,Kind.CHECK,new Facts(null,Area.NONE,false,true,10_000L,10_000L,MONTH,10_000L,BigDecimal.ZERO,0L,0L,"MISSING_ELIGIBILITY"));
        assertThatThrownBy(()->run(Origin.SYNTHETIC_TEST_ONLY,Strategy.YAMU_KNOWN_TRANSACTION_BENEFIT,List.of(unknown),rules(),MONTH,opening(),Kind.CHECK,List.of())).isInstanceOf(Unresolved.class);
    }
    @Test void duplicatesUnorderedInputsAndUnknownOpeningFailBeforeReplay() {
        var p=purchase("T1",1,10_000,Area.NONE,Kind.CREDIT);
        assertThatThrownBy(()->run(Origin.SYNTHETIC_TEST_ONLY,Strategy.RECORDED_ACTUAL,List.of(p,p),rules(),MONTH,opening(),Kind.CREDIT,List.of())).hasMessage("DUPLICATE_PURCHASE");
        assertThatThrownBy(()->run(Origin.SYNTHETIC_TEST_ONLY,Strategy.RECORDED_ACTUAL,List.of(purchase("T2",2,1_000,Area.NONE,Kind.CREDIT),p),rules(),MONTH,opening(),Kind.CREDIT,List.of())).hasMessage("UNORDERED_PURCHASES");
        assertThatThrownBy(()->run(Origin.SYNTHETIC_TEST_ONLY,Strategy.RECORDED_ACTUAL,List.of(p),rules(),MONTH,Map.of(),Kind.CREDIT,List.of())).isInstanceOf(Unresolved.class);
    }
    @Test void ledgerRejectsDoubleSettlementAndDoesNotResetCapAtDateChange() {
        var state=new DeepDreamReplayState(MONTH,opening(),terms());
        var p=purchase("T1",1,10_000,Area.RETAIL_STORE,Kind.CREDIT);
        var calc=new CardBenefitCalculator().calculate(transaction(p),policy(terms().get(Kind.CREDIT),rules(),p,state.snapshot(Kind.CREDIT)));
        state.settle(p,Kind.CREDIT,calc);var after=state.snapshots();
        assertThatThrownBy(()->state.settle(p,Kind.CREDIT,calc)).hasMessage("DUPLICATE_EVENT");assertThat(state.snapshots()).isEqualTo(after);
        var next=purchase("T2",2,10_000,Area.RETAIL_STORE,Kind.CREDIT);
        var policy=policy(terms().get(Kind.CREDIT),rules(),next,state.snapshot(Kind.CREDIT));assertThat(policy.limits().get(0).used()).isEqualTo(140);
    }
}
