package com.yamu.be.recommendation;

import com.yamu.be.recommendation.BenefitConditionEvaluator.Match;
import com.yamu.be.recommendation.BenefitPolicy.Benefit;
import com.yamu.be.recommendation.BenefitPolicy.Limit;
import com.yamu.be.recommendation.BenefitPolicy.Rounding;
import com.yamu.be.recommendation.BenefitPolicy.Unit;
import com.yamu.be.recommendation.RecommendationInput.Transaction;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** POL-012. 명시적 적용 그룹 또는 기존 순서별 조합을 계산한다. 한도 사용 예상값은 호출 안의 지역 변수다. */
public final class CardBenefitCalculator {
    private final BenefitConditionEvaluator conditionEvaluator = new BenefitConditionEvaluator();

    public record Problem(Long benefitId, String code) { }
    public record BenefitValue(long benefitId, int version, String name, BenefitPolicy.BenefitType type,
                               BigDecimal rawValue, BigDecimal value, Unit unit, BigDecimal krwValue,
                               String reason) { }
    public record LimitUse(long limitId, int version, Unit unit, BenefitPolicy.Period period,
                           BigDecimal remainingBefore, BigDecimal consumed) { }
    public record UnallocatedBenefitGroup(List<Long> benefitIds, long limitGroupId, BigDecimal totalAmount,
                                          Unit unit, String reasonCode) {
        public UnallocatedBenefitGroup { benefitIds = List.copyOf(benefitIds); }
    }
    public record Calculation(boolean complete, BigDecimal netBenefit, List<BenefitValue> benefits,
                              List<LimitUse> limitUses, List<Problem> problems,
                              List<UnallocatedBenefitGroup> unallocatedBenefitGroups) {
        public Calculation(boolean complete, BigDecimal netBenefit, List<BenefitValue> benefits,
                List<LimitUse> limitUses, List<Problem> problems) {
            this(complete, netBenefit, benefits, limitUses, problems, List.of());
        }
        public Calculation {
            unallocatedBenefitGroups = List.copyOf(unallocatedBenefitGroups);
            benefits = List.copyOf(benefits);
            limitUses = List.copyOf(limitUses);
            problems = List.copyOf(problems);
            if (complete != (netBenefit != null) || (complete && !problems.isEmpty())) {
                throw new IllegalArgumentException("계산 완료 상태와 값·사유가 맞지 않습니다.");
            }
            var ids = new java.util.HashSet<Long>();
            for (var group : unallocatedBenefitGroups) {
                if (group.benefitIds().size() < 2 || group.totalAmount() == null || group.totalAmount().signum() < 0
                        || !"SHARED_LIMIT_ALLOCATION_UNRESOLVED".equals(group.reasonCode())) throw new IllegalArgumentException("배분 미확정 묶음 오류");
                for (var id : group.benefitIds()) {
                    if (!ids.add(id) || benefits.stream().noneMatch(b -> b.benefitId() == id && b.value() == null)) {
                        throw new IllegalArgumentException("배분 미확정 혜택 참조 오류");
                    }
                }
            }
            if (benefits.stream().anyMatch(b -> b.value() == null && !ids.contains(b.benefitId()))) {
                throw new IllegalArgumentException("미확정 상세 금액의 근거 누락");
            }
        }
    }

