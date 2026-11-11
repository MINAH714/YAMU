package com.yamu.be.payment.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.card.domain.CardType;
import com.yamu.be.payment.PaymentPlan;
import com.yamu.be.payment.domain.PaymentChannel;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import com.yamu.be.payment.lookup.PaymentCardLookup;
import com.yamu.be.payment.merchant.MerchantLookup;
import com.yamu.be.payment.pos.PosProperties;
import com.yamu.be.payment.pos.PosTerminalResolver;
import com.yamu.be.payment.request.MerchantContextSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** DB 없이 보는 경계: 다른 POS 결제로 꽉 찬 페이지 넘기기, 50건 상한, 스냅샷 없는 거래, 같은 카드 한 번만 읽기. 나머지는 통합 테스트가 본다. */
class PosPaymentHistoryServiceTest {

    static final String POS_CODE = "POS-TEST-CAFE";
    static final String OTHER_POS = "POS-TEST-CAFE-DEPT";
    static final long MERCHANT_ID = 2L;
    static final Instant NOW = Instant.parse("2026-11-11T03:00:00Z");

    final ObjectMapper objectMapper = new ObjectMapper();
    PaymentTransactionRepository payments;
    PaymentCardLookup cards;
    PosPaymentHistoryService service;

    @BeforeEach
    void setUp() {
        payments = mock(PaymentTransactionRepository.class);
        cards = mock(PaymentCardLookup.class);
        PosTerminalResolver terminals = mock(PosTerminalResolver.class);
        when(terminals.resolve("key", POS_CODE)).thenReturn(
                new PosProperties.Terminal(POS_CODE, "TEST_CAFE", PaymentChannel.OFFLINE, null));
        MerchantLookup merchants = mock(MerchantLookup.class);
        when(merchants.findByBrandCode("TEST_CAFE")).thenReturn(
                Optional.of(new MerchantLookup.Merchant(MERCHANT_ID, "테스트 카페", "TEST_CAFE", "COFFEE_SHOP")));
        when(cards.findReceiptCard(any())).thenReturn(
                Optional.of(new PaymentCardLookup.ReceiptCard("HANA", CardType.CREDIT, "5678")));
        service = new PosPaymentHistoryService(terminals, merchants, payments, cards, objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void 다른_POS_결제로_꽉_찬_100건은_건너뛰고_마지막_번호_앞에서_이어_읽는다() {
        List<PaymentTransaction> otherPos = range(300, 201, OTHER_POS);
        List<PaymentTransaction> mine = range(200, 198, POS_CODE);
        when(query(Long.MAX_VALUE)).thenReturn(otherPos);
        when(query(201L)).thenReturn(mine);

        PosPaymentHistory history = service.list("key", POS_CODE);

        assertThat(history.items()).extracting(PosPaymentHistory.Item::paymentId).containsExactly(200L, 199L, 198L);
    }

    @Test
    void 기간은_지금부터_7일_전이고_승인_취소_상태만_읽는다() {
        when(query(Long.MAX_VALUE)).thenReturn(List.of());

        service.list("key", POS_CODE);

        verify(payments).findTop100ByMerchantIdAndStatusInAndRequestedAtGreaterThanEqualAndIdLessThanOrderByIdDesc(
                MERCHANT_ID, PosPaymentHistoryService.STATUSES, LocalDateTime.of(2026, 11, 4, 3, 0), Long.MAX_VALUE);
    }

    @Test
    void 쉰_건이_차면_더_읽지_않는다() {
        when(query(Long.MAX_VALUE)).thenReturn(range(500, 401, POS_CODE));

        PosPaymentHistory history = service.list("key", POS_CODE);

        assertThat(history.items()).hasSize(PosPaymentHistoryService.MAX_ITEMS);
        assertThat(history.items().get(0).paymentId()).isEqualTo(500L);
        assertThat(history.items().get(49).paymentId()).isEqualTo(451L);
        verify(payments, times(1))
                .findTop100ByMerchantIdAndStatusInAndRequestedAtGreaterThanEqualAndIdLessThanOrderByIdDesc(
                        anyLong(), any(), any(), anyLong());
    }

    @Test
    void 거래_조건_스냅샷이_없는_결제는_POS_결제가_아니므로_빠진다() {
        PaymentTransaction noSnapshot = payment(10L, null);
        when(query(Long.MAX_VALUE)).thenReturn(List.of(approved(11L, POS_CODE), noSnapshot));

        assertThat(service.list("key", POS_CODE).items())
                .extracting(PosPaymentHistory.Item::paymentId).containsExactly(11L);
    }

    @Test
    void 같은_카드는_한_번만_읽는다() {
        when(query(Long.MAX_VALUE)).thenReturn(List.of(approved(3L, POS_CODE), approved(2L, POS_CODE),
                approved(1L, POS_CODE)));

        PosPaymentHistory history = service.list("key", POS_CODE);

        assertThat(history.items()).allSatisfy(item -> {
            assertThat(item.card().issuerName()).isEqualTo("HANA");
            assertThat(item.cancelable()).isTrue();
        });
        verify(cards, times(1)).findReceiptCard(7L);
    }

    // ───────── 도우미 ─────────

    private List<PaymentTransaction> query(long beforeId) {
        return payments.findTop100ByMerchantIdAndStatusInAndRequestedAtGreaterThanEqualAndIdLessThanOrderByIdDesc(
                eq(MERCHANT_ID), any(), any(), eq(beforeId));
    }

    /** from부터 to까지(내림차순) 승인 전 결제. 상태는 SQL이 거르므로 여기서는 보지 않는다. */
    private List<PaymentTransaction> range(long from, long to, String posCode) {
        List<PaymentTransaction> rows = new ArrayList<>();
        for (long id = from; id >= to; id--) {
            rows.add(payment(id, snapshot(posCode)));
        }
        return rows;
    }

    private PaymentTransaction approved(long id, String posCode) {
        PaymentTransaction payment = payment(id, snapshot(posCode));
        payment.startApproval(7L, 3L);
        payment.approve(15_000, 0L, LocalDate.of(2026, 11, 1), LocalDateTime.of(2026, 11, 10, 5, 30), null);
        return payment;
    }

    private String snapshot(String posCode) {
        return new MerchantContextSnapshot(MerchantContextSnapshot.CURRENT_VERSION, posCode, "TEST_CAFE", "테스트 카페",
                "COFFEE_SHOP", PaymentChannel.OFFLINE, null, "POS-0001", 15_000, PaymentPlan.LUMP_SUM, null)
                .toJson(objectMapper);
    }

    private PaymentTransaction payment(long id, String snapshot) {
        PaymentTransaction payment = PaymentTransaction.request(1L, MERCHANT_ID, PaymentChannel.OFFLINE, null, snapshot,
                "00000000-0000-0000-0000-000000000000", new byte[32], 15_000, 1, LocalDateTime.of(2026, 11, 10, 5, 30));
        ReflectionTestUtils.setField(payment, "id", id);
        return payment;
    }
}
