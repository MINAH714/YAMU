package com.yamu.be.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** 결제 상태 전이(명세 「결제 상태·예외 처리」 4-2). 허용하지 않는 전이는 IllegalStateException이다. */
class PaymentTransactionTransitionTest {

    static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 10, 22, 5, 30);
    static final Duration WINDOW = Duration.ofSeconds(60);

    @Test
    void 요청_직후에는_승인할_수_있고_마감은_요청_시각에서_60초_뒤다() {
        PaymentTransaction payment = requested();

        assertThat(payment.isConfirmable()).isTrue();
        assertThat(payment.confirmDeadline(WINDOW)).isEqualTo(REQUESTED_AT.plusSeconds(60));
        assertThat(payment.isPastDeadline(WINDOW, instant(REQUESTED_AT.plusSeconds(59)))).isFalse();
        assertThat(payment.isPastDeadline(WINDOW, instant(REQUESTED_AT.plusSeconds(60)))).isTrue();
    }

    @Test
    void REQUESTED에서_승인을_시작하면_PROCESSING이고_카드가_기록된다() {
        PaymentTransaction payment = requested();

        payment.startApproval(15L, 3L);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(payment.getUserCardId()).isEqualTo(15L);
        assertThat(payment.getCardProductId()).isEqualTo(3L);
        assertThat(payment.isConfirmable()).isFalse();
    }

    @Test
    void PROCESSING에서_승인하면_APPROVED이고_CHECK에_필요한_칸이_모두_찬다() {
        PaymentTransaction payment = requested();
        payment.startApproval(15L, 3L);
        LocalDateTime approvedAt = REQUESTED_AT.plusSeconds(10);

        payment.approve(15_000, 0, LocalDate.of(2026, 10, 1), approvedAt, null);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment.getApprovedAmount()).isEqualTo(15_000);
        assertThat(payment.getRecognizedPerformanceAmount()).isZero();
        assertThat(payment.getPerformanceMonth()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(payment.getApprovedAt()).isEqualTo(approvedAt);
        assertThat(payment.getInstallmentMonths()).isEqualTo(1);
        assertThat(payment.getFailureCode()).isNull();
    }

    @Test
    void PROCESSING에서_거절하면_DECLINED이고_사유와_카드가_남는다() {
        PaymentTransaction payment = requested();
        payment.startApproval(15L, 3L);

        payment.decline("INSUFFICIENT_BALANCE");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(payment.getFailureCode()).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(payment.getUserCardId()).isEqualTo(15L);
        assertThat(payment.isConfirmable()).isTrue();
    }

    @Test
    void DECLINED에서_다른_카드로_다시_시작하면_PROCESSING이고_앞_시도의_사유는_지워진다() {
        PaymentTransaction payment = requested();
        payment.startApproval(15L, 3L);
        payment.decline("INSUFFICIENT_BALANCE");

        payment.startApproval(16L, 4L);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(payment.getUserCardId()).isEqualTo(16L);
        assertThat(payment.getCardProductId()).isEqualTo(4L);
        assertThat(payment.getFailureCode()).isNull();
    }

    @Test
    void REQUESTED와_DECLINED는_만료할_수_있다() {
        PaymentTransaction fresh = requested();
        fresh.expire();
        assertThat(fresh.getStatus()).isEqualTo(PaymentStatus.EXPIRED);

        PaymentTransaction declined = requested();
        declined.startApproval(15L, 3L);
        declined.decline("INSUFFICIENT_BALANCE");
        declined.expire();
        assertThat(declined.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
    }

    @Test
    void PROCESSING은_만료할_수_없고_APPROVED는_다시_시작하거나_거절할_수_없다() {
        PaymentTransaction processing = requested();
        processing.startApproval(15L, 3L);
        assertThatThrownBy(processing::expire).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> processing.startApproval(16L, 4L)).isInstanceOf(IllegalStateException.class);

        PaymentTransaction approved = requested();
        approved.startApproval(15L, 3L);
        approved.approve(15_000, 0, LocalDate.of(2026, 10, 1), REQUESTED_AT, null);
        assertThatThrownBy(() -> approved.startApproval(16L, 4L)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> approved.decline("UNPAID_BILL")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(approved::expire).isInstanceOf(IllegalStateException.class);

        PaymentTransaction fresh = requested();
        assertThatThrownBy(() -> fresh.approve(1, 0, LocalDate.of(2026, 10, 1), REQUESTED_AT, null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> fresh.decline("UNPAID_BILL")).isInstanceOf(IllegalStateException.class);
    }

    private static PaymentTransaction requested() {
        return PaymentTransaction.request(7L, 1L, PaymentChannel.OFFLINE, FacilityType.GENERAL, null,
                "4b4a1f4e-0d1e-4a1e-9b4e-5f7d2c1a0001", new byte[32], 15_000, 1, REQUESTED_AT);
    }

    private static Instant instant(LocalDateTime utc) {
        return utc.toInstant(ZoneOffset.UTC);
    }
}
