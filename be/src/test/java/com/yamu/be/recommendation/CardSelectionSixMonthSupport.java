package com.yamu.be.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.yamu.be.recommendation.BenefitPolicy.*;
import com.yamu.be.recommendation.BenefitPolicy.Period;
import com.yamu.be.recommendation.RecommendationInput.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** EXP-006 only: calendar/state adapter; no production policy or arithmetic changes. */
final class CardSelectionSixMonthSupport {
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final LocalDate FROM = LocalDate.of(2026,11,1), UNTIL = LocalDate.of(2027,5,1);
    record Purchase(int number, String purpose, String brand, String category, String channel, long amount, Instant at) { }
    record Key(int card, long limit, int version, String period) { }
    record Prepared(BenefitPolicy policy, Transaction transaction, List<RecommendationDataIssue> assemblyIssues) { }
    record Run(String strategy, int seed, BigDecimal total, List<Map<String,Object>> months, List<Map<String,Object>> trades) { }
    interface Provider { Prepared prepare(Purchase purchase, int card, long previous); }

    static List<Purchase> generate(JsonNode spec) {
        var random = new Random(spec.get("generatorSeed").asLong());
        var result = new ArrayList<Purchase>();
        for (int m=0;m<spec.get("months").asInt();m++) {
            var month = YearMonth.parse(spec.get("startMonth").asText()).plusMonths(m);
            for (var group : spec.get("groups")) {
                String schedule=group.get("schedule").asText();var days=new ArrayList<Integer>();
                for(int day=1;day<=month.lengthOfMonth();day++) {
                    var week=month.atDay(day).getDayOfWeek();
                    boolean accepted=switch(schedule) {
                        case "WEEKDAYS","COMMUTE_WEEKDAYS"->week.getValue()<=5;
                        case "SUNDAYS"->week==DayOfWeek.SUNDAY;
                        case "SATURDAYS"->week==DayOfWeek.SATURDAY;
                        case "DAY_10"->day==10;
                        case "DISTINCT_DAYS"->true;
                        default->throw new IllegalArgumentException(schedule);
                    };if(accepted)days.add(day);
                }
                if(schedule.equals("COMMUTE_WEEKDAYS")) {
                    for(int day:days)for(int hour:new int[]{8,18})
                        result.add(new Purchase(result.size()+1,group.get("purpose").asText(),group.get("brands").get(0).asText(),group.get("category").asText(),
                                group.get("channel").asText(),group.get("min").asLong(),month.atDay(day).atTime(hour,15).atZone(SEOUL).toInstant()));
                    continue;
                }
                if(!schedule.equals("SUNDAYS")&&!schedule.equals("DAY_10"))Collections.shuffle(days,random);
                int count=group.has("counts")?group.get("counts").get(m).asInt():group.get("count").asInt();
                if(count>days.size())throw new IllegalArgumentException("INSUFFICIENT_DISTINCT_DAYS");
                for(int n=0;n<count;n++) {
                    int minute=random.nextInt(60);long unit=group.get("unit").asLong();
                    long amount=group.get("min").asLong()+unit*random.nextInt((int)((group.get("max").asLong()-group.get("min").asLong())/unit+1));
                    result.add(new Purchase(result.size()+1,group.get("purpose").asText(),group.get("brands").get(n%group.get("brands").size()).asText(),
                            group.get("category").asText(),group.get("channel").asText(),amount,
                            month.atDay(days.get(n)).atTime(group.get("hour").asInt(),minute).atZone(SEOUL).toInstant()));
                }
            }
            long spending=result.stream().filter(p->YearMonth.from(p.at().atZone(SEOUL)).equals(month)).mapToLong(Purchase::amount).sum();
            if(spending>spec.get("monthlyBudgetCeiling").asLong())throw new IllegalArgumentException("LIFESTYLE_BUDGET_EXCEEDED_NO_RESAMPLING");
        }
        result.sort(Comparator.comparing(Purchase::at).thenComparingInt(Purchase::number));
        var numbered=new ArrayList<Purchase>();
        for(var p:result) numbered.add(new Purchase(numbered.size()+1,p.purpose(),p.brand(),p.category(),p.channel(),p.amount(),p.at()));
        return List.copyOf(numbered);
    }

