package com.yamu.be.recommendation;

import static com.yamu.be.recommendation.RecommendationFixtures.*;
import static org.assertj.core.api.Assertions.*;
import com.yamu.be.recommendation.BenefitPolicy.*;
import com.yamu.be.recommendation.BenefitConditionEvaluator.Match;
import com.yamu.be.recommendation.RecommendationInput.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 확인된 정책 fixture의 계산 검증. 실제 Seed 정책 완전성 검증이 아니다. */
class BenefitCompositionTest {
    final CardBenefitCalculator calculator = new CardBenefitCalculator();
    final Transaction tx = new Transaction(100_000,Plan.LUMP_SUM,1,Instant.parse("2026-11-01T00:00:00Z"),1L,"CAFE",Map.of());

    Benefit rate(long id, String rate, Unit unit, List<Long> limits) {
        return rate(id, rate, unit, limits, 100_000L);
    }
    Benefit rate(long id, String rate, Unit unit, List<Long> limits, long base) {
        return new Benefit(id,1,"fixture",unit==Unit.POINT?BenefitType.POINT:BenefitType.DISCOUNT,CalculationType.RATE,
                new BigDecimal(rate),unit,unit==Unit.POINT?BigDecimal.ONE:null,base,0L,0L,conditions(),rounding(),CalculationOrder.ROUND_THEN_LIMIT,limits);
    }
    Limit limit(long id,Unit unit,long maximum,long used) { return new Limit(id,1,unit,Period.MONTHLY,maximum,used,null,null); }
    BenefitPolicy policy(List<Benefit> benefits,List<Limit> limits,ApplicationMode mode,Map<Long,Fallback> fallbacks) {
        var members=benefits.stream().map(b->new ApplicationMember(b.id(),mode==ApplicationMode.PRIORITY_ONE?(b.id()==1?1:2):null)).toList();
        return new BenefitPolicy(true,400_000L,BigDecimal.ZERO,benefits,limits,null,List.of(new ApplicationGroup(1,mode,members)),fallbacks);
    }

