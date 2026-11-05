package com.yamu.be.payment.memo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import jakarta.persistence.EntityManager;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 메모(Task 5.3.5)를 인증 필터·실제 MySQL까지 묶어 확인한다. 결제 행은 SQL로 넣는다.
 * 테스트마다 트랜잭션을 되돌리므로 요청과 데이터가 같은 연결·트랜잭션을 쓴다.
 */
@Transactional
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-payment-memo-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentMemoIntegrationTest extends MySqlTestSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentTransactionRepository payments;
    @Autowired EntityManager entityManager;

    long owner;
    long other;
    long card;
    long merchant;

    @BeforeEach
    void setUp() {
        OwnershipFixtures fixtures = new OwnershipFixtures(jdbc);
        owner = fixtures.user();
        other = fixtures.user();
        card = fixtures.card(owner, fixtures.product(), null, "ACTIVE");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        merchant = insert("merchant", "merchant_id", new HashMap<>(Map.of("merchant_name", "테스트 카페",
                "brand_code", "MEMO-" + UUID.randomUUID(), "merchant_category_code", "F01-CAFE",
                "created_at", now, "updated_at", now)));
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mockMvc.perform(patch("/api/payments/1/memo").contentType(MediaType.APPLICATION_JSON).content("{\"memo\":\"a\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 메모를_남기면_앞뒤_공백을_지워_저장하고_시각을_돌려준다() throws Exception {
        long paymentId = payment("APPROVED");

        memo(owner, paymentId, "{\"memo\": \"  팀 회식 1/N 정산 예정  \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.paymentId").value(paymentId))
                .andExpect(jsonPath("$.data.memo").value("팀 회식 1/N 정산 예정"))
                .andExpect(jsonPath("$.data.memoUpdatedAt").isString());

        Map<String, Object> row = row(paymentId);
        assertThat(row.get("memo")).isEqualTo("팀 회식 1/N 정산 예정");
        assertThat(row.get("memo_updated_at")).isNotNull();
    }

    @Test
    void null_빈_문자열_공백뿐이면_메모를_지우고_지운_시각을_남긴다() throws Exception {
        for (String body : new String[] {"{\"memo\": null}", "{\"memo\": \"\"}", "{\"memo\": \"   \"}"}) {
            long paymentId = payment("APPROVED");
            memo(owner, paymentId, "{\"memo\": \"지울 메모\"}").andExpect(status().isOk());

            memo(owner, paymentId, body)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.memo").value(nullValue()))
                    .andExpect(jsonPath("$.data.memoUpdatedAt").isString());

            assertThat(row(paymentId).get("memo")).isNull();
            assertThat(row(paymentId).get("memo_updated_at")).isNotNull();
        }
    }

    @Test
    void 이모지를_포함해_100자는_저장되고_101자는_400_MEMO_TOO_LONG이다() throws Exception {
        long paymentId = payment("APPROVED");
        String hundred = "😀".repeat(50) + "가".repeat(50);

        memo(owner, paymentId, "{\"memo\": \"" + hundred + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memo").value(hundred));

        memo(owner, paymentId, "{\"memo\": \"" + hundred + "나\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MEMO_TOO_LONG"))
                .andExpect(jsonPath("$.error.details.maxLength").value(100));
        assertThat(row(paymentId).get("memo")).isEqualTo(hundred);
    }

    @Test
    void memo_칸이_없거나_문자열이_아니면_400_INVALID_REQUEST이고_바꾸지_않는다() throws Exception {
        long paymentId = payment("APPROVED");

        memo(owner, paymentId, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("memo"));
        memo(owner, paymentId, "{\"memo\": 123}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        memo(owner, paymentId, "[\"memo\"]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(row(paymentId).get("memo_updated_at")).isNull();
    }

    @Test
    void 다른_사용자의_결제와_없는_결제는_404이고_바꾸지_않는다() throws Exception {
        long paymentId = payment("APPROVED");

        memo(other, paymentId, "{\"memo\": \"남의 메모\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        memo(owner, Long.MAX_VALUE, "{\"memo\": \"없는 결제\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        assertThat(row(paymentId).get("memo")).isNull();
    }

    @Test
    void 취소된_결제와_처리_중인_결제에도_메모를_남길_수_있다() throws Exception {
        for (String status : new String[] {"CANCELED", "PROCESSING", "DECLINED", "REQUESTED", "EXPIRED"}) {
            long paymentId = payment(status);
            memo(owner, paymentId, "{\"memo\": \"" + status + " 메모\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.memo").value(status + " 메모"));
        }
    }

    @Test
    void 메모는_결제의_version과_updated_at을_바꾸지_않는다() throws Exception {
        long paymentId = payment("PROCESSING");
        Map<String, Object> before = row(paymentId);

        memo(owner, paymentId, "{\"memo\": \"처리 중 메모\"}").andExpect(status().isOk());

        Map<String, Object> after = row(paymentId);
        assertThat(after.get("version")).isEqualTo(before.get("version"));
        assertThat(after.get("updated_at")).isEqualTo(before.get("updated_at"));
    }

    @Test
    void 메모를_남긴_뒤_먼저_읽어_둔_결제를_취소로_저장해도_메모를_덮어쓰지_않는다() throws Exception {
        long paymentId = payment("APPROVED");
        // 취소 처리가 메모 저장 전에 결제를 읽었다(메모 칸은 아직 null인 엔티티)
        PaymentTransaction loaded = payments.findById(paymentId).orElseThrow();
        assertThat(loaded.getMemo()).isNull();

        memo(owner, paymentId, "{\"memo\": \"취소 전 메모\"}").andExpect(status().isOk());
        loaded.cancel(LocalDateTime.now(ZoneOffset.UTC));
        payments.saveAndFlush(loaded);
        entityManager.clear();

        Map<String, Object> row = row(paymentId);
        assertThat(row.get("status")).isEqualTo("CANCELED");
        assertThat(row.get("memo")).isEqualTo("취소 전 메모");
        assertThat(row.get("memo_updated_at")).isNotNull();
    }

    private ResultActions memo(long userId, long paymentId, String body) throws Exception {
        return mockMvc.perform(patch("/api/payments/{paymentId}/memo", paymentId)
                .with(AuthUsers.asUser(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private Map<String, Object> row(long paymentId) {
        return jdbc.queryForMap("SELECT status, memo, memo_updated_at, version, updated_at FROM payment_transaction "
                + "WHERE payment_transaction_id = ?", paymentId);
    }

    /** 15,000원 일시불 결제 행. 승인·취소 상태면 승인 칸도 채운다(ck_payment_approval). */
    private long payment(String status) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5);
        UUID a = UUID.randomUUID();
        Map<String, Object> row = new HashMap<>();
        row.put("user_id", owner);
        row.put("merchant_id", merchant);
        row.put("request_key", UUID.randomUUID().toString());
        row.put("request_fingerprint", ByteBuffer.allocate(32).putLong(a.getMostSignificantBits())
                .putLong(a.getLeastSignificantBits()).array());
        row.put("source", "YAMU");
        row.put("status", status);
        row.put("requested_amount", 15_000L);
        row.put("installment_months", 1);
        row.put("requested_at", now);
        row.put("created_at", now);
        row.put("updated_at", now);
        if (!"REQUESTED".equals(status) && !"EXPIRED".equals(status)) {
            Long product = jdbc.queryForObject("SELECT card_product_id FROM user_card WHERE user_card_id = ?",
                    Long.class, card);
            row.put("user_card_id", card);
            row.put("card_product_id", product);
        }
        if ("APPROVED".equals(status) || "CANCELED".equals(status)) {
            row.put("approved_amount", 15_000L);
            row.put("recognized_performance_amount", 0L);
            row.put("performance_month", LocalDate.of(2026, 11, 1));
            row.put("approved_at", now.plusSeconds(10));
        }
        if ("CANCELED".equals(status)) {
            row.put("canceled_at", now.plusMinutes(1));
        }
        return insert("payment_transaction", "payment_transaction_id", row);
    }

    private long insert(String table, String idColumn, Map<String, Object> row) {
        return new SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns(idColumn)
                .usingColumns(row.keySet().toArray(String[]::new))
                .executeAndReturnKey(row).longValue();
    }
}
