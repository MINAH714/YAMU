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

/** POL-012. 허용된 순서별 조합을 계산하며 한도 사용 예상값은 호출 안의 지역 변수에만 둔다. */
public final class CardBenefitCalculator {
    private final BenefitConditionEvaluator conditionEvaluator = new BenefitConditionEvaluator();

    public record Problem(Long benefitId, String code) { }
    public record BenefitValue(long benefitId, int version, String name, BenefitPolicy.BenefitType type,
                               BigDecimal rawValue, BigDecimal value, Unit unit, BigDecimal krwValue,
                               String reason) { }
    public record LimitUse(long limitId, int version, Unit unit, BenefitPolicy.Period period,
                           BigDecimal remainingBefore, BigDecimal consumed) { }
    public record Calculation(boolean complete, BigDecimal netBenefit, List<BenefitValue> benefits,
                              List<LimitUse> limitUses, List<Problem> problems) {
        public Calculation {
            benefits = List.copyOf(benefits);
            limitUses = List.copyOf(limitUses);
            problems = List.copyOf(problems);
            if (complete != (netBenefit != null) || (complete && !problems.isEmpty())) {
                throw new IllegalArgumentException("계산 완료 상태와 값·사유가 맞지 않습니다.");
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

    private Calculation calculateCombination(Transaction transaction, BenefitPolicy policy, List<Long> combination,
                                             Map<Long, Benefit> benefits, Map<Long, Limit> limits) {
        Map<Long, BigDecimal> consumed = new HashMap<>();
        List<BenefitValue> values = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (long benefitId : combination) {
            Benefit benefit = benefits.get(benefitId);
            if (benefit.conditions() == null || !benefit.conditions().currentVersionVerified()) {
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
                throw new IllegalArgumentException("혜택 계산 대상액이 원 결제 금액보다 큽니다.");
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
                    ? BigDecimal.valueOf(benefit.baseAmount()).multiply(benefit.rateOrFixedValue())
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
