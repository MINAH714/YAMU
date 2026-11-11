package com.yamu.be.payment.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.yamu.be.payment.approve.ApprovalNumbers;
import com.yamu.be.payment.approve.PaymentApprover;
import com.yamu.be.payment.barcode.Barcode;
import com.yamu.be.payment.barcode.BarcodeStatus;
import com.yamu.be.payment.barcode.BarcodeStore;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.MutableClock;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

/**
 * POS 결제 내역(S15P31E103-423)을 실제 MySQL·Redis로 확인한다. 결제 요청·승인은 실제 API로 만들고 승인 실행 포트만 바꿔 끼운다.
 * 취소·기간은 결제 행을 직접 고쳐 만든다(취소 흐름은 결제 취소 테스트가 본다).
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-pos-payment-history-test")
@Import(PosPaymentHistoryIntegrationTest.ClockConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PosPaymentHistoryIntegrationTest extends MySqlTestSupport {

    static final String POS_KEY = "test-pos-key";
    static final String POS_CODE = "POS-TEST-CAFE";
    static final String OTHER_POS_SAME_BRAND = "POS-TEST-CAFE-DEPT";
    static final Duration RETENTION = Duration.ofMinutes(10);

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

    @MockitoBean PaymentApprover approver;

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired BarcodeStore barcodes;
    @Autowired PaymentTransactionRepository payments;
    @Autowired MutableClock clock;

    final ObjectMapper json = new ObjectMapper();
    OwnershipFixtures fixtures;
    long user;
    long creditCard;
    long cafe;
    int barcodeSeq;

    @BeforeEach
    void setUp() {
        deleteLedgerRows();
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
        creditCard = fixtures.card(user, fixtures.product(), account, "ACTIVE");
        cafe = merchant("테스트 카페", "TEST_CAFE", "COFFEE_SHOP");
        merchant("테스트 상점", "TEST_SHOP", "COFFEE_SHOP");
        when(approver.approve(any())).thenAnswer(
                inv -> PosPaymentResultIntegrationTest.approvedLike(inv.getArgument(0)));
    }

    @AfterEach
    void deleteLedgerRows() {
        jdbc.update("DELETE FROM payment_benefit");
        jdbc.update("DELETE FROM payment_limit_usage");
        jdbc.update("DELETE FROM monthly_limit_usage");
        jdbc.update("DELETE FROM card_monthly_performance");
    }

    @Test
    void 이_POS의_승인_취소_결제만_최신순으로_나오고_취소는_승인된_결제만_가능하다() throws Exception {
        long first = approved(POS_CODE, 10_000, "POS-0001");
        long second = approved(POS_CODE, 20_000, "POS-0002");
        LocalDateTime canceledAt = LocalDateTime.of(2026, 11, 11, 1, 0);
        cancel(first, canceledAt);
        requested(POS_CODE, 30_000, "POS-0003"); // 승인 전
        approved(OTHER_POS_SAME_BRAND, 40_000, "DEPT-0001"); // 같은 브랜드의 다른 POS

        PaymentTransaction approvedRow = payments.findById(second).orElseThrow();
        String body = list(POS_KEY, POS_CODE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].paymentId").value(second))
                .andExpect(jsonPath("$.data.items[0].orderNo").value("POS-0002"))
                .andExpect(jsonPath("$.data.items[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.data.items[0].approvalNo")
                        .value(ApprovalNumbers.of(approvedRow.getApprovedAt(), second)))
                .andExpect(jsonPath("$.data.items[0].canceledAt").value((Object) null))
                .andExpect(jsonPath("$.data.items[0].amount").value(20_000))
                .andExpect(jsonPath("$.data.items[0].chargedAmount").value(20_000))
                .andExpect(jsonPath("$.data.items[0].instantDiscount").value(0))
                .andExpect(jsonPath("$.data.items[0].card.issuerName").value("TEST"))
                .andExpect(jsonPath("$.data.items[0].card.maskedNumber").value("****-****-****-1234"))
                .andExpect(jsonPath("$.data.items[0].cancelable").value(true))
                .andExpect(jsonPath("$.data.items[1].paymentId").value(first))
                .andExpect(jsonPath("$.data.items[1].status").value("CANCELED"))
                .andExpect(jsonPath("$.data.items[1].canceledAt").value("2026-11-11T01:00:00Z"))
                .andExpect(jsonPath("$.data.items[1].approvedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.items[1].cancelable").value(false))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("nickname", "userCardId", "userId");
    }

    @Test
    void 같은_브랜드의_다른_POS는_자기_결제만_본다() throws Exception {
        approved(POS_CODE, 10_000, "POS-0001");
        long dept = approved(OTHER_POS_SAME_BRAND, 40_000, "DEPT-0001");

        list(POS_KEY, OTHER_POS_SAME_BRAND)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].paymentId").value(dept));
        list(POS_KEY, "POS-TEST-SHOP")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void 요청한_지_7일이_지난_결제는_빠지고_7일_정각은_남는다() throws Exception {
        long old = approved(POS_CODE, 10_000, "POS-0001");
        long edge = approved(POS_CODE, 20_000, "POS-0002");
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        jdbc.update("UPDATE payment_transaction SET requested_at = ? WHERE payment_transaction_id = ?",
                now.minusDays(7).minusSeconds(1), old);
        jdbc.update("UPDATE payment_transaction SET requested_at = ? WHERE payment_transaction_id = ?",
                now.minusDays(7), edge);

        list(POS_KEY, POS_CODE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].paymentId").value(edge));
    }

    @Test
    void 만료된_결제는_나오지_않는다() throws Exception {
        long expired = requested(POS_CODE, 10_000, "POS-0001");
        jdbc.update("UPDATE payment_transaction SET status = 'EXPIRED' WHERE payment_transaction_id = ?", expired);

        list(POS_KEY, POS_CODE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void POS_키가_없거나_다르면_401이다() throws Exception {
        list(null, POS_CODE)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_POS_KEY"));
        list("wrong-key", POS_CODE)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_POS_KEY"));
    }

    @Test
    void 설정에_없는_posCode는_404이고_posCode가_없으면_400이다() throws Exception {
        list(POS_KEY, "POS-UNKNOWN")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("MERCHANT_NOT_FOUND"));
        mockMvc.perform(get("/api/pos/payment-requests").header("X-POS-Key", POS_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    // ───────── 도우미 ─────────

    private ResultActions list(String posKey, String posCode) throws Exception {
        var builder = get("/api/pos/payment-requests").param("posCode", posCode);
        if (posKey != null) {
            builder.header("X-POS-Key", posKey);
        }
        return mockMvc.perform(builder);
    }

    private long approved(String posCode, long amount, String orderNo) throws Exception {
        long paymentId = requested(posCode, amount, orderNo);
        mockMvc.perform(post("/api/payments/{paymentId}/approve", paymentId)
                        .with(AuthUsers.asUser(user))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userCardId\": " + creditCard + "}"))
                .andExpect(status().isOk());
        return paymentId;
    }

    private void cancel(long paymentId, LocalDateTime canceledAt) {
        jdbc.update("UPDATE payment_transaction SET status = 'CANCELED', canceled_at = ? "
                + "WHERE payment_transaction_id = ?", canceledAt, paymentId);
    }

    /** POS 결제 요청 API로 REQUESTED 결제를 만든다. 바코드는 저장소에 직접 넣는다. */
    private long requested(String posCode, long amount, String orderNo) throws Exception {
        String token = String.format("%016d", 1_000_000_000_000_000L + user * 1_000 + barcodeSeq++);
        Instant now = clock.instant();
        assertThat(barcodes.issue(new Barcode(token, user, BarcodeStatus.ISSUED, now, now.plusSeconds(60)), RETENTION))
                .isTrue();
        Map<String, Object> body = Map.of("barcodeToken", token, "merchantId", cafe, "posCode", posCode,
                "amount", amount, "orderNo", orderNo);
        MvcResult result = mockMvc.perform(post("/api/pos/payment-requests")
                        .header("X-POS-Key", POS_KEY)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.data.paymentId")).longValue();
    }

    private long merchant(String name, String brandCode, String categoryCode) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return new SimpleJdbcInsert(jdbc).withTableName("merchant").usingGeneratedKeyColumns("merchant_id")
                .usingColumns("merchant_name", "brand_code", "merchant_category_code", "created_at", "updated_at")
                .executeAndReturnKey(Map.of("merchant_name", name, "brand_code", brandCode,
                        "merchant_category_code", categoryCode, "created_at", now, "updated_at", now))
                .longValue();
    }
}
