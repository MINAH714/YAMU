package com.yamu.be.recommendation;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import static com.yamu.be.recommendation.DeepDreamPolicySupport.*;
import static com.yamu.be.recommendation.DeepDreamCaseStudySupport.*;

/** Preparation only. Never reads private input and never permits REAL_CASE replay. */
final class DeepDreamSensitivitySupport {
    enum Scenario {
        A(10_280), B(10_040);
        final long eligibleBase;
        Scenario(long eligibleBase) { this.eligibleBase=eligibleBase; }
    }
    // An explicit independent input: changing the points base must not silently change these amounts.
    record AreaAmounts(long selectionAmount, long deferredBase, String evidence) {
        AreaAmounts {
            if(selectionAmount<0 || deferredBase<0 || selectionAmount>10_280 || deferredBase>10_280
                    || evidence==null || evidence.isBlank()) throw new Unresolved(List.of("AREA_AMOUNTS_REQUIRE_EVIDENCE"));
        }
    }
    record Comparison(Scenario scenario, Run recorded, Run yamu, BigDecimal yamuMinusRecordedPoints) { }
    record Pair(Comparison a, Comparison b, BigDecimal recordedBMinusA, BigDecimal yamuBMinusA,
                BigDecimal advantageBMinusA, List<String> yamuSelectionChangedIds) { }

    static List<Purchase> overlay(List<Purchase> source, String targetId, Scenario scenario,
                                  Map<Kind,AreaAmounts> areaAmounts) {
        if(source.stream().anyMatch(p->p.origin()!=Origin.SYNTHETIC_TEST_ONLY))
            throw new Unresolved(List.of("REAL_CASE_EXECUTION_NOT_APPROVED"));
        if(source.stream().filter(p->p.id().equals(targetId)).count()!=1)
            throw new IllegalArgumentException("TARGET_MUST_BE_UNIQUE");
        var result=new ArrayList<Purchase>();
        for(var p:source) {
            var facts=new EnumMap<Kind,Facts>(Kind.class);
            for(var kind:Kind.values()) {
                var f=p.facts().get(kind);
                if(f==null) throw new Unresolved(List.of("CARD_FACTS_MISSING"));
                boolean target=p.id().equals(targetId);
                var a=target?areaAmounts.get(kind):null;
                if(target && (p.amount()!=10_280 || f.area()!=Area.ABROAD))
                    throw new IllegalArgumentException("TARGET_AMOUNT_OR_AREA_MISMATCH");
                if(target && a==null) throw new Unresolved(List.of("AREA_AMOUNTS_REQUIRE_EVIDENCE"));
                facts.put(kind,new Facts(f.pointEligible(),f.area(),f.bonusEligible(),f.specialBenefitsAbsent(),
                        target?Long.valueOf(scenario.eligibleBase):f.benefitBaseAmount(), f.recognizedAmount(),f.recognitionMonth(),
                        p.amount(),BigDecimal.ZERO,target?Long.valueOf(a.selectionAmount()):f.areaSelectionAmount(),
                        target?Long.valueOf(a.deferredBase()):f.deferredEligibleAmount(),
                        f.evidence()==null || f.evidence().isBlank()?null:f.evidence()+"; POINTS_ONLY_COST_EXCLUDED; MODEL_DEBIT_EQUALS_SOURCE_AMOUNT"
                                +(target?"; SENSITIVITY_"+scenario+"; "+a.evidence():"")));
            }
            result.add(new Purchase(p.id(),p.origin(),p.at(),p.amount(),p.actualCard(),facts));
        }
        return List.copyOf(result);
    }

    static Comparison compare(Origin origin, List<Purchase> source, String targetId, Scenario scenario,
            Map<Kind,AreaAmounts> areaAmounts, Rules rules, YearMonth month,
            Map<Kind,DeepDreamReplayState.Opening> opening, Kind defaultCard) {
        if(origin!=Origin.SYNTHETIC_TEST_ONLY || rules.origin()!=Origin.SYNTHETIC_TEST_ONLY)
            throw new Unresolved(List.of("REAL_CASE_EXECUTION_NOT_APPROVED"));
        var purchases=overlay(source,targetId,scenario,areaAmounts);
        long modeledFunds=0;
        for(var p:purchases) modeledFunds=Math.addExact(modeledFunds,p.amount());
        var modeledOpening=new EnumMap<Kind,DeepDreamReplayState.Opening>(Kind.class);
        for(var kind:Kind.values()) {
            var o=opening.get(kind);
            if(o==null) throw new Unresolved(List.of("OPENING_BENEFIT_STATE_UNKNOWN"));
            // Availability is an approved model assumption, not reconstructed historical funds.
            // Benefit usage, performance and area history are never filled with zero here.
            modeledOpening.put(kind,new DeepDreamReplayState.Opening(o.previous(),o.currentPerformance(),modeledFunds,
                    o.usedExtra(),true,o.areaSpending(),o.deferredBases()));
        }
        var actual=run(origin,Strategy.RECORDED_ACTUAL,purchases,rules,month,modeledOpening,defaultCard,List.of());
        var yamu=run(origin,Strategy.YAMU_KNOWN_TRANSACTION_BENEFIT,purchases,rules,month,modeledOpening,defaultCard,List.of());
        return new Comparison(scenario,actual,yamu,yamu.knownNetBenefit().subtract(actual.knownNetBenefit()));
    }

    static Pair pair(Comparison a, Comparison b) {
        if(a.scenario()!=Scenario.A || b.scenario()!=Scenario.B) throw new IllegalArgumentException("EXPECTED_A_THEN_B");
        var at=a.yamu().trades(); var bt=b.yamu().trades();
        if(at.size()!=bt.size()) throw new IllegalArgumentException("DIFFERENT_TRANSACTION_LISTS");
        var changed=new ArrayList<String>();
        for(int i=0;i<at.size();i++) {
            if(!at.get(i).id().equals(bt.get(i).id())) throw new IllegalArgumentException("DIFFERENT_TRANSACTION_ORDER");
            if(at.get(i).selectedCard()!=bt.get(i).selectedCard()) changed.add(at.get(i).id());
        }
        return new Pair(a,b,b.recorded().knownNetBenefit().subtract(a.recorded().knownNetBenefit()),
                b.yamu().knownNetBenefit().subtract(a.yamu().knownNetBenefit()),
                b.yamuMinusRecordedPoints().subtract(a.yamuMinusRecordedPoints()),List.copyOf(changed));
    }
}
