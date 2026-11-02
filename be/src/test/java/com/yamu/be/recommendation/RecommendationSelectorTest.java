package com.yamu.be.recommendation;

import static com.yamu.be.recommendation.RecommendationFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.card.domain.CardRegistrationStatus;
import com.yamu.be.recommendation.RecommendationInput.*;
import com.yamu.be.recommendation.RecommendationSelector.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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
                CardIssuerStatus.NORMAL, Availability.UNAVAILABLE, "CREDIT_LIMIT_EXCEEDED", null, null, null, null);
        Result result = selector.select(input(1L, excluded, card(2, "500", null)));
        assertThat(result.status()).isEqualTo(Status.READY);
        assertThat(result.selectedUserCardId()).isEqualTo(2L);
        assertThat(result.evaluatedCards().get(0).reason()).isEqualTo("CREDIT_LIMIT_EXCEEDED");
    }

    @Test
    void missingAvailabilityIsNotAnExclusionOrUnlimitedCredit() {
        var unknown = new CardCandidate(2, 1, CardType.CREDIT, CardRegistrationStatus.ACTIVE,
                CardIssuerStatus.NORMAL, Availability.UNKNOWN, "CREDIT_LIMIT_UNKNOWN", null, null, null, null);
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
                CardIssuerStatus.NORMAL, Availability.UNKNOWN, "BALANCE_LOOKUP_TIMEOUT", fixedPolicy("9999"), null, null, null);
    }

    private CardCandidate excluded(long id) {
        return new CardCandidate(id, 1, CardType.CREDIT, CardRegistrationStatus.ACTIVE,
                CardIssuerStatus.NORMAL, Availability.UNAVAILABLE, "UNPAID_BILL", null, null, null, null);
    }

    @Test
    void inactiveSuspendedExpiredAndCheckInstallmentCardsAreExcludedBeforeCalculation() {
        var transaction = new Transaction(50_000, Plan.INSTALLMENT, 2, transaction().occurredAt(), 10L, "CAFE", java.util.Map.of());
        var inactive = new CardCandidate(1, 1, CardType.CREDIT, CardRegistrationStatus.INACTIVE,
                CardIssuerStatus.NORMAL, Availability.UNKNOWN, "UNKNOWN", null, null, null, null);
        var suspended = new CardCandidate(2, 1, CardType.CREDIT, CardRegistrationStatus.ACTIVE,
                CardIssuerStatus.SUSPENDED, Availability.UNKNOWN, "UNKNOWN", null, null, null, null);
        var expired = new CardCandidate(3, 1, CardType.CREDIT, CardRegistrationStatus.ACTIVE,
                CardIssuerStatus.EXPIRED, Availability.UNKNOWN, "UNKNOWN", null, null, null, null);
        var check = new CardCandidate(4, 1, CardType.CHECK, CardRegistrationStatus.ACTIVE,
                CardIssuerStatus.NORMAL, Availability.UNKNOWN, "UNKNOWN", null, null, null, null);
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
                card.issuerStatus(), card.availability(), card.availabilityReason(), policy, card.performance(),
                card.displayOrder(), card.registeredAt());
    }

    @Test
    void singleCardIsRecommendedByMaximumBenefit() {
        Result result = selector.select(input(null, card(7, "800", null)));
        assertThat(result.status()).isEqualTo(Status.READY);
        assertThat(result.selectedUserCardId()).isEqualTo(7L);
        assertThat(result.selectionReason()).isEqualTo(SelectionReason.MAXIMUM_NET_BENEFIT);
        assertThat(result.rankedCards()).hasSize(1);
    }

    @Test
    void defaultCardThatIsSoleMaximumIsExplainedByBenefitRatherThanPreference() {
        Result result = selector.select(input(7L, card(1, "500", null), card(7, "800", null)));
        assertThat(result.selectedUserCardId()).isEqualTo(7L);
        assertThat(result.selectionReason()).isEqualTo(SelectionReason.MAXIMUM_NET_BENEFIT);
        assertThat(result.message()).contains("순혜택이 가장 큰").doesNotContain("기본 카드");
    }

    @Test
    void storedOrderWinsOverOppositeIdOrderOnlyWithinMaximumTie() {
        var first = ordered(card(1, "500", null), 10, "2026-01-01T00:00:00");
        var second = ordered(card(9, "500", null), 0, "2026-02-01T00:00:00");
        Result result = selector.select(input(null, first, second));
        assertThat(result.selectedUserCardId()).isEqualTo(9L);
        assertThat(result.selectionReason()).isEqualTo(SelectionReason.FIXED_ORDER);
        assertThat(selector.select(input(null, first, ordered(card(9, "400", null), 0,
                "2026-02-01T00:00:00"))).selectedUserCardId()).isEqualTo(1L);
    }

    @Test
    void unspecifiedOrderFollowsAssignedOrdersThenRegistrationThenIdAsCardManagement() {
        var assigned = ordered(card(9, "500", null), 5, "2026-03-01T00:00:00");
        var older = ordered(card(7, "500", null), null, "2026-01-01T00:00:00");
        var newer = ordered(card(1, "500", null), null, "2026-02-01T00:00:00");
        assertThat(selector.select(input(null, older, newer, assigned)).selectedUserCardId()).isEqualTo(9L);
        assertThat(selector.select(input(null, newer, older)).selectedUserCardId()).isEqualTo(7L);
        assertThat(selector.select(input(null, older, ordered(card(2, "500", null), null,
                "2026-01-01T00:00:00"))).selectedUserCardId()).isEqualTo(2L);
        // DB의 ASC NULL 등록 시각도 동일하게 앞에 두며, 조회 순서를 사용하지 않는다.
        assertThat(selector.select(input(null, older, card(8, "500", null))).selectedUserCardId()).isEqualTo(8L);
    }

    @Test
    void defaultNewMinimumAndCompleteFixedTiesAreStableForEveryInputPermutation() {
        var notMet = progress(0, 300_000, 0);
        var newlyMet = progress(290_000, 300_000, 10_000);
        var one = ordered(card(1, "500", notMet), 0, "2026-01-01T00:00:00");
        var two = ordered(card(2, "500", newlyMet), 8, "2026-01-01T00:00:00");
        var three = ordered(card(3, "500", newlyMet), 2, "2026-01-01T00:00:00");
        var four = ordered(card(4, "500", notMet), null, "2025-01-01T00:00:00");
        var five = ordered(card(5, "500", notMet), 1, "2026-01-01T00:00:00");
        var candidates = List.of(one, two, three, four, five);
        // 기본 카드가 저장 순서·실적보다 우선한다.
        assertEveryPermutation(candidates, 4L, 4L, SelectionReason.DEFAULT_CARD);
        // 신규 충족 여러 장 중 저장 순서가 앞선 카드가 우선한다.
        assertEveryPermutation(candidates, null, 3L, SelectionReason.FIXED_ORDER);
        // 모두 이미 충족/미달 상태이면 저장 순서로 결정한다.
        var completeTie = candidates.stream().map(card -> new CardCandidate(card.userCardId(), card.userId(),
                card.cardType(), card.registrationStatus(), card.issuerStatus(), card.availability(),
                card.availabilityReason(), card.policy(), notMet, card.displayOrder(), card.registeredAt())).toList();
        assertEveryPermutation(completeTie, null, 1L, SelectionReason.FIXED_ORDER);
    }

    @Test
    void invalidBaseAmountOnlyMakesThatCardUncompared() {
        var invalid = invalidBaseCard();
        Result result = selector.select(input(9L, invalid, card(1, "800", null)));
        assertThat(result.status()).isEqualTo(Status.PARTIAL_READY);
        assertThat(result.selectedUserCardId()).isEqualTo(1L);
        assertThat(result.rankedCards()).extracting(CardEvaluation::userCardId).containsExactly(1L);
        var missing = result.uncomparedCards().get(0);
        assertThat(missing.reason()).isEqualTo("BASE_AMOUNT_INVALID");
        assertThat(missing.calculation().problems()).extracting(CardBenefitCalculator.Problem::code)
                .containsExactly("BASE_AMOUNT_INVALID");
        assertThat(missing.calculation().netBenefit()).isNull();
        Result noValid = selector.select(input(null, invalid, excluded(2)));
        assertThat(noValid.status()).isEqualTo(Status.DATA_INSUFFICIENT);
        assertThat(noValid.selectedUserCardId()).isNull();
        assertThat(noValid.rankedCards()).isEmpty();
    }

    @Test
    void missingValidityIsUncomparedRatherThanConfirmedZero() {
        var source = conditions();
        var unknownEnd = new BenefitPolicy.Conditions(true, true, source.validFrom(), null,
                source.targets(), source.requiredFacts(), source.excludedFacts(), source.schedule());
        var benefit = change(benefit(1, "900"), unknownEnd, rounding(),
                BenefitPolicy.CalculationOrder.ROUND_THEN_LIMIT, List.of());
        var candidate = withPolicy(card(9, "0", null), policy(List.of(benefit), List.of(), List.of(List.of(1L))));
        var result = selector.select(input(null, candidate, card(1, "800", null)));
        assertThat(result.status()).isEqualTo(Status.PARTIAL_READY);
        assertThat(result.selectedUserCardId()).isEqualTo(1L);
        assertThat(result.uncomparedCards().get(0).calculation().netBenefit()).isNull();
    }

    private CardCandidate invalidBaseCard() {
        var benefit = new BenefitPolicy.Benefit(1, 1, "기준액 오류", BenefitPolicy.BenefitType.DISCOUNT,
                BenefitPolicy.CalculationType.RATE, decimal("0.1"), BenefitPolicy.Unit.KRW, null,
                transaction().amount() + 1, 0L, 0L, conditions(), rounding(),
                BenefitPolicy.CalculationOrder.ROUND_THEN_LIMIT, List.of());
        return withPolicy(card(9, "0", null), policy(List.of(benefit), List.of(), List.of(List.of(1L))));
    }

    private CardCandidate ordered(CardCandidate card, Integer order, String registeredAt) {
        return new CardCandidate(card.userCardId(), card.userId(), card.cardType(), card.registrationStatus(),
                card.issuerStatus(), card.availability(), card.availabilityReason(), card.policy(), card.performance(),
                order, registeredAt == null ? null : LocalDateTime.parse(registeredAt));
    }

    private void assertEveryPermutation(List<CardCandidate> candidates, Long defaultCard, long selectedId,
                                        SelectionReason reason) {
        Result expected = selector.select(new RecommendationInput(100, 1, transaction(), defaultCard, candidates));
        assertThat(expected.selectedUserCardId()).isEqualTo(selectedId);
        assertThat(expected.selectionReason()).isEqualTo(reason);
        var permutations = new ArrayList<List<CardCandidate>>();
        permutations(new ArrayList<>(candidates), 0, permutations);
        assertThat(permutations).hasSize(120);
        for (var permutation : permutations) {
            assertThat(selector.select(new RecommendationInput(100, 1, transaction(), defaultCard, permutation)))
                    .isEqualTo(expected);
        }
    }

    private void permutations(List<CardCandidate> cards, int index, List<List<CardCandidate>> result) {
        if (index == cards.size()) {
            result.add(List.copyOf(cards));
            return;
        }
        for (int next = index; next < cards.size(); next++) {
            Collections.swap(cards, index, next);
            permutations(cards, index + 1, result);
            Collections.swap(cards, index, next);
        }
    }

    private BenefitPolicy pointPolicy(String rate) {
        var benefit = new BenefitPolicy.Benefit(1, 1, "공식 환산 검증", BenefitPolicy.BenefitType.POINT,
                BenefitPolicy.CalculationType.FIXED, BigDecimal.ONE, BenefitPolicy.Unit.POINT,
                decimal(rate), null, 0L, 0L, conditions(), rounding(),
                BenefitPolicy.CalculationOrder.ROUND_THEN_LIMIT, List.of());
        return policy(List.of(benefit), List.of(), List.of(List.of(1L)));
    }
}
