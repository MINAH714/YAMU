package com.yamu.be.payment.approve;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yamu.be.payment.domain.FacilityType;
import com.yamu.be.payment.domain.PaymentChannel;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/** 주기 작업이 대상을 빠짐없이 훑고, 판정하지 못한 행을 잠시 건너뛰는지. 판정 자체는 WithdrawalRecoveryTest가 본다. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WithdrawalRecoveryJobTest {

    static final Instant NOW = Instant.parse("2026-10-22T06:00:00Z");

    @Mock PaymentTransactionRepository payments;
    @Mock WithdrawalRecovery recovery;

    final PaymentApprovalProperties properties = new PaymentApprovalProperties(Duration.ofHours(24),
            Duration.ofSeconds(30), Duration.ofSeconds(5), 3, Duration.ofHours(24), Duration.ofSeconds(60),
            Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofMinutes(5), Duration.ofMinutes(10), false);
    final MovableClock clock = new MovableClock(NOW);

    WithdrawalRecoveryJob job;

    @BeforeEach
    void setUp() {
        job = new WithdrawalRecoveryJob(payments, recovery, properties, clock);
        when(payments.findTop50ByStatusInAndFailureCodeAndUpdatedAtAfterAndIdGreaterThanOrderByIdAsc(any(),
                anyString(), any(), anyLong())).thenReturn(List.of());
        when(recovery.recover(anyLong())).thenReturn(PaymentStatus.APPROVED);
    }

    @Test
    void 판정하지_못한_행이_50건_넘게_앞에_쌓여도_결제_번호_순으로_다음_페이지까지_본다() {
        when(payments.findTop50ByStatusAndUpdatedAtBeforeAndIdGreaterThanOrderByIdAsc(eq(PaymentStatus.PROCESSING),
                any(), eq(0L))).thenReturn(rows(1, 50));
        when(payments.findTop50ByStatusAndUpdatedAtBeforeAndIdGreaterThanOrderByIdAsc(eq(PaymentStatus.PROCESSING),
                any(), eq(50L))).thenReturn(rows(51, 51));
        when(recovery.recover(anyLong())).thenReturn(PaymentStatus.PROCESSING);
        when(recovery.recover(51L)).thenReturn(PaymentStatus.APPROVED);

        job.run();

        verify(recovery).recover(51L);
        verify(recovery, times(51)).recover(anyLong());
    }

    @Test
    void 판정하지_못한_행은_보류_시간_동안_건너뛰고_그_뒤에_다시_본다() {
        when(payments.findTop50ByStatusAndUpdatedAtBeforeAndIdGreaterThanOrderByIdAsc(eq(PaymentStatus.PROCESSING),
                any(), eq(0L))).thenReturn(rows(1, 2));
        when(recovery.recover(1L)).thenReturn(PaymentStatus.PROCESSING);
        when(recovery.recover(2L)).thenThrow(new IllegalStateException("복호화 실패"));

        job.run();
        clock.advance(Duration.ofMinutes(1));
        job.run();
        verify(recovery, times(1)).recover(1L);
        verify(recovery, times(1)).recover(2L);

        clock.advance(Duration.ofMinutes(4));
        job.run();
        verify(recovery, times(2)).recover(1L);
        verify(recovery, times(2)).recover(2L);
    }

    @Test
    void 출금_미확인으로_거절_만료한_결제를_늦은_출금_대조로_넘긴다() {
        when(payments.findTop50ByStatusAndUpdatedAtBeforeAndIdGreaterThanOrderByIdAsc(any(), any(), anyLong()))
                .thenReturn(List.of());
        when(payments.findTop50ByStatusInAndFailureCodeAndUpdatedAtAfterAndIdGreaterThanOrderByIdAsc(
                eq(List.of(PaymentStatus.DECLINED, PaymentStatus.EXPIRED)), eq("WITHDRAWAL_NOT_CONFIRMED"), any(),
                eq(0L))).thenReturn(rows(7, 7));

        job.run();

        verify(recovery).recheckDeclined(7L);
        verify(recovery, never()).recover(anyLong());
    }

    private static List<PaymentTransaction> rows(long from, long to) {
        return LongStream.rangeClosed(from, to).mapToObj(id -> {
            PaymentTransaction payment = PaymentTransaction.request(7L, 1L, PaymentChannel.OFFLINE,
                    FacilityType.GENERAL, null, "key-" + id, new byte[32], 15_000, 1,
                    LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
            ReflectionTestUtils.setField(payment, "id", id);
            return payment;
        }).toList();
    }

    static class MovableClock extends Clock {

        private Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
