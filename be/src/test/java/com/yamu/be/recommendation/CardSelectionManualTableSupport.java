package com.yamu.be.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.card.domain.CardProduct;
import com.yamu.be.card.domain.UserCard;
import com.yamu.be.recommendation.RecommendationInput.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Test-only state ledger; product rules and benefit arithmetic remain in the existing engine. */
final class CardSelectionManualTableSupport {
    static com.fasterxml.jackson.databind.JsonNode table;
    static Map<Long, String> limitCodes;
    record Estimate(String product, BigDecimal raw, BigDecimal amount, Map<String, Long> remaining, String reason) { }

    /** Independent human-readable table arithmetic: no calculator, selector, or engine result argument. */
    static Estimate tableEstimate(int cardIndex, String brand, long amount, Long previous, Map<String, Long> usage) {
        if (amount <= 0 || previous == null || !table.get("scope").has(brand)) throw new IllegalArgumentException("Unknown manual-table input");
        var row = table.get("cards").get(cardIndex);
        if (row == null) throw new IllegalArgumentException("Unknown manual-table card");
        if (cardIndex == 0 && previous >= 500000) throw new IllegalArgumentException("First-card tier outside frozen table");
        var remaining = new LinkedHashMap<String, Long>();
        for (var limit : row.get("limits")) {
            String code = limit.get("code").asText();
            Long used = usage.get(code);
            if (used == null || used < 0 || used > limit.get("maximum").asLong()) throw new IllegalArgumentException("Unknown/invalid usage: " + code);
            remaining.put(code, limit.get("maximum").asLong() - used);
        }
        String name = row.get("product").asText();
        boolean applicable = false;
        for (var b : row.get("brands")) applicable |= b.asText().equals(brand);
        if (!applicable) return new Estimate(name, BigDecimal.ZERO, BigDecimal.ZERO, remaining, "TARGET_NOT_APPLICABLE");
        if (previous < row.get("minimumPerformance").asLong()) return new Estimate(name, BigDecimal.ZERO, BigDecimal.ZERO, remaining, "BELOW_REQUIRED_PERFORMANCE");
        if (amount < row.get("minimumPayment").asLong()) return new Estimate(name, BigDecimal.ZERO, BigDecimal.ZERO, remaining, "BELOW_MINIMUM_PAYMENT");
        BigDecimal raw = BigDecimal.valueOf(amount).multiply(new BigDecimal(row.get("rate").asText()));
        BigDecimal capped = raw;
        if (row.has("transactionCap")) capped = capped.min(BigDecimal.valueOf(row.get("transactionCap").asLong()));
        for (long available : remaining.values()) capped = capped.min(BigDecimal.valueOf(available));
        BigDecimal estimate = capped.setScale(0, java.math.RoundingMode.DOWN).multiply(new BigDecimal(row.get("krwPerUnit").asText()));
        return new Estimate(name, raw, estimate, remaining, estimate.signum() > 0 ? "POSITIVE_ESTIMATE" : "ZERO_AFTER_LIMIT_OR_ROUNDING");
    }

    static int chooseManual(List<Estimate> estimates, int defaultIndex) {
        BigDecimal max = estimates.stream().map(Estimate::amount).max(BigDecimal::compareTo).orElseThrow();
        if (estimates.get(defaultIndex).amount().compareTo(max) == 0) return defaultIndex;
        for (int i = 0; i < estimates.size(); i++) if (estimates.get(i).amount().compareTo(max) == 0) return i;
        throw new IllegalStateException("No maximum");
    }

