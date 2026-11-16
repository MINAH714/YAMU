package com.yamu.be.recommendation;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import static com.yamu.be.recommendation.DeepDreamPolicySupport.*;
import static com.yamu.be.recommendation.DeepDreamSensitivitySupport.*;

/** Exhaustive within the declared finite model, never a claim about all real-world uncertainty. */
final class DeepDreamAssumptionSupport {
    record Alternative(String id, Map<Kind,Facts> facts, String rationale) {
        Alternative {
            requireText(id); requireText(rationale); facts=Map.copyOf(facts);
            if(!facts.keySet().equals(EnumSet.allOf(Kind.class))) throw new IllegalArgumentException("BOTH_CARD_FACTS_REQUIRED");
        }
    }
    record Axis(String purchaseId, List<Alternative> alternatives) {
        Axis {
            requireText(purchaseId); alternatives=List.copyOf(alternatives);
            if(alternatives.isEmpty() || alternatives.stream().map(Alternative::id).distinct().count()!=alternatives.size())
                throw new IllegalArgumentException("EMPTY_OR_DUPLICATE_ALTERNATIVES");
        }
    }
    record PolicyAlternative(String id, Rules rules, String rationale) {
        PolicyAlternative { requireText(id); requireText(rationale); Objects.requireNonNull(rules); }
    }
    record Cell(Map<String,String> assumptions, String policyId, Comparison comparison) {
        Cell { assumptions=Map.copyOf(assumptions); }
    }
    record Range(BigDecimal minimum, BigDecimal maximum) { }
    record Report(List<Cell> cells, Range recordedPoints, Range yamuPoints, Range advantage,
                  Set<Integer> advantageSigns, Set<String> selectionSensitiveIds, String coverage) {
        Report { cells=List.copyOf(cells); advantageSigns=Set.copyOf(advantageSigns); selectionSensitiveIds=Set.copyOf(selectionSensitiveIds); }
    }
    private static void requireText(String s) { if(s==null || s.isBlank()) throw new IllegalArgumentException("MISSING_ASSUMPTION_METADATA"); }

    static Report evaluate(Origin origin, List<Purchase> source, String feeTarget, List<Axis> axes,
            List<PolicyAlternative> policies, Map<Kind,AreaAmounts> areaAmounts, YearMonth month,
            Map<Kind,DeepDreamReplayState.Opening> opening, Kind defaultCard) {
        if(origin!=Origin.SYNTHETIC_TEST_ONLY || source.stream().anyMatch(p->p.origin()!=Origin.SYNTHETIC_TEST_ONLY)
                || policies.stream().anyMatch(p->p.rules().origin()!=Origin.SYNTHETIC_TEST_ONLY))
            throw new Unresolved(List.of("REAL_CASE_EXECUTION_NOT_APPROVED"));
        if(policies.isEmpty() || policies.stream().map(PolicyAlternative::id).distinct().count()!=policies.size())
            throw new IllegalArgumentException("EMPTY_OR_DUPLICATE_POLICY_ALTERNATIVES");
        var ids=new HashSet<String>();
        for(var p:source) if(!ids.add(p.id())) throw new IllegalArgumentException("DUPLICATE_PURCHASE");
        var axisIds=new HashSet<String>(); long count=2L*policies.size();
        for(var axis:axes) {
            if(!ids.contains(axis.purchaseId()) || !axisIds.add(axis.purchaseId())) throw new IllegalArgumentException("UNKNOWN_OR_OVERLAPPING_AXIS");
            count=Math.multiplyExact(count,axis.alternatives().size());
            if(count>256) throw new IllegalArgumentException("DECLARED_MATRIX_TOO_LARGE_NO_SAMPLING");
        }
        if(count>256) throw new IllegalArgumentException("DECLARED_MATRIX_TOO_LARGE_NO_SAMPLING");
        var cells=new ArrayList<Cell>();
        expand(source,feeTarget,axes,0,new LinkedHashMap<>(),policies,areaAmounts,month,opening,defaultCard,cells);
        if(cells.size()!=count) throw new IllegalStateException("INCOMPLETE_MATRIX");
        return summarize(cells);
    }
    private static void expand(List<Purchase> source,String feeTarget,List<Axis> axes,int index,Map<String,String> labels,
            List<PolicyAlternative> policies,Map<Kind,AreaAmounts> areaAmounts,YearMonth month,
            Map<Kind,DeepDreamReplayState.Opening> opening,Kind defaultCard,List<Cell> cells) {
        if(index==axes.size()) {
            for(var policy:policies) for(var scenario:Scenario.values())
                cells.add(new Cell(labels,policy.id(),compare(Origin.SYNTHETIC_TEST_ONLY,source,feeTarget,scenario,areaAmounts,
                        policy.rules(),month,opening,defaultCard)));
            return;
        }
        var axis=axes.get(index);
        for(var alternative:axis.alternatives()) {
            var changed=source.stream().map(p->p.id().equals(axis.purchaseId())?
                    new Purchase(p.id(),p.origin(),p.at(),p.amount(),p.actualCard(),alternative.facts()):p).toList();
            var next=new LinkedHashMap<>(labels);next.put(axis.purchaseId(),alternative.id());
            expand(changed,feeTarget,axes,index+1,next,policies,areaAmounts,month,opening,defaultCard,cells);
        }
    }
    static Report summarize(List<Cell> cells) {
        if(cells.isEmpty()) throw new IllegalArgumentException("NO_CELLS");
        var signs=new HashSet<Integer>();var selections=new LinkedHashMap<String,Set<Kind>>();
        var expected=cells.get(0).comparison().yamu().trades().stream().map(t->t.id()).toList();
        for(var cell:cells) {
            var c=cell.comparison();
            if(!c.yamu().trades().stream().map(t->t.id()).toList().equals(expected)
                    || !c.recorded().trades().stream().map(t->t.id()).toList().equals(expected))
                throw new IllegalArgumentException("INCOMPARABLE_TRANSACTION_LISTS");
            signs.add(c.yamuMinusRecordedPoints().signum());
            c.yamu().trades().forEach(t->selections.computeIfAbsent(t.id(),x->new HashSet<>()).add(t.selectedCard()));
        }
        var changed=new HashSet<String>();selections.forEach((id,kinds)->{if(kinds.size()>1)changed.add(id);});
        return new Report(cells,range(cells.stream().map(c->c.comparison().recorded().knownNetBenefit()).toList()),
                range(cells.stream().map(c->c.comparison().yamu().knownNetBenefit()).toList()),
                range(cells.stream().map(c->c.comparison().yamuMinusRecordedPoints()).toList()),signs,changed,
                "DECLARED_CELLS_ONLY_NOT_UNIVERSAL_ROBUSTNESS");
    }
    private static Range range(List<BigDecimal> values) {
        return new Range(values.stream().min(BigDecimal::compareTo).orElseThrow(),values.stream().max(BigDecimal::compareTo).orElseThrow());
    }
}