    public Calculation calculate(Transaction transaction, BenefitPolicy policy) {
        if (policy == null || !policy.complete()) {
            return insufficient(null, "POLICY_INCOMPLETE");
        }
        if (policy.additionalCost() == null) {
            return insufficient(null, "ADDITIONAL_COST_UNKNOWN");
        }
        if (policy.applicationGroups() != null) return calculateApplications(transaction, policy);
        if (policy.allowedCombinations() == null
                || (!policy.benefits().isEmpty() && policy.allowedCombinations().isEmpty())) {
            return insufficient(null, "BENEFIT_COMBINATIONS_UNKNOWN");
        }
        Map<Long, Benefit> benefits = policy.benefits().stream()
                .collect(Collectors.toMap(Benefit::id, Function.identity()));
        Map<Long, Limit> limits = policy.limits().stream()
                .collect(Collectors.toMap(Limit::id, Function.identity()));
        if (policy.allowedCombinations().stream().flatMap(List::stream).distinct().count() != benefits.size()) {
            return insufficient(null, "BENEFIT_COMBINATIONS_INCOMPLETE");
        }

        List<List<Long>> combinations = new ArrayList<>(policy.allowedCombinations());
        if (combinations.isEmpty()) {
            combinations.add(List.of()); // 혜택이 없다는 것을 확인한 빈 정책만 이 경로를 사용한다.
        }
        combinations.sort(this::compareCombinations);
        Calculation best = null;
        List<Problem> problems = new ArrayList<>();
        for (List<Long> combination : combinations) {
            Calculation calculation = calculateCombination(transaction, policy, combination, benefits, limits);
            if (!calculation.complete()) {
                problems.addAll(calculation.problems());
            } else if (best == null || calculation.netBenefit().compareTo(best.netBenefit()) > 0) {
                best = calculation;
            }
        }
        // 미확인 조합을 버리고 확인된 조합만 최대라고 확정하지 않는다.
        if (!problems.isEmpty()) {
            return new Calculation(false, null, List.of(), List.of(), problems.stream().distinct()
                    .sorted(Comparator.comparing((Problem p) -> p.benefitId(), Comparator.nullsFirst(Long::compareTo))
                            .thenComparing(Problem::code)).toList());
        }
        return best;
    }

    private Calculation calculateApplications(Transaction transaction, BenefitPolicy policy) {
        Map<Long, Benefit> benefits = policy.benefits().stream().collect(Collectors.toMap(Benefit::id, Function.identity()));
        Map<Long, Limit> limits = policy.limits().stream().collect(Collectors.toMap(Limit::id, Function.identity()));
        for (var entry : policy.fallbacks().entrySet()) {
            var group = policy.applicationGroups().stream().filter(g -> g.members().stream().anyMatch(m -> m.benefitId() == entry.getKey())).findFirst();
            if (group.isEmpty() || group.get().mode() != BenefitPolicy.ApplicationMode.PRIORITY_ONE
                    || group.get().members().stream().noneMatch(m -> m.benefitId() == entry.getValue().benefitId())) {
                return insufficient(entry.getKey(), "FALLBACK_APPLICATION_GROUP_INVALID");
            }
        }
        var assigned = new java.util.HashSet<Long>();
        var calculations = new ArrayList<Calculation>();
        for (var group : policy.applicationGroups()) {
            for (var member : group.members()) {
                if (!benefits.containsKey(member.benefitId()) || !assigned.add(member.benefitId())) return insufficient(null, "APPLICATION_MEMBER_INVALID");
            }
            Calculation calculation;
            if (group.mode() == BenefitPolicy.ApplicationMode.STACK) {
                calculation = stack(transaction, policy, group.members().stream().map(BenefitPolicy.ApplicationMember::benefitId).toList(), benefits, limits);
            } else if (group.mode() == BenefitPolicy.ApplicationMode.MAX_ONE) {
                calculation = null;
                for (var member : group.members().stream().sorted(Comparator.comparingLong(BenefitPolicy.ApplicationMember::benefitId)).toList()) {
                    var candidate = single(transaction, policy, member.benefitId(), benefits, limits);
                    if (!candidate.complete()) return candidate;
                    if (calculation == null || candidate.netBenefit().compareTo(calculation.netBenefit()) > 0) calculation = candidate;
                }
            } else {
                var applicable = new ArrayList<BenefitPolicy.ApplicationMember>();
                Integer priority = null;
                for (var member : group.members().stream().sorted(Comparator.comparing(BenefitPolicy.ApplicationMember::priorityOrder)).toList()) {
                    if (priority != null && member.priorityOrder() > priority) break;
                    var benefit = benefits.get(member.benefitId());
                    Match match = applicable(transaction, policy, benefit);
                    if (match == Match.UNKNOWN) return insufficient(benefit.id(), "PRIORITY_CONDITION_UNKNOWN");
                    if (match == Match.MATCHED) { priority = member.priorityOrder(); applicable.add(member); }
                }
                if (applicable.size() > 1) return insufficient(null, "PRIORITY_AMBIGUOUS");
                calculation = applicable.isEmpty() ? new Calculation(true, BigDecimal.ZERO, List.of(), List.of(), List.of())
                        : single(transaction, policy, applicable.get(0).benefitId(), benefits, limits);
            }
            if (!calculation.complete()) return calculation;
            calculations.add(calculation);
        }
        var ungrouped = policy.benefits().stream().filter(b -> !assigned.contains(b.id())).toList();
        long active = 0;
        for (var benefit : ungrouped) {
            Match match = applicable(transaction, policy, benefit);
            if (match == Match.UNKNOWN) return insufficient(benefit.id(), "CONDITION_UNKNOWN");
            if (match == Match.MATCHED) active++;
        }
        boolean groupedActive = policy.applicationGroups().stream().flatMap(g -> g.members().stream())
                .anyMatch(m -> applicable(transaction, policy, benefits.get(m.benefitId())) != Match.NOT_MATCHED);
        if (active > 1 || active > 0 && groupedActive) return insufficient(null, "BENEFIT_RELATIONSHIP_UNKNOWN");
        for (var benefit : ungrouped) {
            var calculation = single(transaction, policy, benefit.id(), benefits, limits);
            if (!calculation.complete()) return calculation;
            calculations.add(calculation);
        }
        // 서로 다른 적용 그룹이 공유하는 한도의 차감 순서는 관계 정보 없이 정하지 않는다.
        var seenLimits = new java.util.HashSet<Long>();
        for (var calculation : calculations) for (var use : calculation.limitUses()) {
            if (!seenLimits.add(use.limitId())) return insufficient(null, "CROSS_GROUP_SHARED_LIMIT_ORDER_UNKNOWN");
        }
        return combine(calculations, policy.additionalCost());
    }