    /** Adapter supplies only frozen purchase facts and already accumulated usage, never expected payout. */
    static List<Estimate> manualEstimates(Purchase purchase, Ledger ledger) {
        var scope = table.get("scope").get(purchase.brand());
        if (scope == null || !scope.get("category").asText().equals(purchase.transaction().merchantCategoryCode())
                || !Boolean.TRUE.equals(purchase.transaction().facts().get("CHANNEL:" + scope.get("channel").asText())))
            throw new IllegalArgumentException("Purchase outside frozen manual-table scope");
        var result = new ArrayList<Estimate>();
        for (int i = 0; i < purchase.cards().size(); i++) {
            var p = purchase.cards().get(i);
            assertThat(p.card().product().getProductName()).isEqualTo(table.get("cards").get(i).get("product").asText());
            var usage = new HashMap<String, Long>();
            for (var limit : p.policy().limits()) {
                if (limit.period() != BenefitPolicy.Period.PER_TRANSACTION)
                    usage.put(limitCodes.get(limit.id()), ledger.used.getOrDefault(ledger.key(p.card().row().getId(), limit, p.transaction()), 0L));
            }
            result.add(tableEstimate(i, purchase.brand(), purchase.transaction().amount(), p.policy().previousMonthPerformance(), usage));
        }
        return List.copyOf(result);
    }
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
        assertThat(result.evaluatedCards()).hasSize(purchase.cards().size()).allSatisfy(e -> {
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
            // Compute AND choose before invoking engine evaluation. The engine remains settlement/audit only for MANUAL_TABLE.
            List<Estimate> estimates = strategy.equals("MANUAL_TABLE") ? manualEstimates(purchase, ledger) : List.of();
            int defaultIndex = java.util.stream.IntStream.range(0, purchase.cards().size())
                    .filter(i -> purchase.cards().get(i).card().row().getId() == defaultCard).findFirst().orElseThrow();
            int manualIndex = estimates.isEmpty() ? -1 : chooseManual(estimates, defaultIndex);
            var evaluation = evaluate(purchase, ledger, recognition, user, defaultCard, credit);
            int index = switch (strategy) {
                case "FIXED_FIRST" -> 0;
                case "FIXED_TOKTOK" -> 1;
                case "FIXED_LOCA" -> 2;
                case "CATEGORY" -> Objects.requireNonNull(categoryRule.get(purchase.transaction().merchantCategoryCode()));
                case "RANDOM" -> random.nextInt(purchase.cards().size());
                case "YAMU" -> java.util.stream.IntStream.range(0, purchase.cards().size()).filter(i -> evaluation.result().selectedUserCardId().equals(purchase.cards().get(i).card().row().getId())).findFirst().orElseThrow();
                case "MANUAL_TABLE" -> manualIndex;
                default -> throw new IllegalArgumentException(strategy);
            };
            var candidate = evaluation.candidates().get(index);
            long id = candidate.userCardId();
            var calculation = evaluation.result().evaluatedCards().stream().filter(e -> e.userCardId() == id).findFirst().orElseThrow().calculation();
            var row = new LinkedHashMap<String, Object>();
            row.put("strategy", strategy); row.put("seed", seed); row.put("transaction", purchase.number());
            row.put("timeKst", purchase.transaction().occurredAt().atZone(SEOUL).toString());
            row.put("brand", purchase.brand()); row.put("amount", purchase.transaction().amount());
            row.put("selectedIndex", index); row.put("selectedCard", purchase.cards().get(index).card().product().getProductName());
            row.put("benefitKrw", calculation.netBenefit());
            row.put("benefits", calculation.benefits());
            row.put("candidateEvaluations", evaluation.result().evaluatedCards());
            row.put("selectionReason", strategy.equals("YAMU") ? evaluation.result().selectionReason() : strategy);
            if (strategy.equals("MANUAL_TABLE")) {
                var audit = new ArrayList<Map<String, Object>>();
                for (int i = 0; i < estimates.size(); i++) {
                    long cardId = purchase.cards().get(i).card().row().getId();
                    var actual = evaluation.result().evaluatedCards().stream().filter(e -> e.userCardId() == cardId).findFirst().orElseThrow().calculation().netBenefit();
                    var entry = new LinkedHashMap<String, Object>();
                    entry.put("card", estimates.get(i).product()); entry.put("raw", estimates.get(i).raw());
                    entry.put("expected", estimates.get(i).amount()); entry.put("actual", actual);
                    entry.put("differenceActualMinusExpected", actual.subtract(estimates.get(i).amount()));
                    entry.put("remainingBefore", estimates.get(i).remaining()); entry.put("estimateReason", estimates.get(i).reason());
                    audit.add(entry);
                }
                row.put("manualCandidateAudit", audit);
                row.put("manualSelectedExpected", estimates.get(index).amount());
                row.put("manualSelectedError", calculation.netBenefit().subtract(estimates.get(index).amount()));
                long ties = estimates.stream().filter(e -> e.amount().compareTo(estimates.get(index).amount()) == 0).count();
                row.put("selectionReason", estimates.get(index).amount().signum() == 0 ? "ALL_ZERO_DEFAULT"
                        : ties > 1 ? index == defaultIndex ? "TIE_DEFAULT" : "TIE_FIXED_ORDER" : "MAX_TABLE_ESTIMATE");
            }
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
        assertThat(ledger.spent.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(purchases.stream().mapToLong(p -> p.transaction().amount()).sum());
        return new Run(strategy, seed, total, List.copyOf(trades), List.copyOf(limitRows), Map.copyOf(ledger.performance), Map.copyOf(ledger.spent));
    }
}
