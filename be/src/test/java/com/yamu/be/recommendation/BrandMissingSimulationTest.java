package com.yamu.be.recommendation;

import static com.yamu.be.recommendation.RecommendationFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.recommendation.BenefitPolicy.*;
import com.yamu.be.recommendation.RecommendationInput.Transaction;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BrandMissingSimulationTest {
    final CardBenefitCalculator calculator = new CardBenefitCalculator();
    final CalculationOptions enabled = new CalculationOptions(true);
    final Transaction tx = new Transaction(transaction().amount(), transaction().plan(), 1,
            transaction().occurredAt(), null, "COFFEE_SHOP", Map.of());
    final Target brand = new Target(TargetType.BRAND, 10L, null, false);
    final Target category = new Target(TargetType.CATEGORY, null, "COFFEE_SHOP", false);

    @Test void relationshipProbeDoesNotRecordUnusedLowerPriorityBrandRegardlessOfOrder() {
        var first = benefit(1, "1000");
        var second = withConditions(2, brandOnly());
        CardBenefitCalculator.Calculation baseline = null;
        for (var options : List.of(CalculationOptions.DEFAULT, enabled)) {
            for (boolean reversed : List.of(false, true)) {
                var rows = reversed ? List.of(second, first) : List.of(first, second);
                var members = rows.stream().map(b -> new ApplicationMember(b.id(), (int) b.id())).toList();
                var policy = new BenefitPolicy(true, 400_000L, BigDecimal.ZERO, rows, List.of(), null,
                        List.of(new ApplicationGroup(1, ApplicationMode.PRIORITY_ONE, members)), Map.of());
                var result = calculator.calculate(tx, policy, options);
                assertThat(result.netBenefit()).isEqualByComparingTo("1000");
                assertThat(result.missingInformation()).isEmpty();
                assertThat(result.excludedBenefits()).isEmpty();
                assertThat(result.simulationRules()).isEmpty();
                if (baseline == null) baseline = result;
                else assertThat(result).isEqualTo(baseline);
                assertThat(calculator.calculate(tx, policy)).isEqualTo(result);
            }
        }
    }

    @Test void skippedAmountCalculationOmitsRoundingButRetainsActuallyUsedPolicyEvidence() {
        var rule = new SimulationRule("YAMU_SIM_CARD_POLICY", 1, "검증용 모의 정책", 7L, 1L,
                List.of("minimumPayment=200000", "validity=YAMU_MOCK_2026-11_KST", "roundingMode=DOWN", "roundingUnit=1"));
        for (String path : List.of("minimum", "condition", "applied")) {
            var original = benefit(1, "1000");
            var c = path.equals("condition")
                    ? targets(List.of(new TargetGroup(1, false, List.of(new Target(TargetType.CATEGORY, null, "OTHER", false)))), Set.of(), Set.of())
                    : conditions();
            long minimum = path.equals("minimum") ? 200_000L : 0L;
            var b = new Benefit(1, 1, original.name(), original.type(), original.calculationType(), original.rateOrFixedValue(),
                    original.unit(), original.pointKrwRate(), original.baseAmount(), minimum, 0L, c,
                    original.rounding(), original.calculationOrder(), List.of());
            var actualRule = new SimulationRule(rule.id(), rule.version(), rule.description(), rule.userCardId(), rule.benefitId(),
                    List.of("minimumPayment=" + minimum, "validity=YAMU_MOCK_2026-11_KST", "roundingMode=DOWN", "roundingUnit=1"));
            var policy = new BenefitPolicy(true, 400_000L, BigDecimal.ZERO, List.of(b), List.of(), List.of(List.of(1L)),
                    null, Map.of(), List.of(actualRule));
            for (var options : List.of(CalculationOptions.DEFAULT, enabled)) {
                var result = calculator.calculate(tx, policy, options);
                assertThat(result.complete()).isTrue();
                assertThat(result.netBenefit()).isEqualByComparingTo(path.equals("applied") ? "1000" : "0");
                var fields = result.simulationRules().stream().flatMap(r -> r.appliedFields().stream()).toList();
                if (path.equals("minimum")) assertThat(fields).containsExactly("minimumPayment=200000");
                else if (path.equals("condition")) assertThat(fields).containsExactly("minimumPayment=0", "validity=YAMU_MOCK_2026-11_KST");
                else assertThat(fields).containsExactlyElementsOf(actualRule.appliedFields());
                assertThat(calculator.calculate(tx, policy).simulationRules()).isEmpty();
            }
        }
    }

    Conditions targets(List<TargetGroup> groups, Set<String> facts, Set<String> exclusions) {
        return new Conditions(true, true, POLICY_START, BenefitPolicy.NO_END_DATE, List.of(), facts,
                exclusions, Schedule.unrestricted(), groups);
    }
    Conditions brandOnly() { return targets(List.of(new TargetGroup(1, false, List.of(brand))), Set.of(), Set.of()); }
    Benefit withConditions(long id, Conditions c) {
        return change(benefit(id, id == 1 ? "1000" : "500"), c, rounding(), CalculationOrder.ROUND_THEN_LIMIT, List.of());
    }
    BenefitPolicy legacy(Conditions c) {
        return policy(List.of(withConditions(1, c), benefit(2, "500")), List.of(), List.of(List.of(1L, 2L)));
    }
    BenefitPolicy grouped(ApplicationMode mode, Conditions first, Map<Long, Fallback> fallbacks) {
        return new BenefitPolicy(true, 400_000L, BigDecimal.ZERO, List.of(withConditions(1, first), benefit(2, "500")),
                List.of(), null, List.of(new ApplicationGroup(1, mode, List.of(
                        new ApplicationMember(1, mode == ApplicationMode.PRIORITY_ONE ? 1 : null),
                        new ApplicationMember(2, mode == ApplicationMode.PRIORITY_ONE ? 2 : null)))), fallbacks);
    }

    @Test void defaultOffAndExistingJsonRemainCompatible() throws Exception {
        var policy = legacy(brandOnly());
        var original = calculator.calculate(tx, policy);
        assertThat(original.complete()).isFalse();
        assertThat(original.netBenefit()).isNull();
        assertThat(original.problems()).extracting(CardBenefitCalculator.Problem::code).containsExactly("CONDITION_UNKNOWN");
        var explicitOff = calculator.calculate(tx, policy, CalculationOptions.DEFAULT);
        assertThat(explicitOff.problems()).isEqualTo(original.problems());
        assertThat(explicitOff.netBenefit()).isNull();
        assertThat(explicitOff.excludedBenefits()).isEmpty();
        assertThat(explicitOff.missingInformation()).extracting(CardBenefitCalculator.MissingInformation::code).containsExactly("BRAND_MISSING");
        var json = new ObjectMapper().valueToTree(original);
        assertThat(json.has("missingInformation")).isFalse();
        assertThat(json.has("excludedBenefits")).isFalse();
        assertThat(json.has("simulationRules")).isFalse();
        assertThat(new ObjectMapper().treeToValue(json, CardBenefitCalculator.Calculation.class)).isEqualTo(original);
    }

    @Test void exclusionIsNotZeroBenefitAndDoesNotLeakAcrossCalls() {
        var result = calculator.calculate(tx, legacy(brandOnly()), enabled);
        assertThat(result.complete()).isTrue();
        assertThat(result.netBenefit()).isEqualByComparingTo("500");
        assertThat(result.problems()).isEmpty();
        assertThat(result.excludedBenefits()).containsExactly(new CardBenefitCalculator.ExcludedBenefit(1, "BRAND_MISSING"));
        assertThat(result.benefits()).extracting(CardBenefitCalculator.BenefitValue::benefitId).containsExactly(2L);
        assertThat(result.simulationRules()).singleElement().satisfies(rule -> assertThat(rule.benefitId()).isEqualTo(1L));
        assertThat(calculator.calculate(tx, legacy(brandOnly())).complete()).isFalse();
        assertThat(calculator.calculate(transaction(), legacy(brandOnly()), enabled).netBenefit()).isEqualByComparingTo("1500");
    }

    @Test void matchedCategoryOrPathSurvivesButAndPathNeedsBrand() {
        var or = targets(List.of(new TargetGroup(1, false, List.of(brand)), new TargetGroup(2, false, List.of(category))), Set.of(), Set.of());
        var result = calculator.calculate(tx, legacy(or), enabled);
        assertThat(result.netBenefit()).isEqualByComparingTo("1500");
        assertThat(result.missingInformation()).isEmpty();
        assertThat(result.excludedBenefits()).isEmpty();
        var and = targets(List.of(new TargetGroup(1, false, List.of(brand, category))), Set.of(), Set.of());
        assertThat(calculator.calculate(tx, legacy(and), enabled).netBenefit()).isEqualByComparingTo("500");
    }

    @Test void brandExclusionIsNotDeletedAndOtherMismatchSuppressesMissing() {
        var c = targets(List.of(new TargetGroup(1, false, List.of(category)), new TargetGroup(2, true, List.of(brand))), Set.of(), Set.of());
        assertThat(calculator.calculate(tx, legacy(c), enabled).excludedBenefits()).hasSize(1);
        var wrongCategory = new Target(TargetType.CATEGORY, null, "OTHER", false);
        var mismatch = targets(List.of(new TargetGroup(1, false, List.of(brand, wrongCategory))), Set.of(), Set.of());
        var result = calculator.calculate(tx, legacy(mismatch), enabled);
        assertThat(result.netBenefit()).isEqualByComparingTo("500");
        assertThat(result.missingInformation()).isEmpty();
        assertThat(result.excludedBenefits()).isEmpty();
    }

    @Test void disabledFallbackKeepsOriginalProblemCodeAndAmounts() {
        var fixture = new BenefitCompositionTest();
        var preferred = fixture.rate(1, "0.05", Unit.KRW, List.of(9L));
        var base = fixture.rate(2, "0.015", Unit.KRW, List.of());
        base = change(base, brandOnly(), base.rounding(), base.calculationOrder(), base.limitIds());
        var policy = fixture.policy(List.of(preferred, base), List.of(fixture.limit(9, Unit.KRW, 1000, 0)),
                ApplicationMode.PRIORITY_ONE, Map.of(1L, new Fallback(2, "EXCESS_PAYMENT_AMOUNT")));
        var existing = calculator.calculate(tx, policy);
        var disabled = calculator.calculate(tx, policy, CalculationOptions.DEFAULT);
        assertThat(disabled.problems()).isEqualTo(existing.problems());
        assertThat(disabled.netBenefit()).isEqualTo(existing.netBenefit());
        assertThat(disabled.benefits()).isEqualTo(existing.benefits());
        assertThat(disabled.limitUses()).isEqualTo(existing.limitUses());
        assertThat(disabled.excludedBenefits()).isEmpty();
    }

    @Test void channelFacilityAndPolicyUnknownAreNotResolvedByBrandOption() {
        var c = targets(List.of(new TargetGroup(1, false, List.of(brand))), Set.of("CHANNEL:ONLINE"), Set.of("FACILITY:DEPARTMENT_STORE"));
        var result = calculator.calculate(tx, legacy(c), enabled);
        assertThat(result.complete()).isFalse();
        assertThat(result.netBenefit()).isNull();
        assertThat(result.excludedBenefits()).isEmpty();
        assertThat(result.missingInformation()).extracting(CardBenefitCalculator.MissingInformation::code)
                .containsExactlyInAnyOrder("BRAND_MISSING", "CHANNEL_MISSING", "FACILITY_MISSING");
        var noSchedule = new Conditions(true, true, POLICY_START, BenefitPolicy.NO_END_DATE, List.of(brand), Set.of(), Set.of(), null);
        assertThat(calculator.calculate(tx, legacy(noSchedule), enabled).complete()).isFalse();
    }

    @ParameterizedTest @EnumSource(value = ApplicationMode.class, names = {"STACK", "MAX_ONE"})
    void independentGroupCalculatesSelectedScope(ApplicationMode mode) {
        var result = calculator.calculate(tx, grouped(mode, brandOnly(), Map.of()), enabled);
        assertThat(result.netBenefit()).isEqualByComparingTo("500");
        assertThat(result.excludedBenefits()).hasSize(1);
    }

    @Test void priorityAndFallbackDoNotSkipUnknownPreferredBenefit() {
        for (var fallbacks : List.of(Map.<Long, Fallback>of(), Map.of(1L, new Fallback(2, "EXCESS_PAYMENT_AMOUNT")))) {
            var result = calculator.calculate(tx, grouped(ApplicationMode.PRIORITY_ONE, brandOnly(), fallbacks), enabled);
            assertThat(result.complete()).isFalse();
            assertThat(result.netBenefit()).isNull();
            assertThat(result.problems()).extracting(CardBenefitCalculator.Problem::code).containsExactly("PRIORITY_CONDITION_UNKNOWN");
            assertThat(result.excludedBenefits()).isEmpty();
            assertThat(result.missingInformation()).extracting(CardBenefitCalculator.MissingInformation::code).containsExactly("BRAND_MISSING");
        }
    }

    @Test void excludedBenefitDoesNotConsumeSharedLimitAndEmptySelectedScopeIsZero() {
        var b = change(withConditions(1, brandOnly()), brandOnly(), rounding(), CalculationOrder.ROUND_THEN_LIMIT, List.of(9L));
        var regular = change(benefit(2, "500"), conditions(), rounding(), CalculationOrder.ROUND_THEN_LIMIT, List.of(9L));
        var limit = new Limit(9, 1, Unit.KRW, Period.MONTHLY, 600L, 0L, null, null);
        var policy = policy(List.of(b, regular), List.of(limit), List.of(List.of(1L, 2L)));
        var result = calculator.calculate(tx, policy, enabled);
        assertThat(result.netBenefit()).isEqualByComparingTo("500");
        assertThat(result.limitUses()).singleElement().satisfies(use -> assertThat(use.consumed()).isEqualByComparingTo("500"));
        result = calculator.calculate(tx, policy(List.of(b), List.of(limit), List.of(List.of(1L))), enabled);
        assertThat(result.complete()).isTrue();
        assertThat(result.netBenefit()).isZero();
        assertThat(result.benefits()).isEmpty();
        assertThat(result.limitUses()).isEmpty();
        assertThat(result.excludedBenefits()).hasSize(1);
    }

    @Test void suppliedAssumptionsAreReturnedWithoutAutomaticallyApplyingBasicPurchase() {
        var c = targets(List.of(new TargetGroup(1, false, List.of(category))), Set.of(), Set.of("FACILITY:DEPARTMENT_STORE"));
        assertThat(calculator.calculate(tx, legacy(c), enabled).complete()).isFalse();
        assertThat(calculator.calculate(tx, legacy(c), enabled).simulationRules()).isEmpty();
        var prepared = BasicPurchaseSimulation.apply(tx, null, null);
        var result = calculator.calculate(prepared, legacy(c), enabled);
        assertThat(result.complete()).isTrue();
        assertThat(result.simulationRules()).isEqualTo(prepared.simulationRules());
        assertThat(prepared.facts()).doesNotContainKey("CHANNEL:ONLINE");
    }

    @Test void legacyFlatOrAndExclusionHaveSameBrandSemantics() {
        var flat = new Conditions(true, true, POLICY_START, BenefitPolicy.NO_END_DATE,
                List.of(brand, category), Set.of(), Set.of(), Schedule.unrestricted());
        assertThat(calculator.calculate(tx, legacy(flat), enabled).netBenefit()).isEqualByComparingTo("1500");
        var except = new Conditions(true, true, POLICY_START, BenefitPolicy.NO_END_DATE,
                List.of(category, new Target(TargetType.BRAND, 10L, null, true)), Set.of(), Set.of(), Schedule.unrestricted());
        assertThat(calculator.calculate(tx, legacy(except), enabled).netBenefit()).isEqualByComparingTo("500");
    }

    @Test void knownMinimumFailureDoesNotReportBrandMissing() {
        var b = withConditions(1, brandOnly());
        b = new Benefit(b.id(), b.version(), b.name(), b.type(), b.calculationType(), b.rateOrFixedValue(),
                b.unit(), b.pointKrwRate(), b.baseAmount(), tx.amount() + 1, b.minimumPerformance(), b.conditions(),
                b.rounding(), b.calculationOrder(), b.limitIds());
        var result = calculator.calculate(tx, policy(List.of(b), List.of(), List.of(List.of(1L))), enabled);
        assertThat(result.netBenefit()).isZero();
        assertThat(result.excludedBenefits()).isEmpty();
        assertThat(result.missingInformation()).isEmpty();
    }

    @Test void concurrentCallsDoNotShareExclusionState() {
        java.util.stream.IntStream.range(0, 100).parallel().forEach(i -> {
            var result = calculator.calculate(i % 2 == 0 ? tx : transaction(), legacy(brandOnly()), enabled);
            assertThat(result.netBenefit()).isEqualByComparingTo(i % 2 == 0 ? "500" : "1500");
            assertThat(result.excludedBenefits()).hasSize(i % 2 == 0 ? 1 : 0);
        });
    }

    @Test void excludingBrandDoesNotCompleteAnotherBenefitWithMissingFacility() {
        var facility = targets(List.of(new TargetGroup(1, false, List.of(category))),
                Set.of(), Set.of("FACILITY:DEPARTMENT_STORE"));
        var policy = policy(List.of(withConditions(1, brandOnly()), withConditions(2, facility)),
                List.of(), List.of(List.of(1L, 2L)));
        var result = calculator.calculate(tx, policy, enabled);
        assertThat(result.complete()).isFalse();
        assertThat(result.netBenefit()).isNull();
        assertThat(result.excludedBenefits()).containsExactly(new CardBenefitCalculator.ExcludedBenefit(1, "BRAND_MISSING"));
        assertThat(result.problems()).containsExactly(new CardBenefitCalculator.Problem(2L, "CONDITION_UNKNOWN"));
        assertThat(result.missingInformation()).extracting(CardBenefitCalculator.MissingInformation::code)
                .containsExactly("BRAND_MISSING", "FACILITY_MISSING");
    }

    @Test void fallbackDoesNotReenterAnUnknownBrandTarget() {
        var fixture = new BenefitCompositionTest();
        var preferred = fixture.rate(1, "0.05", Unit.KRW, List.of(9L));
        var base = fixture.rate(2, "0.015", Unit.KRW, List.of());
        base = change(base, brandOnly(), base.rounding(), base.calculationOrder(), base.limitIds());
        var policy = fixture.policy(List.of(preferred, base), List.of(fixture.limit(9, Unit.KRW, 1000, 0)),
                ApplicationMode.PRIORITY_ONE, Map.of(1L, new Fallback(2, "EXCESS_PAYMENT_AMOUNT")));
        var result = calculator.calculate(tx, policy, enabled);
        assertThat(result.complete()).isFalse();
        assertThat(result.netBenefit()).isNull();
        assertThat(result.problems()).extracting(CardBenefitCalculator.Problem::code).containsExactly("FALLBACK_CONDITION_UNKNOWN");
        assertThat(result.excludedBenefits()).isEmpty();
    }
}