    private Match applicable(Transaction transaction, BenefitPolicy policy, Benefit benefit) {
        if (benefit.conditions() == null || !benefit.conditions().currentVersionVerified()) return Match.UNKNOWN;
        if (zeroReason(transaction, policy, benefit) != null) return Match.NOT_MATCHED;
        var match = conditionEvaluator.evaluate(benefit.conditions(), transaction);
        if (match == Match.MATCHED && (benefit.minimumPerformance() == null || benefit.minimumPayment() == null
                || benefit.minimumPerformance() > 0 && policy.previousMonthPerformance() == null)) return Match.UNKNOWN;
        return match;
    }

    private BenefitPolicy withoutCost(BenefitPolicy policy, List<Benefit> benefits, List<Limit> limits) {
        return new BenefitPolicy(true, policy.previousMonthPerformance(), BigDecimal.ZERO, benefits, limits, null);
    }

    private Calculation single(Transaction transaction, BenefitPolicy policy, long id, Map<Long, Benefit> benefits, Map<Long, Limit> limits) {
        var fallback = policy.fallbacks().get(id);
        if (fallback == null || applicable(transaction, policy, benefits.get(id)) == Match.NOT_MATCHED) {
            return calculateCombination(transaction, withoutCost(policy, policy.benefits(), policy.limits()), List.of(id), benefits, limits);
        }
        var preferred = benefits.get(id);
        var base = benefits.get(fallback.benefitId());
        if (base == null || base.id() == id || policy.fallbacks().containsKey(base.id())
                || preferred.calculationType() != BenefitPolicy.CalculationType.RATE || base.calculationType() != BenefitPolicy.CalculationType.RATE
                || preferred.rateOrFixedValue() == null || preferred.rateOrFixedValue().signum() <= 0
                || preferred.baseAmount() == null || base.baseAmount() == null || preferred.unit() != base.unit()) {
            return insufficient(id, "FALLBACK_POLICY_UNKNOWN");
        }
        var monetary = preferred.limitIds().stream().map(limits::get).filter(java.util.Objects::nonNull)
                .filter(l -> l.unit() != Unit.COUNT).toList();
        if (monetary.size() != 1 || monetary.get(0).unit() != preferred.unit()) return insufficient(id, "FALLBACK_LIMIT_AMBIGUOUS");
        var limit = monetary.get(0);
        if (limit.maximum() == null || limit.used() == null && limit.period() != BenefitPolicy.Period.PER_TRANSACTION) return insufficient(id, "LIMIT_UNKNOWN");
        BigDecimal funded;
        try {
            funded = remainingBefore(limit).divide(preferred.rateOrFixedValue()).min(BigDecimal.valueOf(preferred.baseAmount()));
        } catch (ArithmeticException nonTerminatingPrincipal) {
            return insufficient(id, "FALLBACK_PRINCIPAL_SPLIT_UNREPRESENTABLE");
        }
        if (funded.signum() > 0 && !preferred.baseAmount().equals(base.baseAmount())) return insufficient(id, "FALLBACK_BASE_SCOPE_UNKNOWN");
        var first = calculateCombination(transaction, withoutCost(policy, policy.benefits(), policy.limits()), List.of(id), benefits, limits, Map.of(id, funded));
        if (!first.complete()) return first;
        var excess = BigDecimal.valueOf(base.baseAmount()).subtract(funded).max(BigDecimal.ZERO);
        if (excess.signum() == 0) return first;
        var second = calculateCombination(transaction, withoutCost(policy, policy.benefits(), policy.limits()), List.of(base.id()), benefits, limits,
                Map.of(base.id(), excess));
        if (!second.complete()) return second;
        if (base.limitIds().stream().anyMatch(preferred.limitIds()::contains)) return insufficient(id, "FALLBACK_SHARED_LIMIT_UNKNOWN");
        return combine(List.of(first, second), BigDecimal.ZERO);
    }

