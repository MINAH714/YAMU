package com.yamu.be.recommendation;

import static com.yamu.be.recommendation.RecommendationFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.card.domain.CardRegistrationStatus;
import com.yamu.be.recommendation.RecommendationInput.*;
import com.yamu.be.recommendation.RecommendationSelector.*;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RecommendationSelectorTest {
    private final RecommendationSelector selector = new RecommendationSelector();

    @Test
    void higherBenefitWinsOverDefaultAndPerformance() {
        Result result = selector.select(input(1L,
                card(1, "500", progress(290_000, 300_000, 10_000)), card(2, "800", null)));
        assertThat(result.selectedUserCardId()).isEqualTo(2L);
        assertThat(result.status()).isEqualTo(Status.READY);
        assertThat(result.uncomparedCards()).isEmpty();
        assertThat(result.selectionReason()).isEqualTo(SelectionReason.MAXIMUM_NET_BENEFIT);
        assertThat(result.performanceTieBreakSkipped()).isFalse();
        assertThat(result.message()).doesNotContain("기본 카드");
    }

    @Test
    void exactTieUsesDefaultBeforeMissingAuxiliaryInformation() {
        Result result = selector.select(input(2L, card(1, "500.0", null), card(2, "500.00", null)));
        assertThat(result.selectedUserCardId()).isEqualTo(2L);
        assertThat(result.selectionReason()).isEqualTo(SelectionReason.DEFAULT_CARD);
        assertThat(result.performanceTieBreakSkipped()).isFalse();
    }

    @Test
    void doesNotUseDisplayRoundingOrNearEquality() {
        // 공식 환산 후의 소수 원화 차이를 보존한다.
        var first = pointPolicy("500.01");
        var second = pointPolicy("500.02");
        Result result = selector.select(input(1L, withPolicy(card(1, "0", null), first),
                withPolicy(card(2, "0", null), second)));
        assertThat(result.selectedUserCardId()).isEqualTo(2L);
    }

    @Test
    void newlyMetMinimumWinsOnlyWhenExactlyTied() {
        Result result = selector.select(input(null,
                card(1, "500", progress(280_000, 300_000, 10_000)),
                card(2, "500", progress(290_000, 300_000, 10_000))));
        assertThat(result.selectedUserCardId()).isEqualTo(2L);
        assertThat(result.selectionReason()).isEqualTo(SelectionReason.NEW_MINIMUM_PERFORMANCE);
        assertThat(result.message()).doesNotContain("다음 달");
    }

    @ParameterizedTest
    @CsvSource({"280000,10000", "300000,10000", "400000,10000"})
    void deficitAndAchievementRateDoNotGivePreference(long before, long contribution) {
        Result result = selector.select(input(null,
                card(2, "500", progress(before, 300_000, contribution)),
                card(1, "500", progress(10_000, 300_000, 0))));
        assertThat(result.selectedUserCardId()).isEqualTo(1L);
        assertThat(result.selectionReason()).isEqualTo(SelectionReason.FIXED_ORDER);
    }

    @Test
    void multipleNewlyMetCardsUseFixedOrderWithinThoseCards() {
        Result result = selector.select(input(null,
                card(1, "500", progress(0, 300_000, 0)),
                card(9, "500", progress(290_000, 300_000, 10_000)),
                card(5, "500", progress(280_000, 300_000, 20_000))));
        assertThat(result.selectedUserCardId()).isEqualTo(5L);
        assertThat(result.selectionReason()).isEqualTo(SelectionReason.FIXED_ORDER);
        assertThat(result.message()).contains("새로 충족하는 카드들 중");
    }

    @Test
    void anyUnknownProgressSkipsCriterionForEntireMaximumTie() {
        Result result = selector.select(input(null, card(1, "500", null),
                card(2, "500", progress(290_000, 300_000, 10_000))));
        assertThat(result.selectedUserCardId()).isEqualTo(1L);
        assertThat(result.performanceTieBreakSkipped()).isTrue();
    }

    @Test
    void unknownRecognitionPeriodSkipsAuxiliaryCriterion() {
        // 누적액 조회 월의 선택은 RecommendationPerformanceAssemblerTest에서 검증한다.
        var progress = new PerformanceProgress(null, 290_000L, 300_000L, 10_000L);
        Result result = selector.select(input(null, card(1, "500", progress(0, 300_000, 0)),
                card(2, "500", progress)));
        assertThat(result.selectedUserCardId()).isEqualTo(1L);
        assertThat(result.performanceTieBreakSkipped()).isTrue();
    }

    @Test
    void missingRequiredPerformanceAmountsOnlySkipAuxiliaryTieCriterion() {
        YearMonth period = YearMonth.of(2026, 10);
        for (var unknown : List.of(new PerformanceProgress(period, null, 300_000L, 10_000L),
                new PerformanceProgress(period, 290_000L, null, 10_000L),
                new PerformanceProgress(period, 290_000L, 300_000L, null))) {
            Result result = selector.select(input(null, card(1, "500", unknown),
                    card(2, "500", progress(290_000, 300_000, 10_000))));
            assertThat(result.status()).isEqualTo(Status.READY);
            assertThat(result.selectedUserCardId()).isEqualTo(1L);
            assertThat(result.performanceTieBreakSkipped()).isTrue();
            assertThat(result.uncomparedCards()).isEmpty();
        }
    }

    @Test
    void unknownProgressOnLowerBenefitCardDoesNotSuppressMaximumTieCriterion() {
        Result result = selector.select(input(null, card(3, "1", null),
                card(1, "500", progress(0, 300_000, 0)),
                card(2, "500", progress(290_000, 300_000, 10_000))));
        assertThat(result.selectedUserCardId()).isEqualTo(2L);
        assertThat(result.performanceTieBreakSkipped()).isFalse();
    }

    @Test
    void confirmedZeroWithUnknownCardKeepsPartialStatusAndSelectsDefault() {
        Result result = selector.select(input(1L, card(1, "0", null), withPolicy(card(2, "0", null), null)));
        assertThat(result.status()).isEqualTo(Status.PARTIAL_READY);
        assertThat(result.selectedUserCardId()).isEqualTo(1L);
        assertThat(result.noBenefit()).isTrue();
        assertThat(result.rankedCards()).extracting(CardEvaluation::userCardId).containsExactly(1L);
        assertThat(result.evaluatedCards()).extracting(CardEvaluation::status)
                .containsExactly(CardStatus.CALCULATED, CardStatus.NOT_COMPARED);
        assertThat(result.message()).contains("확인된 카드 중", "0원");
    }

    @Test
    void confirmedZeroStillUsesTieBreakAndExplainsNoBenefit() {
        Result result = selector.select(input(2L, card(1, "0", null), card(2, "0", null)));
        assertThat(result.status()).isEqualTo(Status.READY);
        assertThat(result.noBenefit()).isTrue();
        assertThat(result.selectedUserCardId()).isEqualTo(2L);
        assertThat(result.message()).contains("0원", "기본 카드");
    }

    @Test
    void excludedDefaultWithMissingPolicyDoesNotBlockOtherCandidates() {
        var excluded = new CardCandidate(1, 1, CardType.CREDIT, CardRegistrationStatus.ACTIVE,
                CardIssuerStatus.NORMAL, Availability.UNAVAILABLE, "CREDIT_LIMIT_EXCEEDED", null, null);
        Result result = selector.select(input(1L, excluded, card(2, "500", null)));
        assertThat(result.status()).isEqualTo(Status.READY);
        assertThat(result.selectedUserCardId()).isEqualTo(2L);
        assertThat(result.evaluatedCards().get(0).reason()).isEqualTo("CREDIT_LIMIT_EXCEEDED");
    }

    @Test
    void missingAvailabilityIsNotAnExclusionOrUnlimitedCredit() {
        var unknown = new CardCandidate(2, 1, CardType.CREDIT, CardRegistrationStatus.ACTIVE,
                CardIssuerStatus.NORMAL, Availability.UNKNOWN, "CREDIT_LIMIT_UNKNOWN", null, null);
        assertThat(selector.select(input(null, card(1, "500", null), unknown)).status())
                .isEqualTo(Status.PARTIAL_READY);
    }

    @Test
    void recommendsMaximumConfirmedBenefitAndIncludesUncomparedPolicyReason() {
        Result result = selector.select(input(3L, card(1, "800", null), card(2, "500", null),
                withPolicy(card(3, "0", null), null)));
        assertThat(result.status()).isEqualTo(Status.PARTIAL_READY);
        assertThat(result.selectedUserCardId()).isEqualTo(1L);
        assertThat(result.noBenefit()).isFalse();
        assertThat(result.rankedCards()).extracting(CardEvaluation::userCardId).containsExactly(1L, 2L);
        assertThat(result.uncomparedCards()).hasSize(1);
        CardEvaluation missing = result.uncomparedCards().get(0);
        assertThat(missing.userCardId()).isEqualTo(3L);
        assertThat(missing.status()).isEqualTo(CardStatus.NOT_COMPARED);
        assertThat(missing.reason()).isEqualTo("BENEFIT_DATA_INSUFFICIENT");
        assertThat(missing.calculation().netBenefit()).isNull();
        assertThat(missing.calculation().problems()).extracting(CardBenefitCalculator.Problem::code)
                .containsExactly("POLICY_INCOMPLETE");
        assertThat(missing.message()).contains("비교에서 제외", "관리자에게 문의");
        assertThat(result.message()).contains("확인된 카드 중", "800원").doesNotContain("더 클 수");
        assertThat(missing.message()).doesNotContain("더 클 수");
        assertThatThrownBy(() -> result.uncomparedCards().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void onlyUnknownCardsHaveInsufficientDataWithoutSelectionOrRanking() {
        Result result = selector.select(input(null, withPolicy(card(1, "0", null), null), unknownAvailability(2)));
        assertThat(result.status()).isEqualTo(Status.DATA_INSUFFICIENT);
        assertThat(result.selectedUserCardId()).isNull();
        assertThat(result.selectionReason()).isNull();
        assertThat(result.rankedCards()).isEmpty();
        assertThat(result.noBenefit()).isFalse();
        assertThat(result.uncomparedCards()).hasSize(2);
    }

    @Test
    void excludedAndUnknownCardsHaveInsufficientDataRatherThanNoEligibleCard() {
        Result result = selector.select(input(null, excluded(1), unknownAvailability(2)));
        assertThat(result.status()).isEqualTo(Status.DATA_INSUFFICIENT);
        assertThat(result.rankedCards()).isEmpty();
        assertThat(result.uncomparedCards()).extracting(CardEvaluation::userCardId).containsExactly(2L);
        assertThat(result.evaluatedCards().get(0).status()).isEqualTo(CardStatus.EXCLUDED);
    }

    @Test
    void partialResultNeverSelectsUnknownAvailabilityEvenWhenItIsDefault() {
        Result result = selector.select(input(2L, card(1, "500", null), unknownAvailability(2)));
        assertThat(result.status()).isEqualTo(Status.PARTIAL_READY);
        assertThat(result.selectedUserCardId()).isEqualTo(1L);
        assertThat(result.uncomparedCards().get(0).reason()).isEqualTo("BALANCE_LOOKUP_TIMEOUT");
        assertThat(result.uncomparedCards().get(0).calculation()).isNull();
        assertThat(result.uncomparedCards().get(0).message()).contains("결제 가능 여부");
    }

    @Test
    void partialZeroTieStillUsesDefaultCard() {
        Result result = selector.select(input(2L, card(1, "0", null), card(2, "0", null), unknownAvailability(3)));
        assertThat(result.status()).isEqualTo(Status.PARTIAL_READY);
        assertThat(result.selectedUserCardId()).isEqualTo(2L);
        assertThat(result.selectionReason()).isEqualTo(SelectionReason.DEFAULT_CARD);
        assertThat(result.noBenefit()).isTrue();
    }

    @Test
    void partialTieStillUsesNewMinimumAndIgnoresAuxiliaryDataOnUncomparedCard() {
        Result result = selector.select(input(null, card(1, "500", progress(0, 300_000, 0)),
                card(2, "500", progress(290_000, 300_000, 10_000)), unknownAvailability(3)));
        assertThat(result.status()).isEqualTo(Status.PARTIAL_READY);
        assertThat(result.selectedUserCardId()).isEqualTo(2L);
        assertThat(result.selectionReason()).isEqualTo(SelectionReason.NEW_MINIMUM_PERFORMANCE);
        assertThat(result.performanceTieBreakSkipped()).isFalse();
    }

    @Test
    void auxiliaryInformationAloneDoesNotMakeReadyPartial() {
        Result result = selector.select(input(null, card(1, "500", null),
                card(2, "500", progress(290_000, 300_000, 10_000))));
        assertThat(result.status()).isEqualTo(Status.READY);
        assertThat(result.performanceTieBreakSkipped()).isTrue();
        assertThat(result.uncomparedCards()).isEmpty();
        assertThat(result.selectedUserCardId()).isEqualTo(1L);
    }

    @Test
    void partialTieSkipsAuxiliaryCriterionForAllRemainingConfirmedTies() {
        Result result = selector.select(input(null, card(1, "500", null),
                card(2, "500", progress(290_000, 300_000, 10_000)), unknownAvailability(3)));
        assertThat(result.status()).isEqualTo(Status.PARTIAL_READY);
        assertThat(result.selectedUserCardId()).isEqualTo(1L);
        assertThat(result.performanceTieBreakSkipped()).isTrue();
        assertThat(result.uncomparedCards()).extracting(CardEvaluation::userCardId).containsExactly(3L);
    }

    @Test
    void partialShuffledInputAndRepeatedCallsKeepConfirmedRankingAndMissingReasons() {
        var cards = List.of(card(1, "800", null), card(2, "500", null), unknownAvailability(3), excluded(4));
        Result expected = selector.select(new RecommendationInput(100, 1, transaction(), null, cards));
        for (int seed = 0; seed < 20; seed++) {
            var shuffled = new ArrayList<>(cards);
            Collections.shuffle(shuffled, new java.util.Random(seed));
            assertThat(selector.select(new RecommendationInput(100, 1, transaction(), null, shuffled))).isEqualTo(expected);
        }
        assertThat(IntStream.range(0, 30).parallel().mapToObj(index -> selector.select(
                new RecommendationInput(100, 1, transaction(), null, cards))).toList()).allMatch(expected::equals);
    }

    private CardCandidate unknownAvailability(long id) {
        return new CardCandidate(id, 1, CardType.CREDIT, CardRegistrationStatus.ACTIVE,
                CardIssuerStatus.NORMAL, Availability.UNKNOWN, "BALANCE_LOOKUP_TIMEOUT", fixedPolicy("9999"), null);
    }

    private CardCandidate excluded(long id) {
        return new CardCandidate(id, 1, CardType.CREDIT, CardRegistrationStatus.ACTIVE,
                CardIssuerStatus.NORMAL, Availability.UNAVAILABLE, "UNPAID_BILL", null, null);
    }

    @Test
    void inactiveSuspendedExpiredAndCheckInstallmentCardsAreExcludedBeforeCalculation() {
        var transaction = new Transaction(50_000, Plan.INSTALLMENT, 2, transaction().occurredAt(), 10L, "CAFE", java.util.Map.of());
        var inactive = new CardCandidate(1, 1, CardType.CREDIT, CardRegistrationStatus.INACTIVE,
                CardIssuerStatus.NORMAL, Availability.UNKNOWN, "UNKNOWN", null, null);
        var suspended = new CardCandidate(2, 1, CardType.CREDIT, CardRegistrationStatus.ACTIVE,
                CardIssuerStatus.SUSPENDED, Availability.UNKNOWN, "UNKNOWN", null, null);
        var expired = new CardCandidate(3, 1, CardType.CREDIT, CardRegistrationStatus.ACTIVE,
                CardIssuerStatus.EXPIRED, Availability.UNKNOWN, "UNKNOWN", null, null);
        var check = new CardCandidate(4, 1, CardType.CHECK, CardRegistrationStatus.ACTIVE,
                CardIssuerStatus.NORMAL, Availability.UNKNOWN, "UNKNOWN", null, null);
        Result result = selector.select(new RecommendationInput(100, 1, transaction, null,
                List.of(inactive, suspended, expired, check)));
        assertThat(result.status()).isEqualTo(Status.NO_ELIGIBLE_CARD);
        assertThat(result.rankedCards()).isEmpty();
        assertThat(result.evaluatedCards()).allMatch(card -> card.status() == CardStatus.EXCLUDED);
    }

    @Test
    void emptyCandidatesHaveNoSelection() {
        assertThat(selector.select(input(null)).status()).isEqualTo(Status.NO_ELIGIBLE_CARD);
    }

    @Test
    void additionalCostReducesEconomicValue() {
        var policy = fixedPolicy("800");
        policy = new BenefitPolicy(true, policy.previousMonthPerformance(), decimal("400"),
                policy.benefits(), policy.limits(), policy.allowedCombinations());
        assertThat(selector.select(input(null, card(1, "500", null),
                withPolicy(card(2, "0", null), policy))).selectedUserCardId()).isEqualTo(1L);
    }

    @Test
    void shuffledInputsAndConcurrentRetriesProduceSameImmutableResult() {
        var candidates = List.of(card(1, "500", progress(0, 300_000, 0)),
                card(2, "500", progress(290_000, 300_000, 10_000)), card(3, "300", null));
        var request = new RecommendationInput(100, 1, transaction(), null, candidates);
        Result expected = selector.select(request);
        for (int index = 0; index < 20; index++) {
            var shuffled = new ArrayList<>(candidates);
            Collections.shuffle(shuffled, new java.util.Random(index));
            assertThat(selector.select(new RecommendationInput(100, 1, transaction(), null, shuffled))).isEqualTo(expected);
        }
        assertThat(IntStream.range(0, 50).parallel().mapToObj(index -> selector.select(request)).toList())
                .allMatch(expected::equals);
        assertThat(request.cards()).containsExactlyElementsOf(candidates);
        assertThatThrownBy(() -> expected.rankedCards().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsDuplicateCardsAndOtherUserData() {
        var candidate = card(1, "500", null);
        assertThatThrownBy(() -> input(null, candidate, candidate)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecommendationInput(100, 2, transaction(), null, List.of(candidate)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void performanceComparisonDoesNotOverflow() {
        assertThat(progress(Long.MAX_VALUE - 1, Long.MAX_VALUE, 2).newlyMeetsMinimum()).isTrue();
    }

    private CardCandidate withPolicy(CardCandidate card, BenefitPolicy policy) {
        return new CardCandidate(card.userCardId(), card.userId(), card.cardType(), card.registrationStatus(),
                card.issuerStatus(), card.availability(), card.availabilityReason(), policy, card.performance());
    }

    private BenefitPolicy pointPolicy(String rate) {
        var benefit = new BenefitPolicy.Benefit(1, 1, "공식 환산 검증", BenefitPolicy.BenefitType.POINT,
                BenefitPolicy.CalculationType.FIXED, BigDecimal.ONE, BenefitPolicy.Unit.POINT,
                decimal(rate), null, 0L, 0L, conditions(), rounding(),
                BenefitPolicy.CalculationOrder.ROUND_THEN_LIMIT, List.of());
        return policy(List.of(benefit), List.of(), List.of(List.of(1L)));
    }
}
