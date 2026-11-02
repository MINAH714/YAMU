package com.yamu.be.payment.approve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
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
import com.yamu.be.payment.lookup.PaymentCardLookup;
import com.yamu.be.payment.lookup.PaymentCardLookup.ActiveCard;
import com.yamu.be.payment.pos.PosProperties;
import com.yamu.be.payment.recommend.CardRecommender;
import com.yamu.be.payment.recommend.RecommendationResult;
import com.yamu.be.payment.recommend.RecommendationResult.Candidate;
import com.yamu.be.payment.recommend.RecommendationResult.CardSummary;
import com.yamu.be.payment.recommend.RecommendationResult.RuleVersion;
import com.yamu.be.payment.recommend.RecommendationResult.TieBreak;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
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
            Duration.ofSeconds(30), 3, Duration.ofHours(24), Duration.ofSeconds(60), Duration.ofSeconds(30),
            Duration.ofSeconds(60), false);
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    PaymentApprovalService service;
    PaymentTransaction payment;
    RecommendationResult recommendation;

    @BeforeEach
    void setUp() {
        service = new PaymentApprovalService(payments, cardQuery, cards, snapshots, recommender, approver, ledger,
                billing, idempotency, attempts, expiry, recovery, barcodes, locks, transaction, new ObjectMapper(),
                posProperties, properties, clock);
        payment = requested(15_000, 1);
        recommendation = recommendation(List.of(candidate(1, CREDIT), candidate(2, CHECK)));

        when(idempotency.find(anyLong(), anyString())).thenReturn(Optional.empty());
        when(idempotency.lockKey(anyLong(), anyString())).thenReturn("lock");
        when(locks.tryLock(anyString(), any())).thenReturn(Optional.of(lock));
        when(payments.findByIdAndUserId(PAYMENT_ID, 7L)).thenAnswer(inv -> Optional.of(payment));
        when(payments.findById(PAYMENT_ID)).thenAnswer(inv -> Optional.of(payment));
        when(payments.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transaction.execute(any())).thenAnswer(inv -> inv.<TransactionCallback<Object>>getArgument(0)
                .doInTransaction(mock(TransactionStatus.class)));
        when(attempts.count(PAYMENT_ID)).thenReturn(0);
        when(attempts.start(eq(PAYMENT_ID), anyLong(), anyString(), any()))
                .thenAnswer(inv -> new Attempt(1, inv.getArgument(1), KEY, inv.getArgument(3), "PROCESSING", null,
                        null));
        when(attempts.triedCardIds(PAYMENT_ID)).thenReturn(Set.of());
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
        verify(attempts).finish(any(), eq("APPROVED"), eq(null), eq(PAYMENT_ID));
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

    // ───────── 사전 검사·청구 회차 ─────────

    @Test
    void 사전_검사가_거절하면_선점과_시도_없이_그대로_던진다() {
        doThrow(new BusinessException(ErrorCode.DATA_INSUFFICIENT)).when(approver).precheck(eq(7L), any(), any());

        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.DATA_INSUFFICIENT);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
        verify(attempts, never()).start(anyLong(), anyLong(), anyString(), any());
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
        recommendation = new RecommendationResult("rec_none", REQUESTED_AT, false, List.of(), List.of(),
                RecommendationResult.UnavailableReason.NO_ELIGIBLE_CARD);

        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.CARD_NOT_ELIGIBLE);
        verify(attempts, never()).start(anyLong(), anyLong(), anyString(), any());
    }

    // ───────── 상태·마감·횟수 ─────────

    @Test
    void 이미_승인된_결제는_PAYMENT_ALREADY_PROCESSED_처리_중이면_PAYMENT_IN_PROGRESS_만료면_PAYMENT_EXPIRED다() {
        payment.startApproval(15L, 3L);
        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.PAYMENT_IN_PROGRESS);

        payment.approve(15_000, 0, LocalDate.of(2026, 10, 1), REQUESTED_AT, null);
        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.PAYMENT_ALREADY_PROCESSED);

        payment = requested(15_000, 1);
        payment.expire();
        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.PAYMENT_EXPIRED);
    }

    @Test
    void 마감이_지났으면_EXPIRED로_바꾸고_PAYMENT_EXPIRED다() {
        when(expiry.isPastDeadline(payment)).thenReturn(true);
        when(expiry.expire(PAYMENT_ID)).thenReturn(PaymentStatus.EXPIRED);

        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.PAYMENT_EXPIRED);
        verify(expiry).expire(PAYMENT_ID);
        verify(attempts, never()).start(anyLong(), anyLong(), anyString(), any());
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
    void 선점_경쟁에서_밀리면_PAYMENT_IN_PROGRESS다() {
        doThrow(new ObjectOptimisticLockingFailureException("payment", null)).when(transaction).execute(any());

        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.PAYMENT_IN_PROGRESS);
        verify(approver, never()).approve(any());
    }

    // ───────── 실행 결과 ─────────

    @Test
    void 실행이_예외로_끝나면_PROCESSING에_두고_결과를_기억하지_않는다() {
        doThrow(new BusinessException(ErrorCode.FIN_API_UNAVAILABLE)).when(approver).approve(any());

        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.FIN_API_UNAVAILABLE);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(payment.getUserCardId()).isEqualTo(15L);
        verify(barcodes, never()).markUsed(anyLong());
        verify(idempotency, never()).remember(anyLong(), anyString(), any());
        verify(attempts, never()).finish(any(), anyString(), any(), anyLong());
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
        verify(attempts).finish(any(), eq("DECLINED"), eq("INSUFFICIENT_BALANCE"), eq(PAYMENT_ID));
    }

    @Test
    void 세_번째_거절이면_더_시도할_수_없다고_알린다() {
        doReturn(ApprovalOutcome.Declined.of(FailureCode.CREDIT_LIMIT_EXCEEDED)).when(approver).approve(any());
        when(attempts.count(PAYMENT_ID)).thenReturn(2);
        when(attempts.start(eq(PAYMENT_ID), anyLong(), anyString(), any()))
                .thenAnswer(inv -> new Attempt(3, inv.getArgument(1), KEY, inv.getArgument(3), "PROCESSING", null,
                        null));

        PaymentApprovalResponse response = service.approve(ME, PAYMENT_ID, request(null), KEY);

        assertThat(response.retryable()).isFalse();
        assertThat(response.remainingAttempts()).isZero();
        assertThat(response.suggestedCards()).isEmpty();
    }

    // ───────── 결과 불명 복구·되돌리기(5.2.3) ─────────

    @Test
    void 금융망에_보내지_못했으면_결제를_REQUESTED로_되돌리고_502이며_결과를_기억하지_않는다() {
        doThrow(new ApprovalNotSentException("연결 실패", null)).when(approver).approve(any());

        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(16L), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.FIN_API_UNAVAILABLE);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
        assertThat(payment.getUserCardId()).isEqualTo(16L);
        verify(attempts).finish(any(), eq(ApprovalAttemptStore.NOT_SENT), eq(null), eq(PAYMENT_ID));
        verify(idempotency, never()).remember(anyLong(), anyString(), any());
        verify(barcodes, never()).markUsed(anyLong());
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
    void 복구가_승인으로_확정하면_행에서_만든_승인_응답을_돌려주고_새_시도를_만들지_않는다() {
        payment.startApproval(15L, 3L);
        payment.assignFinRequestNo("20261022143005000001");
        when(recovery.recover(PAYMENT_ID)).thenAnswer(inv -> {
            payment.approve(15_000, 0, LocalDate.of(2026, 10, 1), REQUESTED_AT.plusSeconds(20), "1101");
            return PaymentStatus.APPROVED;
        });

        PaymentApprovalResponse response = service.approve(ME, PAYMENT_ID, request(16L), KEY);

        assertThat(response.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(response.approvalNo()).isEqualTo("Y202610220000001024");
        assertThat(response.selectedCard().userCardId()).isEqualTo(15L);
        assertThat(response.selectedCard().recommendedRank()).isEqualTo(1);
        assertThat(response.selectedCard().userSelected()).isFalse();
        assertThat(response.chargedAmount()).isEqualTo(15_000);
        verify(approver, never()).approve(any());
        verify(attempts, never()).start(anyLong(), anyLong(), anyString(), any());
        verify(idempotency).remember(eq(7L), eq(KEY), any());
    }

    @Test
    void 복구_뒤에도_PROCESSING이면_PAYMENT_IN_PROGRESS다() {
        payment.startApproval(15L, 3L);
        when(recovery.recover(PAYMENT_ID)).thenReturn(PaymentStatus.PROCESSING);

        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.PAYMENT_IN_PROGRESS);
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
    void 결제별_정산_잠금을_못_잡으면_선점하지_않고_PAYMENT_IN_PROGRESS다() {
        when(locks.tryLock(startsWith(WithdrawalRecovery.SETTLE_LOCK_PREFIX), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.PAYMENT_IN_PROGRESS);
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
    void 같은_키에_다른_카드를_보내면_IDEMPOTENCY_KEY_REUSED다() {
        when(idempotency.find(7L, KEY)).thenReturn(Optional.of(new Previous("1024:auto", PAYMENT_ID, null)));

        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(16L), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void 같은_키를_아직_처리_중이면_PAYMENT_IN_PROGRESS다() {
        when(locks.tryLock(anyString(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(ME, PAYMENT_ID, request(null), KEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.PAYMENT_IN_PROGRESS);
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
        return new RecommendationResult("rec_test", REQUESTED_AT, false, candidates, List.of(), null);
    }

    private static Candidate candidate(int rank, ActiveCard card) {
        CardSummary summary = new CardSummary(card.userCardId(), card.cardProductId(), card.cardType(),
                card.defaultCard(), card.alias(), card.last4(), card.issuerCode(), card.productName());
        return new Candidate(rank, summary, 0, List.of(), 15_000, "mock", rank == 1 ? TieBreak.DEFAULT_CARD : null,
                new RuleVersion(card.cardProductId(), 0));
    }
}
