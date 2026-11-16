package com.yamu.be.recommendation;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import static com.yamu.be.recommendation.DeepDreamPolicySupport.*;

/** Independent in-memory path ledger. No DB writes or contact with a financial API. */
final class DeepDreamReplayState {
    record Opening(Long previous, Long currentPerformance, Long available, Long usedExtra, Boolean normal,
                   Map<Area,Long> areaSpending, Map<Area,Long> deferredBases) { }
    record CardSnapshot(long previous, Map<YearMonth,Long> performance, long available, long usedExtra,
                        boolean normal, Map<Area,Long> areaSpending, Map<Area,Long> deferredBases,
                        long spent, BigDecimal netBenefit) {
        CardSnapshot { performance=Map.copyOf(performance); areaSpending=Map.copyOf(areaSpending); deferredBases=Map.copyOf(deferredBases); }
    }
    private final EnumMap<Kind,CardSnapshot> cards = new EnumMap<>(Kind.class);
    private final Set<String> appliedEvents = new HashSet<>();
    private final YearMonth month;
    DeepDreamReplayState(YearMonth month, Map<Kind,Opening> opening, Map<Kind,Terms> terms) {
        this.month=Objects.requireNonNull(month);
        for (var kind : Kind.values()) {
            Opening o=opening.get(kind);
            if (o==null || o.previous()==null || o.currentPerformance()==null || o.available()==null || o.usedExtra()==null || o.normal()==null
                    || o.areaSpending()==null || o.deferredBases()==null) throw new Unresolved(List.of("OPENING_STATE_UNKNOWN:"+kind));
            if (o.previous()<0 || o.currentPerformance()<0 || o.available()<0 || o.usedExtra()<0 || o.usedExtra()>terms.get(kind).cap(o.previous()))
                throw new IllegalArgumentException("INVALID_OPENING_STATE");
            validateAreas(o.areaSpending()); validateAreas(o.deferredBases());
            cards.put(kind,new CardSnapshot(o.previous(),Map.of(month,o.currentPerformance()),o.available(),o.usedExtra(),o.normal(),o.areaSpending(),o.deferredBases(),0,BigDecimal.ZERO));
        }
    }
    private void validateAreas(Map<Area,Long> amounts) {
        for (var area : Area.values()) if (area!=Area.NONE && (amounts.get(area)==null || amounts.get(area)<0))
            throw new Unresolved(List.of("OPENING_AREA_STATE_UNKNOWN:"+area));
        if (amounts.containsKey(Area.NONE)) throw new IllegalArgumentException("NONE_IS_NOT_DREAM_AREA");
    }
    CardSnapshot snapshot(Kind kind) { return cards.get(kind); }
    Map<Kind,CardSnapshot> snapshots() { return Map.copyOf(cards); }
    void fund(String eventId, Kind kind, long delta) {
        if (eventId==null || appliedEvents.contains(eventId)) throw new IllegalArgumentException("DUPLICATE_OR_MISSING_EVENT");
        var s=snapshot(kind); long after=Math.addExact(s.available(),delta);
        if (after<0) throw new IllegalArgumentException("NEGATIVE_AVAILABILITY");
        cards.put(kind,new CardSnapshot(s.previous(),s.performance(),after,s.usedExtra(),s.normal(),s.areaSpending(),s.deferredBases(),s.spent(),s.netBenefit()));
        appliedEvents.add(eventId);
    }
    void settle(Purchase p, Kind kind, CardBenefitCalculator.Calculation c) {
        if (appliedEvents.contains(p.id())) throw new IllegalArgumentException("DUPLICATE_EVENT");
        if (!YearMonth.from(p.at().atZone(SEOUL)).equals(month)) throw new IllegalArgumentException("TRANSACTION_OUTSIDE_MONTH");
        if (!c.complete() || !c.problems().isEmpty() || !c.unallocatedBenefitGroups().isEmpty()) throw new Unresolved(List.of("SETTLEMENT_NOT_DETERMINATE"));
        var s=snapshot(kind); var f=p.facts().get(kind);
        if (f==null || !f.issues(kind,p.amount()).isEmpty()) throw new Unresolved(List.of("SETTLEMENT_FACTS_UNKNOWN"));
        if (!s.normal() || s.available()<f.debitAmount()) throw new IllegalStateException("CARD_UNAVAILABLE");
        long capUse=0;
        for(var use:c.limitUses()) {
            if(use.limitId()!=1 || use.period()!=BenefitPolicy.Period.MONTHLY || use.unit()!=BenefitPolicy.Unit.POINT)
                throw new IllegalArgumentException("UNEXPECTED_LIMIT_USE");
            capUse=Math.addExact(capUse,use.consumed().longValueExact());
        }
        var performance=new HashMap<>(s.performance()); performance.merge(f.recognitionMonth(),f.recognizedAmount(),Math::addExact);
        var areas=new EnumMap<Area,Long>(Area.class); areas.putAll(s.areaSpending());
        var bases=new EnumMap<Area,Long>(Area.class); bases.putAll(s.deferredBases());
        if(f.area()!=Area.NONE) { areas.merge(f.area(),f.areaSelectionAmount(),Math::addExact); bases.merge(f.area(),f.deferredEligibleAmount(),Math::addExact); }
        cards.put(kind,new CardSnapshot(s.previous(),performance,s.available()-f.debitAmount(),Math.addExact(s.usedExtra(),capUse),s.normal(),areas,bases,
                Math.addExact(s.spent(),f.debitAmount()),s.netBenefit().add(c.netBenefit())));
        appliedEvents.add(p.id());
    }
}
