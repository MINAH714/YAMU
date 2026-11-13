package com.yamu.be.recommendation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import static com.yamu.be.recommendation.DeepDreamPolicySupport.*;

/** Read-only observation-end calculation. Never feeds a past recommendation or mutates its cap ledger. */
final class DeepDreamDeferredSettlement {
    enum Mode { INTERIM, VIRTUAL_OBSERVATION_END }
    record Result(String status, Mode mode, Area selectedArea, BigDecimal points, Long remainingBefore,
                  Long remainingAfter, List<String> issues) { }
    static Result evaluate(Mode mode, Terms terms, Rules rules, DeepDreamReplayState.CardSnapshot state) {
        if(mode==Mode.INTERIM) return new Result("UNRESOLVED_MONTH_NOT_CLOSED",mode,null,null,null,null,List.of("FUTURE_CONSUMPTION_NOT_OBSERVED"));
        var issues=new ArrayList<>(rules.immediateIssues());
        if(!"SELECTED_AREA_AGGREGATE_DOWN".equals(rules.deferredRounding())) issues.add("DEFERRED_ROUNDING_UNCONFIRMED_OR_UNSUPPORTED");
        long maximum=state.areaSpending().values().stream().mapToLong(Long::longValue).max().orElseThrow();
        var tied=state.areaSpending().entrySet().stream().filter(e->e.getValue()==maximum).map(Map.Entry::getKey).sorted().toList();
        if(maximum>0 && tied.size()>1 && !"AREA_ENUM_ORDER".equals(rules.tieRule())) issues.add("TOP_AREA_TIE_UNCONFIRMED");
        if(!issues.isEmpty()) return new Result("BLOCKED",mode,null,null,null,null,List.copyOf(issues));
        long remaining=terms.cap(state.previous())-state.usedExtra();
        if(remaining<0) throw new IllegalArgumentException("NEGATIVE_SHARED_REMAINDER");
        if(maximum==0 || state.previous()<terms.minimum()) return new Result("VIRTUAL_ONLY",mode,null,BigDecimal.ZERO,remaining,remaining,List.of());
        Area selected=tied.get(0);
        BigDecimal raw=BigDecimal.valueOf(state.deferredBases().get(selected)).multiply(terms.deferred());
        BigDecimal points=raw.min(BigDecimal.valueOf(remaining)).setScale(0,RoundingMode.DOWN);
        return new Result("VIRTUAL_ONLY",mode,selected,points,remaining,remaining-points.longValueExact(),List.of());
    }
}