    private Calculation stack(Transaction transaction, BenefitPolicy policy, List<Long> ids, Map<Long, Benefit> benefits, Map<Long, Limit> limits) {
        var frequencies = new HashMap<Long, Long>();
        for (var id : ids) {
            if (policy.fallbacks().containsKey(id)) return insufficient(id, "STACK_FALLBACK_UNSUPPORTED");
            if (applicable(transaction, policy, benefits.get(id)) == Match.NOT_MATCHED) continue;
            for (var limit : benefits.get(id).limitIds()) frequencies.merge(limit, 1L, Long::sum);
        }
        var shared = frequencies.entrySet().stream().filter(e -> e.getValue() > 1).map(Map.Entry::getKey).toList();
        var sharedMoney = shared.stream().filter(id -> limits.get(id) != null && limits.get(id).unit() != Unit.COUNT).toList();
        if (sharedMoney.size() > 1) return insufficient(null, "SHARED_LIMIT_TOPOLOGY_UNKNOWN");
        var singles = new ArrayList<Calculation>();
        var individualBenefits = new HashMap<Long, Benefit>();
        for (var id : ids) {
            Benefit b = benefits.get(id);
            var filtered = b.limitIds().stream().filter(l -> !sharedMoney.contains(l)).toList();
            individualBenefits.put(id, new Benefit(b.id(), b.version(), b.name(), b.type(), b.calculationType(), b.rateOrFixedValue(),
                    b.unit(), b.pointKrwRate(), b.baseAmount(), b.minimumPayment(), b.minimumPerformance(), b.conditions(), b.rounding(), b.calculationOrder(), filtered));
        }
        for (var id : ids) {
            var calculation = calculateCombination(transaction, withoutCost(policy, policy.benefits(), policy.limits()), List.of(id), individualBenefits, limits);
            if (!calculation.complete()) return calculation;
            singles.add(calculation);
        }
        var result = combine(singles, BigDecimal.ZERO);
        var uses = new HashMap<Long, LimitUse>();
        for (var calculation : singles) for (var use : calculation.limitUses()) {
            var limit = limits.get(use.limitId());
            var old = uses.get(use.limitId());
            BigDecimal consumed = old == null ? use.consumed() : limit.unit() == Unit.COUNT && limit.countMode() == BenefitPolicy.CountMode.PER_TRANSACTION
                    ? old.consumed().max(use.consumed()) : old.consumed().add(use.consumed());
            if (consumed.compareTo(use.remainingBefore()) > 0) return insufficient(null, "SHARED_COUNT_ALLOCATION_UNKNOWN");
            uses.put(use.limitId(), new LimitUse(use.limitId(), use.version(), use.unit(), use.period(), use.remainingBefore(), consumed));
        }
        var values = new ArrayList<>(result.benefits());
        BigDecimal total = result.netBenefit();
        var unallocated = new ArrayList<UnallocatedBenefitGroup>();
        if (!sharedMoney.isEmpty()) {
            var limit = limits.get(sharedMoney.get(0));
            var members = values.stream().filter(b -> benefits.get(b.benefitId()).limitIds().contains(limit.id())).toList();
            BigDecimal amount = members.stream().map(BenefitValue::value).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (amount.signum() > 0) {
                if (limit.maximum() == null || limit.used() == null && limit.period() != BenefitPolicy.Period.PER_TRANSACTION) return insufficient(null, "LIMIT_UNKNOWN");
                if (members.stream().anyMatch(b -> b.unit() != limit.unit())) return insufficient(null, "LIMIT_UNIT_CONVERSION_UNVERIFIED");
                BigDecimal cap = remainingBefore(limit);
                BigDecimal paid = amount.min(cap);
                BigDecimal conversion = limit.unit() == Unit.KRW ? BigDecimal.ONE : benefits.get(members.get(0).benefitId()).pointKrwRate();
                if (limit.unit() == Unit.POINT && members.stream().anyMatch(b -> benefits.get(b.benefitId()).pointKrwRate().compareTo(conversion) != 0)) return insufficient(null, "SHARED_POINT_CONVERSION_UNKNOWN");
                total = total.subtract(amount.subtract(paid).multiply(conversion));
                uses.put(limit.id(), new LimitUse(limit.id(), limit.version(), limit.unit(), limit.period(), cap, paid));
                if (paid.compareTo(amount) < 0) {
                    var positive = members.stream().filter(b -> b.value().signum() > 0).toList();
                    if (positive.stream().anyMatch(b -> benefits.get(b.benefitId()).calculationOrder() != BenefitPolicy.CalculationOrder.ROUND_THEN_LIMIT)) return insufficient(null, "SHARED_ROUNDING_ORDER_UNKNOWN");
                    if (paid.signum() == 0 || positive.size() == 1) {
                        for (var b : positive) values.set(values.indexOf(b), new BenefitValue(b.benefitId(), b.version(), b.name(), b.type(), b.rawValue(),
                                paid, b.unit(), paid.multiply(conversion), "SHARED_LIMIT_CAPPED"));
                    } else {
                        var affected = positive.stream().map(BenefitValue::benefitId).toList();
                        for (var b : positive) values.set(values.indexOf(b), new BenefitValue(b.benefitId(), b.version(), b.name(), b.type(), b.rawValue(), null, b.unit(), null, "SHARED_LIMIT_ALLOCATION_UNRESOLVED"));
                        // 총액/공유 한도만 확정된다. 개별 금액·횟수 사용 예상치를 만들어내지 않는다.
                        var uncertainLimits = affected.stream().flatMap(id -> benefits.get(id).limitIds().stream()).filter(id -> id != limit.id()).toList();
                        uncertainLimits.forEach(uses::remove);
                        unallocated.add(new UnallocatedBenefitGroup(affected, limit.id(), paid, limit.unit(), "SHARED_LIMIT_ALLOCATION_UNRESOLVED"));
                    }
                }
            }
        }
        // 한도 적용으로 상세가 확정 0/부분 금액으로 바뀐 경우 원래 개별 계산의 사용 예상치를 재사용하지 않는다.
        var uncertain = values.stream().filter(v -> v.value() == null).flatMap(v -> benefits.get(v.benefitId()).limitIds().stream())
                .filter(id -> !sharedMoney.contains(id)).collect(Collectors.toSet());
        var finalConsumed = new HashMap<Long, BigDecimal>();
        for (var value : values) {
            if (value.value() == null) continue;
            if (List.of("CONDITION_NOT_MATCHED", "BELOW_MINIMUM_PAYMENT", "BELOW_REQUIRED_PERFORMANCE", "COUNT_LIMIT_EXHAUSTED").contains(value.reason())) continue;
            for (var limitId : benefits.get(value.benefitId()).limitIds()) {
                if (sharedMoney.contains(limitId) || uncertain.contains(limitId)) continue;
                var limit = limits.get(limitId);
                if (limit.unit() == Unit.COUNT) {
                    if (value.value().signum() > 0 || Boolean.TRUE.equals(limit.consumeOnZero())) {
                        if (limit.countMode() == BenefitPolicy.CountMode.PER_TRANSACTION) finalConsumed.put(limitId, BigDecimal.ONE);
                        else finalConsumed.merge(limitId, BigDecimal.ONE, BigDecimal::add);
                    }
                } else finalConsumed.merge(limitId, value.value(), BigDecimal::add);
            }
        }
        uses.keySet().removeIf(id -> !sharedMoney.contains(id));
        for (var entry : finalConsumed.entrySet()) {
            var limit = limits.get(entry.getKey());
            uses.put(limit.id(), new LimitUse(limit.id(), limit.version(), limit.unit(), limit.period(), remainingBefore(limit), entry.getValue()));
        }
        return new Calculation(true, total, values, uses.values().stream().sorted(Comparator.comparingLong(LimitUse::limitId)).toList(), List.of(), unallocated);
    }

