package com.yamu.be.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.card.domain.CardProduct;
import com.yamu.be.card.domain.UserCard;
import com.yamu.be.recommendation.RecommendationInput.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Test-only state ledger; product rules and benefit arithmetic remain in the existing engine. */
final class MonthlyCardSelectionSimulationSupport {
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    record Card(UserCard row, CardProduct product) { }
    record Prepared(Card card, BenefitPolicy policy, Transaction transaction) { }
    record Purchase(int number, String brand, Transaction transaction, List<Prepared> cards) { }
    record Key(long card, long limit, int version, String period) { }
    record Evaluation(RecommendationSelector.Result result, List<CardCandidate> candidates) { }
    record Run(String strategy, int seed, BigDecimal total, List<Map<String, Object>> trades,
               List<Map<String, Object>> limits, Map<Long, Long> performance, Map<Long, Long> spent) { }

    static final class Ledger {
        final Map<Key, Long> used = new HashMap<>();
        final Map<Long, Long> performance = new HashMap<>();
        final Map<Long, Long> spent = new HashMap<>();
        Key key(long card, BenefitPolicy.Limit limit, Transaction tx) {
            var date = tx.occurredAt().atZone(SEOUL).toLocalDate();
            String period = switch (limit.period()) {
                case MONTHLY -> YearMonth.from(date).toString();
                case DAILY -> date.toString();
                case PER_TRANSACTION -> "TRANSACTION";
            };
            return new Key(card, limit.id(), limit.version(), period);
        }
        BenefitPolicy overlay(Prepared prepared) {
            var p = prepared.policy();
            var limits = p.limits().stream().map(l -> new BenefitPolicy.Limit(l.id(), l.version(), l.unit(),
                    l.period(), l.maximum(), l.period() == BenefitPolicy.Period.PER_TRANSACTION ? 0L
                    : used.getOrDefault(key(prepared.card().row().getId(), l, prepared.transaction()), 0L),
                    l.countMode(), l.consumeOnZero())).toList();
            return new BenefitPolicy(p.complete(), p.previousMonthPerformance(), p.additionalCost(), p.benefits(),
                    limits, p.allowedCombinations(), p.applicationGroups(), p.fallbacks(), p.simulationRules());
        }
    }

    static Evaluation evaluate(Purchase purchase, Ledger ledger, RecommendationRecognitionResolver recognition,
                               long user, long defaultCard, long credit) {
        var candidates = new ArrayList<CardCandidate>();
        for (var prepared : purchase.cards()) {
            var row = prepared.card().row();
            var policy = ledger.overlay(prepared);
            var issues = new ArrayList<RecommendationDataIssue>();
            var recognized = recognition.resolve(row.getId(), prepared.card().product(), prepared.transaction(), policy, issues);
            assertThat(issues).as("recognition purchase %s card %s", purchase.number(), row.getId()).isEmpty();
            assertThat(recognized.period()).isEqualTo(YearMonth.of(2026, 11));
            assertThat(recognized.recognizedAmount()).isNotNull();
            assertThat(recognized.minimumAmount()).isNotNull();
            assertThat(ledger.spent.getOrDefault(row.getId(), 0L) + purchase.transaction().amount()).isLessThanOrEqualTo(credit);
            candidates.add(new CardCandidate(row.getId(), user, CardType.CREDIT, row.getRegistrationStatus(),
                    row.getIssuerStatus(), Availability.AVAILABLE, null, policy,
                    new PerformanceProgress(recognized.period(), ledger.performance.getOrDefault(row.getId(), 0L),
                            recognized.minimumAmount(), recognized.recognizedAmount()),
                    candidates.size(), row.getRegisteredAt(), prepared.transaction()));
        }
        var result = new RecommendationSelector().select(new RecommendationInput((long) purchase.number(), user,
                purchase.transaction(), defaultCard, candidates));
        assertThat(result.status()).as("purchase %s", purchase.number()).isEqualTo(RecommendationSelector.Status.READY);
        assertThat(result.evaluatedCards()).hasSize(2).allSatisfy(e -> {
            assertThat(e.status()).isEqualTo(RecommendationSelector.CardStatus.CALCULATED);
            assertThat(e.calculation().complete()).isTrue();
            assertThat(e.calculation().problems()).isEmpty();
            assertThat(e.calculation().unallocatedBenefitGroups()).isEmpty();
        });
        return new Evaluation(result, List.copyOf(candidates));
    }