    static BenefitPolicy extend(BenefitPolicy p) {
        var benefits=p.benefits().stream().map(b->{
            var c=b.conditions();
            if(c.validFrom()==null||c.validUntil()==null) throw new IllegalStateException("UNKNOWN_VALIDITY");
            var extended=new Conditions(c.verified(),c.currentVersionVerified(),FROM,UNTIL,c.targets(),c.requiredFacts(),c.excludedFacts(),c.schedule(),c.targetGroups());
            return new Benefit(b.id(),b.version(),b.name(),b.type(),b.calculationType(),b.rateOrFixedValue(),b.unit(),b.pointKrwRate(),b.baseAmount(),
                    b.minimumPayment(),b.minimumPerformance(),extended,b.rounding(),b.calculationOrder(),b.limitIds());
        }).toList();
        var traces=new ArrayList<>(p.simulationRules());
        traces.add(new SimulationRule("EXP006_FROZEN_NOVEMBER_POLICY",1,"Approved test-only constant policy across six calendar months",null,null,List.of("validity=2026-11-01..2027-05-01")));
        return new BenefitPolicy(p.complete(),p.previousMonthPerformance(),p.additionalCost(),benefits,p.limits(),p.allowedCombinations(),p.applicationGroups(),p.fallbacks(),traces);
    }

    static final class Ledger {
        YearMonth month;
        long[] previous={400000,400000}, performance={0,0}, spent={0,0};
        final Map<Key,Long> used=new HashMap<>();
        void enter(YearMonth next) {
            if(month==null) { month=next; return; }
            if(next.equals(month)) return;
            if(!next.equals(month.plusMonths(1))) throw new IllegalArgumentException("NONCONSECUTIVE_MONTH");
            previous=performance.clone(); performance=new long[2]; spent=new long[2]; month=next;
        }
        Key key(int card,Limit l,Instant at) {
            var date=at.atZone(SEOUL).toLocalDate();
            return new Key(card,l.id(),l.version(),switch(l.period()) {
                case MONTHLY -> YearMonth.from(date).toString(); case DAILY -> date.toString(); case PER_TRANSACTION -> "TRANSACTION";
            });
        }
        BenefitPolicy overlay(int card,Prepared prepared) {
            var p=prepared.policy();
            var limits=p.limits().stream().map(l->new Limit(l.id(),l.version(),l.unit(),l.period(),l.maximum(),
                    l.period()==Period.PER_TRANSACTION?0L:used.getOrDefault(key(card,l,prepared.transaction().occurredAt()),0L),l.countMode(),l.consumeOnZero())).toList();
            return new BenefitPolicy(p.complete(),previous[card],p.additionalCost(),p.benefits(),limits,p.allowedCombinations(),p.applicationGroups(),p.fallbacks(),p.simulationRules());
        }
    }

