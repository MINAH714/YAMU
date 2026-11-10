package com.yamu.be.recommendation;

import static com.yamu.be.recommendation.RecommendationFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.yamu.be.recommendation.BenefitConditionEvaluator.Match;
import com.yamu.be.recommendation.BenefitPolicy.*;
import com.yamu.be.recommendation.RecommendationInput.*;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BenefitConditionEvaluatorTest {
    private final BenefitConditionEvaluator evaluator = new BenefitConditionEvaluator();

    @Test
    void scopedBrandPathKeepsExclusionsAndDoesNotMutateTransactionOrPolicy() {
        var brand = new Target(TargetType.BRAND, 10L, null, false);
        var category = new Target(TargetType.CATEGORY, null, "COFFEE_SHOP", false);
        var excludedBrand = new Target(TargetType.BRAND, 20L, null, true);
        var original = new Transaction(10000, Plan.LUMP_SUM, 1, transaction().occurredAt(),
                null, "COFFEE_SHOP", Map.of());
        var c = new Conditions(true, true, POLICY_START, BenefitPolicy.NO_END_DATE,
                List.of(brand, category, excludedBrand), Set.of("RULE:OFFICIAL_MERCHANT_ROUTE"), Set.of(), Schedule.unrestricted());
        var result = evaluator.explainWithoutBrandPaths(c, original);
        assertThat(result.match()).isEqualTo(Match.UNKNOWN);
        assertThat(result.missingInformation()).containsExactlyInAnyOrder(
                new BenefitConditionEvaluator.Missing("BRAND_MISSING", "merchantId"),
                new BenefitConditionEvaluator.Missing("FACT_MISSING", "facts.RULE:OFFICIAL_MERCHANT_ROUTE"));
        assertThat(result.brandOnlyUnknown()).isFalse();
        assertThat(original.merchantId()).isNull();
        assertThat(original.facts()).isEmpty();
        assertThat(c.targets()).containsExactly(brand, category, excludedBrand);
    }

    @Test
    void scopedFlatBrandPathDropsUnusedFactsButPreservesKnownMismatch() {
        var c = new Conditions(true, true, POLICY_START, BenefitPolicy.NO_END_DATE,
                List.of(new Target(TargetType.BRAND, 10L, null, false)),
                Set.of("RULE:UTILITY_PAYMENT"), Set.of(), Schedule.unrestricted());
        var unknown = new Transaction(10000, Plan.LUMP_SUM, 1, transaction().occurredAt(), null, "COFFEE_SHOP", Map.of());
        assertThat(evaluator.explainWithoutBrandPaths(c, unknown).brandOnlyUnknown()).isTrue();
        assertThat(evaluator.explain(c, unknown).missingInformation()).hasSize(2);
        var mismatch = new Transaction(10000, Plan.LUMP_SUM, 1, transaction().occurredAt(), null, "COFFEE_SHOP",
                Map.of("RULE:UTILITY_PAYMENT", false));
        assertThat(evaluator.explainWithoutBrandPaths(c, mismatch).match()).isEqualTo(Match.NOT_MATCHED);
        assertThat(evaluator.explainWithoutBrandPaths(c, mismatch).missingInformation()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-30T12:59:59Z,NOT_MATCHED",
            "2026-10-30T13:00:00Z,MATCHED",
            "2026-10-30T16:00:00Z,MATCHED",
            "2026-10-30T16:59:59Z,MATCHED",
            "2026-10-30T17:00:00Z,NOT_MATCHED",
            "2026-10-31T13:00:00Z,NOT_MATCHED"
    })
    void overnightUsesSeoulAndStartingDayWithExclusiveEnd(String instant, Match expected) {
        var conditions = withSchedule(new Schedule(Set.of(DayOfWeek.FRIDAY), LocalTime.of(22, 0), LocalTime.of(2, 0)));
        assertThat(evaluator.evaluate(conditions, at(instant, Map.of()))).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"2026-10-30T04:59:59Z,NOT_MATCHED", "2026-10-30T05:00:00Z,MATCHED",
            "2026-10-30T08:59:59Z,MATCHED", "2026-10-30T09:00:00Z,NOT_MATCHED"})
    void daytimeIsStartInclusiveAndEndExclusive(String instant, Match expected) {
        var conditions = withSchedule(new Schedule(Set.of(), LocalTime.of(14, 0), LocalTime.of(18, 0)));
        assertThat(evaluator.evaluate(conditions, at(instant, Map.of()))).isEqualTo(expected);
    }

    @Test
    void validityUsesKoreanDateAndDoesNotAssumeNullDatesAreCurrent() {
        var source = conditions();
        var known = new Conditions(true, true, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1),
                source.targets(), Set.of(), Set.of(), source.schedule());
        assertThat(evaluator.evaluate(known, at("2026-10-31T14:59:59Z", Map.of()))).isEqualTo(Match.NOT_MATCHED);
        assertThat(evaluator.evaluate(known, at("2026-10-31T15:00:00Z", Map.of()))).isEqualTo(Match.MATCHED);
        assertThat(evaluator.evaluate(known, at("2026-11-30T14:59:59Z", Map.of()))).isEqualTo(Match.MATCHED);
        assertThat(evaluator.evaluate(known, at("2026-11-30T15:00:00Z", Map.of()))).isEqualTo(Match.NOT_MATCHED);
        var unverified = new Conditions(true, false, null, null, source.targets(), Set.of(), Set.of(), source.schedule());
        assertThat(evaluator.evaluate(unverified, transaction())).isEqualTo(Match.UNKNOWN);
    }

    @Test
    void missingStartOrEndRemainsUnknownEvenWithVerifiedFlags() {
        var source = conditions();
        var missingEnd = new Conditions(true, true, POLICY_START, null,
                source.targets(), Set.of(), Set.of(), source.schedule());
        var missingStart = new Conditions(true, true, null, BenefitPolicy.NO_END_DATE,
                source.targets(), Set.of(), Set.of(), source.schedule());
        assertThat(evaluator.evaluate(missingEnd, transaction())).isEqualTo(Match.UNKNOWN);
        assertThat(evaluator.evaluate(missingStart, transaction())).isEqualTo(Match.UNKNOWN);
    }

    @Test
    void confirmedNoEndDateIsUnboundedRatherThanAnExclusiveOrdinaryEndDate() {
        var unlimited = conditions();
        assertThat(unlimited.validUntil()).isEqualTo(LocalDate.of(9999, 12, 31));
        assertThat(evaluator.evaluate(unlimited, transaction())).isEqualTo(Match.MATCHED);
        assertThat(evaluator.evaluate(unlimited, at("9999-12-31T12:00:00Z", Map.of()))).isEqualTo(Match.MATCHED);
    }

    @Test
    void includedTargetsAreOrButAdditionalFactsAreAndAndExclusionsOverride() {
        var conditions = new Conditions(true, true, POLICY_START, BenefitPolicy.NO_END_DATE,
                List.of(new Target(TargetType.BRAND, 10L, null, false),
                        new Target(TargetType.CATEGORY, null, "OTHER", false)),
                Set.of("CHANNEL:OFFLINE", "RULE:OFFICIAL_PURCHASE_ROUTE"),
                Set.of("FACILITY:AIRPORT"), Schedule.unrestricted());
        var facts = Map.of("CHANNEL:OFFLINE", true, "RULE:OFFICIAL_PURCHASE_ROUTE", true, "FACILITY:AIRPORT", false);
        assertThat(evaluator.evaluate(conditions, at(transaction().occurredAt().toString(), facts))).isEqualTo(Match.MATCHED);
        assertThat(evaluator.evaluate(conditions, at(transaction().occurredAt().toString(),
                Map.of("CHANNEL:OFFLINE", true, "RULE:OFFICIAL_PURCHASE_ROUTE", false, "FACILITY:AIRPORT", false))))
                .isEqualTo(Match.NOT_MATCHED);
        assertThat(evaluator.evaluate(conditions, at(transaction().occurredAt().toString(),
                Map.of("FACILITY:AIRPORT", true)))).isEqualTo(Match.NOT_MATCHED);
    }

    @Test
    void missingExclusionFactIsNotFalseOrGeneralFacility() {
        var conditions = new Conditions(true, true, POLICY_START, BenefitPolicy.NO_END_DATE, conditions().targets(), Set.of(),
                Set.of("FACILITY:DEPARTMENT_STORE"), Schedule.unrestricted());
        assertThat(evaluator.evaluate(conditions, transaction())).isEqualTo(Match.UNKNOWN);
    }

    @Test
    void missingPurchaseRouteIsNotAutomaticallySupported() {
        var conditions = new Conditions(true, true, POLICY_START, BenefitPolicy.NO_END_DATE, conditions().targets(),
                Set.of("RULE:CHEOEUM_CAFE_ROUTE"), Set.of(), Schedule.unrestricted());
        assertThat(evaluator.evaluate(conditions, transaction())).isEqualTo(Match.UNKNOWN);
    }

    @Test
    void interestFreeExclusionUsesPreservedPlanAndCannotBeOverriddenByFacts() {
        var conditions = new Conditions(true, true, POLICY_START, BenefitPolicy.NO_END_DATE, conditions().targets(), Set.of(),
                Set.of("INSTALLMENT:INTEREST_FREE"), Schedule.unrestricted());
        var installment = new Transaction(50_000, Plan.INSTALLMENT, 12, transaction().occurredAt(), 10L,
                "COFFEE_SHOP", Map.of("INSTALLMENT:INTEREST_FREE", false));
        assertThat(evaluator.evaluate(conditions, installment)).isEqualTo(Match.NOT_MATCHED);
        assertThat(evaluator.evaluate(conditions, transaction())).isEqualTo(Match.MATCHED);
    }

    @Test
    void absentTargetsOrScheduleAreUnknownRatherThanNoRestriction() {
        assertThat(evaluator.evaluate(null, transaction())).isEqualTo(Match.UNKNOWN);
        assertThat(evaluator.evaluate(new Conditions(true, true, POLICY_START, BenefitPolicy.NO_END_DATE, List.of(), Set.of(), Set.of(),
                Schedule.unrestricted()), transaction())).isEqualTo(Match.UNKNOWN);
        assertThat(evaluator.evaluate(new Conditions(true, true, POLICY_START, BenefitPolicy.NO_END_DATE, conditions().targets(), Set.of(), Set.of(),
                null), transaction())).isEqualTo(Match.UNKNOWN);
    }

    @Test
    void incompleteConditionInvestigationDoesNotEstablishTargetMismatch() {
        var incomplete = new Conditions(false, true, POLICY_START, BenefitPolicy.NO_END_DATE,
                List.of(new Target(TargetType.BRAND, 999L, null, false)), Set.of(), Set.of(), Schedule.unrestricted());
        assertThat(evaluator.evaluate(incomplete, transaction())).isEqualTo(Match.UNKNOWN);
    }

    @Test
    void categoryMatchingDoesNotInventHierarchy() {
        var conditions = new Conditions(true, true, POLICY_START, BenefitPolicy.NO_END_DATE,
                List.of(new Target(TargetType.CATEGORY, null, "COFFEE", false)), Set.of(), Set.of(), Schedule.unrestricted());
        assertThat(evaluator.evaluate(conditions, transaction())).isEqualTo(Match.NOT_MATCHED);
    }

    @Test
    void unknownMerchantOrCategoryDoNotBecomeKnownMismatches() {
        var missing = new Transaction(100_000, Plan.LUMP_SUM, 1, transaction().occurredAt(), null, null, Map.of());
        var conditions = new Conditions(true, true, POLICY_START, BenefitPolicy.NO_END_DATE,
                List.of(new Target(TargetType.CATEGORY, null, "COFFEE_SHOP", false)), Set.of(), Set.of(), Schedule.unrestricted());
        assertThat(evaluator.evaluate(conditions, missing)).isEqualTo(Match.UNKNOWN);
    }

    @Test
    void rejectsMalformedSchedule() {
        assertThatThrownBy(() -> new Schedule(Set.of(), LocalTime.NOON, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Schedule(Set.of(), LocalTime.NOON, LocalTime.NOON)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Schedule(Set.of(), LocalTime.of(12, 0, 1), LocalTime.of(13, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"0,LUMP_SUM,1", "-1,LUMP_SUM,1", "100000,LUMP_SUM,2", "49999,INSTALLMENT,2",
            "50000,INSTALLMENT,1", "50000,INSTALLMENT,13"})
    void rejectsInvalidPreservedTransaction(long amount, Plan plan, int months) {
        assertThatThrownBy(() -> new Transaction(amount, plan, months, transaction().occurredAt(), 10L, "CAFE", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"1,LUMP_SUM,1", "50000,INSTALLMENT,2", "50000,INSTALLMENT,12"})
    void acceptsBoundaryTransactions(long amount, Plan plan, int months) {
        assertThatCode(() -> new Transaction(amount, plan, months, transaction().occurredAt(), 10L, "CAFE", Map.of()))
                .doesNotThrowAnyException();
    }

    private Conditions withSchedule(Schedule schedule) {
        return new Conditions(true, true, POLICY_START, BenefitPolicy.NO_END_DATE, conditions().targets(), Set.of(), Set.of(), schedule);
    }

    private Transaction at(String instant, Map<String, Boolean> facts) {
        var transaction = transaction();
        return new Transaction(transaction.amount(), transaction.plan(), transaction.installmentMonths(),
                Instant.parse(instant), transaction.merchantId(), transaction.merchantCategoryCode(), facts);
    }
}