    @ParameterizedTest @CsvSource({"6000,5000,0","5000,5000,0","1000,2200,1200","0,1500,1500"})
    void fallbackSplitsUnroundedPrincipalAndDoesNotRunBaseTwice(long cap,long total,long baseValue) {
        var preferred=rate(1,"0.05",Unit.POINT,List.of(9L));
        var base=rate(2,"0.015",Unit.POINT,List.of());
        var result=calculator.calculate(tx,policy(List.of(preferred,base),List.of(limit(9,Unit.POINT,cap,0)),ApplicationMode.PRIORITY_ONE,Map.of(1L,new Fallback(2,"EXCESS_PAYMENT_AMOUNT"))));
        assertThat(result.complete()).isTrue();
        assertThat(result.netBenefit()).isEqualByComparingTo(Long.toString(total));
        assertThat(result.benefits().stream().filter(b->b.benefitId()==2).map(CardBenefitCalculator.BenefitValue::value)
                .reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo(Long.toString(baseValue));
        assertThat(preferred.baseAmount()).isEqualTo(100_000L);
    }

    @Test void fallbackStillChecksBaseConditionsAndRejectsChains() {
        var preferred=rate(1,"0.05",Unit.POINT,List.of(9L));
        var base=rate(2,"0.015",Unit.POINT,List.of());
        var notApplicable=new Conditions(true,true,POLICY_START,BenefitPolicy.NO_END_DATE,
                List.of(new Target(TargetType.BRAND,99L,null,false)),Set.of(),Set.of(),Schedule.unrestricted());
        base=change(base,notApplicable,base.rounding(),base.calculationOrder(),base.limitIds());
        var result=calculator.calculate(tx,policy(List.of(preferred,base),List.of(limit(9,Unit.POINT,1000,0)),ApplicationMode.PRIORITY_ONE,Map.of(1L,new Fallback(2,"EXCESS_PAYMENT_AMOUNT"))));
        assertThat(result.netBenefit()).isEqualByComparingTo("1000");
        result=calculator.calculate(tx,policy(List.of(preferred,base),List.of(limit(9,Unit.POINT,1000,0)),ApplicationMode.PRIORITY_ONE,
                Map.of(1L,new Fallback(2,"EXCESS_PAYMENT_AMOUNT"),2L,new Fallback(1,"EXCESS_PAYMENT_AMOUNT"))));
        assertThat(result.complete()).isFalse();
    }

    @Test void exhaustedPriorityDoesNotAutomaticallyRunNextBenefitWithoutFallback() {
        var result=calculator.calculate(tx,policy(List.of(rate(1,"0.05",Unit.POINT,List.of(9L)),rate(2,"0.015",Unit.POINT,List.of())),
                List.of(limit(9,Unit.POINT,0,0)),ApplicationMode.PRIORITY_ONE,Map.of()));
        assertThat(result.complete()).isTrue();
        assertThat(result.netBenefit()).isZero();
        assertThat(result.benefits()).extracting(CardBenefitCalculator.BenefitValue::benefitId).containsExactly(1L);
    }

    @Test void maximumUsesSameUsageAndIsIndependentOfInputOrder() {
        var a=rate(1,"0.05",Unit.KRW,List.of(9L)); var b=rate(2,"0.1",Unit.KRW,List.of(9L));
        for(var rows:List.of(List.of(a,b),List.of(b,a))) {
            var result=calculator.calculate(tx,policy(rows,List.of(limit(9,Unit.KRW,12_000,5000)),ApplicationMode.MAX_ONE,Map.of()));
            assertThat(result.netBenefit()).isEqualByComparingTo("7000");
            assertThat(result.benefits()).extracting(CardBenefitCalculator.BenefitValue::benefitId).containsExactly(2L);
        }
    }

    @Test void noriStyleStackHasKnownTotalAndUnknownAllocationOnlyWhenSharedCapBinds() {
        var noriTx=new Transaction(15_000,Plan.LUMP_SUM,1,tx.occurredAt(),1L,"CAFE",Map.of(),"YOUTUBE_PREMIUM_SUBSCRIPTION");
        var mobile=rate(1,"0.1",Unit.KRW,List.of(8L,9L),15_000L);
        var subscription=change(benefit(2,"1000"),conditions(),rounding(),CalculationOrder.ROUND_THEN_LIMIT,List.of(7L,9L));
        var policy=policy(List.of(mobile,subscription),List.of(limit(8,Unit.KRW,3000,0),limit(7,Unit.KRW,2000,0),limit(9,Unit.KRW,1800,0)),ApplicationMode.STACK,Map.of());
        var result=calculator.calculate(noriTx,policy);
        assertThat(result.complete()).isTrue();
        assertThat(result.netBenefit()).isEqualByComparingTo("1800");
        assertThat(result.benefits()).allSatisfy(b->assertThat(b.value()).isNull());
        assertThat(result.unallocatedBenefitGroups()).singleElement().satisfies(g->{
            assertThat(g.benefitIds()).containsExactly(1L,2L); assertThat(g.totalAmount()).isEqualByComparingTo("1800");
        });
        assertThat(result.limitUses()).extracting(CardBenefitCalculator.LimitUse::limitId).containsExactly(9L);
        var input=new RecommendationInput(1L,1,noriTx,null,List.of(new CardCandidate(1,1,CardType.CREDIT,
                com.yamu.be.card.domain.CardRegistrationStatus.ACTIVE,com.yamu.be.card.domain.CardIssuerStatus.NORMAL,Availability.AVAILABLE,null,policy,null,0,null)));
        assertThat(new RecommendationSelector().select(input).status()).isEqualTo(RecommendationSelector.Status.READY);
        var sufficient=policy(List.of(mobile,subscription),List.of(limit(8,Unit.KRW,3000,0),limit(7,Unit.KRW,2000,0),limit(9,Unit.KRW,3000,0)),ApplicationMode.STACK,Map.of());
        assertThat(calculator.calculate(noriTx,sufficient).unallocatedBenefitGroups()).isEmpty();
        assertThat(calculator.calculate(noriTx,sufficient).netBenefit()).isEqualByComparingTo("2500");
    }

    @ParameterizedTest @CsvSource({"1500,true", "1500.5,false"})
    void limitThenRoundSharedTotalIsKnownOnlyForIntegerUnitDownInputs(String raw,boolean known) {
        var a=change(benefit(1,raw),conditions(),rounding(),CalculationOrder.LIMIT_THEN_ROUND,List.of(9L));
        var b=change(benefit(2,"1000"),conditions(),rounding(),CalculationOrder.LIMIT_THEN_ROUND,List.of(9L));
        var result=calculator.calculate(tx,policy(List.of(a,b),List.of(limit(9,Unit.KRW,1800,0)),ApplicationMode.STACK,Map.of()));
        assertThat(result.complete()).isEqualTo(known);
        if(known) {
            assertThat(result.netBenefit()).isEqualByComparingTo("1800");
            assertThat(result.benefits()).allSatisfy(v->assertThat(v.value()).isNull());
        } else assertThat(result.problems()).extracting(CardBenefitCalculator.Problem::code).contains("SHARED_ROUNDING_ORDER_UNKNOWN");
    }

    @Test void purchaseItemAndMerchantAreAndPathsOrAndUnknownIsNotFalse() {
        var groups=List.of(new TargetGroup(1,false,List.of(new Target(TargetType.BRAND,1L,null,false),
                new Target(TargetType.PURCHASE_ITEM,null,"YOUTUBE_PREMIUM_SUBSCRIPTION",false))),
                new TargetGroup(2,false,List.of(new Target(TargetType.BRAND,2L,null,false))));
        var conditions=new Conditions(true,true,POLICY_START,BenefitPolicy.NO_END_DATE,List.of(),Set.of(),Set.of(),Schedule.unrestricted(),groups);
        var evaluator=new BenefitConditionEvaluator();
        assertThat(evaluator.evaluate(conditions,tx)).isEqualTo(Match.UNKNOWN);
        var specified=new Transaction(tx.amount(),tx.plan(),1,tx.occurredAt(),1L,"CAFE",Map.of(),"YOUTUBE_PREMIUM_SUBSCRIPTION");
        assertThat(evaluator.evaluate(conditions,specified)).isEqualTo(Match.MATCHED);
        assertThat(evaluator.evaluate(conditions,new Transaction(tx.amount(),tx.plan(),1,tx.occurredAt(),3L,"CAFE",Map.of()))).isEqualTo(Match.NOT_MATCHED);
        assertThat(evaluator.evaluate(conditions,new Transaction(tx.amount(),tx.plan(),1,tx.occurredAt(),2L,"CAFE",Map.of()))).isEqualTo(Match.MATCHED);
        for(var a:Match.values()) for(var b:Match.values()) {
            assertThat(BenefitConditionEvaluator.and(List.of(a,b))).isEqualTo(a==Match.NOT_MATCHED||b==Match.NOT_MATCHED?Match.NOT_MATCHED:a==Match.MATCHED&&b==Match.MATCHED?Match.MATCHED:Match.UNKNOWN);
            assertThat(BenefitConditionEvaluator.or(List.of(a,b))).isEqualTo(a==Match.MATCHED||b==Match.MATCHED?Match.MATCHED:a==Match.NOT_MATCHED&&b==Match.NOT_MATCHED?Match.NOT_MATCHED:Match.UNKNOWN);
        }
    }

    @Test void unknownExclusionBlocksMatchedInclusionButIrrelevantUnknownDoesNotBlockMismatch() {
        var evaluator=new BenefitConditionEvaluator();
        var inclusion=new TargetGroup(1,false,List.of(new Target(TargetType.BRAND,1L,null,false)));
        var exclusion=new TargetGroup(2,true,List.of(new Target(TargetType.PURCHASE_ITEM,null,"YOUTUBE_PREMIUM_SUBSCRIPTION",false)));
        var conditions=new Conditions(true,true,POLICY_START,BenefitPolicy.NO_END_DATE,List.of(),Set.of(),Set.of(),Schedule.unrestricted(),List.of(inclusion,exclusion));
        assertThat(evaluator.evaluate(conditions,tx)).isEqualTo(Match.UNKNOWN);
        var other=new Transaction(tx.amount(),tx.plan(),1,tx.occurredAt(),2L,"CAFE",Map.of());
        assertThat(evaluator.evaluate(conditions,other)).isEqualTo(Match.NOT_MATCHED);
    }

    @Test void twoMatchingPathsStillPayOneBenefitAndAmbiguousPriorityIsUnknown() {
        var b=rate(1,"0.05",Unit.KRW,List.of());
        var groups=List.of(new TargetGroup(1,false,List.of(new Target(TargetType.BRAND,1L,null,false))),
                new TargetGroup(2,false,List.of(new Target(TargetType.CATEGORY,null,"CAFE",false))));
        var conditions=new Conditions(true,true,POLICY_START,BenefitPolicy.NO_END_DATE,List.of(),Set.of(),Set.of(),Schedule.unrestricted(),groups);
        b=change(b,conditions,b.rounding(),b.calculationOrder(),b.limitIds());
        var result=calculator.calculate(tx,policy(List.of(b),List.of(),ApplicationMode.STACK,Map.of()));
        assertThat(result.netBenefit()).isEqualByComparingTo("5000");assertThat(result.benefits()).hasSize(1);
        var second=rate(2,"0.015",Unit.KRW,List.of());
        var ambiguous=new BenefitPolicy(true,400_000L,BigDecimal.ZERO,List.of(b,second),List.of(),null,
                List.of(new ApplicationGroup(1,ApplicationMode.PRIORITY_ONE,List.of(new ApplicationMember(1,1),new ApplicationMember(2,1)))),Map.of());
        assertThat(calculator.calculate(tx,ambiguous).problems()).extracting(CardBenefitCalculator.Problem::code).containsExactly("PRIORITY_AMBIGUOUS");
    }

    @Test void exhaustedSharedAmountDoesNotReportOldIndividualConsumption() {
        var a=rate(1,"0.05",Unit.KRW,List.of(8L,9L,10L));
        var b=rate(2,"0.01",Unit.KRW,List.of(7L,9L,10L));
        var count=new Limit(10,1,Unit.COUNT,Period.MONTHLY,2L,0L,CountMode.PER_BENEFIT,false);
        var result=calculator.calculate(tx,policy(List.of(a,b),List.of(limit(8,Unit.KRW,5000,0),limit(7,Unit.KRW,5000,0),limit(9,Unit.KRW,0,0),count),ApplicationMode.STACK,Map.of()));
        assertThat(result.complete()).isTrue();assertThat(result.netBenefit()).isZero();
        assertThat(result.benefits()).allSatisfy(v->assertThat(v.value()).isZero());
        assertThat(result.limitUses()).allSatisfy(u->assertThat(u.consumed()).isZero());
        assertThat(result.unallocatedBenefitGroups()).isEmpty();
    }

    @Test void fallbackRejectsMultipleMonetaryLimitsInsteadOfChoosingFirst() {
        var a=rate(1,"0.05",Unit.POINT,List.of(8L,9L));
        var b=rate(2,"0.015",Unit.POINT,List.of());
        var result=calculator.calculate(tx,policy(List.of(a,b),List.of(limit(8,Unit.POINT,1000,0),limit(9,Unit.POINT,2000,0)),ApplicationMode.PRIORITY_ONE,Map.of(1L,new Fallback(2,"EXCESS_PAYMENT_AMOUNT"))));
        assertThat(result.problems()).extracting(CardBenefitCalculator.Problem::code).containsExactly("FALLBACK_LIMIT_AMBIGUOUS");
    }
}
