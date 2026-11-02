package com.yamu.be.recommendation;

import static com.yamu.be.recommendation.RecommendationFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.yamu.be.recommendation.BenefitPolicy.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CardBenefitCalculatorTest {
    private final CardBenefitCalculator calculator = new CardBenefitCalculator();

    @Test
    void rateUsesVerifiedBaseAndOfficialPointConversionBeforeComparison() {
        var benefit = new Benefit(1, 3, "포인트", BenefitType.POINT, CalculationType.RATE,
                decimal("0.05"), Unit.POINT, decimal("0.5"), 9_999L, 0L, 0L,
                conditions(), rounding(), CalculationOrder.ROUND_THEN_LIMIT, List.of());
        var result = calculator.calculate(transaction(), policy(List.of(benefit), List.of(), List.of(List.of(1L))));
        assertThat(result.netBenefit()).isEqualByComparingTo("249.5");
        assertThat(result.benefits().get(0).value()).isEqualByComparingTo("499");
        assertThat(result.benefits().get(0).rawValue()).isEqualByComparingTo("499.95");
        assertThat(result.benefits().get(0).version()).isEqualTo(3);
    }

    @ParameterizedTest
    @CsvSource({"DOWN,1", "UP,2", "HALF_UP,2", "HALF_EVEN,2"})
    void usesExplicitProductRounding(RoundingMode mode, String expected) {
        var benefit = change(benefit(1, "1.6"), conditions(),
                new Rounding(mode, BigDecimal.ONE, RoundingStage.PER_TRANSACTION, null),
                CalculationOrder.ROUND_THEN_LIMIT, List.of());
        assertThat(calculate(benefit).netBenefit()).isEqualByComparingTo(expected);
    }

    @Test
    void roundingStageUsesMarginalAggregateRatherThanTransactionRounding() {
        var aggregate = change(benefit(1, "1.6"), conditions(),
                new Rounding(RoundingMode.DOWN, BigDecimal.ONE, RoundingStage.AFTER_AGGREGATION, decimal("1.6")),
                CalculationOrder.ROUND_THEN_LIMIT, List.of());
        assertThat(calculate(aggregate).netBenefit()).isEqualByComparingTo("2");
        assertThat(calculate(benefit(1, "1.6")).netBenefit()).isEqualByComparingTo("1");
    }

    @Test
    void missingRoundingAndAggregateContextAreNotDefaults() {
        var missing = change(benefit(1, "10"), conditions(), null,
                CalculationOrder.ROUND_THEN_LIMIT, List.of());
        assertInsufficient(calculate(missing), "CALCULATION_POLICY_UNKNOWN");
        var aggregate = change(missing, conditions(),
                new Rounding(RoundingMode.DOWN, BigDecimal.ONE, RoundingStage.AFTER_AGGREGATION, null),
                CalculationOrder.ROUND_THEN_LIMIT, List.of());
        assertInsufficient(calculate(aggregate), "CALCULATION_POLICY_UNKNOWN");
    }

    @Test
    void sharedMonthlyLimitIsConsumedLocallyOnceAcrossBenefits() {
        var first = change(benefit(1, "800"), conditions(), rounding(), CalculationOrder.ROUND_THEN_LIMIT, List.of(10L));
        var second = change(benefit(2, "800"), conditions(), rounding(), CalculationOrder.ROUND_THEN_LIMIT, List.of(10L));
        var limit = new Limit(10, 2, Unit.KRW, Period.MONTHLY, 2_000L, 1_000L, null, null);
        var policy = policy(List.of(first, second), List.of(limit), List.of(List.of(1L, 2L)));
        var expected = calculator.calculate(transaction(), policy);
        assertThat(expected.netBenefit()).isEqualByComparingTo("1000");
        assertThat(expected.benefits()).extracting(value -> value.value().longValueExact()).containsExactly(800L, 200L);
        assertThat(expected.limitUses()).hasSize(1);
        assertThat(expected.limitUses().get(0).consumed()).isEqualByComparingTo("1000");
        assertThat(expected.limitUses().get(0).remainingBefore()).isEqualByComparingTo("1000");
        assertThat(expected.limitUses().get(0).version()).isEqualTo(2);
        assertThat(calculator.calculate(transaction(), policy)).isEqualTo(expected);
        assertThat(limit.used()).isEqualTo(1_000L);
    }

    @Test
    void perTransactionDailyAndMonthlyCapsAllApplyInTheirOwnUnit() {
        var benefit = change(benefit(1, "900"), conditions(), rounding(), CalculationOrder.ROUND_THEN_LIMIT,
                List.of(10L, 11L, 12L));
        var limits = List.of(new Limit(10, 1, Unit.KRW, Period.PER_TRANSACTION, 800L, null, null, null),
                new Limit(11, 1, Unit.KRW, Period.DAILY, 1_000L, 400L, null, null),
                new Limit(12, 1, Unit.KRW, Period.MONTHLY, 2_000L, 1_500L, null, null));
        var result = calculator.calculate(transaction(), policy(List.of(benefit), limits, List.of(List.of(1L))));
        assertThat(result.netBenefit()).isEqualByComparingTo("500");
        assertThat(result.limitUses()).allMatch(use -> use.consumed().compareTo(decimal("500")) == 0);
    }

    @ParameterizedTest
    @CsvSource({"PER_BENEFIT,600", "PER_TRANSACTION,1200"})
    void countConsumptionIsExplicitRatherThanAssumed(CountMode mode, String expected) {
        var first = change(benefit(1, "600"), conditions(), rounding(), CalculationOrder.ROUND_THEN_LIMIT, List.of(10L));
        var second = change(benefit(2, "600"), conditions(), rounding(), CalculationOrder.ROUND_THEN_LIMIT, List.of(10L));
        var limit = new Limit(10, 1, Unit.COUNT, Period.DAILY, 1L, 0L, mode, false);
        var result = calculator.calculate(transaction(), policy(List.of(first, second), List.of(limit), List.of(List.of(1L, 2L))));
        assertThat(result.netBenefit()).isEqualByComparingTo(expected);
        assertThat(result.limitUses().get(0).consumed()).isEqualByComparingTo("1");
    }

    @Test
    void zeroBenefitConsumptionFollowsExplicitCountPolicy() {
        var first = change(benefit(1, "0"), conditions(), rounding(), CalculationOrder.ROUND_THEN_LIMIT, List.of(10L));
        var second = change(benefit(2, "600"), conditions(), rounding(), CalculationOrder.ROUND_THEN_LIMIT, List.of(10L));
        for (boolean consumeZero : List.of(false, true)) {
            var limit = new Limit(10, 1, Unit.COUNT, Period.DAILY, 1L, 0L, CountMode.PER_BENEFIT, consumeZero);
            var result = calculator.calculate(transaction(), policy(List.of(first, second), List.of(limit), List.of(List.of(1L, 2L))));
            assertThat(result.netBenefit()).isEqualByComparingTo(consumeZero ? "0" : "600");
        }
    }

    @Test
    void alternativeCombinationsAreComparedInsteadOfSummedOrGreedilySelected() {
        var first = benefit(1, "800");
        var second = benefit(2, "600");
        var third = benefit(3, "600");
        var policy = policy(List.of(first, second, third), List.of(), List.of(List.of(1L), List.of(2L, 3L)));
        var result = calculator.calculate(transaction(), policy);
        assertThat(result.netBenefit()).isEqualByComparingTo("1200");
        assertThat(result.benefits()).extracting(CardBenefitCalculator.BenefitValue::benefitId).containsExactly(2L, 3L);
        var reversed = new BenefitPolicy(true, policy.previousMonthPerformance(), policy.additionalCost(),
                List.of(third, second, first), List.of(), List.of(List.of(2L, 3L), List.of(1L)));
        assertThat(calculator.calculate(transaction(), reversed)).isEqualTo(result);
    }

    @Test
    void unknownAlternativePreventsPretendingKnownAlternativeIsMaximum() {
        var missing = change(benefit(2, "900"), conditions(), null, CalculationOrder.ROUND_THEN_LIMIT, List.of());
        var result = calculator.calculate(transaction(), policy(List.of(benefit(1, "800"), missing),
                List.of(), List.of(List.of(1L), List.of(2L))));
        assertInsufficient(result, "CALCULATION_POLICY_UNKNOWN");
    }

    @Test
    void confirmedConditionExclusionIsZeroEvenWhenUnusedCalculationFieldsAreMissing() {
        var conditions = new Conditions(true, true, POLICY_START, BenefitPolicy.NO_END_DATE,
                List.of(new Target(TargetType.BRAND, 999L, null, false)), Set.of(), Set.of(), Schedule.unrestricted());
        var benefit = change(benefit(1, "500"), conditions, null, null, List.of());
        assertThat(calculate(benefit).netBenefit()).isEqualByComparingTo("0");
    }

    @Test
    void previousMonthEligibilityIsNotIncreasedByTransactionAmount() {
        var benefit = new Benefit(1, 1, "실적 조건", BenefitType.DISCOUNT, CalculationType.FIXED,
                decimal("500"), Unit.KRW, null, null, 0L, 300_000L, conditions(), rounding(),
                CalculationOrder.ROUND_THEN_LIMIT, List.of());
        var policy = new BenefitPolicy(true, 290_000L, BigDecimal.ZERO, List.of(benefit), List.of(), List.of(List.of(1L)));
        assertThat(calculator.calculate(transaction(), policy).netBenefit()).isEqualByComparingTo("0");
        assertThat(calculator.calculate(transaction(), new BenefitPolicy(true, null, BigDecimal.ZERO,
                List.of(benefit), List.of(), List.of(List.of(1L)))).complete()).isFalse();
    }

    @Test
    void missingLimitsAndUsageAreNotUnlimitedOrUnused() {
        var benefit = change(benefit(1, "500"), conditions(), rounding(), CalculationOrder.ROUND_THEN_LIMIT, List.of(10L));
        assertInsufficient(calculate(benefit), "LIMIT_UNKNOWN");
        var missingUsage = new Limit(10, 1, Unit.KRW, Period.MONTHLY, 5_000L, null, null, null);
        assertInsufficient(calculator.calculate(transaction(), policy(List.of(benefit), List.of(missingUsage), List.of(List.of(1L)))),
                "LIMIT_UNKNOWN");
    }

    @Test
    void emptyVerifiedCatalogAndIncompleteCatalogAreDifferent() {
        var empty = policy(List.of(), List.of(), List.of());
        assertThat(calculator.calculate(transaction(), empty).netBenefit()).isEqualByComparingTo("0");
        assertInsufficient(calculator.calculate(transaction(), new BenefitPolicy(false, null, BigDecimal.ZERO,
                List.of(), List.of(), List.of())), "POLICY_INCOMPLETE");
    }

    @Test
    void combinationsMustCoverPolicyAndCannotRepeatBenefits() {
        assertInsufficient(calculator.calculate(transaction(), policy(List.of(benefit(1, "500")), List.of(), null)),
                "BENEFIT_COMBINATIONS_UNKNOWN");
        assertInsufficient(calculator.calculate(transaction(), policy(List.of(benefit(1, "500"), benefit(2, "600")),
                List.of(), List.of(List.of(1L)))), "BENEFIT_COMBINATIONS_INCOMPLETE");
        assertThatThrownBy(() -> policy(List.of(benefit(1, "500")), List.of(), List.of(List.of(1L, 1L))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roundingUnitAndLimitOrderAreExplicitAndConflictsAreNotSilentlyClamped() {
        var benefit = change(benefit(1, "160"), conditions(),
                new Rounding(RoundingMode.HALF_UP, decimal("100"), RoundingStage.PER_TRANSACTION, null),
                CalculationOrder.ROUND_THEN_LIMIT, List.of(10L));
        var limit = new Limit(10, 1, Unit.KRW, Period.PER_TRANSACTION, 180L, null, null, null);
        assertThat(calculator.calculate(transaction(), policy(List.of(benefit), List.of(limit), List.of(List.of(1L)))))
                .extracting(CardBenefitCalculator.Calculation::netBenefit).isEqualTo(decimal("180"));
        var limitFirst = change(benefit, conditions(), benefit.rounding(), CalculationOrder.LIMIT_THEN_ROUND, List.of(10L));
        assertInsufficient(calculator.calculate(transaction(), policy(List.of(limitFirst), List.of(limit), List.of(List.of(1L)))),
                "ROUNDING_LIMIT_CONFLICT");
    }

    @Test
    void pointRateAndCrossUnitLimitNeedVerifiedConversion() {
        var point = new Benefit(1, 1, "포인트", BenefitType.POINT, CalculationType.FIXED, decimal("100"),
                Unit.POINT, null, null, 0L, 0L, conditions(), rounding(), CalculationOrder.ROUND_THEN_LIMIT, List.of());
        assertInsufficient(calculate(point), "CALCULATION_POLICY_UNKNOWN");
        var benefit = change(benefit(1, "500"), conditions(), rounding(), CalculationOrder.ROUND_THEN_LIMIT, List.of(10L));
        var limit = new Limit(10, 1, Unit.POINT, Period.MONTHLY, 5_000L, 0L, null, null);
        assertInsufficient(calculator.calculate(transaction(), policy(List.of(benefit), List.of(limit), List.of(List.of(1L)))),
                "LIMIT_UNIT_CONVERSION_UNVERIFIED");
    }

    @Test
    void missingCostIsNotZeroAndNoAnnualFeeIsInferred() {
        var policy = fixedPolicy("500");
        assertInsufficient(calculator.calculate(transaction(), new BenefitPolicy(true, policy.previousMonthPerformance(), null,
                policy.benefits(), policy.limits(), policy.allowedCombinations())), "ADDITIONAL_COST_UNKNOWN");
    }

    @Test
    void unverifiedRuleVersionCannotEstablishZeroByItsOldTargetOrThreshold() {
        var unverified = new Conditions(true, false, null, null,
                List.of(new Target(TargetType.BRAND, 999L, null, false)), Set.of(), Set.of(), Schedule.unrestricted());
        var benefit = new Benefit(1, 1, "버전 미확인", BenefitType.DISCOUNT, CalculationType.FIXED,
                decimal("500"), Unit.KRW, null, null, 200_000L, 0L, unverified, rounding(),
                CalculationOrder.ROUND_THEN_LIMIT, List.of());
        assertInsufficient(calculate(benefit), "CONDITION_UNKNOWN");
    }

    @Test
    void confirmedMinimumFailureDoesNotRequireUnrelatedConditionsRoundingOrLimits() {
        var partlyKnownConditions = new Conditions(false, true, POLICY_START, BenefitPolicy.NO_END_DATE,
                List.of(new Target(TargetType.ALL, null, null, false)), Set.of("RULE:UNVERIFIED_ROUTE"),
                Set.of(), null);
        var irrelevant = new Benefit(2, 1, "최소 금액 미달 혜택", BenefitType.DISCOUNT, CalculationType.FIXED,
                null, Unit.KRW, null, null, 200_000L, null, partlyKnownConditions, null, null, List.of(999L));
        var result = calculator.calculate(transaction(), policy(List.of(benefit(1, "800"), irrelevant),
                List.of(), List.of(List.of(1L, 2L))));
        assertThat(result.complete()).isTrue();
        assertThat(result.netBenefit()).isEqualByComparingTo("800");
        assertThat(result.benefits().get(1).reason()).isEqualTo("BELOW_MINIMUM_PAYMENT");
    }

    @Test
    void verifiedTargetMismatchDoesNotRequireUnusedBenefitCalculationOrUsage() {
        var nonMatching = new Conditions(true, true, POLICY_START, BenefitPolicy.NO_END_DATE,
                List.of(new Target(TargetType.BRAND, 999L, null, false)), Set.of(), Set.of(), Schedule.unrestricted());
        var irrelevant = change(benefit(2, "1000"), nonMatching, null, null, List.of(999L));
        var result = calculator.calculate(transaction(), policy(List.of(benefit(1, "800"), irrelevant),
                List.of(), List.of(List.of(1L, 2L))));
        assertThat(result.complete()).isTrue();
        assertThat(result.netBenefit()).isEqualByComparingTo("800");
        assertThat(result.problems()).isEmpty();
    }

    private CardBenefitCalculator.Calculation calculate(Benefit benefit) {
        return calculator.calculate(transaction(), policy(List.of(benefit), List.of(), List.of(List.of(benefit.id()))));
    }

    private void assertInsufficient(CardBenefitCalculator.Calculation result, String code) {
        assertThat(result.complete()).isFalse();
        assertThat(result.netBenefit()).isNull();
        assertThat(result.problems()).extracting(CardBenefitCalculator.Problem::code).contains(code);
    }
}