    static Run run(String strategy, int seed, List<Purchase> purchases, RecommendationRecognitionResolver recognition,
                   long user, long defaultCard, long credit, Map<String, Integer> categoryRule) {
        var ledger = new Ledger();
        var random = new Random(seed);
        var trades = new ArrayList<Map<String, Object>>();
        var limitRows = new ArrayList<Map<String, Object>>();
        BigDecimal total = BigDecimal.ZERO;
        for (var purchase : purchases) {
            var evaluation = evaluate(purchase, ledger, recognition, user, defaultCard, credit);
            int index = switch (strategy) {
                case "FIXED_FIRST" -> 0;
                case "FIXED_TOKTOK" -> 1;
                case "CATEGORY" -> Objects.requireNonNull(categoryRule.get(purchase.transaction().merchantCategoryCode()));
                case "RANDOM" -> random.nextInt(2);
                case "YAMU" -> evaluation.result().selectedUserCardId().equals(purchase.cards().get(0).card().row().getId()) ? 0 : 1;
                default -> throw new IllegalArgumentException(strategy);
            };
            var candidate = evaluation.candidates().get(index);
            long id = candidate.userCardId();
            var calculation = evaluation.result().evaluatedCards().stream().filter(e -> e.userCardId() == id).findFirst().orElseThrow().calculation();
            var row = new LinkedHashMap<String, Object>();
            row.put("strategy", strategy); row.put("seed", seed); row.put("transaction", purchase.number());
            row.put("timeKst", purchase.transaction().occurredAt().atZone(SEOUL).toString());
            row.put("brand", purchase.brand()); row.put("amount", purchase.transaction().amount());
            row.put("selectedCard", purchase.cards().get(index).card().product().getProductName());
            row.put("benefitKrw", calculation.netBenefit());
            row.put("benefits", calculation.benefits());
            row.put("candidateEvaluations", evaluation.result().evaluatedCards());
            row.put("selectionReason", strategy.equals("YAMU") ? evaluation.result().selectionReason() : strategy);
            row.put("performanceBefore", ledger.performance.getOrDefault(id, 0L));
            row.put("recognizedAmount", candidate.performance().recognizedAmount());
            row.put("performanceMonth", candidate.performance().period().toString());
            ledger.performance.merge(id, candidate.performance().recognizedAmount(), Math::addExact);
            ledger.spent.merge(id, purchase.transaction().amount(), Math::addExact);
            row.put("performanceAfter", ledger.performance.get(id));
            row.put("availableCreditAfter", credit - ledger.spent.get(id));
            for (var use : calculation.limitUses()) {
                var limit = candidate.policy().limits().stream().filter(l -> l.id() == use.limitId()).findFirst().orElseThrow();
                assertThat(use.remainingBefore()).isEqualByComparingTo(BigDecimal.valueOf(limit.maximum() - limit.used()));
                assertThat(use.consumed()).isBetween(BigDecimal.ZERO, use.remainingBefore());
                long after = Math.addExact(limit.used(), use.consumed().longValueExact());
                var key = ledger.key(id, limit, purchase.transaction());
                if (limit.period() != BenefitPolicy.Period.PER_TRANSACTION) ledger.used.put(key, after);
                var change = new LinkedHashMap<String, Object>();
                change.put("strategy", strategy); change.put("seed", seed); change.put("transaction", purchase.number());
                change.put("card", row.get("selectedCard")); change.put("limitId", limit.id());
                change.put("unit", limit.unit()); change.put("period", key.period());
                change.put("maximum", limit.maximum()); change.put("usedBefore", limit.used());
                change.put("consumed", use.consumed()); change.put("usedAfter", after);
                limitRows.add(change);
            }
            total = total.add(calculation.netBenefit()); trades.add(row);
        }
        assertThat(ledger.spent.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(309_000L);
        return new Run(strategy, seed, total, List.copyOf(trades), List.copyOf(limitRows), Map.copyOf(ledger.performance), Map.copyOf(ledger.spent));
    }
}
