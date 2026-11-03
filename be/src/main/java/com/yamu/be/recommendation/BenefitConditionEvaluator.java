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
        // 무이자 여부는 보존된 결제 방식에서 확정된다. 외부 map으로 덮어쓸 수 없다.
        if (code.equals("INSTALLMENT:INTEREST_FREE")) {
            return transaction.plan() == RecommendationInput.Plan.INSTALLMENT;
        }
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
