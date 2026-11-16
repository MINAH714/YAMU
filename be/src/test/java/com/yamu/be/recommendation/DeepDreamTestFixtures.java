package com.yamu.be.recommendation;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.yamu.be.recommendation.DeepDreamPolicySupport.*;

/** Fabricated edge cases only. NONE of these policy choices resolves actual Deep Dream unknowns. */
final class DeepDreamTestFixtures {
    static final YearMonth MONTH=YearMonth.of(2026,11);
    static Rules rules() {
        return new Rules(Origin.SYNTHETIC_TEST_ONLY,"FABRICATED_TEST_RULES_NOT_OFFICIAL",LocalDate.of(2026,11,1),LocalDate.of(2026,12,1),
                BenefitPolicy.RoundingStage.PER_TRANSACTION,BenefitPolicy.CalculationOrder.LIMIT_THEN_ROUND,true,
                "SELECTED_AREA_AGGREGATE_DOWN","AREA_ENUM_ORDER",true);
    }
    static Map<Area,Long> zeroAreas() {
        var amounts=new EnumMap<Area,Long>(Area.class); for(var area:Area.values()) if(area!=Area.NONE) amounts.put(area,0L);return amounts;
    }
    static Map<Kind,DeepDreamReplayState.Opening> opening() {
        return Map.of(Kind.CREDIT,new DeepDreamReplayState.Opening(2_000_000L,0L,1_000_000L,0L,true,zeroAreas(),zeroAreas()),
                Kind.CHECK,new DeepDreamReplayState.Opening(250_000L,0L,1_000_000L,0L,true,zeroAreas(),zeroAreas()));
    }
    static Facts facts(long amount,Area area) {
        return new Facts(true,area,false,true,amount,amount,MONTH,amount,BigDecimal.ZERO,area==Area.NONE?0L:amount,area==Area.NONE?0L:amount,"FABRICATED_TEST_PURCHASE");
    }
    static Purchase purchase(String id,int day,long amount,Area area,Kind actual) {
        return new Purchase(id,Origin.SYNTHETIC_TEST_ONLY,LocalDate.of(2026,11,day).atTime(12,0).atZone(SEOUL).toInstant(),amount,actual,
                Map.of(Kind.CREDIT,facts(amount,area),Kind.CHECK,facts(amount,area)));
    }
    static Purchase withFacts(Purchase p,Kind kind,Facts facts) {
        var values=new EnumMap<Kind,Facts>(Kind.class);values.putAll(p.facts());values.put(kind,facts);
        return new Purchase(p.id(),p.origin(),p.at(),p.amount(),p.actualCard(),values);
    }
}
