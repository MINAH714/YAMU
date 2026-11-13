package com.yamu.be.recommendation;

import com.yamu.be.recommendation.BenefitPolicy.Conditions;
import com.yamu.be.recommendation.BenefitPolicy.Schedule;
import com.yamu.be.recommendation.BenefitPolicy.Target;
import com.yamu.be.recommendation.RecommendationInput.Transaction;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** FR-014. 알려진 불일치와 미확인을 구분하며 업종 접두어·구매 경로를 추정하지 않는다. */
public final class BenefitConditionEvaluator {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public enum Match { MATCHED, NOT_MATCHED, UNKNOWN }

    public record Missing(String code, String field) { }
    public record Evaluation(Match match, java.util.List<Missing> missingInformation, boolean policyUnknown) {
        public Evaluation { missingInformation = java.util.List.copyOf(missingInformation); }
        public boolean brandOnlyUnknown() {
            return match == Match.UNKNOWN && !policyUnknown && !missingInformation.isEmpty()
                    && missingInformation.stream().allMatch(m -> m.code().equals("BRAND_MISSING"));
        }
    }

    /** 브랜드 포함 경로 전체를 계산 범위에서 제외한다. 제외 조건과 원본 거래는 변경하지 않는다. */
    public Evaluation explainWithoutBrandPaths(Conditions conditions, Transaction transaction) {
        var original = explain(conditions, transaction);
        if (transaction.merchantId() != null || original.match() != Match.UNKNOWN || original.policyUnknown()) {
            return original;
        }
        var groups = conditions.targetGroups() == null
                ? conditions.targets().stream().map(t -> new BenefitPolicy.TargetGroup(1, t.excluded(),
                        java.util.List.of(new Target(t.type(), t.merchantId(), t.categoryCode(), false)))).toList()
                : conditions.targetGroups();
        boolean removed = groups.stream().anyMatch(g -> !g.excluded()
                && g.members().stream().anyMatch(t -> t.type() == BenefitPolicy.TargetType.BRAND)
                && and(g.members().stream().map(t -> targetMatch(t, transaction)).toList()) != Match.NOT_MATCHED);
        if (!removed) return original;
        var remaining = groups.stream().filter(g -> g.excluded()
                || g.members().stream().noneMatch(t -> t.type() == BenefitPolicy.TargetType.BRAND)).toList();
        boolean possible = remaining.stream().filter(g -> !g.excluded())
                .anyMatch(g -> and(g.members().stream().map(t -> targetMatch(t, transaction)).toList()) != Match.NOT_MATCHED);
        if (!possible) {
            // 0원 확정이 아니다. 호출자가 BRAND_MISSING 근거로 혜택 전체를 제외한다.
            return new Evaluation(Match.UNKNOWN, java.util.List.of(new Missing("BRAND_MISSING", "merchantId")), false);
        }
        return explain(new Conditions(conditions.verified(), conditions.currentVersionVerified(),
                conditions.validFrom(), conditions.validUntil(), conditions.targets(), conditions.requiredFacts(),
                conditions.excludedFacts(), conditions.schedule(), remaining), transaction);
    }

    /** 기존 판정이 UNKNOWN인 경우에만, 결과에 남아 있는 미확인 입력의 근거를 수집한다. */
    public Evaluation explain(Conditions conditions, Transaction transaction) {
        Match match = evaluate(conditions, transaction);
        if (match != Match.UNKNOWN) return new Evaluation(match, java.util.List.of(), false);
        if (conditions == null || !conditions.verified() || !conditions.currentVersionVerified()
                || conditions.validFrom() == null || conditions.validUntil() == null) {
            return new Evaluation(match, java.util.List.of(), true);
        }
        var included = new java.util.ArrayList<Evaluation>();
        var excluded = new java.util.ArrayList<Evaluation>();
        if (conditions.targetGroups() != null) {
            for (var group : conditions.targetGroups()) {
                var members = group.members().stream().map(t -> explainTarget(t, transaction)).toList();
                (group.excluded() ? excluded : included).add(merge(members, true));
            }
        } else {
            for (var target : conditions.targets()) {
                (target.excluded() ? excluded : included).add(explainTarget(target, transaction));
            }
        }
        var missing = new java.util.ArrayList<Missing>();
        var inclusion = merge(included, false);
        missing.addAll(inclusion.missingInformation());
        excluded.stream().filter(e -> e.match() == Match.UNKNOWN)
                .forEach(e -> missing.addAll(e.missingInformation()));
        java.util.stream.Stream.concat(conditions.requiredFacts().stream(), conditions.excludedFacts().stream())
                .filter(key -> fact(transaction, key) == null).sorted()
                .forEach(key -> missing.add(new Missing(key.startsWith("CHANNEL:") ? "CHANNEL_MISSING"
                        : key.startsWith("FACILITY:") ? "FACILITY_MISSING" : "FACT_MISSING", "facts." + key)));
        return new Evaluation(match, missing.stream().distinct().toList(),
                inclusion.policyUnknown() || conditions.schedule() == null);
    }