    private Calculation combine(List<Calculation> calculations, BigDecimal cost) {
        return new Calculation(true, calculations.stream().map(Calculation::netBenefit).reduce(BigDecimal.ZERO, BigDecimal::add).subtract(cost),
                calculations.stream().flatMap(c -> c.benefits().stream()).toList(), calculations.stream().flatMap(c -> c.limitUses().stream()).toList(), List.of(),
                calculations.stream().flatMap(c -> c.unallocatedBenefitGroups().stream()).toList());
    }

    private Calculation calculateCombination(Transaction transaction, BenefitPolicy policy, List<Long> combination,
                                             Map<Long, Benefit> benefits, Map<Long, Limit> limits) {
        return calculateCombination(transaction, policy, combination, benefits, limits, Map.of());
    }

    private Calculation calculateCombination(Transaction transaction, BenefitPolicy policy, List<Long> combination,
                                             Map<Long, Benefit> benefits, Map<Long, Limit> limits, Map<Long, BigDecimal> baseAmounts) {
        Map<Long, BigDecimal> consumed = new HashMap<>();
        List<BenefitValue> values = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (long benefitId : combination) {
            Benefit benefit = benefits.get(benefitId);
            if (benefit.conditions() == null || !benefit.conditions().currentVersionVerified()
                    || benefit.conditions().validFrom() == null || benefit.conditions().validUntil() == null) {
                return insufficient(benefitId, "CONDITION_UNKNOWN");
            }
            // 확인된 현행 약관의 최소 금액·실적에 미달하면 다른 조건·계산 자료는 이번 거래에 사용하지 않는다.
            String zeroReason = zeroReason(transaction, policy, benefit);
            if (zeroReason != null) {
                values.add(zero(benefit, zeroReason));
                continue;
            }
            Match match = conditionEvaluator.evaluate(benefit.conditions(), transaction);
            if (match == Match.NOT_MATCHED) {
                values.add(zero(benefit, "CONDITION_NOT_MATCHED"));
                continue;
            }
            if (match == Match.UNKNOWN) {
                return insufficient(benefitId, "CONDITION_UNKNOWN");
            }
            if (benefit.minimumPayment() == null || benefit.minimumPerformance() == null
                    || (benefit.minimumPerformance() > 0 && policy.previousMonthPerformance() == null)) {
                return insufficient(benefitId, "PERFORMANCE_OR_MINIMUM_UNKNOWN");
            }
            if (benefit.rateOrFixedValue() == null || benefit.calculationOrder() == null
                    || !roundingKnown(benefit.rounding())
                    || (benefit.unit() == Unit.POINT && benefit.pointKrwRate() == null)
                    || (benefit.calculationType() == BenefitPolicy.CalculationType.RATE && benefit.baseAmount() == null)) {
                return insufficient(benefitId, "CALCULATION_POLICY_UNKNOWN");
            }
            if (benefit.baseAmount() != null && benefit.baseAmount() > transaction.amount()) {
                return insufficient(benefitId, "BASE_AMOUNT_INVALID");
            }
            List<Limit> linked = new ArrayList<>();
            BigDecimal cap = null;
            boolean countExhausted = false;
            for (long limitId : benefit.limitIds()) {
                Limit limit = limits.get(limitId);
                if (limit == null || limit.maximum() == null
                        || (limit.period() != BenefitPolicy.Period.PER_TRANSACTION && limit.used() == null)) {
                    return insufficient(benefitId, "LIMIT_UNKNOWN");
                }
                linked.add(limit);
                BigDecimal remaining = remainingBefore(limit).subtract(consumed.getOrDefault(limitId, BigDecimal.ZERO));
                if (limit.unit() == Unit.COUNT) {
                    if (limit.countMode() == null || limit.consumeOnZero() == null) {
                        return insufficient(benefitId, "COUNT_POLICY_UNKNOWN");
                    }
                    boolean alreadyCounted = limit.countMode() == BenefitPolicy.CountMode.PER_TRANSACTION
                            && consumed.getOrDefault(limitId, BigDecimal.ZERO).signum() > 0;
                    countExhausted |= !alreadyCounted && remaining.compareTo(BigDecimal.ONE) < 0;
                } else {
                    // 원/포인트 사이의 한도 적용 순서는 별도 약관 근거 없이는 추정하지 않는다.
                    if (limit.unit() != benefit.unit()) {
                        return insufficient(benefitId, "LIMIT_UNIT_CONVERSION_UNVERIFIED");
                    }
                    cap = cap == null ? remaining : cap.min(remaining);
                }
            }
            BigDecimal raw = benefit.calculationType() == BenefitPolicy.CalculationType.RATE
                    ? baseAmounts.getOrDefault(benefitId, BigDecimal.valueOf(benefit.baseAmount())).multiply(benefit.rateOrFixedValue())
                    : benefit.rateOrFixedValue();
            BigDecimal value;
            if (countExhausted) {
                value = BigDecimal.ZERO;
            } else if (benefit.calculationOrder() == BenefitPolicy.CalculationOrder.ROUND_THEN_LIMIT) {
                value = roundedContribution(raw, benefit.rounding());
                if (cap != null) {
                    value = value.min(cap);
                }
            } else {
                value = roundedContribution(cap == null ? raw : raw.min(cap), benefit.rounding());
                if (cap != null && value.compareTo(cap) > 0) {
                    return insufficient(benefitId, "ROUNDING_LIMIT_CONFLICT");
                }
            }
            if (!countExhausted) {
                for (Limit limit : linked) {
                    if (limit.unit() == Unit.COUNT) {
                        if (value.signum() > 0 || limit.consumeOnZero()) {
                            if (limit.countMode() == BenefitPolicy.CountMode.PER_TRANSACTION) {
                                consumed.put(limit.id(), BigDecimal.ONE);
                            } else {
                                consumed.merge(limit.id(), BigDecimal.ONE, BigDecimal::add);
                            }
                        }
                    } else {
                        consumed.merge(limit.id(), value, BigDecimal::add);
                    }
                }
            }
            BigDecimal krw = benefit.unit() == Unit.KRW ? value : value.multiply(benefit.pointKrwRate());
            values.add(new BenefitValue(benefit.id(), benefit.version(), benefit.name(), benefit.type(), raw,
                    value, benefit.unit(), krw, countExhausted ? "COUNT_LIMIT_EXHAUSTED"
                    : value.signum() == 0 ? "ZERO_BENEFIT" : "APPLIED"));
            total = total.add(krw);
        }
        List<LimitUse> uses = new TreeMap<>(consumed).entrySet().stream().map(entry -> {
            Limit limit = limits.get(entry.getKey());
            return new LimitUse(limit.id(), limit.version(), limit.unit(), limit.period(),
                    remainingBefore(limit), entry.getValue());
        }).toList();
        return new Calculation(true, total.subtract(policy.additionalCost()), values, uses, List.of());
    }

