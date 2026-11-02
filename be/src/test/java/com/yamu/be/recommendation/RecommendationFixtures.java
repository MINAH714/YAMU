package com.yamu.be.recommendation;

import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.card.domain.CardRegistrationStatus;
import com.yamu.be.recommendation.BenefitPolicy.*;
import com.yamu.be.recommendation.RecommendationInput.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 상품 Seed를 대체하지 않는, 정책 경계 검증용 명시적 입력. */
final class RecommendationFixtures {
    static final LocalDate POLICY_START = LocalDate.of(2020, 1, 1);
    private RecommendationFixtures() { }

    static BigDecimal decimal(String value) { return new BigDecimal(value); }

    static Transaction transaction() {
        return new Transaction(100_000, Plan.LUMP_SUM, 1, Instant.parse("2026-10-30T14:00:00Z"),
                10L, "COFFEE_SHOP", Map.of());
    }

    static Conditions conditions() {
        return new Conditions(true, true, POLICY_START, BenefitPolicy.NO_END_DATE,
                List.of(new Target(TargetType.ALL, null, null, false)), Set.of(), Set.of(), Schedule.unrestricted());
    }

    static Rounding rounding() {
        return new Rounding(RoundingMode.DOWN, BigDecimal.ONE, RoundingStage.PER_TRANSACTION, null);
    }

    static Benefit benefit(long id, String value) {
        return new Benefit(id, 1, "검증용 혜택 " + id, BenefitType.DISCOUNT, CalculationType.FIXED,
                decimal(value), Unit.KRW, null, null, 0L, 0L, conditions(), rounding(),
                CalculationOrder.ROUND_THEN_LIMIT, List.of());
    }

    static Benefit change(Benefit source, Conditions conditions, Rounding rounding,
                          CalculationOrder order, List<Long> limitIds) {
        return new Benefit(source.id(), source.version(), source.name(), source.type(), source.calculationType(),
                source.rateOrFixedValue(), source.unit(), source.pointKrwRate(), source.baseAmount(),
                source.minimumPayment(), source.minimumPerformance(), conditions, rounding, order, limitIds);
    }

    static BenefitPolicy policy(List<Benefit> benefits, List<Limit> limits, List<List<Long>> combinations) {
        return new BenefitPolicy(true, 400_000L, BigDecimal.ZERO, benefits, limits, combinations);
    }

    static BenefitPolicy fixedPolicy(String value) {
        return policy(List.of(benefit(1, value)), List.of(), List.of(List.of(1L)));
    }

    static CardCandidate card(long id, String value, PerformanceProgress progress) {
        return new CardCandidate(id, 1, CardType.CREDIT, CardRegistrationStatus.ACTIVE,
                CardIssuerStatus.NORMAL, Availability.AVAILABLE, null, fixedPolicy(value), progress, null, null);
    }

    static PerformanceProgress progress(long before, long minimum, long contribution) {
        return new PerformanceProgress(YearMonth.of(2026, 10), before, minimum, contribution);
    }

    static RecommendationInput input(Long defaultCard, CardCandidate... cards) {
        return new RecommendationInput(100, 1, transaction(), defaultCard, List.of(cards));
    }
}