    private Evaluation explainTarget(Target target, Transaction transaction) {
        Match match = targetMatch(target, transaction);
        if (match != Match.UNKNOWN) return new Evaluation(match, java.util.List.of(), false);
        Missing missing = switch (target.type()) {
            case BRAND -> new Missing("BRAND_MISSING", "merchantId");
            case CATEGORY -> new Missing("MERCHANT_CATEGORY_MISSING", "merchantCategoryCode");
            case PURCHASE_ITEM -> new Missing("PURCHASE_ITEM_MISSING", "purchaseItemCode");
            case ALL -> throw new IllegalStateException("ALL은 미확인 대상이 아닙니다.");
        };
        return new Evaluation(match, java.util.List.of(missing), false);
    }

    private Evaluation merge(java.util.List<Evaluation> values, boolean conjunction) {
        var matches = values.stream().map(Evaluation::match).toList();
        Match match = conjunction ? and(matches) : or(matches);
        if (match != Match.UNKNOWN) return new Evaluation(match, java.util.List.of(), false);
        return new Evaluation(match, values.stream().filter(e -> e.match() == Match.UNKNOWN)
                .flatMap(e -> e.missingInformation().stream()).distinct().toList(),
                values.isEmpty() || values.stream().anyMatch(Evaluation::policyUnknown));
    }

    public Match evaluate(Conditions conditions, Transaction transaction) {
        if (conditions == null || !conditions.verified() || !conditions.currentVersionVerified()
                || conditions.validFrom() == null || conditions.validUntil() == null) {
            return Match.UNKNOWN;
        }
        LocalDateTime local = LocalDateTime.ofInstant(transaction.occurredAt(), SEOUL);
        if (local.toLocalDate().isBefore(conditions.validFrom())
                || (!conditions.validUntil().equals(BenefitPolicy.NO_END_DATE)
                && !local.toLocalDate().isBefore(conditions.validUntil()))) {
            return Match.NOT_MATCHED;
        }

        boolean unknown = false;
        boolean included = false;
        boolean includeUnknown = false;
        if (conditions.targetGroups() != null) {
            boolean hasInclude = false;
            for (var group : conditions.targetGroups()) {
                Match match = and(group.members().stream().map(t -> targetMatch(t, transaction)).toList());
                if (group.excluded()) {
                    if (match == Match.MATCHED) return Match.NOT_MATCHED;
                    unknown |= match == Match.UNKNOWN;
                } else {
                    hasInclude = true;
                    included |= match == Match.MATCHED;
                    includeUnknown |= match == Match.UNKNOWN;
                }
            }
            if (!hasInclude) unknown = true;
            else if (!included && !includeUnknown) return Match.NOT_MATCHED;
            unknown |= !included && includeUnknown;
        } else {
            for (Target target : conditions.targets()) {
                Match match = targetMatch(target, transaction);
                if (target.excluded()) {
                    if (match == Match.MATCHED) {
                        return Match.NOT_MATCHED;
                    }
                    unknown |= match == Match.UNKNOWN;
                } else {
                    included |= match == Match.MATCHED;
                    includeUnknown |= match == Match.UNKNOWN;
                }
            }
            if (!included && !includeUnknown) {
                // 포함 대상 자체가 없다면 정책 누락이며 '대상 없음'으로 단정하지 않는다.
                if (conditions.targets().stream().noneMatch(target -> !target.excluded())) {
                    unknown = true;
                } else {
                    return Match.NOT_MATCHED;
                }
            }
            unknown |= !included && includeUnknown;
        }

        for (String fact : conditions.excludedFacts()) {
            Boolean value = fact(transaction, fact);
            if (Boolean.TRUE.equals(value)) {
                return Match.NOT_MATCHED;
            }
            unknown |= value == null;
        }
        for (String fact : conditions.requiredFacts()) {
            Boolean value = fact(transaction, fact);
            if (Boolean.FALSE.equals(value)) {
                return Match.NOT_MATCHED;
            }
            unknown |= value == null;
        }
        if (conditions.schedule() == null) {
            unknown = true;
        } else if (!withinSchedule(conditions.schedule(), local)) {
            return Match.NOT_MATCHED;
        }
        return unknown ? Match.UNKNOWN : Match.MATCHED;
    }