    private String zeroReason(Transaction transaction, BenefitPolicy policy, Benefit benefit) {
        if (benefit.minimumPayment() != null && transaction.amount() < benefit.minimumPayment()) {
            return "BELOW_MINIMUM_PAYMENT";
        }
        if (benefit.minimumPerformance() != null && policy.previousMonthPerformance() != null
                && policy.previousMonthPerformance() < benefit.minimumPerformance()) {
            return "BELOW_REQUIRED_PERFORMANCE";
        }
        return null;
    }

    private boolean roundingKnown(Rounding rounding) {
        return rounding != null && rounding.mode() != null && rounding.unit() != null && rounding.stage() != null
                && (rounding.stage() == BenefitPolicy.RoundingStage.PER_TRANSACTION
                || rounding.aggregateRawBefore() != null);
    }

    private BigDecimal roundedContribution(BigDecimal raw, Rounding rounding) {
        if (rounding.stage() == BenefitPolicy.RoundingStage.AFTER_AGGREGATION) {
            return round(rounding.aggregateRawBefore().add(raw), rounding)
                    .subtract(round(rounding.aggregateRawBefore(), rounding));
        }
        return round(raw, rounding);
    }

    private BigDecimal round(BigDecimal value, Rounding rounding) {
        return value.divide(rounding.unit(), 0, rounding.mode()).multiply(rounding.unit());
    }

    private BigDecimal remainingBefore(Limit limit) {
        long used = limit.period() == BenefitPolicy.Period.PER_TRANSACTION ? 0 : limit.used();
        return BigDecimal.valueOf(limit.maximum()).subtract(BigDecimal.valueOf(used)).max(BigDecimal.ZERO);
    }

    private BenefitValue zero(Benefit benefit, String reason) {
        return new BenefitValue(benefit.id(), benefit.version(), benefit.name(), benefit.type(),
                BigDecimal.ZERO, BigDecimal.ZERO, benefit.unit(), BigDecimal.ZERO, reason);
    }

    private Calculation insufficient(Long benefitId, String code) {
        return new Calculation(false, null, List.of(), List.of(), List.of(new Problem(benefitId, code)));
    }

    private int compareCombinations(List<Long> first, List<Long> second) {
        for (int index = 0; index < Math.min(first.size(), second.size()); index++) {
            int compared = Long.compare(first.get(index), second.get(index));
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(first.size(), second.size());
    }
}
