package com.yamu.be.payment.approve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.yamu.be.global.calendar.BusinessDayCalendar;
import com.yamu.be.payment.approve.CreditLimitSource.LimitCheck;
import com.yamu.be.payment.bank.SsafyTransferApi;
import com.yamu.be.payment.barcode.Barcode;
import com.yamu.be.payment.barcode.BarcodeStatus;
import com.yamu.be.payment.barcode.BarcodeStore;
import com.yamu.be.payment.billing.BillItem;
import com.yamu.be.payment.billing.BillItemRepository;
import com.yamu.be.payment.billing.BillItemStatus;
import com.yamu.be.payment.billing.BillStatus;
import com.yamu.be.payment.billing.BillingCycle;
import com.yamu.be.payment.billing.BillingCycle.Cycle;
import com.yamu.be.payment.billing.CardBill;
import com.yamu.be.payment.billing.CardBillRepository;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.MutableClock;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

/**
 * 신용카드 모의 승인(Task 5.2.4)을 실제 MySQL·Redis·영업일 달력으로 확인한다. 계좌·금융망은 쓰지 않는다
 * (금융망 API는 가짜이고 호출되지 않아야 한다). 모의 한도 포트는 가짜로 두고 기본은 검사 생략이다.
 * 청구 월·출금 예정일은 테스트 시계(지금)의 한국 날짜를 같은 규칙({@link BillingCycle})에 넣어 비교한다. 규칙 자체는 단위 테스트가 본다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-credit-approval-test")
@Import(CreditApprovalIntegrationTest.ClockConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CreditApprovalIntegrationTest extends MySqlTestSupport {

    static final String POS_KEY = "test-pos-key";
    static final Duration RETENTION = Duration.ofMinutes(10);
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final int PAYMENT_DAY = 15;
    static final int CUTOFF_DAYS = 14;

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @MockitoBean SsafyTransferApi transfer;
    @MockitoBean CreditLimitSource limits;

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired BarcodeStore barcodes;
    @Autowired PaymentTransactionRepository payments;
    @Autowired CardBillRepository bills;
    @Autowired BillItemRepository items;
    @Autowired ApprovalAttemptStore attempts;
    @Autowired BusinessDayCalendar calendar;
    @Autowired MutableClock clock;

    final ObjectMapper json = new ObjectMapper();
    OwnershipFixtures fixtures;
    long user;
    long creditCard;
    long secondCard;
    long cardWithoutDay;
    long cafe;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM bill_item");
        jdbc.update("DELETE FROM card_bill");
        jdbc.update("DELETE FROM payment_transaction");
        jdbc.update("DELETE FROM user_card");
        jdbc.update("DELETE FROM linked_account");
        jdbc.update("DELETE FROM card_product");
        jdbc.update("DELETE FROM merchant");
        jdbc.update("DELETE FROM app_user");
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
        clock.reset();
        fixtures = new OwnershipFixtures(jdbc);
        user = fixtures.user();
        long account = fixtures.account(user);
        long creditProduct = fixtures.product();
        creditCard = fixtures.card(user, creditProduct, account, "ACTIVE");
        jdbc.update("UPDATE user_card SET is_default = TRUE, payment_day = ? WHERE user_card_id = ?", PAYMENT_DAY,
                creditCard);
        secondCard = fixtures.card(user, creditProduct, account, "ACTIVE");
        jdbc.update("UPDATE user_card SET payment_day = ? WHERE user_card_id = ?", PAYMENT_DAY, secondCard);
        cardWithoutDay = fixtures.card(user, creditProduct, account, "ACTIVE");
        cafe = merchant("테스트 카페", "TEST_CAFE", "COFFEE_SHOP");

        doReturn(LimitCheck.skipped()).when(limits).check(anyLong(), anyLong(), anyLong());
    }

    // ───────── 승인·청구 회차 ─────────

    @Test
    void 일시불_신용_승인은_출금_없이_그달_청구서와_회차_하나를_만든다() throws Exception {
        Requested requested = requested(15_000, null);
        Cycle expected = cycle().cycleFor(today());
        LocalDate expectedWithdrawal = calendar.businessDayOnOrAfter(expected.paymentDate());

        approve(requested.paymentId(), UUID.randomUUID().toString(), body(creditCard))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.chargedAmount").value(15_000))
                .andExpect(jsonPath("$.data.billItems.length()").value(1))
                .andExpect(jsonPath("$.data.billItems[0].billItemId").isNumber())
                .andExpect(jsonPath("$.data.billItems[0].seq").value(1))
                .andExpect(jsonPath("$.data.billItems[0].amount").value(15_000))
                .andExpect(jsonPath("$.data.billItems[0].billingMonth").value(expected.billingMonth().toString()))
                .andExpect(jsonPath("$.data.billItems[0].expectedWithdrawalDate").value(expectedWithdrawal.toString()))
                .andExpect(jsonPath("$.data.billItems[0].status").value("PENDING"));

        PaymentTransaction payment = payments.findById(requested.paymentId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment.getFinRequestNo()).isNull();
        assertThat(payment.getFinTransactionNo()).isNull();
        verifyNoInteractions(transfer);

        List<BillItem> created = items.findByPaymentTransactionIdOrderByInstallmentNoAsc(requested.paymentId());
        assertThat(created).hasSize(1);
        BillItem item = created.get(0);
        assertThat(item.getUserId()).isEqualTo(user);
        assertThat(item.getUserCardId()).isEqualTo(creditCard);
        assertThat(item.getInstallmentNo()).isEqualTo(1);
        assertThat(item.getBillingMonth()).isEqualTo(expected.billingMonth().atDay(1));
        assertThat(item.getPrincipalAmount()).isEqualTo(15_000);
        assertThat(item.getFeeAmount()).isZero();
        assertThat(item.getBilledAmount()).isEqualTo(15_000);
        assertThat(item.getStatus()).isEqualTo(BillItemStatus.PENDING);

        CardBill bill = bills.findById(item.getCardBillId()).orElseThrow();
        assertThat(bill.getUserId()).isEqualTo(user);
        assertThat(bill.getUserCardId()).isEqualTo(creditCard);
        assertThat(bill.getLinkedAccountId()).isNull();
        assertThat(bill.getStatus()).isEqualTo(BillStatus.ACCUMULATING);
        assertThat(bill.getBillingMonth()).isEqualTo(expected.billingMonth().atDay(1));
        assertThat(bill.getPeriodStart()).isEqualTo(expected.periodStart());
        assertThat(bill.getPeriodEnd()).isEqualTo(expected.periodEnd());
        assertThat(bill.getWithdrawalDate()).isEqualTo(expectedWithdrawal);
        assertThat(calendar.isBusinessDay(bill.getWithdrawalDate())).isTrue();
        assertThat(bill.getConfirmedAmount()).isNull();
        assertThat(bill.getPaidAmount()).isZero();
    }

    @Test
    void 할부는_회차마다_연속된_달의_청구서를_만들고_나머지는_마지막_회차에_더한다() throws Exception {
        Requested requested = requested(100_000, 3);
        YearMonth first = cycle().cycleFor(today()).billingMonth();

        approve(requested.paymentId(), UUID.randomUUID().toString(), body(creditCard))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.plan").value("INSTALLMENT"))
                .andExpect(jsonPath("$.data.installmentMonths").value(3))
                .andExpect(jsonPath("$.data.chargedAmount").value(100_000))
                .andExpect(jsonPath("$.data.billItems.length()").value(3))
                .andExpect(jsonPath("$.data.billItems[0].seq").value(1))
                .andExpect(jsonPath("$.data.billItems[0].amount").value(33_333))
                .andExpect(jsonPath("$.data.billItems[0].billingMonth").value(first.toString()))
                .andExpect(jsonPath("$.data.billItems[1].seq").value(2))
                .andExpect(jsonPath("$.data.billItems[1].amount").value(33_333))
                .andExpect(jsonPath("$.data.billItems[1].billingMonth").value(first.plusMonths(1).toString()))
                .andExpect(jsonPath("$.data.billItems[2].seq").value(3))
                .andExpect(jsonPath("$.data.billItems[2].amount").value(33_334))
                .andExpect(jsonPath("$.data.billItems[2].billingMonth").value(first.plusMonths(2).toString()));

        List<BillItem> created = items.findByPaymentTransactionIdOrderByInstallmentNoAsc(requested.paymentId());
        assertThat(created).extracting(BillItem::getInstallmentNo).containsExactly(1, 2, 3);
        assertThat(created).extracting(BillItem::getPrincipalAmount).containsExactly(33_333L, 33_333L, 33_334L);
        assertThat(created).extracting(BillItem::getBilledAmount).containsExactly(33_333L, 33_333L, 33_334L);
        assertThat(created).extracting(BillItem::getCardBillId).doesNotContainNull().doesNotHaveDuplicates();
        assertThat(billCount(creditCard)).isEqualTo(3L);
        for (BillItem item : created) {
            CardBill bill = bills.findById(item.getCardBillId()).orElseThrow();
            assertThat(bill.getUserCardId()).isEqualTo(creditCard);
            assertThat(bill.getBillingMonth()).isEqualTo(item.getBillingMonth());
            assertThat(bill.getStatus()).isEqualTo(BillStatus.ACCUMULATING);
            assertThat(bill.getPeriodEnd()).isEqualTo(bill.getBillingMonth().withDayOfMonth(PAYMENT_DAY).minusDays(CUTOFF_DAYS));
            assertThat(bill.getWithdrawalDate()).isAfterOrEqualTo(bill.getBillingMonth().withDayOfMonth(PAYMENT_DAY));
            assertThat(calendar.isBusinessDay(bill.getWithdrawalDate())).isTrue();
        }
        verifyNoInteractions(transfer);
    }

    @Test
    void 같은_카드로_같은_달에_두_번_결제하면_청구서_하나에_회차가_붙는다() throws Exception {
        Requested first = requested(15_000, null);
        Requested second = requested(20_000, null);

        approve(first.paymentId(), UUID.randomUUID().toString(), body(creditCard))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        approve(second.paymentId(), UUID.randomUUID().toString(), body(creditCard))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        assertThat(billCount(creditCard)).isEqualTo(1L);
        List<Long> assigned = jdbc.queryForList(
                "SELECT card_bill_id FROM bill_item WHERE payment_transaction_id IN (?, ?)", Long.class,
                first.paymentId(), second.paymentId());
        assertThat(assigned).hasSize(2);
        assertThat(assigned.stream().distinct()).hasSize(1);
    }

    @Test
    void 다른_카드는_같은_달이어도_청구서가_다르다() throws Exception {
        Requested first = requested(15_000, null);
        Requested second = requested(20_000, null);

        approve(first.paymentId(), UUID.randomUUID().toString(), body(creditCard))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        approve(second.paymentId(), UUID.randomUUID().toString(), body(secondCard))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        assertThat(billCount(creditCard)).isEqualTo(1L);
        assertThat(billCount(secondCard)).isEqualTo(1L);
    }

    @Test
    void 같은_카드_같은_달의_동시_승인도_청구서는_하나다() throws Exception {
        Requested first = requested(15_000, null);
        Requested second = requested(20_000, null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (Requested requested : List.of(first, second)) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(5, TimeUnit.SECONDS);
                    return approve(requested.paymentId(), UUID.randomUUID().toString(), body(creditCard))
                            .andReturn().getResponse().getStatus();
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(billCount(creditCard)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bill_item", Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT card_bill_id) FROM bill_item", Long.class)).isEqualTo(1L);
        for (Requested requested : List.of(first, second)) {
            assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus())
                    .isEqualTo(PaymentStatus.APPROVED);
        }
    }

    // ───────── 사전 검사·한도 ─────────

    @Test
    void 결제일이_없는_신용카드는_승인_전_422이고_결제는_REQUESTED_그대로다() throws Exception {
        Requested requested = requested(15_000, null);

        approve(requested.paymentId(), UUID.randomUUID().toString(), body(cardWithoutDay))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("DATA_INSUFFICIENT"))
                .andExpect(jsonPath("$.error.details.reason").value("PAYMENT_DAY_MISSING"));

        PaymentTransaction payment = payments.findById(requested.paymentId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
        assertThat(payment.getUserCardId()).isNull();
        assertThat(attempts.count(requested.paymentId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bill_item", Long.class)).isZero();

        // 결제일이 있는 카드로 다시 요청하면 그대로 승인된다
        approve(requested.paymentId(), UUID.randomUUID().toString(), body(creditCard))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.selectedCard.userCardId").value(creditCard));
        assertThat(attempts.count(requested.paymentId())).isEqualTo(1);
    }

    @Test
    void 한도를_모르면_승인_전_422다() throws Exception {
        Requested requested = requested(15_000, null);
        doReturn(LimitCheck.unknown()).when(limits).check(anyLong(), anyLong(), anyLong());

        approve(requested.paymentId(), UUID.randomUUID().toString(), body(creditCard))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("DATA_INSUFFICIENT"))
                .andExpect(jsonPath("$.error.details.reason").value("CREDIT_LIMIT_UNKNOWN"));

        assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REQUESTED);
        assertThat(attempts.count(requested.paymentId())).isZero();
    }

    @Test
    void 전체_원금이_가용_한도를_넘으면_DECLINED이고_회차를_만들지_않는다() throws Exception {
        Requested requested = requested(100_000, 3);
        doReturn(LimitCheck.exceeded(80_000)).when(limits).check(user, creditCard, 100_000L);

        approve(requested.paymentId(), UUID.randomUUID().toString(), body(creditCard))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DECLINED"))
                .andExpect(jsonPath("$.data.failure.code").value("CREDIT_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.data.failure.details.availableLimit").value(80_000))
                .andExpect(jsonPath("$.data.retryable").value(true))
                .andExpect(jsonPath("$.data.billItems").isEmpty());

        PaymentTransaction payment = payments.findById(requested.paymentId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(payment.getFailureCode()).isEqualTo("CREDIT_LIMIT_EXCEEDED");
        assertThat(attempts.count(requested.paymentId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bill_item", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_bill", Long.class)).isZero();

        // 한도가 넉넉한 다른 카드로 재승인
        approve(requested.paymentId(), UUID.randomUUID().toString(), body(secondCard))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.billItems.length()").value(3));
        assertThat(billCount(secondCard)).isEqualTo(3L);
    }

    // ───────── 도우미 ─────────

    private BillingCycle cycle() {
        return new BillingCycle(PAYMENT_DAY, CUTOFF_DAYS);
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), KST);
    }

    private long billCount(long userCardId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM card_bill WHERE user_card_id = ?", Long.class, userCardId);
    }

    private ResultActions approve(long paymentId, String idempotencyKey, String body) throws Exception {
        MockHttpServletRequestBuilder builder = post("/api/payments/{paymentId}/approve", paymentId)
                .with(AuthUsers.asUser(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(builder);
    }

    private static String body(long userCardId) {
        return "{\"userCardId\": " + userCardId + "}";
    }

    /** POS 결제 요청 API로 REQUESTED 결제를 만든다. months가 있으면 할부. 바코드는 저장소에 직접 넣는다. */
    private Requested requested(long amount, Integer months) throws Exception {
        String token = issued(user);
        Map<String, Object> body = new HashMap<>();
        body.put("barcodeToken", token);
        body.put("merchantId", cafe);
        body.put("posCode", "POS-TEST-CAFE");
        body.put("amount", amount);
        if (months != null) {
            body.put("plan", "INSTALLMENT");
            body.put("installmentMonths", months);
        }
        MvcResult result = mockMvc.perform(post("/api/pos/payment-requests")
                        .header("X-POS-Key", POS_KEY)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        long paymentId = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.data.paymentId")).longValue();
        return new Requested(paymentId, token);
    }

    private String issued(long userId) {
        String token = String.format("%016d", 3_000_000_000_000_000L + userId * 7 + payments.count());
        Instant now = clock.instant();
        assertThat(barcodes.issue(new Barcode(token, userId, BarcodeStatus.ISSUED, now, now.plusSeconds(60)), RETENTION))
                .isTrue();
        return token;
    }

    private long merchant(String name, String brandCode, String categoryCode) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return new SimpleJdbcInsert(jdbc).withTableName("merchant").usingGeneratedKeyColumns("merchant_id")
                .usingColumns("merchant_name", "brand_code", "merchant_category_code", "created_at", "updated_at")
                .executeAndReturnKey(Map.of("merchant_name", name, "brand_code", brandCode,
                        "merchant_category_code", categoryCode, "created_at", now, "updated_at", now))
                .longValue();
    }

    private record Requested(long paymentId, String token) {}
}
