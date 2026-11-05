package com.yamu.be.payment.approve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yamu.be.card.domain.CardType;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.redis.RedisLocks;
import com.yamu.be.payment.PaymentExpiry;
import com.yamu.be.payment.approve.ApprovalAttemptStore.Attempt;
import com.yamu.be.payment.approve.PaymentAccountReader.BankAccess;
import com.yamu.be.payment.bank.BankDirection;
import com.yamu.be.payment.bank.BankTransaction;
import com.yamu.be.payment.bank.SsafyTransferApi;
import com.yamu.be.payment.bank.TagLookup;
import com.yamu.be.payment.bank.TagLookup.Result;
import com.yamu.be.payment.bank.TransferTag;
import com.yamu.be.payment.barcode.BarcodeStore;
import com.yamu.be.payment.domain.FacilityType;
import com.yamu.be.payment.domain.PaymentChannel;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** 결과 불명 출금의 복구 판정. 금융망·저장소는 가짜다. */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
class WithdrawalRecoveryTest {

    static final long PAYMENT_ID = 1024L;
    /** 한국 2026-10-22 14:30:05 = UTC 05:30:05 */
    static final String TX_NO = "20261022143005000001";
    static final Instant REQUEST_TIME = Instant.parse("2026-10-22T05:30:05Z");
    static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 10, 22, 5, 30);
    static final BankAccess ACCESS = new BankAccess("user-key", "0012345678901234", CardType.CHECK, 9L);
    static final Attempt ATTEMPT = Attempt.start("key", "1024:16", null, null, true, REQUESTED_AT).withNo(1)
            .withFinRequestNo(TX_NO);

    @Mock PaymentTransactionRepository payments;
    @Mock PaymentAccountReader accounts;
    @Mock SsafyTransferApi transfer;
    @Mock ApprovalAttemptStore attempts;
    @Mock ApprovalLedger ledger;
    @Mock BarcodeStore barcodes;
    @Mock PaymentExpiry expiry;
    @Mock RedisLocks locks;
    @Mock RedisLocks.Lock lock;
    @Mock TransactionTemplate transaction;

    final PaymentApprovalProperties properties = new PaymentApprovalProperties(Duration.ofHours(24),
            Duration.ofSeconds(30), Duration.ofSeconds(5), 3, Duration.ofHours(24), Duration.ofSeconds(60), Duration.ofSeconds(30),
            Duration.ofSeconds(60), Duration.ofMinutes(5), Duration.ofMinutes(10), false);

    Clock clock;
    WithdrawalRecovery recovery;
    PaymentTransaction payment;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(REQUEST_TIME.plusSeconds(40), ZoneOffset.UTC); // 유예(30초) 지남
        recovery = new WithdrawalRecovery(payments, accounts, transfer, attempts, ledger, barcodes, expiry, locks,
                transaction, properties, clock);
        payment = processingWithRequestNo();
        when(locks.tryLock(anyString(), any())).thenReturn(Optional.of(lock));
        when(payments.findById(PAYMENT_ID)).thenAnswer(inv -> Optional.of(payment));
        when(payments.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transaction.execute(any())).thenAnswer(inv -> inv.<TransactionCallback<Object>>getArgument(0)
                .doInTransaction(mock(TransactionStatus.class)));
        when(accounts.forCard(7L, 16L)).thenReturn(Optional.of(ACCESS));
        when(attempts.lastProcessing(PAYMENT_ID)).thenReturn(Optional.of(ATTEMPT));
        when(attempts.findByFinRequestNo(PAYMENT_ID, TX_NO)).thenReturn(Optional.of(ATTEMPT));
        when(expiry.expire(PAYMENT_ID)).thenAnswer(inv -> payment.getStatus());
    }

    @Test
    void 잠금을_못_잡으면_아무것도_하지_않고_PROCESSING이다() {
        when(locks.tryLock(anyString(), any())).thenReturn(Optional.empty());

        assertThat(recovery.recover(PAYMENT_ID)).isEqualTo(PaymentStatus.PROCESSING);
        verify(transfer, never()).findByTag(anyString(), anyString(), any(), anyLong(), anyString());
        verify(transaction, never()).execute(any());
    }

    @Test
    void PROCESSING이_아니면_현재_상태를_그대로_돌려준다() {
        payment = requested();

        assertThat(recovery.recover(PAYMENT_ID)).isEqualTo(PaymentStatus.REQUESTED);
        verify(transfer, never()).findByTag(anyString(), anyString(), any(), anyLong(), anyString());
    }

    @Test
    void 요청_번호가_없으면_유예_전엔_두고_유예_뒤엔_REQUESTED로_되돌린다() {
        payment = requested();
        payment.startApproval(16L, 4L);
        ReflectionTestUtils.setField(payment, "updatedAt", LocalDateTime.ofInstant(clock.instant().minusSeconds(10),
                ZoneOffset.UTC));
        assertThat(recovery.recover(PAYMENT_ID)).isEqualTo(PaymentStatus.PROCESSING);

        ReflectionTestUtils.setField(payment, "updatedAt", LocalDateTime.ofInstant(clock.instant().minusSeconds(31),
                ZoneOffset.UTC));
        assertThat(recovery.recover(PAYMENT_ID)).isEqualTo(PaymentStatus.REQUESTED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
        verify(attempts).release(PAYMENT_ID, ATTEMPT);
        verify(transfer, never()).findByTag(anyString(), anyString(), any(), anyLong(), anyString());
    }

    @Test
    void 거래내역에서_같은_태그_같은_금액을_찾으면_승인으로_확정한다() {
        when(transfer.findByTag("user-key", "0012345678901234", TransferTag.payment(PAYMENT_ID), 15_000, TX_NO))
                .thenReturn(new TagLookup(Result.FOUND, List.of(bankWithdrawal(15_000))));

        assertThat(recovery.recover(PAYMENT_ID)).isEqualTo(PaymentStatus.APPROVED);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment.getFinTransactionNo()).isEqualTo("1101");
        assertThat(payment.getApprovedAmount()).isEqualTo(15_000);
        assertThat(payment.getPerformanceMonth()).isEqualTo(LocalDate.of(2026, 10, 1));
        verify(ledger).recordApproval(eq(payment), any());
        verify(barcodes).markUsed(PAYMENT_ID);
        verify(attempts).recordResult(eq(PAYMENT_ID), eq(ATTEMPT), argThat(a -> Attempt.APPROVED.equals(a.outcome())));
    }

    @Test
    void 즉시할인으로_요청액보다_적게_출금했으면_저장한_출금액으로_찾아_그_금액으로_승인한다() {
        // 15,000원 결제에 즉시할인 1,000원 → 14,000원 출금. 요청액으로 찾으면 금액 불일치로 PROCESSING에 남았다
        payment = processingWithRequestNo(14_000);
        when(transfer.findByTag("user-key", "0012345678901234", TransferTag.payment(PAYMENT_ID), 14_000, TX_NO))
                .thenReturn(new TagLookup(Result.FOUND, List.of(bankWithdrawal(14_000))));

        assertThat(recovery.recover(PAYMENT_ID)).isEqualTo(PaymentStatus.APPROVED);

        assertThat(payment.getApprovedAmount()).isEqualTo(14_000);
        verify(transfer, never()).findByTag(anyString(), anyString(), any(), eq(15_000L), anyString());
        verify(attempts).recordResult(eq(PAYMENT_ID), eq(ATTEMPT),
                argThat(a -> Attempt.APPROVED.equals(a.outcome()) && Long.valueOf(14_000).equals(a.chargedAmount())));
    }

    @Test
    void 출금_요청액이_없는_옛_행은_결제_요청액으로_찾는다() {
        ReflectionTestUtils.setField(payment, "finRequestAmount", null);
        when(transfer.findByTag("user-key", "0012345678901234", TransferTag.payment(PAYMENT_ID), 15_000, TX_NO))
                .thenReturn(new TagLookup(Result.FOUND, List.of(bankWithdrawal(15_000))));

        assertThat(recovery.recover(PAYMENT_ID)).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment.getApprovedAmount()).isEqualTo(15_000);
    }

    @Test
    void 즉시할인_결제의_늦은_출금도_저장한_출금액으로_찾아_승인으로_바로잡는다() {
        payment = processingWithRequestNo(14_000);
        payment.decline("WITHDRAWAL_NOT_CONFIRMED");
        when(transfer.findByTag("user-key", "0012345678901234", TransferTag.payment(PAYMENT_ID), 14_000, TX_NO))
                .thenReturn(new TagLookup(Result.FOUND, List.of(bankWithdrawal(14_000))));

        assertThat(recovery.recheckBeforeRetry(PAYMENT_ID)).isEqualTo(WithdrawalRecovery.LateCheck.CONFIRMED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment.getApprovedAmount()).isEqualTo(14_000);
    }

    @Test
    void 태그가_없으면_유예_전엔_PROCESSING_유예_뒤엔_WITHDRAWAL_NOT_CONFIRMED로_거절한다() {
        when(transfer.findByTag(anyString(), anyString(), any(), anyLong(), anyString()))
                .thenReturn(new TagLookup(Result.NOT_FOUND, List.of()));

        clock = Clock.fixed(REQUEST_TIME.plusSeconds(10), ZoneOffset.UTC);
        recovery = new WithdrawalRecovery(payments, accounts, transfer, attempts, ledger, barcodes, expiry, locks,
                transaction, properties, clock);
        assertThat(recovery.recover(PAYMENT_ID)).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);

        clock = Clock.fixed(REQUEST_TIME.plusSeconds(30), ZoneOffset.UTC);
        recovery = new WithdrawalRecovery(payments, accounts, transfer, attempts, ledger, barcodes, expiry, locks,
                transaction, properties, clock);
        assertThat(recovery.recover(PAYMENT_ID)).isEqualTo(PaymentStatus.DECLINED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(payment.getFailureCode()).isEqualTo("WITHDRAWAL_NOT_CONFIRMED");
        verify(attempts).recordResult(eq(PAYMENT_ID), eq(ATTEMPT), argThat(a -> Attempt.DECLINED.equals(a.outcome())
                && "WITHDRAWAL_NOT_CONFIRMED".equals(a.failureCode())));
        verify(ledger, never()).recordApproval(any(), any());
    }

    @Test
    void 태그가_없고_마감까지_지났으면_거절_뒤_만료된다() {
        when(transfer.findByTag(anyString(), anyString(), any(), anyLong(), anyString()))
                .thenReturn(new TagLookup(Result.NOT_FOUND, List.of()));
        when(expiry.expire(PAYMENT_ID)).thenAnswer(inv -> {
            payment.expire();
            return PaymentStatus.EXPIRED;
        });

        assertThat(recovery.recover(PAYMENT_ID)).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
    }

    @Test
    void 금액_불일치_태그_둘_이상_조회_실패_신용카드_행은_자동으로_판정하지_않는다() {
        when(transfer.findByTag(anyString(), anyString(), any(), anyLong(), anyString()))
                .thenReturn(new TagLookup(Result.AMOUNT_MISMATCH, List.of(bankWithdrawal(14_000))))
                .thenReturn(new TagLookup(Result.DUPLICATED, List.of(bankWithdrawal(15_000), bankWithdrawal(15_000))))
                .thenThrow(new BusinessException(ErrorCode.FIN_API_UNAVAILABLE));
        assertThat(recovery.recover(PAYMENT_ID)).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(recovery.recover(PAYMENT_ID)).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(recovery.recover(PAYMENT_ID)).isEqualTo(PaymentStatus.PROCESSING);

        when(accounts.forCard(7L, 16L)).thenReturn(Optional.of(new BankAccess("k", "a", CardType.CREDIT, 9L)));
        assertThat(recovery.recover(PAYMENT_ID)).isEqualTo(PaymentStatus.PROCESSING);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        verify(transaction, never()).execute(any());
    }

    @Test
    void 저장_중_동시_수정이면_다시_읽은_상태를_돌려준다() {
        when(transfer.findByTag(anyString(), anyString(), any(), anyLong(), anyString()))
                .thenReturn(new TagLookup(Result.FOUND, List.of(bankWithdrawal(15_000))));
        doThrow(new ObjectOptimisticLockingFailureException("payment", null)).when(transaction).execute(any());

        assertThat(recovery.recover(PAYMENT_ID)).isEqualTo(PaymentStatus.PROCESSING);
        verify(barcodes, never()).markUsed(anyLong());
        verify(attempts, never()).recordResult(anyLong(), any(), any());
    }

    @Test
    void 시도_기록이_비어_있어도_결제_행의_요청액으로_비교해_판정한다() {
        when(attempts.findByFinRequestNo(PAYMENT_ID, TX_NO)).thenReturn(Optional.empty());
        when(attempts.lastProcessing(PAYMENT_ID)).thenReturn(Optional.empty());
        when(transfer.findByTag("user-key", "0012345678901234", TransferTag.payment(PAYMENT_ID), 15_000, TX_NO))
                .thenReturn(new TagLookup(Result.FOUND, List.of(bankWithdrawal(15_000))));

        assertThat(recovery.recover(PAYMENT_ID)).isEqualTo(PaymentStatus.APPROVED);
        verify(attempts, never()).recordResult(anyLong(), any(), any());
    }

    @Test
    void 시도_기록을_고치다_Redis가_실패해도_판정은_그대로다() {
        when(attempts.findByFinRequestNo(PAYMENT_ID, TX_NO)).thenThrow(new RedisConnectionFailureException("down"));
        when(transfer.findByTag(anyString(), anyString(), any(), anyLong(), anyString()))
                .thenReturn(new TagLookup(Result.FOUND, List.of(bankWithdrawal(15_000))));

        assertThat(recovery.recover(PAYMENT_ID)).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void 금액이_다르면_PROCESSING으로_보류하고_거래_번호와_두_금액을_경고로_남긴다(CapturedOutput output) {
        when(transfer.findByTag(anyString(), anyString(), any(), anyLong(), anyString()))
                .thenReturn(new TagLookup(Result.AMOUNT_MISMATCH, List.of(bankWithdrawal(14_000))));

        assertThat(recovery.recover(PAYMENT_ID)).isEqualTo(PaymentStatus.PROCESSING);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(output).contains("paymentId=1024 bankTransactionNo=1101 expectedAmount=15000 bankAmount=14000");
    }

    @Test
    void 금융망_오류가_아닌_예외는_숨기지_않는다() {
        when(transfer.findByTag(anyString(), anyString(), any(), anyLong(), anyString()))
                .thenThrow(new IllegalArgumentException("잘못된 인자"));

        assertThatThrownBy(() -> recovery.recover(PAYMENT_ID)).isInstanceOf(IllegalArgumentException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
    }

    @Test
    void 결과를_닫을_시도는_마지막_PROCESSING이_아니라_요청_번호로_찾는다() {
        Attempt older = Attempt.start("old", "1024:15", null, null, true, REQUESTED_AT).withNo(1)
                .withFinRequestNo(TX_NO);
        when(attempts.findByFinRequestNo(PAYMENT_ID, TX_NO)).thenReturn(Optional.of(older));
        when(transfer.findByTag(anyString(), anyString(), any(), anyLong(), anyString()))
                .thenReturn(new TagLookup(Result.FOUND, List.of(bankWithdrawal(15_000))));

        recovery.recover(PAYMENT_ID);

        verify(attempts).recordResult(eq(PAYMENT_ID), eq(older), argThat(a -> Attempt.APPROVED.equals(a.outcome())
                && "1101".equals(a.finTransactionNo())));
        verify(attempts, never()).lastProcessing(PAYMENT_ID);
    }

    // ───────── 늦은 출금 ─────────

    @Test
    void 출금_미확인_거절_뒤_같은_요청_번호의_출금을_찾으면_대조_기간_안에서_승인으로_바로잡는다() {
        payment.decline("WITHDRAWAL_NOT_CONFIRMED");
        when(transfer.findByTag("user-key", "0012345678901234", TransferTag.payment(PAYMENT_ID), 15_000, TX_NO))
                .thenReturn(new TagLookup(Result.FOUND, List.of(bankWithdrawal(15_000))));

        assertThat(recovery.recheckDeclined(PAYMENT_ID)).isEqualTo(WithdrawalRecovery.LateCheck.CONFIRMED);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment.getFailureCode()).isNull();
        assertThat(payment.getFinTransactionNo()).isEqualTo("1101");
        verify(ledger).recordApproval(eq(payment), any());
        verify(barcodes).markUsed(PAYMENT_ID);
    }

    @Test
    void 만료된_출금_미확인_결제도_늦은_출금이면_승인으로_바로잡는다() {
        payment.decline("WITHDRAWAL_NOT_CONFIRMED");
        payment.expire();
        when(transfer.findByTag(anyString(), anyString(), any(), anyLong(), anyString()))
                .thenReturn(new TagLookup(Result.FOUND, List.of(bankWithdrawal(15_000))));

        assertThat(recovery.recheckDeclined(PAYMENT_ID)).isEqualTo(WithdrawalRecovery.LateCheck.CONFIRMED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void 대조_기간이_지났거나_출금_미확인_거절이_아니면_주기_작업은_다시_보지_않는다() {
        payment.decline("WITHDRAWAL_NOT_CONFIRMED");
        clock = Clock.fixed(REQUEST_TIME.plus(properties.lateWithdrawalWindow()), ZoneOffset.UTC);
        recovery = new WithdrawalRecovery(payments, accounts, transfer, attempts, ledger, barcodes, expiry, locks,
                transaction, properties, clock);
        assertThat(recovery.recheckDeclined(PAYMENT_ID)).isEqualTo(WithdrawalRecovery.LateCheck.NOT_APPLICABLE);

        payment = processingWithRequestNo();
        payment.decline("INSUFFICIENT_BALANCE");
        assertThat(recovery.recheckDeclined(PAYMENT_ID)).isEqualTo(WithdrawalRecovery.LateCheck.NOT_APPLICABLE);
        verify(transfer, never()).findByTag(anyString(), anyString(), any(), anyLong(), anyString());
    }

    @Test
    void 재승인_직전_확인은_대조_기간과_상관없이_보고_없으면_NOT_FOUND_조회_실패면_UNDECIDED다() {
        payment.decline("WITHDRAWAL_NOT_CONFIRMED");
        clock = Clock.fixed(REQUEST_TIME.plus(Duration.ofHours(1)), ZoneOffset.UTC);
        recovery = new WithdrawalRecovery(payments, accounts, transfer, attempts, ledger, barcodes, expiry, locks,
                transaction, properties, clock);
        when(transfer.findByTag(anyString(), anyString(), any(), anyLong(), anyString()))
                .thenReturn(new TagLookup(Result.NOT_FOUND, List.of()))
                .thenThrow(new BusinessException(ErrorCode.FIN_API_UNAVAILABLE));

        assertThat(recovery.recheckBeforeRetry(PAYMENT_ID)).isEqualTo(WithdrawalRecovery.LateCheck.NOT_FOUND);
        assertThat(recovery.recheckBeforeRetry(PAYMENT_ID)).isEqualTo(WithdrawalRecovery.LateCheck.UNDECIDED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DECLINED);
        verify(locks, never()).tryLock(anyString(), any());
    }

    @Test
    void 요청_번호의_앞_14자리는_한국_시각이다() {
        assertThat(WithdrawalRecovery.requestTime(TX_NO)).isEqualTo(REQUEST_TIME);
    }

    // ───────── 도우미 ─────────

    private static BankTransaction bankWithdrawal(long amount) {
        return new BankTransaction("1101", LocalDate.of(2026, 10, 22), LocalTime.of(14, 30, 6),
                BankDirection.WITHDRAWAL, amount, 1_000_000, "YAMU-P1024");
    }

    private static PaymentTransaction requested() {
        PaymentTransaction payment = PaymentTransaction.request(7L, 1L, PaymentChannel.OFFLINE, FacilityType.GENERAL,
                null, "4b4a1f4e-0d1e-4a1e-9b4e-5f7d2c1a0001", new byte[32], 15_000, 1, REQUESTED_AT);
        ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
        return payment;
    }

    private static PaymentTransaction processingWithRequestNo() {
        return processingWithRequestNo(15_000);
    }

    /** @param withdrawalAmount 출금 요청액(결제 요청액 15,000원 − 즉시할인) */
    private static PaymentTransaction processingWithRequestNo(long withdrawalAmount) {
        PaymentTransaction payment = requested();
        payment.startApproval(16L, 4L);
        payment.assignFinRequestNo(TX_NO, withdrawalAmount);
        return payment;
    }
}