    static Run run(String strategy,int seed,List<Purchase> purchases,Provider provider,
                   List<CardSelectionExperimentSupport.Card> cards,RecommendationRecognitionResolver recognition,long user) {
        var ledger=new Ledger(); var random=new Random(seed); var selector=new RecommendationSelector();
        var trades=new ArrayList<Map<String,Object>>(); var monthly=new ArrayList<Map<String,Object>>();
        BigDecimal total=BigDecimal.ZERO, monthBenefit=BigDecimal.ZERO; long monthSpend=0;
        for(var p:purchases) {
            var month=YearMonth.from(p.at().atZone(SEOUL));
            if(ledger.month!=null&&!ledger.month.equals(month)) {
                monthly.add(monthRow(ledger,monthBenefit,monthSpend)); monthBenefit=BigDecimal.ZERO;monthSpend=0;
            }
            ledger.enter(month);
            var candidates=new ArrayList<CardCandidate>();var assembly=new ArrayList<RecommendationDataIssue>();
            for(int i=0;i<2;i++) {
                var prepared=provider.prepare(p,i,ledger.previous[i]);assembly.addAll(prepared.assemblyIssues());
                var policy=ledger.overlay(i,prepared);var card=cards.get(i);
                var issues=new ArrayList<RecommendationDataIssue>();
                var progress=recognition.resolve(card.row().getId(),card.product(),prepared.transaction(),policy,issues);
                assertThat(issues).as("recognition %s %s",p.number(),i).isEmpty();
                assertThat(progress.period()).isEqualTo(month);assertThat(progress.recognizedAmount()).isNotNull();
                assertThat(progress.minimumAmount()).isNotNull();
                assertThat(ledger.spent[i]+p.amount()).isLessThanOrEqualTo(10000000);
                candidates.add(new CardCandidate(card.row().getId(),user,CardType.CREDIT,card.row().getRegistrationStatus(),card.row().getIssuerStatus(),
                        Availability.AVAILABLE,null,policy,new PerformanceProgress(month,ledger.performance[i],progress.minimumAmount(),progress.recognizedAmount()),i,card.row().getRegisteredAt(),prepared.transaction()));
            }
            var result=selector.select(new RecommendationInput((long)p.number(),user,candidates.get(0).calculationTransaction(),cards.get(0).row().getId(),candidates));
            assertThat(result.status()).isEqualTo(RecommendationSelector.Status.READY);
            assertThat(result.evaluatedCards()).hasSize(2).allSatisfy(e->{
                assertThat(e.status()).as("transaction %s: %s",p.number(),e).isEqualTo(RecommendationSelector.CardStatus.CALCULATED);
                assertThat(e.calculation().complete()).isTrue();assertThat(e.calculation().problems()).isEmpty();
                assertThat(e.calculation().unallocatedBenefitGroups()).isEmpty();
            });
            int index=switch(strategy) {
                case "FIXED_FIRST"->0;case "FIXED_TOKTOK"->1;case "CATEGORY"->p.category().equals("COFFEE_SHOP")?1:0;
                case "RANDOM"->random.nextInt(2);
                case "YAMU"->result.selectedUserCardId().equals(cards.get(0).row().getId())?0:1;
                default->throw new IllegalArgumentException(strategy);
            };
            var chosen=candidates.get(index);
            var calc=result.evaluatedCards().stream().filter(e->e.userCardId()==chosen.userCardId()).findFirst().orElseThrow().calculation();
            var row=new LinkedHashMap<String,Object>();
            row.put("transaction",p);row.put("selectedIndex",index);row.put("selectedCard",cards.get(index).product().getProductName());
            row.put("reason",strategy.equals("YAMU")?result.selectionReason():strategy);
            row.put("benefitKrw",calc.netBenefit());row.put("candidateEvaluations",result.evaluatedCards());
            row.put("assemblyIssues",assembly);row.put("previousPerformance",ledger.previous.clone());row.put("performanceBefore",ledger.performance.clone());
            row.put("candidateRecognizedAmounts",candidates.stream().map(c->c.performance().recognizedAmount()).toList());
            row.put("candidateLimitsBefore",candidates.stream().map(c->c.policy().limits()).toList());
            var changes=new ArrayList<Map<String,Object>>();
            for(var use:calc.limitUses()) {
                var l=chosen.policy().limits().stream().filter(x->x.id()==use.limitId()).findFirst().orElseThrow();
                assertThat(use.remainingBefore()).isEqualByComparingTo(BigDecimal.valueOf(l.maximum()-l.used()));
                assertThat(use.consumed()).isBetween(BigDecimal.ZERO,use.remainingBefore());
                long after=Math.addExact(l.used(),use.consumed().longValueExact());
                if(l.period()!=Period.PER_TRANSACTION) ledger.used.put(ledger.key(index,l,p.at()),after);
                changes.add(Map.of("limitId",l.id(),"period",ledger.key(index,l,p.at()).period(),"maximum",l.maximum(),"usedBefore",l.used(),"consumed",use.consumed(),"usedAfter",after));
            }
            ledger.performance[index]=Math.addExact(ledger.performance[index],chosen.performance().recognizedAmount());
            ledger.spent[index]=Math.addExact(ledger.spent[index],p.amount());
            row.put("performanceAfter",ledger.performance.clone());row.put("spentThisMonthAfter",ledger.spent.clone());row.put("limitChanges",changes);
            total=total.add(calc.netBenefit());monthBenefit=monthBenefit.add(calc.netBenefit());monthSpend+=p.amount();trades.add(row);
        }
        monthly.add(monthRow(ledger,monthBenefit,monthSpend));
        return new Run(strategy,seed,total,List.copyOf(monthly),List.copyOf(trades));
    }
    static Map<String,Object> monthRow(Ledger l,BigDecimal benefit,long spending) {
        return Map.of("month",l.month.toString(),"totalKrw",benefit,"spending",spending,"previousPerformance",l.previous.clone(),
                "recognizedPerformance",l.performance.clone(),"cardSpending",l.spent.clone());
    }
}
