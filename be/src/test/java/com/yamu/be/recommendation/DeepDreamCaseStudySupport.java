package com.yamu.be.recommendation;

import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import com.yamu.be.card.domain.CardRegistrationStatus;
import com.yamu.be.card.domain.CardIssuerStatus;
import static com.yamu.be.recommendation.DeepDreamPolicySupport.*;

/** Offline runner infrastructure. Real-case execution is deliberately disabled in this release. */
final class DeepDreamCaseStudySupport {
    enum Strategy { RECORDED_ACTUAL, YAMU_KNOWN_TRANSACTION_BENEFIT }
    record Funding(String id, String beforePurchaseId, Kind kind, long delta) { }
    record Trade(String id, Kind actualCard, Kind selectedCard, Object reason,
                 List<RecommendationSelector.CardEvaluation> candidates,
                 CardBenefitCalculator.Calculation selectedCalculation,
                 Map<Kind,DeepDreamReplayState.CardSnapshot> before,
                 Map<Kind,DeepDreamReplayState.CardSnapshot> after) { }
    record Run(Strategy strategy, BigDecimal knownNetBenefit, String deferredStatus, List<Trade> trades,
               Map<Kind,DeepDreamReplayState.CardSnapshot> finalState) { }

    static Run run(Origin origin, Strategy strategy, List<Purchase> purchases, Rules rules, YearMonth month,
                   Map<Kind,DeepDreamReplayState.Opening> opening, Kind defaultCard, List<Funding> funding) {
        // Not configurable: changing a JSON approval flag cannot accidentally execute the private case.
        if(origin!=Origin.SYNTHETIC_TEST_ONLY || rules.origin()!=Origin.SYNTHETIC_TEST_ONLY
                || purchases.stream().anyMatch(p->p.origin()!=Origin.SYNTHETIC_TEST_ONLY))
            throw new Unresolved(List.of("REAL_CASE_EXECUTION_NOT_APPROVED"));
        if(defaultCard==null) throw new Unresolved(List.of("DEFAULT_CARD_UNKNOWN"));
        if(purchases.isEmpty()) throw new IllegalArgumentException("EMPTY_PURCHASES");
        var ids=new HashSet<String>(); Instant last=null;
        for(var p:purchases) {
            if(!ids.add(p.id())) throw new IllegalArgumentException("DUPLICATE_PURCHASE");
            if(last!=null && p.at().isBefore(last)) throw new IllegalArgumentException("UNORDERED_PURCHASES");
            if(!YearMonth.from(p.at().atZone(SEOUL)).equals(month)) throw new IllegalArgumentException("TRANSACTION_OUTSIDE_MONTH");
            last=p.at();
        }
        var fundingIds=new HashSet<String>();
        for(var f:funding) if(!ids.contains(f.beforePurchaseId()) || ids.contains(f.id()) || !fundingIds.add(f.id())) throw new IllegalArgumentException("INVALID_FUNDING_EVENT");
        var terms=terms(); var state=new DeepDreamReplayState(month,opening,terms);
        var trades=new ArrayList<Trade>(); BigDecimal total=BigDecimal.ZERO;
        for(var p:purchases) {
            funding.stream().filter(f->f.beforePurchaseId().equals(p.id())).forEach(f->state.fund(f.id(),f.kind(),f.delta()));
            var before=state.snapshots(); var candidates=new ArrayList<RecommendationInput.CardCandidate>();
            for(var kind:Kind.values()) {
                var s=state.snapshot(kind); var policy=policy(terms.get(kind),rules,p,s); var f=p.facts().get(kind);
                var availability=s.available()>=f.debitAmount() ? RecommendationInput.Availability.AVAILABLE : RecommendationInput.Availability.UNAVAILABLE;
                candidates.add(new RecommendationInput.CardCandidate(kind.ordinal()+1L,1,RecommendationInput.CardType.valueOf(kind.name()),
                        CardRegistrationStatus.ACTIVE,s.normal()?CardIssuerStatus.NORMAL:CardIssuerStatus.SUSPENDED,availability,
                        availability==RecommendationInput.Availability.AVAILABLE?null:"INSUFFICIENT_AVAILABLE_FUNDS",policy,
                        new RecommendationInput.PerformanceProgress(f.recognitionMonth(),s.performance().getOrDefault(f.recognitionMonth(),0L),terms.get(kind).minimum(),f.recognizedAmount()),
                        kind.ordinal(),LocalDateTime.of(2026,1,1,0,0),transaction(p)));
            }
            var result=new RecommendationSelector().select(new RecommendationInput((long)trades.size()+1,1,transaction(p),defaultCard.ordinal()+1L,candidates));
            if(result.status()!=RecommendationSelector.Status.READY) throw new Unresolved(List.of("RECOMMENDATION_NOT_COMPLETE:"+result.status()));
            Kind selected=strategy==Strategy.RECORDED_ACTUAL?p.actualCard():Kind.values()[Math.toIntExact(result.selectedUserCardId()-1)];
            var evaluation=result.evaluatedCards().stream().filter(e->e.userCardId()==selected.ordinal()+1L).findFirst().orElseThrow();
            if(evaluation.status()!=RecommendationSelector.CardStatus.CALCULATED) throw new Unresolved(List.of("SELECTED_CARD_NOT_PAYABLE_OR_COMPUTABLE"));
            state.settle(p,selected,evaluation.calculation()); total=total.add(evaluation.calculation().netBenefit());
            trades.add(new Trade(p.id(),p.actualCard(),selected,strategy==Strategy.RECORDED_ACTUAL?"RECORDED_ACTUAL":result.selectionReason(),result.evaluatedCards(),evaluation.calculation(),before,state.snapshots()));
        }
        return new Run(strategy,total,"UNRESOLVED_MONTH_NOT_CLOSED",List.copyOf(trades),state.snapshots());
    }
}
