package com.yamu.be.payment.reject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.yamu.be.payment.approve.ApprovalCommand;
import com.yamu.be.payment.approve.ApprovalOutcome;
import com.yamu.be.payment.approve.FailureCode;
import com.yamu.be.payment.approve.PaymentApprover;
import com.yamu.be.payment.barcode.Barcode;
import com.yamu.be.payment.barcode.BarcodeStatus;
import com.yamu.be.payment.barcode.BarcodeStore;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.Concurrently;
import com.yamu.be.support.MutableClock;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
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
 * 앱의 결제 요청 거절(S15P31E103-360)을 실제 MySQL·Redis로 확인한다. 결제 요청은 실제 POS API로 만들고, 승인 실행 포트만
 * 테스트가 바꿔 끼운다. 시간은 {@link MutableClock}으로 옮겨 마감 경계를 본다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-payment-reject-test")
@Import(PaymentRejectIntegrationTest.ClockConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentRejectIntegrationTest extends MySqlTestSupport {

    static final String POS_KEY = "test-pos-key";
    static final String POS_CODE = "POS-TEST-CAFE";
    static final Duration RETENTION = Duration.ofMinutes(10);
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final String USER_REJECTED = "{\"reason\":\"USER_REJECTED\"}";

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
    long otherUser;
    long creditProduct;
    long creditCard;
    long cafe;

    @BeforeEach
    void setUp() {
        deleteLedgerRows();
        jdbc.update("DELETE FROM payment_transaction");
        jdbc.update("DELETE FROM user_card");
        jdbc.update("DELETE FROM linked_account");
        jdbc.update("DELETE FROM card_product");
        jdbc.update("DELETE FROM merchant");
        // 승인·취소·청구 사건이 쌓은 알림(Task 9.3.2)이 사용자를 참조한다
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM app_user");
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
        clock.reset();
        fixtures = new OwnershipFixtures(jdbc);
        user = fixtures.user();
        otherUser = fixtures.user();
        creditProduct = fixtures.product();
        long account = fixtures.account(user);
        creditCard = fixtures.card(user, creditProduct, account, "ACTIVE");
        jdbc.update("UPDATE user_card SET is_default = TRUE WHERE user_card_id = ?", creditCard);
        cafe = merchant();
        when(approver.approve(any())).thenAnswer(inv -> approvedLike(inv.getArgument(0)));
    }

    /** 승인 원장이 남긴 행은 카드를 지울 수 없게 하므로 시작·끝에 지운다. */
    @AfterEach
    void deleteLedgerRows() {
        jdbc.update("DELETE FROM payment_benefit");
        jdbc.update("DELETE FROM payment_limit_usage");
        jdbc.update("DELETE FROM monthly_limit_usage");
        jdbc.update("DELETE FROM card_monthly_performance");
    }

    static ApprovalOutcome.Approved approvedLike(ApprovalCommand command) {
        long amount = command.terms().amount();
        LocalDate month = command.now().atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).toLocalDate()
                .withDayOfMonth(1);
        return new ApprovalOutcome.Approved(amount, amount, 0, month, List.of(), null, null, null);
    }

    // ───────── 거절 성공 ─────────

    @Test
    void 승인_전_결제를_거절하면_REJECTED이고_POS는_바로_REJECTED를_받고_바코드는_닫힌다() throws Exception {
        Requested requested = requested();

        reject(requested.paymentId(), UUID.randomUUID().toString(), USER_REJECTED)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentId").value(requested.paymentId()))
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REJECTED);
        posResult(requested.paymentId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.approvalNo").value((Object) null))
                .andExpect(jsonPath("$.data.retryable").value((Object) null));
        assertThat(barcodes.find(requested.token()).orElseThrow().status()).isEqualTo(BarcodeStatus.EXPIRED);
    }

    @Test
    void 본문_없이도_거절된다() throws Exception {
        Requested requested = requested();

        reject(requested.paymentId(), UUID.randomUUID().toString(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));
    }

    @Test
    void 카드_거절_뒤_마감_전이면_결제_요청을_거절할_수_있고_그_뒤_재승인은_409다() throws Exception {
        Requested requested = requested();
        doReturn(new ApprovalOutcome.Declined(FailureCode.INSUFFICIENT_BALANCE, "잔액이 부족해요.", Map.of()))
                .when(approver).approve(any());
        approve(requested.paymentId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DECLINED"));

        reject(requested.paymentId(), UUID.randomUUID().toString(), USER_REJECTED)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        // 앞 시도의 카드·거절 사유는 남는다(POS 영수증이 마지막 거절 사유를 보인다)
        assertThat(payments.findById(requested.paymentId()).orElseThrow().getFailureCode())
                .isEqualTo("INSUFFICIENT_BALANCE");
        approve(requested.paymentId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_ALREADY_PROCESSED"))
                .andExpect(jsonPath("$.error.details.status").value("REJECTED"));
    }

    @Test
    void 거절한_결제는_결제_내역_기본_목록에_없고_상세에는_REJECTED로_보인다() throws Exception {
        Requested requested = requested();
        reject(requested.paymentId(), UUID.randomUUID().toString(), USER_REJECTED).andExpect(status().isOk());

        // 테스트 시계(MutableClock)는 시간대를 바꿀 수 없어 기본 기간(이번 달) 대신 월을 넘긴다
        String thisMonth = java.time.YearMonth.from(clock.instant().atZone(KST)).toString();
        mockMvc.perform(get("/api/payments").param("yearMonth", thisMonth).with(AuthUsers.asUser(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
        mockMvc.perform(get("/api/payments/{paymentId}", requested.paymentId()).with(AuthUsers.asUser(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.statusHistory[0].status").value("REQUESTED"))
                .andExpect(jsonPath("$.data.statusHistory[1].status").value("REJECTED"));
    }

    // ───────── 재시도·동시 요청 ─────────

    @Test
    void 같은_키나_다른_키로_다시_보내도_같은_200이고_마감_뒤에_와도_그렇다() throws Exception {
        Requested requested = requested();
        String key = UUID.randomUUID().toString();
        String first = reject(requested.paymentId(), key, USER_REJECTED)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        String sameKey = reject(requested.paymentId(), key, USER_REJECTED)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String otherKey = reject(requested.paymentId(), UUID.randomUUID().toString(), USER_REJECTED)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        clock.advance(Duration.ofSeconds(120));
        String afterDeadline = reject(requested.paymentId(), key, USER_REJECTED)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(List.of(sameKey, otherKey, afterDeadline)).containsOnly(first);
        assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REJECTED);
    }

    @Test
    void 거절을_연타해도_모두_200이고_한_번만_바뀐다() throws Exception {
        Requested requested = requested();

        List<Integer> statuses = Concurrently.run(5, () -> reject(requested.paymentId(), UUID.randomUUID().toString(),
                USER_REJECTED).andReturn().getResponse().getStatus());

        assertThat(statuses).containsOnly(200);
        assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REJECTED);
    }

    @Test
    void 승인과_거절이_동시에_오면_하나만_성공하고_결제_상태가_그쪽과_같다() throws Exception {
        for (int round = 0; round < 5; round++) {
            Requested requested = requested();
            List<Callable<String>> tasks = List.of(
                    () -> "approve:" + approve(requested.paymentId()).andReturn().getResponse().getStatus(),
                    () -> "reject:" + reject(requested.paymentId(), UUID.randomUUID().toString(), USER_REJECTED)
                            .andReturn().getResponse().getStatus());

            List<String> results = Concurrently.run(tasks);

            PaymentStatus finalStatus = payments.findById(requested.paymentId()).orElseThrow().getStatus();
            if (finalStatus == PaymentStatus.REJECTED) {
                assertThat(results).containsExactlyInAnyOrder("reject:200", "approve:409");
            } else {
                assertThat(finalStatus).isEqualTo(PaymentStatus.APPROVED);
                assertThat(results).containsExactlyInAnyOrder("approve:200", "reject:409");
            }
        }
    }

    // ───────── 거절할 수 없는 경우 ─────────

    @Test
    void 마감이_지났으면_EXPIRED로_저장하고_410이며_바코드도_닫힌다() throws Exception {
        Requested requested = requested();
        clock.advance(Duration.ofSeconds(60));

        reject(requested.paymentId(), UUID.randomUUID().toString(), USER_REJECTED)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_EXPIRED"));

        assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(barcodes.find(requested.token()).orElseThrow().status()).isEqualTo(BarcodeStatus.EXPIRED);
    }

    @Test
    void 승인된_결제는_409_PAYMENT_ALREADY_PROCESSED다() throws Exception {
        Requested requested = requested();
        approve(requested.paymentId()).andExpect(status().isOk());

        reject(requested.paymentId(), UUID.randomUUID().toString(), USER_REJECTED)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_ALREADY_PROCESSED"))
                .andExpect(jsonPath("$.error.details.status").value("APPROVED"));
        assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void 승인_처리_중이면_409_처리_중이다() throws Exception {
        Requested requested = requested();
        jdbc.update("UPDATE payment_transaction SET status = 'PROCESSING', user_card_id = ?, card_product_id = ? "
                + "WHERE payment_transaction_id = ?", creditCard, creditProduct, requested.paymentId());

        reject(requested.paymentId(), UUID.randomUUID().toString(), USER_REJECTED)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_IN_PROGRESS"));
        assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PROCESSING);
    }

    @Test
    void 출금_미확인으로_거절된_결제는_409_PAYMENT_NOT_REJECTABLE이고_DECLINED로_남는다() throws Exception {
        Requested requested = requested();
        jdbc.update("UPDATE payment_transaction SET status = 'DECLINED', failure_code = 'WITHDRAWAL_NOT_CONFIRMED', "
                + "user_card_id = ?, card_product_id = ? WHERE payment_transaction_id = ?",
                creditCard, creditProduct, requested.paymentId());

        reject(requested.paymentId(), UUID.randomUUID().toString(), USER_REJECTED)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_NOT_REJECTABLE"))
                .andExpect(jsonPath("$.error.details.reason").value("WITHDRAWAL_NOT_CONFIRMED"));
        assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.DECLINED);
    }

    @Test
    void 다른_사용자의_결제와_없는_결제는_404다() throws Exception {
        Requested requested = requested();

        mockMvc.perform(post("/api/payments/{paymentId}/reject", requested.paymentId())
                        .with(AuthUsers.asUser(otherUser))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(USER_REJECTED))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        reject(999_999L, UUID.randomUUID().toString(), USER_REJECTED)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REQUESTED);
    }

    @Test
    void 멱등성_키가_없거나_사유가_약속한_값이_아니면_400이다() throws Exception {
        Requested requested = requested();

        reject(requested.paymentId(), null, USER_REJECTED)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        reject(requested.paymentId(), UUID.randomUUID().toString(), "{\"reason\":\"그냥\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("reason"));
        assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REQUESTED);
    }

    // ───────── 도우미 ─────────

    private ResultActions reject(long paymentId, String key, String body) throws Exception {
        var builder = post("/api/payments/{paymentId}/reject", paymentId).with(AuthUsers.asUser(user));
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(builder);
    }

    private ResultActions approve(long paymentId) throws Exception {
        return mockMvc.perform(post("/api/payments/{paymentId}/approve", paymentId)
                .with(AuthUsers.asUser(user))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userCardId\": " + creditCard + "}"));
    }

    private ResultActions posResult(long paymentId) throws Exception {
        return mockMvc.perform(get("/api/pos/payment-requests/{paymentId}", paymentId)
                .param("posCode", POS_CODE).header("X-POS-Key", POS_KEY));
    }

    /** POS 결제 요청 API로 REQUESTED 결제를 만든다. 바코드는 저장소에 직접 넣는다. */
    private Requested requested() throws Exception {
        String token = issued();
        Map<String, Object> body = new HashMap<>();
        body.put("barcodeToken", token);
        body.put("merchantId", cafe);
        body.put("posCode", POS_CODE);
        body.put("amount", 15_000);
        body.put("orderNo", "POS-0001");
        MvcResult result = mockMvc.perform(post("/api/pos/payment-requests")
                        .header("X-POS-Key", POS_KEY)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        long paymentId = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.data.paymentId"))
                .longValue();
        return new Requested(paymentId, token);
    }

    private String issued() {
        String token = String.format("%016d", 1_000_000_000_000_000L + user * 7 + payments.count());
        Instant now = clock.instant();
        assertThat(barcodes.issue(new Barcode(token, user, BarcodeStatus.ISSUED, now, now.plusSeconds(60)), RETENTION))
                .isTrue();
        return token;
    }

    private long merchant() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return new org.springframework.jdbc.core.simple.SimpleJdbcInsert(jdbc).withTableName("merchant")
                .usingGeneratedKeyColumns("merchant_id")
                .usingColumns("merchant_name", "brand_code", "merchant_category_code", "created_at", "updated_at")
                .executeAndReturnKey(Map.of("merchant_name", "테스트 카페", "brand_code", "TEST_CAFE",
                        "merchant_category_code", "COFFEE_SHOP", "created_at", now, "updated_at", now))
                .longValue();
    }

    private record Requested(long paymentId, String token) {}
}
