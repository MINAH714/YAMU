package com.yamu.be.payment.cancel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yamu.be.payment.billing.Refund;
import com.yamu.be.payment.billing.RefundRepository;
import com.yamu.be.payment.billing.RefundStatus;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.Concurrently;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

/**
 * 결제 전체 취소(Task 5.3.2)를 실제 MySQL·Redis로 확인한다. 승인 흐름과 떼어 보려고 승인된 결제 행은 SQL로 직접 넣는다.
 *
 * <p>청구 회차 처리는 S15P31E103-200 Merge 뒤 붙이므로 여기서는 회차 없는 결제와, 회차가 있으면 임시 구현이 취소를 막는지만 본다.
 * 실적·한도 복구도 원장 반영(Task 5.2.5) 전이라 응답의 {@code restored}는 비어 있다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-payment-cancel-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentCancelIntegrationTest extends MySqlTestSupport {

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PaymentTransactionRepository payments;
    @Autowired RefundRepository refunds;

    OwnershipFixtures fixtures;
    long user;
    long otherUser;
    long account;
    long creditCard;
    long checkCard;
    long merchant;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM refund");
        jdbc.update("DELETE FROM bill_item");
        jdbc.update("DELETE FROM card_bill");
        jdbc.update("DELETE FROM payment_transaction");
        jdbc.update("DELETE FROM user_card");
        jdbc.update("DELETE FROM linked_account");
        jdbc.update("DELETE FROM card_product");
        jdbc.update("DELETE FROM merchant");
        jdbc.update("DELETE FROM app_user");
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
        fixtures = new OwnershipFixtures(jdbc);
        user = fixtures.user();
        otherUser = fixtures.user();
        account = fixtures.account(user);
        creditCard = fixtures.card(user, fixtures.product(), account, "ACTIVE");
        long checkProduct = fixtures.product();
        jdbc.update("UPDATE card_product SET card_type = 'CHECK', billing_cutoff_days = NULL WHERE card_product_id = ?",
                checkProduct);
        checkCard = fixtures.card(user, checkProduct, account, "ACTIVE");
        merchant = merchant();
    }

    // ───────── 취소 성공 ─────────

    @Test
    void 승인된_신용카드_결제를_취소하면_CANCELED와_취소_시각이_남고_승인_당시_값은_그대로다() throws Exception {
        long paymentId = approved(user, creditCard, "YAMU");

        cancel(paymentId, UUID.randomUUID().toString(), "{\"reason\": \"고객 요청\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentId").value(paymentId))
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.canceledAt").isString())
                .andExpect(jsonPath("$.data.restored.performance").doesNotExist())
                .andExpect(jsonPath("$.data.restored.limitImpacts.length()").value(0))
                .andExpect(jsonPath("$.data.billItems.length()").value(0))
                .andExpect(jsonPath("$.data.refunds.length()").value(0))
                .andExpect(jsonPath("$.data.accountBalanceAfter").doesNotExist());

        PaymentTransaction payment = payments.findById(paymentId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(payment.getCanceledAt()).isNotNull();
        // 승인 당시 값은 지우지 않는다. 실적 복구는 원장 반영 전이라 아직 하지 않았으므로 복구 시각도 없다
        assertThat(payment.getApprovedAmount()).isEqualTo(15_000L);
        assertThat(payment.getRecognizedPerformanceAmount()).isEqualTo(15_000L);
        assertThat(payment.getPerformanceMonth()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(payment.getPerformanceReversedAt()).isNull();
    }

    @Test
    void 본문_없이도_취소된다() throws Exception {
        long paymentId = approved(user, creditCard, "YAMU");

        cancel(paymentId, UUID.randomUUID().toString(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"));
    }

    @Test
    void 등록_해제한_카드의_결제도_취소된다() throws Exception {
        long paymentId = approved(user, creditCard, "YAMU");
        jdbc.update("UPDATE user_card SET status = 'UNREGISTERED' WHERE user_card_id = ?", creditCard);

        cancel(paymentId, UUID.randomUUID().toString(), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"));
    }

    // ───────── 재시도·동시 요청 ─────────

    @Test
    void 같은_키로_다시_보내면_처음_응답을_그대로_돌려준다() throws Exception {
        long paymentId = approved(user, creditCard, "YAMU");
        String key = UUID.randomUUID().toString();

        String first = cancel(paymentId, key, "{\"reason\": \"고객 요청\"}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = cancel(paymentId, key, "{\"reason\": \"고객 요청\"}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void 같은_키에_다른_사유를_보내면_409_IDEMPOTENCY_KEY_REUSED() throws Exception {
        long paymentId = approved(user, creditCard, "YAMU");
        String key = UUID.randomUUID().toString();
        cancel(paymentId, key, "{\"reason\": \"고객 요청\"}").andExpect(status().isOk());

        cancel(paymentId, key, "{\"reason\": \"단순 변심\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void 다른_키로_다시_취소하면_409_ALREADY_CANCELED() throws Exception {
        long paymentId = approved(user, creditCard, "YAMU");
        cancel(paymentId, UUID.randomUUID().toString(), null).andExpect(status().isOk());

        cancel(paymentId, UUID.randomUUID().toString(), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_CANCELED"));
    }

    @Test
    void 다른_키로_동시에_취소하면_한_건만_성공하고_나머지는_ALREADY_CANCELED() throws Exception {
        long paymentId = approved(user, creditCard, "YAMU");

        List<String> results = Concurrently.run(3, () -> {
            String body = cancel(paymentId, UUID.randomUUID().toString(), null).andReturn().getResponse()
                    .getContentAsString();
            return body.contains("\"success\":true") ? "OK" : body.contains("ALREADY_CANCELED") ? "ALREADY" : body;
        });

        assertThat(results).containsExactlyInAnyOrder("OK", "ALREADY", "ALREADY");
        assertThat(payments.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.CANCELED);
    }

    // ───────── 취소할 수 없는 결제 ─────────

    @Test
    void 남의_결제와_없는_결제는_404() throws Exception {
        long others = approved(otherUser, fixtures.card(otherUser, fixtures.product(), null, "ACTIVE"), "YAMU");

        cancel(others, UUID.randomUUID().toString(), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        cancel(999_999L, UUID.randomUUID().toString(), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        assertThat(payments.findById(others).orElseThrow().getStatus()).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void 승인되지_않은_결제는_409_PAYMENT_NOT_CANCELABLE() throws Exception {
        long requested = requested(user);

        cancel(requested, UUID.randomUUID().toString(), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_NOT_CANCELABLE"))
                .andExpect(jsonPath("$.error.details.reason").value("NOT_APPROVED"))
                .andExpect(jsonPath("$.error.details.status").value("REQUESTED"));
        assertThat(payments.findById(requested).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REQUESTED);
    }

    @Test
    void SEED_거래는_409_PAYMENT_NOT_CANCELABLE() throws Exception {
        long seed = approved(user, creditCard, "SEED");

        cancel(seed, UUID.randomUUID().toString(), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_NOT_CANCELABLE"))
                .andExpect(jsonPath("$.error.details.reason").value("SEED_TRANSACTION"));
        assertThat(payments.findById(seed).orElseThrow().getStatus()).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void 체크카드_결제는_입금_구현_전까지_409_PAYMENT_NOT_CANCELABLE() throws Exception {
        long paymentId = approved(user, checkCard, "YAMU");

        cancel(paymentId, UUID.randomUUID().toString(), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_NOT_CANCELABLE"))
                .andExpect(jsonPath("$.error.details.reason").value("CHECK_CARD_NOT_SUPPORTED"));
        assertThat(payments.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void 청구_회차가_있으면_회차_처리_구현_전에는_취소하지_않고_결제를_APPROVED로_둔다() throws Exception {
        long paymentId = approved(user, creditCard, "YAMU");
        billItem(paymentId);

        cancel(paymentId, UUID.randomUUID().toString(), null)
                .andExpect(status().isInternalServerError());

        PaymentTransaction payment = payments.findById(paymentId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment.getCanceledAt()).isNull();
    }

    // ───────── 잘못된 요청 ─────────

    @Test
    void 취소_금액을_보내면_400_PARTIAL_CANCEL_NOT_SUPPORTED() throws Exception {
        long paymentId = approved(user, creditCard, "YAMU");

        cancel(paymentId, UUID.randomUUID().toString(), "{\"amount\": 5000}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PARTIAL_CANCEL_NOT_SUPPORTED"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("amount"));
        assertThat(payments.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void 사유가_문자열이_아니거나_100자를_넘으면_400_INVALID_REQUEST() throws Exception {
        long paymentId = approved(user, creditCard, "YAMU");

        cancel(paymentId, UUID.randomUUID().toString(), "{\"reason\": 1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("reason"));
        cancel(paymentId, UUID.randomUUID().toString(), "{\"reason\": \"" + "가".repeat(101) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void Idempotency_Key가_없거나_UUID가_아니면_400() throws Exception {
        long paymentId = approved(user, creditCard, "YAMU");

        cancel(paymentId, null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        cancel(paymentId, "not-a-uuid", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    // ───────── 환급 엔티티 ─────────

    @Test
    void 환급은_회차당_한_행이고_수수료_0원으로_원금과_같은_금액을_적는다() {
        long paymentId = approved(user, creditCard, "YAMU");
        long itemId = billItem(paymentId);

        Refund saved = refunds.saveAndFlush(Refund.pending(paymentId, itemId, user, account, 15_000, LocalDate.of(2026, 11, 6)));

        Refund read = refunds.findById(saved.getId()).orElseThrow();
        assertThat(read.getStatus()).isEqualTo(RefundStatus.PENDING);
        assertThat(read.getFeeAmount()).isZero();
        assertThat(read.getRefundAmount()).isEqualTo(15_000L);
        assertThat(read.getRequestKey()).hasSize(36);
        assertThat(refunds.findByPaymentTransactionIdOrderByBillItemIdAsc(paymentId)).hasSize(1);
        Refund duplicate = Refund.pending(paymentId, itemId, user, account, 15_000, null);
        assertThatThrownBy(() -> refunds.saveAndFlush(duplicate)).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ───────── 도우미 ─────────

    private ResultActions cancel(long paymentId, String idempotencyKey, String body) throws Exception {
        MockHttpServletRequestBuilder builder = post("/api/payments/{paymentId}/cancel", paymentId)
                .with(AuthUsers.asUser(user));
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(builder);
    }

    /** 승인된 15,000원 일시불 결제 행. source는 YAMU 또는 SEED. */
    private long approved(long owner, long userCardId, String source) {
        Long productId = jdbc.queryForObject("SELECT card_product_id FROM user_card WHERE user_card_id = ?", Long.class,
                userCardId);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Map<String, Object> row = paymentRow(owner, source, "APPROVED");
        row.put("user_card_id", userCardId);
        row.put("card_product_id", productId);
        row.put("approved_amount", 15_000L);
        row.put("recognized_performance_amount", 15_000L);
        row.put("performance_month", LocalDate.of(2026, 11, 1));
        row.put("approved_at", now);
        return insert("payment_transaction", "payment_transaction_id", row);
    }

    /** 카드 선택 전 REQUESTED 결제 행. */
    private long requested(long owner) {
        return insert("payment_transaction", "payment_transaction_id", paymentRow(owner, "YAMU", "REQUESTED"));
    }

    private Map<String, Object> paymentRow(long owner, String source, String status) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UUID a = UUID.randomUUID();
        Map<String, Object> row = new HashMap<>();
        row.put("user_id", owner);
        row.put("merchant_id", merchant);
        row.put("request_key", UUID.randomUUID().toString());
        row.put("request_fingerprint", ByteBuffer.allocate(32).putLong(a.getMostSignificantBits())
                .putLong(a.getLeastSignificantBits()).array());
        row.put("source", source);
        row.put("status", status);
        row.put("requested_amount", 15_000L);
        row.put("installment_months", 1);
        row.put("requested_at", now);
        row.put("created_at", now);
        row.put("updated_at", now);
        return row;
    }

    /** 청구서 배정 전 일시불 회차. 회차 엔티티(S15P31E103-200)가 아직 develop에 없어 SQL로 넣는다. */
    private long billItem(long paymentId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Map<String, Object> row = new HashMap<>();
        row.put("payment_transaction_id", paymentId);
        row.put("user_id", user);
        row.put("user_card_id", creditCard);
        row.put("installment_no", 1);
        row.put("billing_month", LocalDate.of(2026, 12, 1));
        row.put("principal_amount", 15_000L);
        row.put("fee_amount", 0L);
        row.put("billed_amount", 15_000L);
        row.put("status", "PENDING");
        row.put("created_at", now);
        row.put("updated_at", now);
        return insert("bill_item", "bill_item_id", row);
    }

    private long merchant() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return insert("merchant", "merchant_id", Map.of("merchant_name", "테스트 카페", "brand_code", "TEST_CAFE",
                "merchant_category_code", "COFFEE_SHOP", "created_at", now, "updated_at", now));
    }

    private long insert(String table, String idColumn, Map<String, Object> row) {
        return new SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns(idColumn)
                .usingColumns(row.keySet().toArray(String[]::new))
                .executeAndReturnKey(row).longValue();
    }
}
