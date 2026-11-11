package com.yamu.be.payment.cancel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.payment.PaymentPlan;
import com.yamu.be.payment.barcode.Barcode;
import com.yamu.be.payment.barcode.BarcodeStatus;
import com.yamu.be.payment.barcode.BarcodeStore;
import com.yamu.be.payment.domain.PaymentChannel;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import com.yamu.be.payment.request.MerchantContextSnapshot;
import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.Concurrently;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

/**
 * POS 결제 취소(S15P31E103-424)를 실제 MySQL·Redis로 확인한다. 승인된 신용카드 결제와 바코드는 직접 넣는다.
 * 취소 처리 자체(회차·환급·체크카드 환불·금융망 실패)는 {@link PaymentCancelIntegrationTest}·{@link CheckCardCancelIntegrationTest}가
 * 보므로 여기서는 POS 쪽 확인(바코드·고객·posCode·멱등·동시 요청)과 실적 복구가 앱 취소와 같이 일어나는지를 본다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-pos-payment-cancel-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PosPaymentCancelIntegrationTest extends MySqlTestSupport {

    static final String POS_KEY = "test-pos-key";
    static final String POS_CODE = "POS-TEST-CAFE";
    static final String OTHER_POS_SAME_BRAND = "POS-TEST-CAFE-DEPT";
    static final Duration RETENTION = Duration.ofMinutes(10);
    static final LocalDate NOVEMBER = LocalDate.of(2026, 11, 1);

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired BarcodeStore barcodes;
    @Autowired PaymentTransactionRepository payments;
    @Autowired ObjectMapper objectMapper;

    OwnershipFixtures fixtures;
    long user;
    long otherUser;
    long creditCard;
    long merchant;
    int barcodeSeq;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM card_monthly_performance");
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
        creditCard = fixtures.card(user, fixtures.product(), fixtures.account(user), "ACTIVE");
        merchant = insert("merchant", "merchant_id", Map.of("merchant_name", "테스트 카페", "brand_code", "TEST_CAFE",
                "merchant_category_code", "COFFEE_SHOP", "created_at", now(), "updated_at", now()));
    }

    /** 실적 합계 행은 카드를 지울 수 없게 하므로 다음 테스트 클래스를 위해 지운다. */
    @AfterEach
    void deletePerformanceRows() {
        jdbc.update("DELETE FROM card_monthly_performance");
    }

    // ───────── 취소 성공 ─────────

    @Test
    void 고객_바코드로_취소하면_CANCELED이고_실적이_되돌아가며_바코드는_닫힌다() throws Exception {
        long paymentId = approved(POS_CODE);
        performance(40_000);
        String token = issued(user);

        String body = cancel(paymentId, POS_CODE, token, UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentId").value(paymentId))
                .andExpect(jsonPath("$.data.orderNo").value("POS-0001"))
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.canceledAt").isString())
                .andExpect(jsonPath("$.data.amount").value(15_000))
                .andExpect(jsonPath("$.data.chargedAmount").value(15_000))
                .andReturn().getResponse().getContentAsString();

        // 앱 취소 응답의 실적·청구·잔액은 POS에 보이지 않는다
        assertThat(body).doesNotContain("restored", "billItems", "refunds", "accountBalanceAfter", "userId");
        PaymentTransaction payment = payments.findById(paymentId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(payment.getPerformanceReversedAt()).isNotNull();
        assertThat(recognized()).isEqualTo(25_000L);
        assertThat(barcodeStatus(token)).isEqualTo(BarcodeStatus.EXPIRED);
    }

    @Test
    void 같은_키로_다시_보내면_바코드가_닫혀_있어도_처음_응답을_돌려준다() throws Exception {
        long paymentId = approved(POS_CODE);
        performance(40_000);
        String token = issued(user);
        String key = UUID.randomUUID().toString();

        String first = cancel(paymentId, POS_CODE, token, key)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = cancel(paymentId, POS_CODE, token, key)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(recognized()).isEqualTo(25_000L);
    }

    // ───────── 고객·POS 불일치 ─────────

    @Test
    void 다른_고객의_바코드면_404이고_결제와_바코드를_바꾸지_않는다() throws Exception {
        long paymentId = approved(POS_CODE);
        String token = issued(otherUser);

        cancel(paymentId, POS_CODE, token, UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        assertThat(payments.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(barcodeStatus(token)).isEqualTo(BarcodeStatus.ISSUED);
    }

    @Test
    void 결제를_만든_POS가_아니면_같은_브랜드여도_404다() throws Exception {
        long paymentId = approved(POS_CODE);
        String token = issued(user);

        cancel(paymentId, OTHER_POS_SAME_BRAND, token, UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        assertThat(payments.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(barcodeStatus(token)).isEqualTo(BarcodeStatus.ISSUED);
    }

    @Test
    void POS_요청이_아닌_거래는_404다() throws Exception {
        long paymentId = approved(null);
        String token = issued(user);

        cancel(paymentId, POS_CODE, token, UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    // ───────── 바코드 상태 ─────────

    @Test
    void 없는_바코드는_404_BARCODE_NOT_FOUND다() throws Exception {
        long paymentId = approved(POS_CODE);

        cancel(paymentId, POS_CODE, "9999999999999999", UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BARCODE_NOT_FOUND"));
        cancel(paymentId, POS_CODE, "not-a-barcode", UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BARCODE_NOT_FOUND"));
    }

    @Test
    void 유효_시간이_지난_바코드는_410이고_결제를_바꾸지_않는다() throws Exception {
        long paymentId = approved(POS_CODE);
        Instant issuedAt = Instant.now().minusSeconds(61);
        String token = token();
        barcodes.issue(new Barcode(token, user, BarcodeStatus.ISSUED, issuedAt, issuedAt.plusSeconds(60)), RETENTION);

        cancel(paymentId, POS_CODE, token, UUID.randomUUID().toString())
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("BARCODE_EXPIRED"));

        assertThat(payments.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void 결제에_스캔된_바코드는_409_BARCODE_ALREADY_USED다() throws Exception {
        long paymentId = approved(POS_CODE);
        String token = issued(user);
        barcodes.scan(token, user, 999_999L, Instant.now(), RETENTION);

        cancel(paymentId, POS_CODE, token, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BARCODE_ALREADY_USED"));

        assertThat(payments.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void 이미_취소된_결제는_409이고_바코드를_닫지_않아_다른_결제에_다시_쓸_수_있다() throws Exception {
        long paymentId = approved(POS_CODE);
        jdbc.update("UPDATE payment_transaction SET status = 'CANCELED', canceled_at = ? "
                + "WHERE payment_transaction_id = ?", now(), paymentId);
        String token = issued(user);

        cancel(paymentId, POS_CODE, token, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_CANCELED"));

        assertThat(barcodeStatus(token)).isEqualTo(BarcodeStatus.ISSUED);
    }

    // ───────── 연타·동시 요청 ─────────

    @Test
    void 같은_결제를_다른_키로_5번_동시에_취소해도_한_번만_취소되고_실적도_한_번만_되돌린다() throws Exception {
        long paymentId = approved(POS_CODE);
        performance(40_000);
        String token = issued(user);

        List<Integer> statuses = Concurrently.run(5, () -> cancel(paymentId, POS_CODE, token,
                UUID.randomUUID().toString()).andReturn().getResponse().getStatus());

        assertThat(statuses).filteredOn(code -> code == 200).hasSize(1);
        // 나머지는 이미 취소(409)·처리 중(409)·닫힌 바코드(410) 중 하나다
        assertThat(statuses).filteredOn(code -> code != 200).allMatch(code -> code == 409 || code == 410);
        assertThat(recognized()).isEqualTo(25_000L);
    }

    @Test
    void 앱이_먼저_취소하면_POS_취소는_409_ALREADY_CANCELED다() throws Exception {
        long paymentId = approved(POS_CODE);
        performance(40_000);
        mockMvc.perform(post("/api/payments/{paymentId}/cancel", paymentId)
                        .with(AuthUsers.asUser(user))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk());

        cancel(paymentId, POS_CODE, issued(user), UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_CANCELED"));
        assertThat(recognized()).isEqualTo(25_000L);
    }

    // ───────── 요청 형식 ─────────

    @Test
    void POS_키가_없으면_401이고_Idempotency_Key가_없으면_400이다() throws Exception {
        long paymentId = approved(POS_CODE);
        String token = issued(user);
        String body = objectMapper.writeValueAsString(Map.of("posCode", POS_CODE, "barcodeToken", token));

        mockMvc.perform(post("/api/pos/payment-requests/{paymentId}/cancel", paymentId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_POS_KEY"));
        mockMvc.perform(post("/api/pos/payment-requests/{paymentId}/cancel", paymentId)
                        .header("X-POS-Key", POS_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        assertThat(payments.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(barcodeStatus(token)).isEqualTo(BarcodeStatus.ISSUED);
    }

    @Test
    void 바코드나_posCode가_비면_400이다() throws Exception {
        long paymentId = approved(POS_CODE);

        mockMvc.perform(post("/api/pos/payment-requests/{paymentId}/cancel", paymentId)
                        .header("X-POS-Key", POS_KEY)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"posCode\": \"" + POS_CODE + "\", \"barcodeToken\": \" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mockMvc.perform(post("/api/pos/payment-requests/{paymentId}/cancel", paymentId)
                        .header("X-POS-Key", POS_KEY)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"barcodeToken\": \"1234567890123456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    // ───────── 도우미 ─────────

    private ResultActions cancel(long paymentId, String posCode, String token, String key) throws Exception {
        return mockMvc.perform(post("/api/pos/payment-requests/{paymentId}/cancel", paymentId)
                .header("X-POS-Key", POS_KEY)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("posCode", posCode, "barcodeToken", token))));
    }

    private String issued(long owner) {
        String token = token();
        Instant now = Instant.now();
        assertThat(barcodes.issue(new Barcode(token, owner, BarcodeStatus.ISSUED, now, now.plusSeconds(60)), RETENTION))
                .isTrue();
        return token;
    }

    private String token() {
        return String.format("%016d", 1_000_000_000_000_000L + user * 1_000 + barcodeSeq++);
    }

    private BarcodeStatus barcodeStatus(String token) {
        return barcodes.find(token).orElseThrow().status();
    }

    /** 이 결제가 들어간 11월 실적 합계 행. 승인 원장이 쌓았을 값을 직접 넣는다. */
    private void performance(long recognizedAmount) {
        jdbc.update("INSERT INTO card_monthly_performance (user_card_id, performance_month, recognized_amount, "
                + "created_at, updated_at) VALUES (?, ?, ?, NOW(6), NOW(6))", creditCard, NOVEMBER, recognizedAmount);
    }

    private Long recognized() {
        return jdbc.queryForObject("SELECT recognized_amount FROM card_monthly_performance WHERE user_card_id = ?",
                Long.class, creditCard);
    }

    /**
     * 승인된 신용카드 결제 행. posCode가 있으면 그 POS가 만든 거래 조건 스냅샷을 넣고, null이면 스냅샷 없는 거래다.
     */
    private long approved(String posCode) {
        Long productId = jdbc.queryForObject("SELECT card_product_id FROM user_card WHERE user_card_id = ?", Long.class,
                creditCard);
        UUID a = UUID.randomUUID();
        Map<String, Object> row = new HashMap<>();
        row.put("user_id", user);
        row.put("merchant_id", merchant);
        row.put("request_key", UUID.randomUUID().toString());
        row.put("request_fingerprint", ByteBuffer.allocate(32).putLong(a.getMostSignificantBits())
                .putLong(a.getLeastSignificantBits()).array());
        row.put("source", "YAMU");
        row.put("status", "APPROVED");
        row.put("requested_amount", 15_000L);
        row.put("installment_months", 1);
        row.put("requested_at", now());
        row.put("created_at", now());
        row.put("updated_at", now());
        row.put("user_card_id", creditCard);
        row.put("card_product_id", productId);
        row.put("approved_amount", 15_000L);
        row.put("recognized_performance_amount", 15_000L);
        row.put("performance_month", NOVEMBER);
        row.put("approved_at", now());
        if (posCode != null) {
            row.put("merchant_context_snapshot", new MerchantContextSnapshot(MerchantContextSnapshot.CURRENT_VERSION,
                    posCode, "TEST_CAFE", "테스트 카페", "COFFEE_SHOP", PaymentChannel.OFFLINE, null, "POS-0001", 15_000,
                    PaymentPlan.LUMP_SUM, null).toJson(objectMapper));
        }
        return insert("payment_transaction", "payment_transaction_id", row);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private long insert(String table, String idColumn, Map<String, Object> row) {
        return new SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns(idColumn)
                .usingColumns(row.keySet().toArray(String[]::new))
                .executeAndReturnKey(row).longValue();
    }
}
