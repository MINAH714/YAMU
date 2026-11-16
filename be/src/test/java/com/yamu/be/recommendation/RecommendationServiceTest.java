package com.yamu.be.recommendation;

import static com.yamu.be.recommendation.RecommendationFixtures.fixedPolicy;
import static com.yamu.be.recommendation.RecommendationRows.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yamu.be.card.CardActivityReader;
import com.yamu.be.card.RecommendationPerformanceAssembler;
import com.yamu.be.card.domain.*;
import com.yamu.be.recommendation.RecommendationCommand.PaymentAvailability;
import com.yamu.be.recommendation.RecommendationInput.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.slf4j.LoggerFactory;

/** DB 조회를 mock한 서비스 연결 테스트. 실제 Selector를 호출하지만 실제 DB 검증과는 구분한다. */
class RecommendationServiceTest {
    UserCardRepository cards = mock(UserCardRepository.class);
    CardProductRepository products = mock(CardProductRepository.class);
    RecommendationPolicyLoader loader = mock(RecommendationPolicyLoader.class);
    RecommendationInputAssembler assembler = spy(new RecommendationInputAssembler(new ObjectMapper()));
    CardActivityReader activity = mock(CardActivityReader.class);
    RecommendationPerformanceAssembler performance = spy(new RecommendationPerformanceAssembler(activity));
    PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    RecommendationService service;
    RecommendationAvailabilityReader availabilityReader = mock(RecommendationAvailabilityReader.class);
    Logger logger = (Logger) LoggerFactory.getLogger(RecommendationService.class);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void setUp() {
        when(availabilityReader.prepare(any(),anyLong(),anyLong(),any())).thenReturn(
                new RecommendationAvailabilityReader.Assessment(Map.of(1L,available(),2L,available(),3L,available()),List.of()));
        logs.start();
        logger.addAppender(logs);
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new RecommendationService(cards, products, loader, assembler, activity, performance,
                new RecommendationRecognitionResolver(new ObjectMapper()), mock(PurchaseItemReader.class), transactions, availabilityReader,
                mock(com.yamu.be.merchant.MerchantCatalogRepository.class));
        when(cards.findOrdered(eq(1L), anyCollection())).thenReturn(List.of(card(1), card(2), card(3)));
        when(products.findAllById(List.of(10L))).thenReturn(List.of(product()));
        when(loader.load(eq(1L), anyList(), any())).thenReturn(empty());
    }

    @AfterEach
    void detachLogCapture() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @Test void simulationReusesPoliciesWithoutPaymentIdAndKeepsActualPaymentResult() {
        doReturn(fixedPolicy("500")).when(assembler).policy(any(), any(), any(), any(), anyList(), any(), any(), any());
        var command = command(Map.of(1L, available(), 2L, available(), 3L, available()));
        var actual = recommend(command).recommendation();
        var simulated = service.recommendSimulation(1L, command.transaction(), CalculationOptions.DEFAULT).recommendation();
        assertThat(simulated.paymentId()).isNull();
        assertThat(simulated.selectedUserCardId()).isEqualTo(actual.selectedUserCardId());
        assertThat(simulated.evaluatedCards()).isEqualTo(actual.evaluatedCards());
        assertThat(simulated.rankedCards()).isEqualTo(actual.rankedCards());
        assertThat(recommend(command).recommendation()).isEqualTo(actual);
        verify(availabilityReader).prepare(isNull(), eq(1L), eq(10_000L), eq(Plan.LUMP_SUM));
        verify(cards, never()).save(any());
    }

    @Test void simulationChecksOwnershipBeforePolicyLoading() {
        var foreign = card(99);
        setField(foreign, "userId", 2L);
        when(cards.findOrdered(anyLong(), anyCollection())).thenReturn(List.of(foreign));
        assertThatIllegalArgumentException().isThrownBy(() ->
                service.recommendSimulation(1L, command(Map.of()).transaction(), CalculationOptions.DEFAULT));
        verifyNoInteractions(products, loader, activity);
    }

    @Test void simulationOptionsDoNotLeakAndOnlyAppliedRulesAreReturned() {
        var fixture = new BrandMissingSimulationTest();
        doReturn(fixture.legacy(fixture.brandOnly())).when(assembler).policy(any(), any(), any(), any(), anyList(), any(), any(), any());
        var enabled = service.recommendSimulation(1L, fixture.tx, new CalculationOptions(true));
        assertThat(enabled.recommendation().rankedCards()).hasSize(3).allSatisfy(c -> {
            assertThat(c.calculation().netBenefit()).isEqualByComparingTo("500");
            assertThat(c.calculation().excludedBenefits()).hasSize(1);
        });
        assertThat(enabled.simulationRules()).hasSize(3).allSatisfy(r -> assertThat(r.userCardId()).isNotNull());
        var disabled = service.recommendSimulation(1L, fixture.tx, CalculationOptions.DEFAULT);
        assertThat(disabled.recommendation().selectedUserCardId()).isNull();
        assertThat(disabled.recommendation().uncomparedCards()).hasSize(3).allSatisfy(c -> {
            assertThat(c.calculation().netBenefit()).isNull();
            assertThat(c.calculation().excludedBenefits()).isEmpty();
            assertThat(c.calculation().problems()).extracting(CardBenefitCalculator.Problem::code).contains("BRAND_MISSING");
        });
        assertThat(disabled.simulationRules()).isEmpty();
        assertThat(service.recommendSimulation(1L, fixture.tx, new CalculationOptions(true))).isEqualTo(enabled);
    }

