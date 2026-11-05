package com.yamu.be.payment.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

/**
 * 결제 내역 CSV 내려받기(Task 5.3.6)를 인증 필터·MySQL·Redis(호출 제한)까지 묶어 확인한다. 결제·혜택 행은 SQL로 넣고
 * 테스트마다 트랜잭션을 되돌린다.
 */
@Transactional
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-payment-export-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentExportIntegrationTest extends MySqlTestSupport {

    /** 한국 2026-10-10 12:00 */
    static final LocalDateTime OCT_10 = LocalDateTime.of(2026, 10, 10, 3, 0);

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;

    long owner;
    long other;
    long product;
    long card;
    long otherCard;
    long merchant;

    @BeforeEach
    void setUp() {
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
        OwnershipFixtures fixtures = new OwnershipFixtures(jdbc);
        owner = fixtures.user();
        other = fixtures.user();
        product = fixtures.product();
        card = fixtures.card(owner, product, null, "ACTIVE");
        jdbc.update("UPDATE user_card SET card_alias = '메인 신용카드' WHERE user_card_id = ?", card);
        otherCard = fixtures.card(other, product, null, "ACTIVE");
        merchant = insert("merchant", "merchant_id", timestamps(Map.of("merchant_name", "스타벅스",
                "brand_code", "EXPORT-" + UUID.randomUUID(), "merchant_category_code", "F01-CAFE")));
    }

    @Test
    void 이번_달_결제를_BOM이_붙은_CSV로_한국_시각과_마스킹된_카드번호로_내려준다() throws Exception {
        long approved = payment(owner, card, "APPROVED", OCT_10, 15_000);
        benefit(approved, 1_500, "AT_BILLING");
        jdbc.update("UPDATE payment_transaction SET memo = '팀 회식, \"1/N\"' WHERE payment_transaction_id = ?", approved);
        payment(owner, card, "DECLINED", OCT_10.plusHours(1), 9_000);
        long canceled = payment(owner, card, "CANCELED", OCT_10.plusHours(2), 20_000);
        jdbc.update("UPDATE payment_transaction SET canceled_at = ? WHERE payment_transaction_id = ?",
                OCT_10.plusHours(3), canceled);
        payment(other, otherCard, "APPROVED", OCT_10, 99_000);

        MvcResult result = mockMvc.perform(export().param("yearMonth", "2026-10").param("status", "APPROVED"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("yamu-payments-2026-10.csv")))
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(Arrays.copyOf(body, 3)).containsExactly(0xEF, 0xBB, 0xBF);
        List<String> lines = lines(body);
        assertThat(lines.get(0)).isEqualTo(PaymentExportService.HEADER);
        assertThat(lines).hasSize(2);
        // 승인 2초 뒤 = 한국 12:00, 청구예정액 = 승인액 − 청구할인
        assertThat(lines.get(1)).isEqualTo("2026-10-10 12:00,스타벅스 역삼점,F01-CAFE,메인 신용카드,****-****-****-1234,"
                + "야무 통합 바코드,일시불,15000,1500,13500,승인,인정,,\"팀 회식, \"\"1/N\"\"\"");
    }

    @Test
    void 기본_상태는_승인과_취소이고_취소_건의_청구예정액은_빈_칸이다() throws Exception {
        payment(owner, card, "APPROVED", OCT_10, 15_000);
        long canceled = payment(owner, card, "CANCELED", OCT_10.plusHours(2), 20_000);
        jdbc.update("UPDATE payment_transaction SET canceled_at = ? WHERE payment_transaction_id = ?",
                OCT_10.plusHours(3), canceled);

        List<String> lines = lines(mockMvc.perform(export().param("yearMonth", "2026-10"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());

        assertThat(lines).hasSize(3);
        assertThat(lines.get(1)).contains(",20000,0,,취소,");
    }

    @Test
    void 목록과_같은_검색_조건을_쓴다() throws Exception {
        payment(owner, card, "APPROVED", OCT_10, 15_000);
        long memoHit = payment(owner, card, "APPROVED", OCT_10.plusHours(1), 12_000);
        jdbc.update("UPDATE payment_transaction SET memo = '회식' WHERE payment_transaction_id = ?", memoHit);

        List<String> lines = lines(mockMvc.perform(export().param("yearMonth", "2026-10").param("q", "회식"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());

        assertThat(lines).hasSize(2);
        assertThat(lines.get(1)).endsWith(",회식");
    }

    @Test
    void 수식으로_시작하는_메모는_앞에_작은따옴표를_붙인다() throws Exception {
        long id = payment(owner, card, "APPROVED", OCT_10, 15_000);
        jdbc.update("UPDATE payment_transaction SET memo = '=HYPERLINK(\"x\")' WHERE payment_transaction_id = ?", id);

        List<String> lines = lines(mockMvc.perform(export().param("yearMonth", "2026-10"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());

        assertThat(lines.get(1)).endsWith(",\"'=HYPERLINK(\"\"x\"\")\"");
    }

    @Test
    void 기간이_12개월을_넘거나_한쪽만_주면_400_RANGE_TOO_LARGE다() throws Exception {
        mockMvc.perform(export().param("from", "2026-01-01").param("to", "2026-12-31"))
                .andExpect(status().isOk());
        mockMvc.perform(export().param("from", "2026-01-01").param("to", "2027-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("RANGE_TOO_LARGE"))
                .andExpect(jsonPath("$.error.details.maxMonths").value(12));
        mockMvc.perform(export().param("from", "2026-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("RANGE_TOO_LARGE"));
    }

    @Test
    void 분당_3번을_넘으면_429_RATE_LIMITED다() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(export().param("yearMonth", "2026-10")).andExpect(status().isOk());
        }
        mockMvc.perform(export().param("yearMonth", "2026-10"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.error.details.retryAfterSeconds").isNumber());
        // 다른 사용자는 따로 센다
        mockMvc.perform(get("/api/payments/export").with(AuthUsers.asUser(other)).param("yearMonth", "2026-10"))
                .andExpect(status().isOk());
    }

    @Test
    void 잘못된_조건은_목록과_같은_400이다() throws Exception {
        mockMvc.perform(export().param("spendingClass", "FOOD"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mockMvc.perform(get("/api/payments/export")).andExpect(status().isUnauthorized());
    }

    private MockHttpServletRequestBuilder export() {
        return get("/api/payments/export").with(AuthUsers.asUser(owner));
    }

    private static List<String> lines(byte[] body) {
        String text = new String(body, 3, body.length - 3, StandardCharsets.UTF_8);
        return Arrays.stream(text.split("\r\n")).toList();
    }

    private long payment(long user, long userCard, String status, LocalDateTime requestedAt, long amount) {
        Map<String, Object> row = new HashMap<>(timestamps(Map.of("user_id", user, "merchant_id", merchant,
                "payment_channel", "OFFLINE", "request_key", UUID.randomUUID().toString(),
                "request_fingerprint", randomHash(), "source", "YAMU", "status", status,
                "requested_amount", amount, "installment_months", 1, "requested_at", requestedAt)));
        row.put("merchant_context_snapshot", """
                {"version":1,"merchantName":"스타벅스 역삼점","categoryCode":"F01-CAFE","channel":"OFFLINE","amount":%d}
                """.formatted(amount));
        row.put("user_card_id", userCard);
        row.put("card_product_id", product);
        if (status.equals("APPROVED") || status.equals("CANCELED")) {
            row.put("approved_at", requestedAt.plusSeconds(2));
            row.put("approved_amount", amount);
            row.put("recognized_performance_amount", amount);
            row.put("performance_month", LocalDate.of(requestedAt.getYear(), requestedAt.getMonth(), 1));
        }
        return insert("payment_transaction", "payment_transaction_id", row);
    }

    private void benefit(long paymentId, long krw, String timing) {
        long cardBenefit = insert("card_benefit", "card_benefit_id", Map.of("card_product_id", product,
                "benefit_code", "CAFE-" + UUID.randomUUID(), "policy_version", 1, "benefit_name", "카페 청구할인",
                "benefit_type", "DISCOUNT", "calculation_type", "FIXED", "fixed_value", krw, "value_unit", "KRW",
                "application_timing", timing, "created_at", now()));
        insert("payment_benefit", "payment_benefit_id", Map.of("payment_transaction_id", paymentId,
                "card_product_id", product, "card_benefit_id", cardBenefit, "benefit_value", krw, "value_unit", "KRW",
                "krw_amount", krw, "created_at", now()));
    }

    private Map<String, Object> timestamps(Map<String, Object> fields) {
        Map<String, Object> row = new HashMap<>(fields);
        row.put("created_at", now());
        row.put("updated_at", now());
        return row;
    }

    private long insert(String table, String idColumn, Map<String, Object> row) {
        return new SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns(idColumn)
                .usingColumns(row.keySet().toArray(String[]::new))
                .executeAndReturnKey(row).longValue();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static byte[] randomHash() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        return ByteBuffer.allocate(32).putLong(a.getMostSignificantBits()).putLong(a.getLeastSignificantBits())
                .putLong(b.getMostSignificantBits()).putLong(b.getLeastSignificantBits()).array();
    }
}