    public static Match and(java.util.List<Match> values) {
        if (values.isEmpty()) return Match.UNKNOWN;
        if (values.contains(Match.NOT_MATCHED)) return Match.NOT_MATCHED;
        return values.contains(Match.UNKNOWN) ? Match.UNKNOWN : Match.MATCHED;
    }

    public static Match or(java.util.List<Match> values) {
        if (values.isEmpty()) return Match.UNKNOWN;
        if (values.contains(Match.MATCHED)) return Match.MATCHED;
        return values.contains(Match.UNKNOWN) ? Match.UNKNOWN : Match.NOT_MATCHED;
    }

    private Boolean fact(Transaction transaction, String code) {
        if (code.equals("RULE:ZERO_OVERSEAS_ELIGIBILITY")) {
            var facts = transaction.facts();
            boolean domestic = Boolean.TRUE.equals(facts.get("REGION:DOMESTIC"));
            boolean overseas = Boolean.TRUE.equals(facts.get("REGION:OVERSEAS"));
            if (domestic && !overseas) return true;
            if (overseas && !domestic) return facts.get("CARD_FORM:GLOBAL");
            return null; // 지역 미확인 또는 모순은 적격으로 추정하지 않는다.
        }
        // 무이자 여부는 보존된 결제 방식에서 확정된다. 외부 map으로 덮어쓸 수 없다.
        if (code.equals("INSTALLMENT:INTEREST_FREE")) {
            return transaction.plan() == RecommendationInput.Plan.INSTALLMENT;
        }
        if (code.equals("INSTALLMENT:LUMP_SUM")) return transaction.plan() == RecommendationInput.Plan.LUMP_SUM;
        return transaction.facts().get(code);
    }

    private Match targetMatch(Target target, Transaction transaction) {
        return switch (target.type()) {
            case ALL -> Match.MATCHED;
            case BRAND -> transaction.merchantId() == null ? Match.UNKNOWN
                    : target.merchantId().equals(transaction.merchantId()) ? Match.MATCHED : Match.NOT_MATCHED;
            case CATEGORY -> transaction.merchantCategoryCode() == null ? Match.UNKNOWN
                    : target.categoryCode().equals(transaction.merchantCategoryCode()) ? Match.MATCHED : Match.NOT_MATCHED;
            case PURCHASE_ITEM -> transaction.purchaseItemCode() == null ? Match.UNKNOWN
                    : target.categoryCode().equals(transaction.purchaseItemCode()) ? Match.MATCHED : Match.NOT_MATCHED;
        };
    }

    private boolean withinSchedule(Schedule schedule, LocalDateTime time) {
        LocalDateTime anchor = time;
        if (schedule.startTime() != null) {
            var clock = time.toLocalTime();
            if (schedule.startTime().isBefore(schedule.endTime())) {
                if (clock.isBefore(schedule.startTime()) || !clock.isBefore(schedule.endTime())) {
                    return false;
                }
            } else {
                if (clock.isBefore(schedule.endTime())) {
                    anchor = time.minusDays(1);
                } else if (clock.isBefore(schedule.startTime())) {
                    return false;
                }
            }
        }
        return schedule.weekdays().isEmpty() || schedule.weekdays().contains(anchor.getDayOfWeek());
    }
}