    @Test void concurrentSimulationsKeepOptionsIsolated() throws Exception {
        var fixture = new BrandMissingSimulationTest();
        doReturn(fixture.legacy(fixture.brandOnly())).when(assembler).policy(any(), any(), any(), any(), anyList(), any(), any(), any());
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<java.util.concurrent.Callable<Void>>();
            for (int i = 0; i < 12; i++) {
                boolean exclude = i % 2 == 0;
                tasks.add(() -> {
                    var result = service.recommendSimulation(1L, fixture.tx, new CalculationOptions(exclude));
                    assertThat(result.recommendation().paymentId()).isNull();
                    assertThat(result.recommendation().rankedCards()).hasSize(exclude ? 3 : 0);
                    assertThat(result.simulationRules()).hasSize(exclude ? 3 : 0);
                    return null;
                });
            }
            for (var task : pool.invokeAll(tasks)) task.get();
        } finally {
            pool.shutdownNow();
        }
        verify(cards, never()).save(any());
    }

    @Test
    void passesLoadedInputsToRealSelectorAndKeepsPartialReadyExclusionAndConfirmedZeroDistinct() {
        // 테스트에서만 제공하는 확인된 입력. 실제 상품의 정책 공급 설정이 아니다.
        doReturn(fixedPolicy("500")).when(assembler).policy(argThat(c -> c.getId() == 1), any(), any(), any(), anyList(), any(), any(), any());
        doReturn(fixedPolicy("0")).when(assembler).policy(argThat(c -> c.getId() == 2), any(), any(), any(), anyList(), any(), any(), any());
        var available = Map.of(1L, available(), 2L, available(), 3L, available());
        var result = recommend(command(available)).recommendation();
        assertThat(result.status()).isEqualTo(RecommendationSelector.Status.PARTIAL_READY);
        assertThat(result.selectedUserCardId()).isEqualTo(1L);
        assertThat(result.rankedCards()).extracting(RecommendationSelector.CardEvaluation::userCardId).containsExactly(1L, 2L);
        assertThat(result.rankedCards().get(1).calculation().netBenefit()).isZero();
        assertThat(result.uncomparedCards()).extracting(RecommendationSelector.CardEvaluation::userCardId).containsExactly(3L);
        verify(products, times(1)).findAllById(List.of(10L));
        verify(transactions).getTransaction(argThat(t -> t.isReadOnly()
                && t.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        verify(transactions).commit(any());
    }

    @ParameterizedTest
    @EnumSource(Plan.class)
    void supportedTransactionsPassExplicitZeroCostFromRealAssemblerWhileOtherPoliciesRemainUnknown(Plan plan) {
        var data = new RecommendationPolicyData(List.of(benefit(1)), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        when(loader.load(eq(1L), anyList(), any())).thenReturn(data);
        when(activity.recognizedAmounts(anyList(), any())).thenReturn(Map.of());
        var assembled = new ArrayList<BenefitPolicy>();
        // 반환값을 대체하지 않고 실제 Assembler가 만든 객체를 관찰한다. 같은 객체가 엔진까지 전달된다.
        doAnswer(invocation -> {
            var actual = (BenefitPolicy) invocation.callRealMethod();
            assembled.add(actual);
            return actual;
        }).when(assembler).policy(any(), any(), any(), any(), anyList(), any(), any(), any());
        var transaction = new Transaction(50_000, plan, plan == Plan.LUMP_SUM ? 1 : 2,
                Instant.parse("2026-09-30T15:30:00Z"), 20L, "CAFE", Map.of());

        var result = recommend(new RecommendationCommand(100, 1, transaction,
                Map.of(1L, available(), 2L, available(), 3L, available())));

        assertThat(assembled).hasSize(3).allSatisfy(policy -> {
            assertThat(policy.additionalCost()).isEqualTo(java.math.BigDecimal.ZERO);
            assertThat(policy.complete()).isFalse();
            assertThat(policy.allowedCombinations()).isNull();
            assertThat(policy.benefits()).singleElement().satisfies(b -> {
                assertThat(b.conditions().verified()).isFalse();
                assertThat(b.conditions().currentVersionVerified()).isFalse();
                assertThat(b.calculationOrder()).isNull();
            });
        });
        assertThat(result.issues()).extracting(RecommendationDataIssue::code)
                .contains("POLICY_COMPLETENESS_UNVERIFIED", "BENEFIT_COMBINATIONS_UNKNOWN")
                .doesNotContain("ADDITIONAL_COST_UNKNOWN");
        assertThat(result.recommendation().status()).isEqualTo(RecommendationSelector.Status.DATA_INSUFFICIENT);
        assertThat(result.recommendation().uncomparedCards()).hasSize(3).allSatisfy(card ->
                assertThat(card.calculation().problems()).extracting(CardBenefitCalculator.Problem::code)
                        .containsExactly("POLICY_INCOMPLETE"));
    }

    @Test
    void knownUnpayableCardDoesNotRequireBenefitPolicyAndUnknownAvailabilityIsNotAvailable() {
        doReturn(fixedPolicy("500")).when(assembler).policy(argThat(c -> c.getId() == 1), any(), any(), any(), anyList(), any(), any(), any());
        var result = recommend(command(Map.of(1L, available(), 2L,
                new PaymentAvailability(Availability.UNAVAILABLE, "INSUFFICIENT_BALANCE")))).recommendation();
        assertThat(result.status()).isEqualTo(RecommendationSelector.Status.PARTIAL_READY);
        assertThat(result.evaluatedCards().get(1).status()).isEqualTo(RecommendationSelector.CardStatus.EXCLUDED);
        assertThat(result.evaluatedCards().get(2).reason()).isEqualTo("PAYMENT_AVAILABILITY_UNKNOWN");
        verify(assembler, times(1)).policy(any(), any(), any(), any(), anyList(), any(), any(), any());
    }

    @Test
    void readsPreviousMonthFromKoreanTransactionDateRatherThanCurrentServerMonthAndKeepsMissingRow() {
        var data = new RecommendationPolicyData(List.of(benefit(1)), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        when(loader.load(eq(1L), anyList(), eq(LocalDate.of(2026, 10, 1)))).thenReturn(data);
        when(activity.recognizedAmounts(List.of(1L, 2L), YearMonth.of(2026, 9))).thenReturn(Map.of(1L, 0L));
        var result = recommend(command(Map.of(1L, available(), 2L, available())));
        verify(assembler).policy(argThat(c -> c.getId() == 1), eq(data), eq(LocalDate.of(2026, 10, 1)), eq(0L), anyList(), any(), any(), any());
        verify(assembler).policy(argThat(c -> c.getId() == 2), eq(data), eq(LocalDate.of(2026, 10, 1)), isNull(), anyList(), any(), any(), any());
        assertThat(result.issues()).anyMatch(i -> i.userCardId() == 2 && i.code().equals("PREVIOUS_PERFORMANCE_MISSING"));
        verify(activity).recognizedAmounts(List.of(1L, 2L), YearMonth.of(2026, 9));
        verifyNoMoreInteractions(activity);
        verify(performance).assemble(argThat(rows -> rows.stream().allMatch(r -> r.period() == null)));
    }

    @Test
    void missingProductIsNotDroppedOrInventedAndKnownExclusionWins() {
        var first = card(1);
        var second = card(2);
        setField(second, "registrationStatus", CardRegistrationStatus.INACTIVE);
        when(cards.findOrdered(eq(1L), anyCollection())).thenReturn(List.of(first, second));
        when(products.findAllById(List.of(10L))).thenReturn(List.of());
        var result = recommend(command(Map.of(1L, available(), 2L, available()))).recommendation();
        assertThat(result.evaluatedCards().get(0).reason()).isEqualTo("PRODUCT_UNKNOWN");
        assertThat(result.evaluatedCards().get(1).status()).isEqualTo(RecommendationSelector.CardStatus.EXCLUDED);
        assertThat(result.status()).isEqualTo(RecommendationSelector.Status.DATA_INSUFFICIENT);
    }

    @Test
    void rejectsAvailabilityForAnotherUsersCardBeforePolicyQueries() {
        assertThatIllegalArgumentException().isThrownBy(() -> recommend(command(Map.of(999L, available()))));
        verifyNoInteractions(products, loader, activity);
    }

    @Test
    void validatesRepositoryOwnershipBeforeReadingAnyProductOrPerformanceData() {
        var foreign = card(99);
        setField(foreign, "userId", 2L);
        when(cards.findOrdered(anyLong(), anyCollection())).thenReturn(List.of(foreign));
        assertThatIllegalArgumentException().isThrownBy(() -> recommend(command(Map.of())));
        verifyNoInteractions(products, loader, activity);
    }

    @Test
    void malformedPolicyForOneCardDoesNotAbortOtherCalculatedCards() {
        doReturn(fixedPolicy("500")).when(assembler).policy(argThat(c -> c.getId() == 1), any(), any(), any(), anyList(), any(), any(), any());
        doThrow(new IllegalArgumentException("incompatible unit")).when(assembler)
                .policy(argThat(c -> c.getId() == 2), any(), any(), any(), anyList(), any(), any(), any());
        var result = recommend(command(Map.of(1L, available(), 2L, available())));
        assertThat(result.recommendation().status()).isEqualTo(RecommendationSelector.Status.PARTIAL_READY);
        assertThat(result.recommendation().selectedUserCardId()).isEqualTo(1L);
        assertThat(result.issues()).anyMatch(i -> i.userCardId() == 2 && i.code().equals("POLICY_DATA_INVALID"));
        assertThat(result.issues()).noneMatch(RecommendationDataIssue::retryable);
        assertThat(logs.list).isEmpty();
    }

    @Test
    void selfReferencingFallbackLeavesOtherProductComparable() {
        var invalidCard = card(2);
        setField(invalidCard, "cardProductId", 20L);
        var invalidProduct = product();
        setField(invalidProduct, "id", 20L);
        var selfReference = benefit(200L);
        setField(selfReference, "cardProductId", 20L);
        setField(selfReference, "fallbackBenefitId", 200L);
        setField(selfReference, "minPerformanceAmount", 0L);
        when(cards.findOrdered(eq(1L), anyCollection())).thenReturn(List.of(card(1), invalidCard));
        when(products.findAllById(List.of(10L, 20L))).thenReturn(List.of(product(), invalidProduct));
        when(loader.load(eq(1L), anyList(), any())).thenReturn(new RecommendationPolicyData(
                List.of(selfReference), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        doCallRealMethod().when(loader).validateFallbacks(any(), any());
        doReturn(fixedPolicy("500")).when(assembler)
                .policy(argThat(c -> c.getId() == 1), any(), any(), any(), anyList(), any(), any(), any());

        var result = recommend(command(Map.of(1L, available(), 2L, available())));

        assertThat(result.recommendation().status()).isEqualTo(RecommendationSelector.Status.PARTIAL_READY);
        assertThat(result.recommendation().selectedUserCardId()).isEqualTo(1L);
        assertThat(result.issues()).filteredOn(i -> i.code().equals("POLICY_DATA_INVALID"))
                .containsExactly(new RecommendationDataIssue(2L, null, "POLICY_DATA_INVALID", false));
        verify(assembler, never()).policy(eq(invalidCard), any(), any(), any(), anyList(), any(), any(), any());
    }

    @Test
    void transientReadFailureRollsBackReadTransactionAndRemainsRetryableInsteadOfUnregisteredPolicy() {
        when(loader.load(anyLong(), anyList(), any())).thenThrow(new TransientDataAccessResourceException("private database detail"));
        var result = recommend(command(Map.of(1L, available())));
        assertThat(result.recommendation().status()).isEqualTo(RecommendationSelector.Status.DATA_INSUFFICIENT);
        assertThat(result.issues()).allMatch(RecommendationDataIssue::retryable);
        assertThat(result.issues()).extracting(RecommendationDataIssue::code).containsOnly("DATABASE_READ_RETRYABLE");
        assertThat(result.toString()).doesNotContain("private database detail");
        assertThat(result.issues()).allSatisfy(i -> assertThat(i.message()).isEqualTo(RecommendationDataIssue.RETRY_MESSAGE));
        assertSafeLog("POLICY_AND_USAGE_BATCH", "[1]", "DATABASE_READ_RETRYABLE", "TransientDataAccessResourceException");
        verify(transactions).rollback(any());
        verify(transactions, never()).commit(any());
        verify(loader, times(1)).load(anyLong(), anyList(), any());
    }

    @Test
    void failedCandidateLookupIsNotReportedAsNoEligibleCardAndNonTransientFailureIsNotSwallowed() {
        when(cards.findOrdered(anyLong(), anyCollection())).thenThrow(new TransientDataAccessResourceException("private"));
        var failure = catchThrowableOfType(() -> recommend(command(Map.of())),
                RecommendationService.RecommendationReadException.class);
        assertThat(failure.code()).isEqualTo("DATABASE_READ_RETRYABLE");
        assertThat(failure.retryable()).isTrue();
        assertThat(failure.paymentId()).isEqualTo(command(Map.of()).paymentId());
        assertThat(failure.getMessage()).isEqualTo(RecommendationDataIssue.RETRY_MESSAGE);
        assertThat(failure.getCause()).isNull();
        assertSafeLog("CANDIDATE_BATCH", "[]", "DATABASE_READ_RETRYABLE", "TransientDataAccessResourceException");
        assertThat(logs.list.get(0).getFormattedMessage()).contains("scope=UNKNOWN_CANDIDATES");
        doThrow(new DataAccessResourceFailureException("schema failure")).when(cards).findOrdered(anyLong(), anyCollection());
        assertThatThrownBy(() -> recommend(command(Map.of()))).isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void preservesStoredDisplayOrderForExactBenefitTieWithoutAssumingRecognitionPeriod() {
        var first = card(1);
        var second = card(2);
        setField(first, "displayOrder", 1);
        setField(second, "displayOrder", 0);
        when(cards.findOrdered(anyLong(), anyCollection())).thenReturn(List.of(second, first));
        doReturn(fixedPolicy("0")).when(assembler).policy(any(), any(), any(), any(), anyList(), any(), any(), any());
        var result = recommend(command(Map.of(1L, available(), 2L, available()))).recommendation();
        assertThat(result.status()).isEqualTo(RecommendationSelector.Status.READY);
        assertThat(result.selectedUserCardId()).isEqualTo(2L);
        assertThat(result.noBenefit()).isTrue();
        assertThat(result.performanceTieBreakSkipped()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"299999,1,2", "299998,1,1", "300000,1,1", "290000,10000,2"})
    void actualServiceRecognitionReachesSelectorAndCrossingBoundaryChoosesCardOnExactBenefitTie(
            long beforeSecond, long amount, long expected) {
        sourceProducts();
        when(activity.recognizedAmounts(List.of(1L, 2L), YearMonth.of(2026, 10)))
                .thenReturn(Map.of(1L, 280_000L, 2L, beforeSecond));
        var inputs = observeSelectorInput();
        var result = recommend(sourceCommand(amount, RecognitionFixtures.ordinaryDomestic("shinhan-cheoeum")));
        assertThat(result.recommendation().status()).isEqualTo(RecommendationSelector.Status.DATA_INSUFFICIENT);
        var actual = inputs.get(0);
        assertThat(actual.cards()).allSatisfy(c -> {
            assertThat(c.performance().period()).isEqualTo(YearMonth.of(2026, 10));
            assertThat(c.performance().minimumAmount()).isEqualTo(300_000L);
            assertThat(c.performance().recognizedAmount()).isEqualTo(amount);
            assertThat(c.performance().isKnown()).isTrue();
        });
        assertThat(actual.cards().get(1).performance().beforeAmount()).isEqualTo(beforeSecond);
        // 실적 변환/조회는 위 실제 서비스 경로 그대로다. 아직 공급되지 않은 혜택 정책만
        // 별도의 선택기 검증 입력으로 명시한다. 실제 DB 상품 추천 완료를 뜻하지 않는다.
        var selected = selectWithConfirmedEqualBenefits(actual);
        assertThat(selected.selectedUserCardId()).isEqualTo(expected);
        assertThat(selected.performanceTieBreakSkipped()).isFalse();
        if (expected == 2) assertThat(selected.selectionReason()).isEqualTo(RecommendationSelector.SelectionReason.NEW_MINIMUM_PERFORMANCE);
    }

    @Test
    void actualSourceExcludedZeroAndUnknownContributionStayDistinctAndRecognitionMonthIsQueried() {
        sourceProducts();
        when(activity.recognizedAmounts(List.of(1L, 2L), YearMonth.of(2026, 11)))
                .thenReturn(Map.of(1L, 280_000L, 2L, 299_999L));
        var inputs = observeSelectorInput();
        var facts = RecognitionFixtures.ordinaryDomestic("shinhan-cheoeum");
        facts.put("PERFORMANCE:POSTPAID_TRANSIT", true);
        facts.put("PERFORMANCE:MOBILE_POSTPAID_TRANSIT", false);
        facts.put("TRANSACTION_KIND:GIFT_CARD_PURCHASE", true);
        recommend(sourceCommand(10_000, facts));
        assertThat(inputs.get(0).cards()).allSatisfy(c -> {
            assertThat(c.performance().period()).isEqualTo(YearMonth.of(2026, 11));
            assertThat(c.performance().recognizedAmount()).isZero();
            assertThat(c.performance().isKnown()).isTrue();
        });
        assertThat(selectWithConfirmedEqualBenefits(inputs.get(0)).performanceTieBreakSkipped()).isFalse();
        facts.remove("TRANSACTION_KIND:GIFT_CARD_PURCHASE");
        recommend(sourceCommand(10_000, facts));
        assertThat(inputs.get(1).cards()).allSatisfy(c -> {
            assertThat(c.performance().recognizedAmount()).isNull();
            assertThat(c.performance().beforeAmount()).isNotNull();
        });
        assertThat(selectWithConfirmedEqualBenefits(inputs.get(1)).performanceTieBreakSkipped()).isTrue();
        verify(activity, times(2)).recognizedAmounts(List.of(1L, 2L), YearMonth.of(2026, 11));
        verifyNoMoreInteractions(activity);
    }

    @Test
    void transientTieAggregateFailureDoesNotEraseLoadedBenefitPolicyOrInventZero() {
        sourceProducts();
        when(activity.recognizedAmounts(List.of(1L, 2L), YearMonth.of(2026, 10)))
                .thenThrow(new QueryTimeoutException("timeout"));
        var inputs = observeSelectorInput();
        var result = recommend(sourceCommand(10_000, RecognitionFixtures.ordinaryDomestic("shinhan-cheoeum")));
        assertThat(inputs.get(0).cards()).allSatisfy(c -> {
            assertThat(c.policy()).isNotNull();
            assertThat(c.performance().beforeAmount()).isNull();
            assertThat(c.performance().recognizedAmount()).isEqualTo(10_000L);
        });
        assertThat(result.issues()).filteredOn(RecommendationDataIssue::retryable)
                .extracting(RecommendationDataIssue::code).containsOnly("RECOGNITION_AGGREGATE_READ_RETRYABLE");
        // 같은 실적 입력을 확인된 동일 혜택 fixture와 결합해 동률 보조 기준만 생략됨을 검증한다.
        assertThat(selectWithConfirmedEqualBenefits(inputs.get(0)).performanceTieBreakSkipped()).isTrue();
        assertSafeLog("TIE_PERFORMANCE_BATCH", "[1, 2]", "RECOGNITION_AGGREGATE_READ_RETRYABLE", "QueryTimeoutException");
    }

    @Test
    void productBatchFailureLogsAllPotentiallyAffectedCardsWithoutExposingExceptionDetails() throws Exception {
        when(products.findAllById(any())).thenThrow(new QueryTimeoutException(
                "private password=secret token=secret jdbc:mysql://private SQL binding=1234",
                new IllegalStateException("private cause")));
        var result = recommend(command(Map.of(1L, available(), 2L, available())));
        assertSafeLog("PRODUCT_BATCH", "[1, 2, 3]", "DATABASE_READ_RETRYABLE", "QueryTimeoutException");
        assertThat(logs.list.get(0).getFormattedMessage()).contains("scope=BATCH_POTENTIALLY_AFFECTED");
        var json = new ObjectMapper().writeValueAsString(result.issues());
        assertThat(json).contains("\"retryable\":true", "\"message\":\"" + RecommendationDataIssue.RETRY_MESSAGE)
                .doesNotContain("password", "secret", "jdbc:", "binding", "private");
        verifyNoInteractions(loader, activity);
    }

    @Test
    void missingPolicyRemainsNonRetryableWithoutDatabaseFailureLogOrRetryNotice() {
        var result = recommend(command(Map.of(1L, available())));
        assertThat(result.issues()).isNotEmpty().noneMatch(RecommendationDataIssue::retryable);
        assertThat(result.issues()).allSatisfy(i -> assertThat(i.message()).isNull());
        assertThat(logs.list).isEmpty();
        assertThat(result.recommendation().status()).isEqualTo(RecommendationSelector.Status.DATA_INSUFFICIENT);
    }

    @Test
    void previousPerformanceFailureIsNotReplacedWithMissingPolicyOrZero() {
        when(loader.load(anyLong(), anyList(), any())).thenReturn(new RecommendationPolicyData(
                List.of(benefit(1)), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        when(activity.recognizedAmounts(anyList(), any())).thenThrow(new QueryTimeoutException("private"));
        var result = recommend(command(Map.of(1L, available(), 2L, available())));
        assertSafeLog("PREVIOUS_PERFORMANCE_BATCH", "[1, 2]", "DATABASE_READ_RETRYABLE", "QueryTimeoutException");
        assertThat(result.issues()).extracting(RecommendationDataIssue::code).containsOnly("DATABASE_READ_RETRYABLE");
        assertThat(result.issues()).extracting(RecommendationDataIssue::userCardId).containsExactly(1L, 2L);
        assertThat(result.recommendation().status()).isEqualTo(RecommendationSelector.Status.DATA_INSUFFICIENT);
        verify(assembler, never()).policy(any(), any(), any(), any(), anyList(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void previousReadOrCompletionFailureKeepsRealIndependentPolicyAndDiscardsDependentPolicy(boolean completionFailure) {
        var data = mixedPreviousRequirements();
        if (completionFailure) {
            when(activity.recognizedAmounts(List.of(2L), YearMonth.of(2026, 9))).thenReturn(Map.of(2L, 900_000L));
            doNothing().doThrow(new QueryTimeoutException("private")).when(transactions).commit(any());
        } else {
            when(activity.recognizedAmounts(List.of(2L), YearMonth.of(2026, 9))).thenThrow(new QueryTimeoutException("private"));
        }
        var inputs = observeSelectorInput();
        var result = recommend(command(Map.of(1L, available(), 2L, available())));
        var realPolicy = inputs.get(0).cards().get(0).policy();
        assertThat(realPolicy).isNotNull();
        assertThat(realPolicy.complete()).isFalse();
        assertThat(realPolicy.additionalCost()).isEqualByComparingTo("0");
        assertThat(realPolicy.previousMonthPerformance()).isNull();
        assertThat(realPolicy.benefits()).singleElement().satisfies(b -> assertThat(b.minimumPerformance()).isZero());
        assertThat(inputs.get(0).cards().get(1).policy()).isNull();
        assertThat(result.issues()).filteredOn(RecommendationDataIssue::retryable)
                .extracting(RecommendationDataIssue::userCardId).containsExactly(2L);
        assertThat(result.issues()).noneMatch(i -> i.code().equals("PREVIOUS_PERFORMANCE_MISSING"));
        verify(assembler).policy(argThat(c -> c.getId() == 1), same(data), any(), isNull(), anyList(), any(), any(), any());
        verify(assembler, never()).policy(argThat(c -> c.getId() == 2), any(), any(), any(), anyList(), any(), any(), any());
        var order = inOrder(transactions, activity, assembler);
        order.verify(transactions).commit(any());
        order.verify(activity).recognizedAmounts(List.of(2L), YearMonth.of(2026, 9));
        if (completionFailure) {
            order.verify(transactions).commit(any());
        } else {
            order.verify(transactions).rollback(any());
        }
        order.verify(assembler).policy(any(), any(), any(), any(), anyList(), any(), any(), any());
        verify(activity, times(1)).recognizedAmounts(anyList(), any());
        verify(transactions, times(2)).getTransaction(argThat(t -> t.isReadOnly()
                && t.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        assertSafeLog(completionFailure ? "PREVIOUS_PERFORMANCE_TRANSACTION_COMPLETION" : "PREVIOUS_PERFORMANCE_BATCH",
                "[2]", "DATABASE_READ_RETRYABLE", "QueryTimeoutException");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void confirmedPolicyFixturesSelectIndependentCardOnFailureAndPreserveSelectionOnSuccess(boolean querySucceeds) {
        mixedPreviousRequirements();
        // 최종 상태 검증 전용 확인 정책. 실제 조립 경로/complete=false 검증은 위 테스트와 분리한다.
        var confirmedA = fixedPolicy("500");
        var confirmedB = fixedPolicy("1000");
        doReturn(confirmedA).when(assembler).policy(argThat(c -> c.getId() == 1), any(), any(), any(), anyList(), any(), any(), any());
        doReturn(confirmedB).when(assembler).policy(argThat(c -> c.getId() == 2), any(), any(), eq(400_000L), anyList(), any(), any(), any());
        if (querySucceeds) {
            when(activity.recognizedAmounts(List.of(2L), YearMonth.of(2026, 9))).thenReturn(Map.of(2L, 400_000L));
        } else {
            when(activity.recognizedAmounts(List.of(2L), YearMonth.of(2026, 9))).thenThrow(new QueryTimeoutException("private"));
        }
        var inputs = observeSelectorInput();
        var result = recommend(command(Map.of(1L, available(), 2L, available())));
        assertThat(inputs.get(0).cards().get(0).policy()).isSameAs(confirmedA);
        assertThat(result.recommendation().status()).isEqualTo(querySucceeds
                ? RecommendationSelector.Status.READY : RecommendationSelector.Status.PARTIAL_READY);
        assertThat(result.recommendation().selectedUserCardId()).isEqualTo(querySucceeds ? 2L : 1L);
        if (!querySucceeds) {
            assertThat(result.recommendation().uncomparedCards()).extracting(RecommendationSelector.CardEvaluation::userCardId)
                    .containsExactly(2L);
            assertThat(result.issues()).filteredOn(RecommendationDataIssue::retryable)
                    .extracting(RecommendationDataIssue::userCardId).containsExactly(2L);
        } else {
            assertThat(inputs.get(0).cards().get(1).policy()).isSameAs(confirmedB);
            assertThat(result.issues()).noneMatch(RecommendationDataIssue::retryable);
        }
    }

    @Test
    void tierLimitAloneStillRequiresPreviousReadAndOnlyDependentCardIsIsolated() {
        var data = mixedPreviousRequirements();
        setField(data.benefits().get(1), "minPerformanceAmount", 0L);
        var group = group();
        var link = link(1);
        setField(group, "cardProductId", 20L);
        setField(link, "cardProductId", 20L);
        var tier = row(com.yamu.be.recommendation.domain.TierLimit.class, "id", 9L, "limitGroupId", 100L,
                "minPerformanceAmount", 300_000L, "limitValue", 5_000L);
        when(loader.load(anyLong(), anyList(), any())).thenReturn(new RecommendationPolicyData(data.benefits(),
                List.of(), List.of(link), List.of(group), List.of(tier), List.of(), List.of()));
        when(activity.recognizedAmounts(List.of(2L), YearMonth.of(2026, 9))).thenThrow(new QueryTimeoutException("private"));
        var inputs = observeSelectorInput();
        var result = recommend(command(Map.of(1L, available(), 2L, available())));
        assertThat(inputs.get(0).cards().get(0).policy()).isNotNull();
        assertThat(inputs.get(0).cards().get(1).policy()).isNull();
        assertThat(result.issues()).filteredOn(RecommendationDataIssue::retryable)
                .extracting(RecommendationDataIssue::userCardId).containsExactly(2L);
    }

    private RecommendationPolicyData mixedPreviousRequirements() {
        var second = card(2);
        setField(second, "cardProductId", 20L);
        var productB = product();
        setField(productB, "id", 20L);
        when(cards.findOrdered(anyLong(), anyCollection())).thenReturn(List.of(card(1), second));
        when(products.findAllById(List.of(10L, 20L))).thenReturn(List.of(product(), productB));
        var noRequirement = benefit(2);
        setField(noRequirement, "minPerformanceAmount", 0L);
        var dependent = benefit(1);
        setField(dependent, "cardProductId", 20L);
        var data = new RecommendationPolicyData(List.of(noRequirement, dependent), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
        when(loader.load(anyLong(), anyList(), any())).thenReturn(data);
        return data;
    }

    @Test
    void readTransactionCompletionFailureDoesNotContinueToTieReadsWithUncommittedInputs() {
        sourceProducts();
        doThrow(new QueryTimeoutException("private")).when(transactions).commit(any());
        var result = recommend(sourceCommand(10_000, RecognitionFixtures.ordinaryDomestic("shinhan-cheoeum")));
        assertSafeLog("INPUT_READ_TRANSACTION_COMPLETION", "[1, 2]", "DATABASE_READ_RETRYABLE", "QueryTimeoutException");
        assertThat(result.issues()).extracting(RecommendationDataIssue::code).containsOnly("DATABASE_READ_RETRYABLE");
        verifyNoInteractions(activity);
    }

    @Test
    void productReadFailureStillProvidesNoticeWhenThereAreNoActiveBenefitCandidates() {
        var inactive = card(1);
        setField(inactive, "registrationStatus", CardRegistrationStatus.INACTIVE);
        when(cards.findOrdered(anyLong(), anyCollection())).thenReturn(List.of(inactive));
        when(products.findAllById(any())).thenThrow(new QueryTimeoutException("private"));
        var result = recommend(command(Map.of()));
        assertThat(result.issues()).hasSize(1).allSatisfy(i -> {
            assertThat(i.userCardId()).isNull();
            assertThat(i.retryable()).isTrue();
            assertThat(i.message()).isEqualTo(RecommendationDataIssue.RETRY_MESSAGE);
        });
        assertSafeLog("PRODUCT_BATCH", "[1]", "DATABASE_READ_RETRYABLE", "QueryTimeoutException");
    }

    private void assertSafeLog(String stage, String ids, String code, String exceptionType) {
        assertThat(logs.list).hasSize(1);
        var event = logs.list.get(0);
        assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
        assertThat(event.getFormattedMessage()).contains("paymentId=" + command(Map.of()).paymentId(),
                "stage=" + stage, "cardIds=" + ids, "code=" + code,
                "exceptionType=org.springframework.dao." + exceptionType, "retryable=true")
                .doesNotContain("private", "password", "jdbc:", "binding", "secret", "timeout");
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(event.getArgumentArray()).noneMatch(Throwable.class::isInstance);
    }

    private void sourceProducts() {
        when(cards.findOrdered(eq(1L), anyCollection())).thenReturn(List.of(card(1), card(2)));
        when(products.findAllById(List.of(10L))).thenReturn(List.of(RecognitionFixtures.product("shinhan-cheoeum", 10)));
    }

    private List<RecommendationInput> observeSelectorInput() {
        var inputs = new ArrayList<RecommendationInput>();
        var actualSelector = spy(new RecommendationSelector());
        doAnswer(invocation -> {
            inputs.add(invocation.getArgument(0));
            return invocation.callRealMethod();
        }).when(actualSelector).select(any());
        setField(service, "selector", actualSelector);
        return inputs;
    }

    private RecommendationCommand sourceCommand(long amount, Map<String, Boolean> facts) {
        return new RecommendationCommand(100, 1, new Transaction(amount, Plan.LUMP_SUM, 1,
                Instant.parse("2026-09-30T15:30:00Z"), 20L, "COFFEE_SHOP", facts), Map.of(1L, available(), 2L, available()));
    }

    private RecommendationSelector.Result selectWithConfirmedEqualBenefits(RecommendationInput actual) {
        var candidates = actual.cards().stream().map(c -> new CardCandidate(c.userCardId(), c.userId(), c.cardType(),
                c.registrationStatus(), c.issuerStatus(), c.availability(), c.availabilityReason(),
                fixedPolicy("0"), c.performance(), c.displayOrder(), c.registeredAt())).toList();
        return new RecommendationSelector().select(new RecommendationInput(actual.paymentId(), actual.userId(),
                actual.transaction(), actual.defaultUserCardId(), candidates));
    }

    // 정책 조회/실패 격리만 검증하는 테스트의 상태 공급 fixture. 실제 상태 판정은 DB 테스트에서 별도 검증한다.
    private RecommendationService.Result recommend(RecommendationCommand command) {
        when(availabilityReader.prepare(any(),anyLong(),anyLong(),any())).thenReturn(
                new RecommendationAvailabilityReader.Assessment(command.availability(),List.of()));
        return service.recommend(command);
    }
    private RecommendationCommand command(Map<Long, PaymentAvailability> states) {
        return new RecommendationCommand(100, 1, new Transaction(10_000, Plan.LUMP_SUM, 1,
                Instant.parse("2026-09-30T15:30:00Z"), 20L, "CAFE", Map.of()), states);
    }

    @Test
    void actualPaymentPortPassesPurchaseItemAndRealAssembledGroupsToSelectorWithoutClaimingPolicyCompleteness() {
        var data = new CompositionAssemblyTest().data(true, false);
        when(loader.load(anyLong(), anyList(), any())).thenReturn(data);
        when(activity.recognizedAmounts(anyList(), any())).thenReturn(Map.of(1L, 400_000L));
        var inputs = observeSelectorInput();
        var port = new com.yamu.be.payment.recommend.DatabaseCardRecommender(service,
                java.time.Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), java.time.ZoneOffset.UTC));
        var request = new com.yamu.be.payment.recommend.RecommendationRequest(100L, 1L,
                new com.yamu.be.payment.PaymentTerms(10_000, com.yamu.be.payment.PaymentPlan.LUMP_SUM, null),
                new com.yamu.be.payment.recommend.RecommendationRequest.MerchantContext(20L, "CAFE", "ONLINE", null),
                java.time.LocalDateTime.of(2026, 10, 1, 0, 0), "YOUTUBE_PREMIUM_SUBSCRIPTION", Map.of(1L, available()), Map.of());
        var result = port.recommend(request);
        assertThat(inputs.get(0).transaction().purchaseItemCode()).isEqualTo("YOUTUBE_PREMIUM_SUBSCRIPTION");
        var policy = inputs.get(0).cards().get(0).policy();
        assertThat(policy.complete()).isFalse();
        assertThat(policy.applicationGroups()).hasSize(1);
        assertThat(policy.benefits().get(0).conditions().targetGroups()).hasSize(1);
        assertThat(policy.additionalCost()).isZero();
        assertThat(result.status()).isEqualTo(com.yamu.be.payment.recommend.RecommendationResult.Status.DATA_INSUFFICIENT);
        assertThat(result.schemaVersion()).isEqualTo(1);
        assertThat(result.uncomparedCards()).hasSize(3);
        assertThat(result.issues()).anyMatch(i -> i.code().equals("POLICY_COMPLETENESS_UNVERIFIED"));
    }

    private PaymentAvailability available() {
        return new PaymentAvailability(Availability.AVAILABLE, null);
    }
}
