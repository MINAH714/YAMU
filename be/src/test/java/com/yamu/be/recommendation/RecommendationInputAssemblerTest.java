package com.yamu.be.recommendation;

import static com.yamu.be.recommendation.RecommendationRows.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.recommendation.domain.*;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecommendationInputAssemblerTest {
    final RecommendationInputAssembler assembler = new RecommendationInputAssembler(new ObjectMapper());
    final LocalDate date = LocalDate.of(2026, 10, 1);
    final ArrayList<RecommendationDataIssue> issues = new ArrayList<>();

    @Test
    void mapsStoredBenefitsAndSharedLimitOnceWithoutAddingDetailUsageToMonthlyAggregate() {
        var data = new RecommendationPolicyData(List.of(benefit(1), benefit(2)), List.of(), List.of(link(1), link(2)),
                List.of(group()), List.of(), List.of(usage(0L)), List.of());
        var policy = assembler.policy(card(1), data, date, 400_000L, issues);
        assertThat(policy.benefits()).hasSize(2).allSatisfy(b -> {
            assertThat(b.limitIds()).containsExactly(100L);
            assertThat(b.rateOrFixedValue()).isEqualByComparingTo("0.05");
        });
        assertThat(policy.limits()).singleElement().satisfies(l -> {
            assertThat(l.maximum()).isEqualTo(5_000L);
            assertThat(l.used()).isZero();
        });
        assertThat(policy.previousMonthPerformance()).isEqualTo(400_000L);
        assertThat(policy.additionalCost()).isEqualTo(java.math.BigDecimal.ZERO);
        assertThat(policy.complete()).isFalse();
        assertThat(policy.allowedCombinations()).isNull();
        assertThat(policy.benefits()).allSatisfy(b -> {
            assertThat(b.calculationOrder()).isNull();
            assertThat(b.baseAmount()).isNull();
            assertThat(b.conditions().verified()).isFalse();
        });
    }

    @Test
    void missingMonthlyRowAfterSuccessfulReadMeansNoUsageYet() {
        // S15P31E103-324: 정상 조회했는데 그 달 행이 없으면 아직 혜택을 쓰지 않은 0이다. 행은 첫 혜택 사용 승인 때 생긴다
        var policy = assembler.policy(card(1), data(List.of(), List.of()), date, null, issues);
        assertThat(policy.limits().get(0).used()).isZero();
        assertThat(issues).extracting(RecommendationDataIssue::code).doesNotContain("LIMIT_USAGE_MISSING");
    }

    @Test
    void rejectsOtherProductUsageAsUnknownInsteadOfZero() {
        // 같은 카드·그룹인데 상품이 다른 행은 자료 불일치다. 행 없음(0)으로 바꾸지 않는다
        var wrong = usage(999L);
        setField(wrong, "cardProductId", 99L);
        var policy = assembler.policy(card(1), data(List.of(), List.of(wrong)), date, null, issues);
        assertThat(policy.limits().get(0).used()).isNull();
        assertThat(issues).extracting(RecommendationDataIssue::code).contains("LIMIT_USAGE_MISSING");
    }

    @Test
    void successfulEmptyDailyAndMonthlyResultsMeanZeroWhileMissingPreviousPerformanceRemainsUnknown() {
        var daily = group();
        setField(daily, "id", 101L);
        setField(daily, "groupCode", "DAILY");
        setField(daily, "periodType", BenefitPolicy.Period.DAILY);
        var dailyLink = link(1);
        setField(dailyLink, "limitGroupId", 101L);
        var data = new RecommendationPolicyData(List.of(benefit(1)), List.of(), List.of(link(1), dailyLink),
                List.of(group(), daily), List.of(), List.of(), List.of());

        var policy = assembler.policy(card(1), data, date, null, issues);

        assertThat(policy.limits()).filteredOn(l -> l.id() == 101).singleElement()
                .satisfies(l -> assertThat(l.used()).isZero());
        assertThat(policy.limits()).filteredOn(l -> l.id() == 100).singleElement()
                .satisfies(l -> assertThat(l.used()).isZero());
        assertThat(policy.previousMonthPerformance()).isNull();
        assertThat(issues).extracting(RecommendationDataIssue::code).doesNotContain("LIMIT_USAGE_MISSING");
    }

    @Test
    void dailyUsageMatchesCardProductAndGroupWithoutAddingUnrelatedRows() {
        var daily = group();
        setField(daily, "periodType", BenefitPolicy.Period.DAILY);
        var data = new RecommendationPolicyData(List.of(benefit(1)), List.of(), List.of(link(1)),
                List.of(daily), List.of(), List.of(), List.of(
                        dailyUsage(2L, 10L, 100L, 900L), dailyUsage(1L, 99L, 100L, 800L),
                        dailyUsage(1L, 10L, 101L, 700L), dailyUsage(1L, 10L, 100L, 3L)));
        assertThat(assembler.policy(card(1), data, date, null, issues).limits()).singleElement()
                .satisfies(l -> assertThat(l.used()).isEqualTo(3L));
    }

    private PaymentLimitUsageRepository.DailyUsage dailyUsage(long card, long product, long group, long value) {
        return new PaymentLimitUsageRepository.DailyUsage() {
            public Long getUserCardId() { return card; }
            public Long getCardProductId() { return product; }
            public Long getLimitGroupId() { return group; }
            public java.math.BigDecimal getUsedValue() { return java.math.BigDecimal.valueOf(value); }
        };
    }

    @Test
    void selectsHalfOpenTierFromPreviousPerformanceAndDoesNotGuessFallback() {
        var lower = tier(0L, 300_000L, 1_000L);
        var upper = tier(300_000L, null, 5_000L);
        var group = group();
        setField(group, "baseLimitValue", null);
        var data = new RecommendationPolicyData(List.of(benefit(1)), List.of(), List.of(link(1)),
                List.of(group), List.of(lower, upper), List.of(), List.of());
        assertThat(assembler.policy(card(1), data, date, 299_999L, issues).limits().get(0).maximum()).isEqualTo(1_000L);
        assertThat(assembler.policy(card(1), data, date, 300_000L, issues).limits().get(0).maximum()).isEqualTo(5_000L);
        assertThat(assembler.policy(card(1), data, date, null, issues).limits().get(0).maximum()).isNull();
        setField(lower, "maxPerformanceAmount", 400_000L);
        assertThat(assembler.policy(card(1), data, date, 300_000L, issues).limits().get(0).maximum()).isNull();
    }

    @Test
    void preservesUnknownDatesAndReportsOverlappingBenefitAndLimitVersions() {
        var first = benefit(1);
        setField(first, "validUntil", null);
        var second = benefit(2);
        setField(second, "benefitCode", first.getBenefitCode());
        setField(second, "policyVersion", 2);
        var nextGroup = group();
        setField(nextGroup, "id", 101L);
        setField(nextGroup, "policyVersion", 2);
        var data = new RecommendationPolicyData(List.of(first, second), List.of(), List.of(link(1)),
                List.of(group(), nextGroup), List.of(), List.of(), List.of());
        var result = assembler.policy(card(1), data, date, 0L, issues);
        assertThat(result.benefits().get(0).conditions().validUntil()).isNull();
        assertThat(result.limits().get(0).maximum()).isNull();
        assertThat(issues).extracting(RecommendationDataIssue::code)
                .contains("BENEFIT_VALIDITY_UNKNOWN", "BENEFIT_VERSION_AMBIGUOUS", "LIMIT_VERSION_OR_VALIDITY_UNKNOWN");
    }

    @Test
    void mapsTargetFactsAndStrictScheduleWithoutInferringPolicyVerification() {
        var benefit = benefit(1);
        setField(benefit, "conditionsJson", "{\"weekdays\":[\"FRI\"],\"startTime\":\"22:00\",\"endTime\":\"02:00\"}");
        var target = row(BenefitTarget.class, "cardBenefitId", 1L, "targetType", BenefitTarget.Kind.CONDITION,
                "targetValue", "CHANNEL:ONLINE", "excluded", true);
        var data = new RecommendationPolicyData(List.of(benefit), List.of(target), List.of(), List.of(), List.of(), List.of(), List.of());
        var converted = assembler.policy(card(1), data, date, 0L, issues).benefits().get(0);
        assertThat(converted.conditions().excludedFacts()).containsExactly("CHANNEL:ONLINE");
        assertThat(converted.conditions().schedule().weekdays()).containsExactly(DayOfWeek.FRIDAY);
        assertThat(converted.conditions().verified()).isFalse();
        setField(benefit, "conditionsJson", "{\"startTime\":null,\"endTime\":\"02:00\"}");
        assertThat(assembler.policy(card(1), data, date, 0L, issues).benefits().get(0).conditions().schedule()).isNull();
        assertThat(issues).extracting(RecommendationDataIssue::code).contains("SCHEDULE_INVALID");
    }

    @Test
    void doesNotDeriveUnroundedAggregateFromFinalBenefitOrLimitUsage() {
        var benefit = benefit(1);
        setField(benefit, "roundingStage", BenefitPolicy.RoundingStage.AFTER_AGGREGATION);
        var data = new RecommendationPolicyData(List.of(benefit), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        assertThat(assembler.policy(card(1), data, date, null, issues).benefits().get(0).rounding().aggregateRawBefore()).isNull();
        assertThat(issues).extracting(RecommendationDataIssue::code).contains("RAW_AGGREGATION_SCOPE_AND_VALUE_UNKNOWN");
    }

    private RecommendationPolicyData data(List<TierLimit> tiers, List<MonthlyLimitUsage> usage) {
        return new RecommendationPolicyData(List.of(benefit(1)), List.of(), List.of(link(1)), List.of(group()), tiers, usage, List.of());
    }

    private MonthlyLimitUsage usage(long amount) {
        return row(MonthlyLimitUsage.class, "userCardId", 1L, "cardProductId", 10L, "limitGroupId", 100L,
                "usageMonth", date.withDayOfMonth(1), "usedValue", amount);
    }

    private TierLimit tier(long lower, Long upper, long value) {
        return row(TierLimit.class, "limitGroupId", 100L, "minPerformanceAmount", lower,
                "maxPerformanceAmount", upper, "limitValue", value);
    }
}
