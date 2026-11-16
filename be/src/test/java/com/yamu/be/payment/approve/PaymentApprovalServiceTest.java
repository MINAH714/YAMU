package com.yamu.be.payment.approve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.card.CardQueryService;
import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.card.domain.CardType;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.redis.RedisLocks;
import com.yamu.be.global.security.AuthUser;
import com.yamu.be.payment.PaymentExpiry;
import com.yamu.be.payment.approve.ApprovalAttemptStore.Attempt;
import com.yamu.be.payment.approve.ApprovalAttemptStore.Reservation;
import com.yamu.be.payment.approve.ApprovalIdempotencyStore.Previous;
import com.yamu.be.payment.barcode.BarcodeStore;
import com.yamu.be.payment.billing.BillItem;
import com.yamu.be.payment.billing.BillingCycle;
import com.yamu.be.payment.billing.CardBill;
import com.yamu.be.payment.billing.CreditBillingWriter;
import com.yamu.be.payment.billing.InstallmentPlan;
import com.yamu.be.payment.domain.FacilityType;
import com.yamu.be.payment.domain.PaymentChannel;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import com.yamu.be.payment.performance.PaymentPerformanceTotals;
import com.yamu.be.payment.lookup.PaymentCardLookup;
import com.yamu.be.payment.lookup.PaymentCardLookup.ActiveCard;
import com.yamu.be.payment.pos.PosProperties;
import com.yamu.be.payment.recommend.CardRecommender;
import com.yamu.be.payment.recommend.RecommendationResult;
import com.yamu.be.payment.recommend.RecommendationResult.BenefitLine;
import com.yamu.be.payment.recommend.RecommendationResult.Candidate;
import com.yamu.be.payment.recommend.RecommendationResult.CardSummary;
import com.yamu.be.payment.recommend.PosPurchaseFacts;
import com.yamu.be.payment.recommend.RecommendationRequest;
import com.yamu.be.payment.recommend.RecommendationResult.ExpectedLimitUse;
import com.yamu.be.payment.recommend.RecommendationResult.PerformanceRecognition;
import com.yamu.be.payment.recommend.RecommendationResult.RuleVersion;
import com.yamu.be.payment.recommend.RecommendationResult.SelectionReason;
import com.yamu.be.payment.recommend.RecommendationSnapshotStore;
import com.yamu.be.support.WeekdayCalendar;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** 승인 흐름의 분기. DB·Redis 없이 저장소·포트를 가짜로 둔다. 실제 동시성·멱등은 PaymentApprovalIntegrationTest가 확인한다. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentApprovalServiceTest {

    static final long PAYMENT_ID = 1024L;
    static final AuthUser ME = new AuthUser(7L, 1L);
    static final String KEY = "4b4a1f4e-0d1e-4a1e-9b4e-5f7d2c1a0001";
    static final String TX_NO = "20261022143005000001";
    static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 10, 22, 5, 30);
    static final Instant NOW = REQUESTED_AT.plusSeconds(30).toInstant(ZoneOffset.UTC);

    static final ActiveCard CREDIT = new ActiveCard(15L, 3L, CardType.CREDIT, CardIssuerStatus.NORMAL, true, 1,
            "메인 카드", "1234", "TEST", "테스트 신용");
    static final ActiveCard CHECK = new ActiveCard(16L, 4L, CardType.CHECK, CardIssuerStatus.NORMAL, false, 2,
            null, "5678", "TEST", "테스트 체크");
    static final ActiveCard SUSPENDED = new ActiveCard(17L, 5L, CardType.CREDIT, CardIssuerStatus.SUSPENDED, false,
            3, null, "9012", "TEST", "정지 카드");

    @Mock PaymentTransactionRepository payments;
    @Mock CardQueryService cardQuery;
    @Mock PaymentCardLookup cards;
    @Mock RecommendationSnapshotStore snapshots;
    @Mock CardRecommender recommender;
    @Mock PaymentApprover approver;
    @Mock ApprovalLedger ledger;
    @Mock PaymentPerformanceTotals performanceTotals;
    @Mock CreditBillingWriter billing;
    @Mock ApprovalIdempotencyStore idempotency;
    @Mock ApprovalAttemptStore attempts;
    @Mock PaymentExpiry expiry;
    @Mock WithdrawalRecovery recovery;
    @Mock BarcodeStore barcodes;
    @Mock RedisLocks locks;
    @Mock RedisLocks.Lock lock;
    @Mock TransactionTemplate transaction;

    final PosProperties posProperties = new PosProperties(null, Duration.ofSeconds(60), Duration.ofMinutes(10),
            Duration.ofMinutes(10), Duration.ofHours(24), Duration.ofSeconds(15), List.of());
    final PaymentApprovalProperties properties = new PaymentApprovalProperties(Duration.ofHours(24),
            Duration.ofSeconds(30), Duration.ofSeconds(5), 3, Duration.ofHours(24), Duration.ofSeconds(60),
            Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofMinutes(5), Duration.ofMinutes(10), false);
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    PaymentApprovalService service;
    PaymentTransaction payment;
    RecommendationResult recommendation;
    int attemptNo;

    @BeforeEach
    void setUp() {
        service = new PaymentApprovalService(payments, cardQuery, cards, snapshots, recommender, approver, ledger,
                billing, idempotency, attempts, expiry, recovery, barcodes, locks, transaction, new ObjectMapper(),
                posProperties, properties, clock, performanceTotals);
        payment = requested(15_000, 1);
        recommendation = recommendation(List.of(candidate(1, CREDIT), candidate(2, CHECK)));
        attemptNo = 1;

        when(idempotency.find(anyLong(), anyString())).thenReturn(Optional.empty());
        when(idempotency.lockKey(anyLong(), anyString())).thenReturn("lock");
        when(attempts.startLockKey(PAYMENT_ID)).thenReturn("start-lock");
        when(locks.tryLock(anyString(), any())).thenReturn(Optional.of(lock));
        when(payments.findByIdAndUserId(PAYMENT_ID, 7L)).thenAnswer(inv -> Optional.of(payment));
        when(payments.findById(PAYMENT_ID)).thenAnswer(inv -> Optional.of(payment));
        when(payments.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transaction.execute(any())).thenAnswer(inv -> inv.<TransactionCallback<Object>>getArgument(0)
                .doInTransaction(mock(TransactionStatus.class)));
        // 승인 전 혜택 재계산(Task 5.2.5): 따로 정하지 않은 테스트는 그 사이 한도가 바뀌지 않아 추천과 같은 결과다
        when(recommender.recommend(any())).thenAnswer(inv -> snapshots.find(PAYMENT_ID).orElse(recommendation));
        when(attempts.count(PAYMENT_ID)).thenReturn(0);
        when(attempts.reserve(eq(PAYMENT_ID), any()))
                .thenAnswer(inv -> new Reservation(Reservation.Status.RESERVED,
                        inv.<Attempt>getArgument(1).withNo(attemptNo)));
        when(attempts.findByKey(PAYMENT_ID, KEY)).thenReturn(Optional.empty());
        when(attempts.triedCardIds(PAYMENT_ID)).thenReturn(Set.of());
        when(attempts.declinedCardIds(PAYMENT_ID)).thenReturn(Set.of());
        when(expiry.isPastDeadline(any())).thenReturn(false);
        when(recovery.recover(PAYMENT_ID)).thenReturn(PaymentStatus.PROCESSING);
        when(snapshots.find(PAYMENT_ID)).thenAnswer(inv -> Optional.of(recommendation));
        when(cards.findActiveCards(7L)).thenReturn(List.of(CREDIT, CHECK, SUSPENDED));
        when(approver.approve(any())).thenAnswer(inv -> approvedLike(inv.getArgument(0)));
    }

    // ───────── 승인 ─────────

    @Test
    void 카드를_생략하면_1순위_후보로_승인하고_승인번호가_있다() {
        PaymentApprovalResponse response = service.approve(ME, PAYMENT_ID, request(null), KEY);

        assertThat(response.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(response.approvalNo()).isEqualTo("Y202610220000001024");
        assertThat(response.approvedAt()).isEqualTo(payment.getApprovedAt());
        assertThat(response.selectedCard().userCardId()).isEqualTo(15L);
        assertThat(response.selectedCard().recommendedRank()).isEqualTo(1);
        assertThat(response.selectedCard().userSelected()).isFalse();
        assertThat(response.selectedCard().nickname()).isEqualTo("메인 카드");
        assertThat(response.selectedCard().maskedNumber()).isEqualTo("****-****-****-1234");
        assertThat(response.amount()).isEqualTo(15_000);
        assertThat(response.chargedAmount()).isEqualTo(15_000);
        assertThat(response.failure()).isNull();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment.getUserCardId()).isEqualTo(15L);
        assertThat(payment.getCardProductId()).isEqualTo(3L);
        assertThat(payment.getApprovedAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        verify(ledger).recordApproval(eq(payment), any());
        verify(barcodes).markUsed(PAYMENT_ID);
        verify(idempotency).remember(eq(7L), eq(KEY), any());
    }

    @Test
    void 직접_고른_카드가_후보면_그_순위를_적고_userSelected다() {
        PaymentApprovalResponse response = service.approve(ME, PAYMENT_ID, request(16L), KEY);

        assertThat(response.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(response.selectedCard().userCardId()).isEqualTo(16L);
        assertThat(response.selectedCard().recommendedRank()).isEqualTo(2);
        assertThat(response.selectedCard().userSelected()).isTrue();
        assertThat(response.selectedCard().nickname()).isEqualTo("테스트 체크");
        verify(cardQuery).requireRegisteredCard(7L, 16L);
    }

    @Test
    void 추천_후보가_아닌_카드를_직접_골라도_적격이면_승인하고_순위는_없다() {
        recommendation = recommendation(List.of(candidate(1, CREDIT)));

        PaymentApprovalResponse response = service.approve(ME, PAYMENT_ID, request(16L), KEY);

        assertThat(response.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(response.selectedCard().recommendedRank()).isNull();
    }

    @Test
    void 스냅샷이_없으면_다시_계산해_저장하고_쓴다() {
        when(snapshots.find(PAYMENT_ID)).thenReturn(Optional.empty());
        when(recommender.recommend(any())).thenReturn(recommendation);

        PaymentApprovalResponse response = service.approve(ME, PAYMENT_ID, request(null), KEY);

        assertThat(response.selectedCard().userCardId()).isEqualTo(15L);
        verify(snapshots).save(eq(PAYMENT_ID), eq(recommendation), eq(Duration.ofMinutes(10)));
    }

    // ───────── 승인 전 혜택 재계산·카드 잠금(Task 5.2.5) ─────────

    @Test
    void 승인_전에_다시_계산한_후보로_승인액을_정하고_시도_기록과_원장_응답에_그_값을_쓴다() {
        BenefitLine instant = new BenefitLine(101L, "카페 즉시할인", "DISCOUNT", 1_000, 1_000, BenefitLine.AT_APPROVAL);
        Candidate fresh = new Candidate(1, candidate(1, CREDIT).card(), java.math.BigDecimal.valueOf(1_000),
                List.of(instant), "mock", SelectionReason.DEFAULT_CARD, new RuleVersion(3L, 1), List.of(),
                List.of(new ExpectedLimitUse(7L, 1, "KRW", "MONTHLY", java.math.BigDecimal.valueOf(5_000),
                        java.math.BigDecimal.valueOf(1_000))));
        when(recommender.recommend(any())).thenReturn(recommendation(List.of(fresh, candidate(2, CHECK))));
        doAnswer(inv -> {
            ApprovalCommand command = inv.getArgument(0);
            long amount = command.chargedAmount();
            return new ApprovalOutcome.Approved(amount, amount, 0, ApprovalDates.performanceMonth(command.now()),
                    ApprovalOutcome.Approved.applied(command.candidate()), null, null, null, command.candidate());
        }).when(approver).approve(any());
        doReturn(new ApprovalLedger.Recorded(Map.of(101L, 777L),
                Map.of(101L, new ApprovalLedger.Recorded.Value("KRW", 1_000L)))).when(ledger).recordApproval(eq(payment), any());

        PaymentApprovalResponse response = service.approve(ME, PAYMENT_ID, request(null), KEY);

        ArgumentCaptor<ApprovalCommand> command = ArgumentCaptor.forClass(ApprovalCommand.class);
        verify(approver).approve(command.capture());
        assertThat(command.getValue().candidate()).isEqualTo(fresh);
        assertThat(command.getValue().chargedAmount()).isEqualTo(14_000);
        ArgumentCaptor<Attempt> reserved = ArgumentCaptor.forClass(Attempt.class);
        verify(attempts).reserve(eq(PAYMENT_ID), reserved.capture());
        assertThat(reserved.getValue().basis()).isEqualTo(fresh);
        assertThat(response.chargedAmount()).isEqualTo(14_000);
        assertThat(response.benefits()).singleElement().satisfies(b -> {
            assertThat(b.paymentBenefitId()).isEqualTo(777L);
            assertThat(b.amount()).isEqualTo(1_000);
            assertThat(b.confirmed()).isTrue();
            assertThat(b.unit()).isEqualTo("KRW");
            assertThat(b.krwAmount()).isEqualTo(1_000L);
        });
        assertThat(response.limitImpacts()).containsExactly(
                new PaymentApprovalResponse.LimitImpact(7L, "MONTHLY", 1_000, 4_000L));
        assertThat(response.performance().yearMonth()).isEqualTo("2026-10");
        assertThat(response.performance().recognizedAmount()).isZero();
        assertThat(response.performance().recognized()).isFalse();
    }

    @Test
    void 같은_카드를_다른_결제가_처리_중이면_409이고_선점하지_않는다() {
        when(locks.tryLock(startsWith(PaymentApprovalService.CARD_LEDGER_LOCK_PREFIX), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_IN_PROGRESS);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
        verify(recommender, never()).recommend(any());
        verify(attempts, never()).reserve(anyLong(), any());
        verify(approver, never()).approve(any());
    }

    @Test
    void 승인_전_재계산이_실패하면_409이고_선점하지_않는다() {
        when(recommender.recommend(any())).thenThrow(new IllegalStateException("DB 읽기 실패"));

        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
        verify(attempts, never()).reserve(anyLong(), any());
        verify(approver, never()).approve(any());
        verify(lock, org.mockito.Mockito.atLeastOnce()).close();
    }

    @Test
    void 다시_계산하니_후보가_아니면_혜택_없이_승인한다() {
        when(recommender.recommend(any())).thenReturn(recommendation(List.of(candidate(1, CHECK))));

        PaymentApprovalResponse response = service.approve(ME, PAYMENT_ID, request(null), KEY);

        ArgumentCaptor<ApprovalCommand> command = ArgumentCaptor.forClass(ApprovalCommand.class);
        verify(approver).approve(command.capture());
        assertThat(command.getValue().card().userCardId()).isEqualTo(15L);
        assertThat(command.getValue().candidate()).isNull();
        assertThat(response.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(response.selectedCard().recommendedRank()).isEqualTo(1);
    }

    @Test
    void 다시_계산한_결과의_그_카드_실적_인정_값을_승인_명령과_시도_기록에_넣고_POS_거래_사실로_계산한다() {
        // Task 5.2.11
        Candidate fresh = candidate(1, CREDIT);
        PerformanceRecognition recognition = new PerformanceRecognition(CREDIT.userCardId(), YearMonth.of(2026, 10), 15_000L);
        when(recommender.recommend(any())).thenReturn(new RecommendationResult("rec_fresh", REQUESTED_AT, false,
                List.of(fresh), List.of(), List.of(), 1, List.of(), List.of(),
                List.of(recognition, new PerformanceRecognition(CHECK.userCardId(), null, null))));
        doAnswer(inv -> {
            ApprovalCommand command = inv.getArgument(0);
            long amount = command.chargedAmount();
            return new ApprovalOutcome.Approved(amount, amount, command.recognizedPerformanceAmount(),
                    command.performanceMonth(), List.of(), null, null, null, command.candidate());
        }).when(approver).approve(any());

        PaymentApprovalResponse response = service.approve(ME, PAYMENT_ID, request(null), KEY);

        ArgumentCaptor<ApprovalCommand> command = ArgumentCaptor.forClass(ApprovalCommand.class);
        verify(approver).approve(command.capture());
        assertThat(command.getValue().recognition()).isEqualTo(recognition);
        ArgumentCaptor<Attempt> reserved = ArgumentCaptor.forClass(Attempt.class);
        verify(attempts).reserve(eq(PAYMENT_ID), reserved.capture());
        assertThat(reserved.getValue().recognition()).isEqualTo(recognition);
        ArgumentCaptor<RecommendationRequest> asked = ArgumentCaptor.forClass(RecommendationRequest.class);
        verify(recommender).recommend(asked.capture());
        assertThat(asked.getValue().verifiedFacts()).isEqualTo(PosPurchaseFacts.verified());
        assertThat(response.performance().recognizedAmount()).isEqualTo(15_000);
        assertThat(response.performance().recognized()).isTrue();
    }

    @Test
    void 추천_밖의_카드를_직접_고르면_추천_결과에_남은_그_카드의_실적_인정_값을_쓴다() {
        PerformanceRecognition recognition = new PerformanceRecognition(16L, YearMonth.of(2026, 10), 15_000L);
        recommendation = new RecommendationResult("rec_test", REQUESTED_AT, false, List.of(candidate(1, CREDIT)),
                List.of(), List.of(), 1, List.of(), List.of(), List.of(recognition));

        service.approve(ME, PAYMENT_ID, request(16L), KEY);

        ArgumentCaptor<ApprovalCommand> command = ArgumentCaptor.forClass(ApprovalCommand.class);
        verify(approver).approve(command.capture());
        assertThat(command.getValue().candidate()).isNull();
        assertThat(command.getValue().recognition()).isEqualTo(recognition);
    }

    @Test
    void 추천_밖의_카드를_직접_고르면_다시_계산하지_않고_카드_잠금도_잡지_않는다() {
        recommendation = recommendation(List.of(candidate(1, CREDIT)));

        service.approve(ME, PAYMENT_ID, request(16L), KEY);

        verify(recommender, never()).recommend(any());
        verify(locks, never()).tryLock(startsWith(PaymentApprovalService.CARD_LEDGER_LOCK_PREFIX), any());
    }

    // ───────── 사전 검사·청구 회차 ─────────

    @Test
    void 사전_검사가_거절하면_선점과_시도_없이_그대로_던진다() {
        doThrow(new BusinessException(ErrorCode.DATA_INSUFFICIENT)).when(approver).precheck(eq(7L), any(), any());

        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.DATA_INSUFFICIENT);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
        verify(attempts, never()).reserve(anyLong(), any());
        verify(approver, never()).approve(any());
        verify(locks, never()).tryLock(startsWith(WithdrawalRecovery.SETTLE_LOCK_PREFIX), any());
        verify(idempotency, never()).remember(anyLong(), anyString(), any());
    }

    @Test
    void 청구_계획이_있는_승인은_청구서를_먼저_확보하고_tx2에서_회차를_적어_응답한다() {
        InstallmentPlan plan = InstallmentPlan.of(15_000, 1, new BillingCycle(15, 14), LocalDate.of(2026, 10, 22),
                new WeekdayCalendar());
        doReturn(new ApprovalOutcome.Approved(15_000, 15_000, 0, LocalDate.of(2026, 10, 1), List.of(), null, null,
                plan)).when(approver).approve(any());
        CardBill bill = CardBill.accumulating(7L, 15L, plan.installments().get(0).cycle(), LocalDate.of(2026, 11, 16));
        ReflectionTestUtils.setField(bill, "id", 501L);
        Map<YearMonth, CardBill> bills = Map.of(YearMonth.of(2026, 11), bill);
        when(billing.ensureBills(7L, 15L, plan)).thenReturn(bills);
        BillItem item = BillItem.pending(PAYMENT_ID, 7L, 15L, 501L, 1, LocalDate.of(2026, 11, 1), 15_000);
        ReflectionTestUtils.setField(item, "id", 9001L);
        when(billing.writeItems(payment, plan, bills)).thenReturn(List.of(item));

        PaymentApprovalResponse response = service.approve(ME, PAYMENT_ID, request(null), KEY);

        assertThat(response.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(response.billItems()).hasSize(1);
        PaymentApprovalResponse.BillItem shown = response.billItems().get(0);
        assertThat(shown.billItemId()).isEqualTo(9001L);
        assertThat(shown.seq()).isEqualTo(1);
        assertThat(shown.amount()).isEqualTo(15_000);
        assertThat(shown.billingMonth()).isEqualTo("2026-11");
        assertThat(shown.expectedWithdrawalDate()).isEqualTo(LocalDate.of(2026, 11, 16));
        assertThat(shown.status()).isEqualTo("PENDING");
        InOrder order = inOrder(billing, ledger);
        order.verify(billing).ensureBills(7L, 15L, plan);
        order.verify(ledger).recordApproval(eq(payment), any());
        order.verify(billing).writeItems(payment, plan, bills);
    }

    @Test
    void 청구_계획이_없는_승인은_청구서와_회차를_만들지_않는다() {
        PaymentApprovalResponse response = service.approve(ME, PAYMENT_ID, request(16L), KEY);

        assertThat(response.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(response.billItems()).isEmpty();
        verify(billing, never()).ensureBills(anyLong(), anyLong(), any());
        verify(billing, never()).writeItems(any(), any(), any());
    }

    @Test
    void 청구서_확보가_실패하면_승인_전으로_바로_되돌리고_시도를_지우며_500이다() {
        InstallmentPlan plan = InstallmentPlan.of(15_000, 1, new BillingCycle(15, 14), LocalDate.of(2026, 10, 22),
                new WeekdayCalendar());
        doReturn(new ApprovalOutcome.Approved(15_000, 15_000, 0, LocalDate.of(2026, 10, 1), List.of(), null, null,
                plan)).when(approver).approve(any());
        when(billing.ensureBills(7L, 15L, plan)).thenThrow(new IllegalStateException("청구서가 이용기간 진행 중이 아닙니다"));

        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
        verify(attempts).release(eq(PAYMENT_ID), any());
        verify(attempts, never()).recordResult(anyLong(), any(), any());
        verify(ledger, never()).recordApproval(any(), any());
        verify(billing, never()).writeItems(any(), any(), any());
        verify(idempotency, never()).remember(anyLong(), anyString(), any());
        verify(barcodes, never()).markUsed(anyLong());
    }

    @Test
    void 청구서가_마감돼_확보하지_못하면_되돌린_뒤_그_업무_오류로_답한다() {
        InstallmentPlan plan = InstallmentPlan.of(15_000, 1, new BillingCycle(15, 14), LocalDate.of(2026, 10, 22),
                new WeekdayCalendar());
        doReturn(new ApprovalOutcome.Approved(15_000, 15_000, 0, LocalDate.of(2026, 10, 1), List.of(), null, null,
                plan)).when(approver).approve(any());
        BusinessException closed = new BusinessException(ErrorCode.DATA_INSUFFICIENT, Map.of("reason", "BILL_CLOSED"));
        when(billing.ensureBills(7L, 15L, plan)).thenThrow(closed);

        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY)).isSameAs(closed);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
        verify(attempts).release(eq(PAYMENT_ID), any());
        verify(attempts, never()).recordResult(anyLong(), any(), any());
    }

    // ───────── 카드 검증 ─────────

    @Test
    void 남의_카드나_없는_카드는_404다() {
        when(cardQuery.requireRegisteredCard(7L, 99L)).thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(99L), KEY))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        verify(transaction, never()).execute(any());
    }

    @Test
    void 정지된_카드는_CARD_NOT_ELIGIBLE이고_할부_결제에_체크카드는_INVALID_INSTALLMENT다() {
        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(17L), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.CARD_NOT_ELIGIBLE);

        payment = requested(90_000, 3);
        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(16L), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INSTALLMENT);
        verify(transaction, never()).execute(any());
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
    }

    @Test
    void 생략했는데_결제할_카드가_없으면_CARD_NOT_ELIGIBLE이고_시도를_세지_않는다() {
        recommendation = recommendation(List.of());

        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.CARD_NOT_ELIGIBLE);
        verify(attempts, never()).reserve(anyLong(), any());
    }

    @Test
    void 이_결제에서_거절된_카드를_다시_고르면_CARD_NOT_ELIGIBLE_ALREADY_DECLINED다() {
        declinedOnce(15L);

        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(15L), KEY))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.CARD_NOT_ELIGIBLE);
                    assertThat(be.getDetails()).containsEntry("reason", "ALREADY_DECLINED");
                });
        verify(attempts, never()).reserve(anyLong(), any());
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DECLINED);
    }

    @Test
    void 생략하면_이_결제에서_거절된_카드를_뺀_1순위로_승인한다() {
        declinedOnce(15L);

        PaymentApprovalResponse response = service.approve(ME, PAYMENT_ID, request(null), KEY);

        assertThat(response.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(response.selectedCard().userCardId()).isEqualTo(16L);
        assertThat(response.selectedCard().recommendedRank()).isEqualTo(2);
    }

    // ───────── 상태·마감·횟수 ─────────

    @Test
    void 이미_승인된_결제는_PAYMENT_ALREADY_PROCESSED_처리_중이면_IDEMPOTENCY_IN_PROGRESS_만료면_PAYMENT_EXPIRED다() {
        payment.startApproval(15L, 3L);
        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_IN_PROGRESS);

        payment.approve(15_000, 0L, LocalDate.of(2026, 10, 1), REQUESTED_AT, null);
        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.PAYMENT_ALREADY_PROCESSED);

        payment = requested(15_000, 1);
        payment.expire();
        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.PAYMENT_EXPIRED);
    }

    @Test
    void 마감이_지났으면_EXPIRED로_바꾸고_바코드도_만료하고_PAYMENT_EXPIRED다() {
        when(expiry.isPastDeadline(payment)).thenReturn(true);
        when(expiry.expire(PAYMENT_ID)).thenReturn(PaymentStatus.EXPIRED);

        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.PAYMENT_EXPIRED);
        verify(expiry).expire(PAYMENT_ID);
        verify(barcodes).expireByPayment(PAYMENT_ID);
        verify(attempts, never()).reserve(anyLong(), any());
    }

    @Test
    void 재승인_횟수를_다_쓰면_RETRY_LIMIT_EXCEEDED다() {
        payment.startApproval(15L, 3L);
        payment.decline("INSUFFICIENT_BALANCE");
        when(attempts.count(PAYMENT_ID)).thenReturn(3);

        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(16L), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.RETRY_LIMIT_EXCEEDED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DECLINED);
    }

    @Test
    void 선검사를_함께_통과해도_예약이_최대_횟수에_닿으면_선점하지_않고_RETRY_LIMIT_EXCEEDED다() {
        when(attempts.count(PAYMENT_ID)).thenReturn(2);
        when(attempts.reserve(eq(PAYMENT_ID), any())).thenReturn(Reservation.LIMIT_REACHED);

        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.RETRY_LIMIT_EXCEEDED);
        verify(transaction, never()).execute(any());
        verify(approver, never()).approve(any());
    }

    @Test
    void 예약_사이에_다른_요청이_먼저_예약했거나_시작_잠금이_잡혀_있으면_IDEMPOTENCY_IN_PROGRESS다() {
        when(attempts.reserve(eq(PAYMENT_ID), any())).thenReturn(Reservation.CONTENDED);
        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_IN_PROGRESS);

        when(locks.tryLock(eq("start-lock"), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_IN_PROGRESS);
        verify(transaction, never()).execute(any());
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
    }

    @Test
    void 시도_예약이_Redis_장애로_실패하면_결제_행은_REQUESTED_그대로다() {
        when(attempts.reserve(eq(PAYMENT_ID), any())).thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY))
                .isInstanceOf(RedisConnectionFailureException.class);
        verify(transaction, never()).execute(any());
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
    }

    @Test
    void 선점_경쟁에서_밀리면_예약을_지우고_IDEMPOTENCY_IN_PROGRESS다() {
        doThrow(new ObjectOptimisticLockingFailureException("payment", null)).when(transaction).execute(any());

        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_IN_PROGRESS);
        verify(attempts).release(eq(PAYMENT_ID), any());
        verify(approver, never()).approve(any());
    }

    // ───────── 실행 결과 ─────────

    @Test
    void 실행이_BusinessException으로_끝나면_PROCESSING에_두고_그_코드로_답하며_결과를_기억하지_않는다() {
        doThrow(new BusinessException(ErrorCode.FIN_API_UNAVAILABLE)).when(approver).approve(any());

        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.FIN_API_UNAVAILABLE);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(payment.getUserCardId()).isEqualTo(15L);
        verify(barcodes, never()).markUsed(anyLong());
        verify(idempotency, never()).remember(anyLong(), anyString(), any());
        verify(attempts, never()).recordResult(anyLong(), any(), any());
        verify(attempts, never()).release(anyLong(), any());
    }

    @Test
    void 실행이_시간_초과_같은_다른_예외로_끝나도_PROCESSING에_두고_502_FIN_API_UNAVAILABLE이다() {
        doThrow(new IllegalStateException("read timed out")).when(approver).approve(any());

        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.FIN_API_UNAVAILABLE);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
    }

    @Test
    void 원장_반영이_실패해도_거래_번호와_출금액을_저장_전에_시도_기록에_남긴다() {
        doReturn(new ApprovalOutcome.Approved(15_000, 14_000, 0, LocalDate.of(2026, 10, 1), List.of(), null, "FIN-0001",
                null))
                .when(approver).approve(any());
        doThrow(new IllegalStateException("ledger")).when(ledger).recordApproval(any(), any());

        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<Attempt> result = ArgumentCaptor.forClass(Attempt.class);
        InOrder order = inOrder(attempts, ledger);
        order.verify(attempts).recordResult(eq(PAYMENT_ID), any(), result.capture());
        order.verify(ledger).recordApproval(any(), any());
        assertThat(result.getValue().outcome()).isEqualTo(Attempt.APPROVED);
        assertThat(result.getValue().finTransactionNo()).isEqualTo("FIN-0001");
        assertThat(result.getValue().chargedAmount()).isEqualTo(14_000L);
        assertThat(result.getValue().response().chargedAmount()).isEqualTo(14_000L);
        verify(idempotency, never()).remember(anyLong(), anyString(), any());
    }

    @Test
    void 거절이면_DECLINED로_저장하고_재시도_정보와_다음_카드를_준다() {
        doReturn(ApprovalOutcome.Declined.of(FailureCode.INSUFFICIENT_BALANCE)).when(approver).approve(any());
        when(attempts.triedCardIds(PAYMENT_ID)).thenReturn(Set.of(15L));

        PaymentApprovalResponse response = service.approve(ME, PAYMENT_ID, request(null), KEY);

        assertThat(response.status()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(response.approvalNo()).isNull();
        assertThat(response.failure().code()).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(response.failure().message()).isEqualTo("연결 계좌 잔액이 부족합니다.");
        assertThat(response.retryable()).isTrue();
        assertThat(response.remainingAttempts()).isEqualTo(2);
        assertThat(response.suggestedCards()).extracting(PaymentApprovalResponse.SuggestedCard::userCardId)
                .containsExactly(16L);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(payment.getFailureCode()).isEqualTo("INSUFFICIENT_BALANCE");
        verify(barcodes, never()).markUsed(anyLong());
        verify(ledger, never()).recordApproval(any(), any());
        ArgumentCaptor<Attempt> result = ArgumentCaptor.forClass(Attempt.class);
        verify(attempts).recordResult(eq(PAYMENT_ID), any(), result.capture());
        assertThat(result.getValue().outcome()).isEqualTo(Attempt.DECLINED);
        assertThat(result.getValue().failureCode()).isEqualTo("INSUFFICIENT_BALANCE");
    }

    @Test
    void 소수_순혜택이어도_다음_카드_목록에_그대로_내려준다() {
        // S15P31E103-283: 포인트 환산율 0.5면 엔진 순혜택이 1.5원처럼 나온다. longValueExact()였으면 승인 뒤 여기서 500
        recommendation = recommendation(List.of(candidate(1, CREDIT, new java.math.BigDecimal("2.5")),
                candidate(2, CHECK, new java.math.BigDecimal("1.5"))));
        doReturn(ApprovalOutcome.Declined.of(FailureCode.INSUFFICIENT_BALANCE)).when(approver).approve(any());
        when(attempts.triedCardIds(PAYMENT_ID)).thenReturn(Set.of(15L));

        PaymentApprovalResponse response = service.approve(ME, PAYMENT_ID, request(null), KEY);

        assertThat(response.suggestedCards()).extracting(PaymentApprovalResponse.SuggestedCard::userCardId,
                PaymentApprovalResponse.SuggestedCard::netBenefit)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(16L, new java.math.BigDecimal("1.5")));
    }

    @Test
    void 세_번째_거절이면_더_시도할_수_없다고_알린다() {
        doReturn(ApprovalOutcome.Declined.of(FailureCode.CREDIT_LIMIT_EXCEEDED)).when(approver).approve(any());
        when(attempts.count(PAYMENT_ID)).thenReturn(2);
        attemptNo = 3;

        PaymentApprovalResponse response = service.approve(ME, PAYMENT_ID, request(null), KEY);

        assertThat(response.retryable()).isFalse();
        assertThat(response.remainingAttempts()).isZero();
        assertThat(response.suggestedCards()).isEmpty();
    }

    @Test
    void 승인_뒤_Redis_쓰기가_실패해도_성공_응답을_돌려준다() {
        doThrow(new RedisConnectionFailureException("down")).when(attempts).recordResult(anyLong(), any(), any());
        doThrow(new RedisConnectionFailureException("down")).when(idempotency).remember(anyLong(), anyString(), any());
        doThrow(new RedisConnectionFailureException("down")).when(barcodes).markUsed(anyLong());

        PaymentApprovalResponse response = service.approve(ME, PAYMENT_ID, request(null), KEY);

        assertThat(response.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
    }

    // ───────── 결과 불명 복구·되돌리기(5.2.3) ─────────

    @Test
    void 금융망에_보내지_못했으면_결제를_REQUESTED로_되돌리고_시도를_지우며_502이고_결과를_기억하지_않는다() {
        doThrow(new ApprovalNotSentException("연결 실패", null)).when(approver).approve(any());

        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(16L), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.FIN_API_UNAVAILABLE);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
        assertThat(payment.getUserCardId()).isEqualTo(16L);
        verify(attempts).release(eq(PAYMENT_ID), any());
        verify(idempotency, never()).remember(anyLong(), anyString(), any());
        verify(barcodes, never()).markUsed(anyLong());
    }

    @Test
    void 보내지_않은_실행이_돌려줄_오류를_실었으면_되돌린_뒤_그_오류로_답한다() {
        BusinessException notFound = new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        doThrow(new ApprovalNotSentException("신용 승인 실행 실패", notFound, notFound)).when(approver).approve(any());

        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(16L), KEY)).isSameAs(notFound);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
        verify(attempts).release(eq(PAYMENT_ID), any());
        verify(attempts, never()).recordResult(anyLong(), any(), any());
        verify(idempotency, never()).remember(anyLong(), anyString(), any());
    }

    @Test
    void PROCESSING_결제는_횟수_카드_검사보다_먼저_복구를_거치고_풀렸으면_이어서_승인한다() {
        payment.startApproval(15L, 3L);
        when(recovery.recover(PAYMENT_ID)).thenAnswer(inv -> {
            payment.releaseApproval();
            return PaymentStatus.REQUESTED;
        });

        PaymentApprovalResponse response = service.approve(ME, PAYMENT_ID, request(16L), KEY);

        assertThat(response.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(response.selectedCard().userCardId()).isEqualTo(16L);
        InOrder order = inOrder(recovery, attempts);
        order.verify(recovery).recover(PAYMENT_ID);
        order.verify(attempts).count(PAYMENT_ID);
    }

    @Test
    void 같은_키의_결과_불명_시도를_복구가_승인으로_확정하면_행에서_만든_승인_응답을_돌려주고_새_시도를_만들지_않는다() {
        payment.startApproval(15L, 3L);
        payment.assignFinRequestNo(TX_NO, 15_000);
        when(attempts.findByKey(PAYMENT_ID, KEY)).thenReturn(Optional.of(unknownAttempt(CREDIT, 1, false)));
        when(recovery.recover(PAYMENT_ID)).thenAnswer(inv -> {
            payment.approve(15_000, 0L, LocalDate.of(2026, 10, 1), REQUESTED_AT.plusSeconds(20), "1101");
            return PaymentStatus.APPROVED;
        });

        PaymentApprovalResponse response = service.approve(ME, PAYMENT_ID, request(null), KEY);

        assertThat(response.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(response.approvalNo()).isEqualTo("Y202610220000001024");
        assertThat(response.selectedCard().userCardId()).isEqualTo(15L);
        assertThat(response.selectedCard().recommendedRank()).isEqualTo(1);
        assertThat(response.selectedCard().userSelected()).isFalse();
        assertThat(response.chargedAmount()).isEqualTo(15_000);
        verify(approver, never()).approve(any());
        verify(attempts, never()).reserve(anyLong(), any());
        verify(idempotency).remember(eq(7L), eq(KEY), any());
    }

    @Test
    void 주기_작업이_먼저_확정했어도_같은_키는_같은_승인_응답이다() {
        payment.startApproval(15L, 3L);
        payment.assignFinRequestNo(TX_NO, 15_000);
        payment.approve(15_000, 0L, LocalDate.of(2026, 10, 1), REQUESTED_AT.plusSeconds(20), "1101");
        when(attempts.findByKey(PAYMENT_ID, KEY)).thenReturn(Optional.of(unknownAttempt(CREDIT, 1, false)
                .withResult(Attempt.APPROVED, null, "1101", 15_000L, null)));

        PaymentApprovalResponse response = service.approve(ME, PAYMENT_ID, request(null), KEY);

        assertThat(response.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(response.selectedCard().userCardId()).isEqualTo(15L);
        verify(recovery, never()).recover(anyLong());
        verify(approver, never()).approve(any());
    }

    @Test
    void 다른_키로_온_요청이_복구를_거쳐_승인이면_409_PAYMENT_ALREADY_PROCESSED다() {
        payment.startApproval(15L, 3L);
        payment.assignFinRequestNo(TX_NO, 15_000);
        when(recovery.recover(PAYMENT_ID)).thenAnswer(inv -> {
            payment.approve(15_000, 0L, LocalDate.of(2026, 10, 1), REQUESTED_AT.plusSeconds(20), "1101");
            return PaymentStatus.APPROVED;
        });

        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(16L), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        verify(approver, never()).approve(any());
    }

    @Test
    void 복구가_출금_미확인으로_거절하면_그_요청은_거절_응답으로_끝나고_다시_출금하지_않는다() {
        payment.startApproval(15L, 3L);
        payment.assignFinRequestNo(TX_NO, 15_000);
        when(attempts.count(PAYMENT_ID)).thenReturn(1);
        when(recovery.recover(PAYMENT_ID)).thenAnswer(inv -> {
            payment.decline("WITHDRAWAL_NOT_CONFIRMED");
            return PaymentStatus.DECLINED;
        });

        PaymentApprovalResponse response = service.approve(ME, PAYMENT_ID, request(16L), KEY);

        assertThat(response.status()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(response.failure().code()).isEqualTo("WITHDRAWAL_NOT_CONFIRMED");
        assertThat(response.selectedCard().userCardId()).isEqualTo(15L);
        assertThat(response.retryable()).isTrue();
        assertThat(response.remainingAttempts()).isEqualTo(2);
        verify(approver, never()).approve(any());
        verify(attempts, never()).reserve(anyLong(), any());
        verify(idempotency).remember(eq(7L), eq(KEY), any());
    }

    @Test
    void 복구_응답은_시도_기록이_없으면_행에_적힌_순위와_직접_선택을_쓴다() {
        // 스냅샷에서 15번 카드는 1순위다. 행에는 다른 값(2순위·직접 선택)을 적어 두 분기를 구분한다
        payment.startApproval(15L, 3L, 2, true);
        payment.assignFinRequestNo(TX_NO, 15_000);
        when(attempts.count(PAYMENT_ID)).thenReturn(1);
        when(recovery.recover(PAYMENT_ID)).thenAnswer(inv -> {
            payment.decline("WITHDRAWAL_NOT_CONFIRMED");
            return PaymentStatus.DECLINED;
        });

        PaymentApprovalResponse response = service.approve(ME, PAYMENT_ID, request(16L), KEY);

        assertThat(response.status()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(response.selectedCard().userCardId()).isEqualTo(15L);
        assertThat(response.selectedCard().recommendedRank()).isEqualTo(2);
        assertThat(response.selectedCard().userSelected()).isTrue();
        verify(approver, never()).approve(any());
    }

    @Test
    void 출금_미확인으로_거절된_결제를_재승인하기_직전에_앞_출금을_다시_본다() {
        payment.startApproval(15L, 3L);
        payment.assignFinRequestNo(TX_NO, 15_000);
        payment.decline("WITHDRAWAL_NOT_CONFIRMED");
        when(attempts.count(PAYMENT_ID)).thenReturn(1);
        attemptNo = 2;

        when(recovery.recheckBeforeRetry(PAYMENT_ID)).thenReturn(WithdrawalRecovery.LateCheck.CONFIRMED);
        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(16L), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.PAYMENT_ALREADY_PROCESSED);

        when(recovery.recheckBeforeRetry(PAYMENT_ID)).thenReturn(WithdrawalRecovery.LateCheck.UNDECIDED);
        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(16L), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.FIN_API_UNAVAILABLE);
        verify(approver, never()).approve(any());
        verify(attempts, never()).reserve(anyLong(), any());

        when(recovery.recheckBeforeRetry(PAYMENT_ID)).thenReturn(WithdrawalRecovery.LateCheck.NOT_FOUND);
        PaymentApprovalResponse response = service.approve(ME, PAYMENT_ID, request(16L), KEY);
        assertThat(response.status()).isEqualTo(PaymentStatus.APPROVED);
        verify(approver).approve(any());
    }

    @Test
    void 정산_잠금이_풀린_사이_출금_미확인으로_거절됐는데_출금_성공이_늦게_오면_승인으로_바로잡는다() {
        doAnswer(inv -> {
            // 출금 대기 중 잠금이 풀려 복구가 이 시도를 출금 미확인으로 거절했다
            payment.assignFinRequestNo(TX_NO, 15_000);
            payment.decline("WITHDRAWAL_NOT_CONFIRMED");
            return new ApprovalOutcome.Approved(15_000, 15_000, 0, LocalDate.of(2026, 10, 1), List.of(), TX_NO,
                    "1101", null);
        }).when(approver).approve(any());

        PaymentApprovalResponse response = service.approve(ME, PAYMENT_ID, request(16L), KEY);

        assertThat(response.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment.getFailureCode()).isNull();
        assertThat(payment.getFinTransactionNo()).isEqualTo("1101");
        verify(ledger).recordApproval(eq(payment), any());
    }

    @Test
    void 복구_뒤에도_PROCESSING이면_IDEMPOTENCY_IN_PROGRESS다() {
        payment.startApproval(15L, 3L);
        when(recovery.recover(PAYMENT_ID)).thenReturn(PaymentStatus.PROCESSING);

        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_IN_PROGRESS);
        verify(approver, never()).approve(any());
    }

    @Test
    void 승인_결과의_출금_요청_번호가_행과_다르면_저장하지_않는다() {
        doReturn(new ApprovalOutcome.Approved(15_000, 15_000, 0, LocalDate.of(2026, 10, 1), List.of(),
                "20261022143005000009", "1101", null)).when(approver).approve(any());

        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(16L), KEY))
                .isInstanceOf(IllegalStateException.class);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        verify(ledger, never()).recordApproval(any(), any());
        verify(barcodes, never()).markUsed(anyLong());
    }

    @Test
    void 결제별_정산_잠금을_못_잡으면_선점하지_않고_IDEMPOTENCY_IN_PROGRESS다() {
        when(locks.tryLock(startsWith(WithdrawalRecovery.SETTLE_LOCK_PREFIX), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_IN_PROGRESS);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
        verify(approver, never()).approve(any());
    }

    // ───────── 멱등 ─────────

    @Test
    void 같은_키_같은_내용이면_처음_응답을_그대로_돌려주고_다시_처리하지_않는다() {
        PaymentApprovalResponse first = service.approve(ME, PAYMENT_ID, request(null), KEY);
        when(idempotency.find(7L, KEY)).thenReturn(Optional.of(new Previous("1024:auto", PAYMENT_ID, first)));

        PaymentApprovalResponse again = service.approve(ME, PAYMENT_ID, request(null), KEY);

        assertThat(again).isSameAs(first);
        verify(approver).approve(any());
    }

    @Test
    void 기억된_응답이_없어도_이_키의_시도가_저장을_마쳤으면_그_응답을_돌려주고_다시_처리하지_않는다() {
        ArgumentCaptor<Attempt> result = ArgumentCaptor.forClass(Attempt.class);
        PaymentApprovalResponse first = service.approve(ME, PAYMENT_ID, request(null), KEY);
        verify(attempts).recordResult(eq(PAYMENT_ID), any(), result.capture());
        when(attempts.findByKey(PAYMENT_ID, KEY)).thenReturn(Optional.of(result.getValue()));

        PaymentApprovalResponse again = service.approve(ME, PAYMENT_ID, request(null), KEY);

        assertThat(again).isEqualTo(first);
        verify(approver).approve(any());
    }

    @Test
    void 이_키의_시도가_실행_중이거나_저장_전이면_IDEMPOTENCY_IN_PROGRESS다() {
        payment.startApproval(15L, 3L);
        Attempt started = Attempt.start(KEY, "1024:auto", null, 1, false, REQUESTED_AT).withNo(1);
        when(attempts.findByKey(PAYMENT_ID, KEY)).thenReturn(Optional.of(started));
        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_IN_PROGRESS);

        // 실행 결과는 남겼지만 tx2가 실패해 행이 PROCESSING
        PaymentApprovalResponse approved = new PaymentApprovalResponse(PAYMENT_ID, PaymentStatus.APPROVED, null, null,
                null, 15_000, null, null, List.of(), 15_000L, null, List.of(), null, List.of(), null, null, null,
                List.of());
        when(attempts.findByKey(PAYMENT_ID, KEY))
                .thenReturn(Optional.of(started.withResult(Attempt.APPROVED, null, null, 15_000L, approved)));
        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_IN_PROGRESS);
        verify(approver, never()).approve(any());
    }

    @Test
    void 같은_키에_다른_카드를_보내면_IDEMPOTENCY_KEY_REUSED다() {
        when(idempotency.find(7L, KEY)).thenReturn(Optional.of(new Previous("1024:auto", PAYMENT_ID, null)));
        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(16L), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);

        when(idempotency.find(7L, KEY)).thenReturn(Optional.empty());
        when(attempts.findByKey(PAYMENT_ID, KEY))
                .thenReturn(Optional.of(Attempt.start(KEY, "1024:auto", null, 1, false, REQUESTED_AT).withNo(1)));
        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(16L), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void 같은_키를_아직_처리_중이면_IDEMPOTENCY_IN_PROGRESS다() {
        when(locks.tryLock(eq("lock"), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_IN_PROGRESS);
    }

    @Test
    void 키가_없으면_IDEMPOTENCY_KEY_REQUIRED다() {
        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), null))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
    }

    // ───────── 도우미 ─────────

    /** 체크카드·혜택 0원 승인과 같은 모양: 승인액 = 원금, 실적 0, 청구 계획 없음. */
    static ApprovalOutcome.Approved approvedLike(ApprovalCommand command) {
        long amount = command.terms().amount();
        return new ApprovalOutcome.Approved(amount, amount, 0, ApprovalDates.performanceMonth(command.now()),
                List.of(), null, null, null);
    }

    /** 결과 불명으로 끝난 이 키의 시도(출금 요청 번호 TX_NO). */
    private static Attempt unknownAttempt(ActiveCard card, Integer rank, boolean userSelected) {
        return Attempt.start(KEY, userSelected ? PAYMENT_ID + ":" + card.userCardId() : PAYMENT_ID + ":auto",
                candidate(rank, card).card(), rank, userSelected, REQUESTED_AT).withNo(1).withFinRequestNo(TX_NO);
    }

    /** 첫 시도가 이 카드로 거절된 상태. 두 번째 시도를 준비한다. */
    private void declinedOnce(Long cardId) {
        payment.startApproval(cardId, 3L);
        payment.decline("INSUFFICIENT_BALANCE");
        when(attempts.count(PAYMENT_ID)).thenReturn(1);
        when(attempts.declinedCardIds(PAYMENT_ID)).thenReturn(Set.of(cardId));
        attemptNo = 2;
    }

    private static PaymentApprovalRequest request(Long userCardId) {
        return new PaymentApprovalRequest(userCardId);
    }

    private static PaymentTransaction requested(long amount, int months) {
        PaymentTransaction payment = PaymentTransaction.request(7L, 1L, PaymentChannel.OFFLINE, FacilityType.GENERAL,
                null, KEY, new byte[32], amount, months, REQUESTED_AT);
        ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
        return payment;
    }

    private static RecommendationResult recommendation(List<Candidate> candidates) {
        return new RecommendationResult("rec_test", REQUESTED_AT, false, candidates, List.of(), List.of());
    }

    private static Candidate candidate(int rank, ActiveCard card) {
        return candidate(rank, card, java.math.BigDecimal.ZERO);
    }

    private static Candidate candidate(int rank, ActiveCard card, java.math.BigDecimal netBenefit) {
        CardSummary summary = new CardSummary(card.userCardId(), card.cardProductId(), card.cardType(),
                card.defaultCard(), card.alias(), card.last4(), card.issuerCode(), card.productName());
        return new Candidate(rank, summary, netBenefit, List.of(), "mock", rank == 1 ? SelectionReason.DEFAULT_CARD : null,
                new RuleVersion(card.cardProductId(), 0));
    }
}
