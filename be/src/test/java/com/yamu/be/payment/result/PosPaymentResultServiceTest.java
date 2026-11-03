package com.yamu.be.payment.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.card.domain.CardType;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.PaymentExpiry;
import com.yamu.be.payment.PaymentPlan;
import com.yamu.be.payment.approve.ApprovalAttemptStore;
import com.yamu.be.payment.approve.PaymentApprovalProperties;
import com.yamu.be.payment.barcode.BarcodeStore;
import com.yamu.be.payment.domain.PaymentChannel;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import com.yamu.be.payment.lookup.PaymentCardLookup;
import com.yamu.be.payment.pos.PosTerminalResolver;
import com.yamu.be.payment.request.MerchantContextSnapshot;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.util.ReflectionTestUtils;

/** DB·Redis 없이 보는 경계: POS 요청이 아닌 거래, 시도 기록 장애, 모르는 거절 사유, 승인 되돌리기 뒤의 카드. 나머지는 통합 테스트가 본다. */
class PosPaymentResultServiceTest {

    static final long PAYMENT_ID = 1024L;
    static final String POS_CODE = "POS-TEST-CAFE";

    final ObjectMapper objectMapper = new ObjectMapper();
    PaymentTransactionRepository payments;
    PaymentExpiry expiry;
    ApprovalAttemptStore attempts;
    PaymentCardLookup cards;
    PosPaymentResultService service;

    @BeforeEach
    void setUp() {
        payments = mock(PaymentTransactionRepository.class);
        expiry = mock(PaymentExpiry.class);
        attempts = mock(ApprovalAttemptStore.class);
        cards = mock(PaymentCardLookup.class);
        service = new PosPaymentResultService(mock(PosTerminalResolver.class), payments, expiry,
                mock(BarcodeStore.class), attempts, cards,
                new PaymentApprovalProperties(Duration.ofHours(24), Duration.ofSeconds(30), Duration.ofSeconds(5), 3,
                        Duration.ofHours(24), Duration.ofSeconds(60), Duration.ofSeconds(30), Duration.ofSeconds(60),
                        Duration.ofMinutes(5), Duration.ofMinutes(10), false),
                objectMapper);
        when(cards.findReceiptCard(any())).thenReturn(
                Optional.of(new PaymentCardLookup.ReceiptCard("HANA", CardType.CREDIT, "5678")));
    }

    @Test
    void 거래_조건_스냅샷이_없는_결제는_POS가_만든_결제가_아니므로_404다() {
        PaymentTransaction payment = payment(null);
        when(payments.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> service.get("key", POS_CODE, PAYMENT_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        verify(expiry, never()).expire(any());
    }

    @Test
    void 시도_기록을_읽지_못하면_마감_전_거절은_retryable_true로_둔다() {
        PaymentTransaction payment = declined("INSUFFICIENT_BALANCE");
        when(payments.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));
        when(attempts.count(PAYMENT_ID)).thenThrow(new RedisConnectionFailureException("down"));

        PosPaymentResult result = service.get("key", POS_CODE, PAYMENT_ID);

        assertThat(result.retryable()).isTrue();
        assertThat(result.card().issuerName()).isEqualTo("HANA");
        assertThat(result.card().maskedNumber()).isEqualTo("****-****-****-5678");
        assertThat(result.card().plan()).isEqualTo(PaymentPlan.LUMP_SUM);
    }

    @Test
    void 마감이_지난_거절은_시도_횟수와_관계없이_retryable_false다() {
        PaymentTransaction payment = declined("INSUFFICIENT_BALANCE");
        when(payments.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));
        when(expiry.isPastDeadline(payment)).thenReturn(true);
        when(attempts.count(PAYMENT_ID)).thenReturn(1);

        assertThat(service.get("key", POS_CODE, PAYMENT_ID).retryable()).isFalse();
    }

    @Test
    void 모르는_거절_사유는_코드를_그대로_두고_기본_문구를_쓴다() {
        PaymentTransaction payment = declined("REMOVED_REASON");
        when(payments.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));

        PosPaymentResult.Failure failure = service.get("key", POS_CODE, PAYMENT_ID).failure();

        assertThat(failure.code()).isEqualTo("REMOVED_REASON");
        assertThat(failure.message()).isEqualTo(PosPaymentResultService.DEFAULT_FAILURE_MESSAGE);
    }

    @Test
    void 승인을_되돌려_REQUESTED가_된_결제는_행에_카드가_남아_있어도_카드를_보이지_않는다() {
        PaymentTransaction payment = posPayment();
        payment.startApproval(7L, 3L);
        payment.releaseApproval();
        when(payments.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));

        PosPaymentResult result = service.get("key", POS_CODE, PAYMENT_ID);

        assertThat(payment.getUserCardId()).isNotNull();
        assertThat(result.status()).isEqualTo(PaymentStatus.REQUESTED);
        assertThat(result.card()).isNull();
        assertThat(result.failure()).isNull();
        assertThat(result.retryable()).isNull();
    }

    private PaymentTransaction declined(String failureCode) {
        PaymentTransaction payment = posPayment();
        payment.startApproval(7L, 3L);
        payment.decline(failureCode);
        return payment;
    }

    private PaymentTransaction posPayment() {
        return payment(new MerchantContextSnapshot(MerchantContextSnapshot.CURRENT_VERSION,
                POS_CODE, "TEST_CAFE", "테스트 카페", "COFFEE_SHOP", PaymentChannel.OFFLINE, null, "POS-0001",
                15_000, PaymentPlan.LUMP_SUM, null).toJson(objectMapper));
    }

    private PaymentTransaction payment(String snapshot) {
        PaymentTransaction payment = PaymentTransaction.request(1L, 2L, PaymentChannel.OFFLINE, null, snapshot,
                "00000000-0000-0000-0000-000000000000", new byte[32], 15_000, 1, LocalDateTime.of(2026, 10, 22, 5, 30));
        ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
        return payment;
    }
}
