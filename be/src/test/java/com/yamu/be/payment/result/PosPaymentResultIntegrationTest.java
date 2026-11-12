package com.yamu.be.payment.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.approve.ApprovalCommand;
import com.yamu.be.payment.approve.ApprovalNumbers;
import com.yamu.be.payment.approve.ApprovalOutcome;
import com.yamu.be.payment.approve.FailureCode;
import com.yamu.be.payment.approve.PaymentApprover;
import com.yamu.be.payment.barcode.Barcode;
import com.yamu.be.payment.barcode.BarcodeStatus;
import com.yamu.be.payment.barcode.BarcodeStore;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.PaymentTransaction;
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
import java.time.format.DateTimeFormatter;
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
 * POS 결제 결과 조회를 실제 MySQL·Redis로 확인한다. 결제 요청·승인은 실제 API로 만들고, 승인 실행 포트만 테스트가 바꿔 끼운다.
 * 시간은 {@link MutableClock}으로 옮겨 마감 경계를 본다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-pos-payment-result-test")
@Import(PosPaymentResultIntegrationTest.ClockConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PosPaymentResultIntegrationTest extends MySqlTestSupport {

    static final String POS_KEY = "test-pos-key";
    static final String POS_CODE = "POS-TEST-CAFE";
    static final Duration RETENTION = Duration.ofMinutes(10);
    static final ZoneId KST = ZoneId.of("Asia/Seoul");

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
    long checkCard;
    long thirdCard;
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
        long creditProduct = product("CREDIT");   // 카드사 SHINHAN: 응답이 이름(신한카드)으로 나오는지 본다
        long checkProduct = product("CHECK");
        long account = fixtures.account(user);
        creditCard = fixtures.card(user, creditProduct, account, "ACTIVE");
        jdbc.update("UPDATE user_card SET is_default = TRUE WHERE user_card_id = ?", creditCard);
        checkCard = fixtures.card(user, checkProduct, account, "ACTIVE");
        thirdCard = fixtures.card(user, creditProduct, account, "ACTIVE");
        cafe = merchant("테스트 카페", "TEST_CAFE", "COFFEE_SHOP");
        when(approver.approve(any())).thenAnswer(inv -> approvedLike(inv.getArgument(0)));
    }

    /**
     * 혜택 0원 승인과 같은 모양: 승인액 = 원금, 실적 0, 청구 계획 없음. 결과 조회는 회차를 보지 않으므로 신용카드도 회차 없이 승인한다.
     * (Task 5.2.4에서 없어진 임시 신용 승인과 같은 값)
     */
    static ApprovalOutcome.Approved approvedLike(ApprovalCommand command) {
        long amount = command.terms().amount();
        LocalDate month = command.now().atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).toLocalDate()
                .withDayOfMonth(1);
        return new ApprovalOutcome.Approved(amount, amount, 0, month, List.of(), null, null, null);
    }

    // ───────── 상태별 응답 ─────────

    @Test
    void 승인_전에는_REQUESTED이고_결과_칸은_비어_있다() throws Exception {
        Requested requested = requested(15_000, null, null);

        result(POS_KEY, requested.paymentId(), POS_CODE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentId").value(requested.paymentId()))
                .andExpect(jsonPath("$.data.orderNo").value("POS-0001"))
                .andExpect(jsonPath("$.data.status").value("REQUESTED"))
                .andExpect(jsonPath("$.data.approvalNo").value((Object) null))
                .andExpect(jsonPath("$.data.approvedAt").value((Object) null))
                .andExpect(jsonPath("$.data.amount").value(15_000))
                .andExpect(jsonPath("$.data.chargedAmount").value((Object) null))
                .andExpect(jsonPath("$.data.instantDiscount").value((Object) null))
                .andExpect(jsonPath("$.data.card").value((Object) null))
                .andExpect(jsonPath("$.data.failure").value((Object) null))
                .andExpect(jsonPath("$.data.retryable").value((Object) null))
                .andExpect(jsonPath("$.data.lateConfirmPending").value((Object) null));
    }

    @Test
    void 승인되면_승인번호_금액_카드가_나오고_개인정보는_없다() throws Exception {
        Requested requested = requested(15_000, null, null);
        approve(requested.paymentId(), creditCard).andExpect(status().isOk());
        PaymentTransaction approved = payments.findById(requested.paymentId()).orElseThrow();

        String body = result(POS_KEY, requested.paymentId(), POS_CODE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.approvalNo")
                        .value(ApprovalNumbers.of(approved.getApprovedAt(), requested.paymentId())))
                .andExpect(jsonPath("$.data.amount").value(15_000))
                .andExpect(jsonPath("$.data.chargedAmount").value(15_000))
                .andExpect(jsonPath("$.data.instantDiscount").value(0))
                .andExpect(jsonPath("$.data.card.issuerName").value("신한카드"))
                .andExpect(jsonPath("$.data.card.cardType").value("CREDIT"))
                .andExpect(jsonPath("$.data.card.maskedNumber").value("****-****-****-1234"))
                .andExpect(jsonPath("$.data.card.plan").value("LUMP_SUM"))
                .andExpect(jsonPath("$.data.card.installmentMonths").value((Object) null))
                .andExpect(jsonPath("$.data.failure").value((Object) null))
                .andExpect(jsonPath("$.data.retryable").value((Object) null))
                .andReturn().getResponse().getContentAsString();

        String approvedAt = JsonPath.read(body, "$.data.approvedAt");
        assertThat(approvedAt).endsWith("Z");
        assertThat(body).doesNotContain("nickname", "userCardId", "userId");
    }

    @Test
    void 할부_승인은_카드에_결제_방식과_개월_수가_나온다() throws Exception {
        Requested requested = requested(100_000, "INSTALLMENT", 3);
        approve(requested.paymentId(), creditCard).andExpect(status().isOk());

        result(POS_KEY, requested.paymentId(), POS_CODE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.card.plan").value("INSTALLMENT"))
                .andExpect(jsonPath("$.data.card.installmentMonths").value(3));
    }

    @Test
    void 즉시할인이_있으면_영수증_금액은_승인액이고_할인은_요청액과의_차이다() throws Exception {
        Requested requested = requested(15_000, null, null);
        doReturn(new ApprovalOutcome.Approved(13_000, 13_000, 13_000, LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1),
                List.of(), null, null, null)).when(approver).approve(any());
        approve(requested.paymentId(), checkCard).andExpect(status().isOk());

        result(POS_KEY, requested.paymentId(), POS_CODE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amount").value(15_000))
                .andExpect(jsonPath("$.data.chargedAmount").value(13_000))
                .andExpect(jsonPath("$.data.instantDiscount").value(2_000))
                .andExpect(jsonPath("$.data.card.cardType").value("CHECK"));
    }

    @Test
    void 마감_전_거절은_retryable_true이고_사유는_기본_문구만_나온다() throws Exception {
        Requested requested = requested(15_000, null, null);
        doReturn(new ApprovalOutcome.Declined(FailureCode.INSUFFICIENT_BALANCE, "잔액 8,000원으로는 결제할 수 없어요.",
                Map.of("availableBalance", 8_000))).when(approver).approve(any());
        approve(requested.paymentId(), checkCard).andExpect(status().isOk());

        result(POS_KEY, requested.paymentId(), POS_CODE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DECLINED"))
                .andExpect(jsonPath("$.data.retryable").value(true))
                .andExpect(jsonPath("$.data.lateConfirmPending").value(false))
                .andExpect(jsonPath("$.data.failure.code").value("INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$.data.failure.message").value("잔액 부족으로 결제가 거절됐습니다."))
                .andExpect(jsonPath("$.data.failure.details").doesNotExist())
                .andExpect(jsonPath("$.data.card.cardType").value("CHECK"))
                .andExpect(jsonPath("$.data.approvalNo").value((Object) null))
                .andExpect(jsonPath("$.data.chargedAmount").value((Object) null));
    }

    @Test
    void 세_번째_거절이면_마감_전이어도_retryable_false다() throws Exception {
        Requested requested = requested(15_000, null, null);
        doReturn(ApprovalOutcome.Declined.of(FailureCode.ISSUER_DECLINED)).when(approver).approve(any());

        approve(requested.paymentId(), creditCard).andExpect(status().isOk());
        approve(requested.paymentId(), checkCard).andExpect(status().isOk());
        result(POS_KEY, requested.paymentId(), POS_CODE)
                .andExpect(jsonPath("$.data.retryable").value(true));

        approve(requested.paymentId(), thirdCard).andExpect(status().isOk());
        result(POS_KEY, requested.paymentId(), POS_CODE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DECLINED"))
                .andExpect(jsonPath("$.data.retryable").value(false));
    }

    @Test
    void 승인_처리_중이면_PROCESSING이고_카드를_보이지_않으며_마감이_지나도_바꾸지_않는다() throws Exception {
        Requested requested = requested(15_000, null, null);
        doThrow(new BusinessException(ErrorCode.FIN_API_UNAVAILABLE)).when(approver).approve(any());
        approve(requested.paymentId(), checkCard).andExpect(status().isBadGateway());

        clock.advance(Duration.ofSeconds(120));
        result(POS_KEY, requested.paymentId(), POS_CODE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.card").value((Object) null))
                .andExpect(jsonPath("$.data.retryable").value((Object) null));
        assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PROCESSING);
    }

    // ───────── 마감 경계 ─────────

    @Test
    void 마감_1초_전에는_REQUESTED이고_아무것도_바꾸지_않는다() throws Exception {
        Requested requested = requested(15_000, null, null);
        long version = payments.findById(requested.paymentId()).orElseThrow().getVersion();
        clock.advance(Duration.ofSeconds(59));

        result(POS_KEY, requested.paymentId(), POS_CODE)
                .andExpect(jsonPath("$.data.status").value("REQUESTED"));

        PaymentTransaction payment = payments.findById(requested.paymentId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
        assertThat(payment.getVersion()).isEqualTo(version);
        assertThat(barcodes.find(requested.token()).orElseThrow().status()).isEqualTo(BarcodeStatus.SCANNED);
    }

    @Test
    void 마감_정각에_조회하면_EXPIRED로_저장되고_바코드도_EXPIRED가_된다() throws Exception {
        Requested requested = requested(15_000, null, null);
        clock.advance(Duration.ofSeconds(60));

        result(POS_KEY, requested.paymentId(), POS_CODE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXPIRED"))
                .andExpect(jsonPath("$.data.failure").value((Object) null))
                .andExpect(jsonPath("$.data.card").value((Object) null))
                .andExpect(jsonPath("$.data.retryable").value((Object) null));

        assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(barcodes.find(requested.token()).orElseThrow().status()).isEqualTo(BarcodeStatus.EXPIRED);
    }

    @Test
    void 거절_뒤_마감이_지나면_EXPIRED이고_마지막_거절_사유와_카드를_같이_보여_준다() throws Exception {
        Requested requested = requested(15_000, null, null);
        doReturn(ApprovalOutcome.Declined.of(FailureCode.CREDIT_LIMIT_EXCEEDED)).when(approver).approve(any());
        approve(requested.paymentId(), creditCard).andExpect(status().isOk());
        clock.advance(Duration.ofSeconds(60));

        result(POS_KEY, requested.paymentId(), POS_CODE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXPIRED"))
                .andExpect(jsonPath("$.data.failure.code").value("CREDIT_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.data.card.cardType").value("CREDIT"))
                .andExpect(jsonPath("$.data.retryable").value((Object) null));
        assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.EXPIRED);
    }

    @Test
    void 출금_미확인_거절은_만료된_뒤에도_대조_기간과_잠금_시간_동안_lateConfirmPending_true다() throws Exception {
        Requested requested = requested(15_000, null, null);
        doReturn(ApprovalOutcome.Declined.of(FailureCode.WITHDRAWAL_NOT_CONFIRMED)).when(approver).approve(any());
        approve(requested.paymentId(), checkCard).andExpect(status().isOk());
        // 승인 실행은 목이라 요청 번호를 남기지 않는다. 실제 체크카드 출금처럼 지금 시각(한국)으로 채번한 번호를 넣는다
        String finRequestNo = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                .format(clock.instant().atZone(KST)) + "000001";
        jdbc.update("UPDATE payment_transaction SET fin_request_no = ?, fin_request_amount = ? "
                + "WHERE payment_transaction_id = ?", finRequestNo, 15_000, requested.paymentId());

        result(POS_KEY, requested.paymentId(), POS_CODE)
                .andExpect(jsonPath("$.data.status").value("DECLINED"))
                .andExpect(jsonPath("$.data.lateConfirmPending").value(true));

        clock.advance(Duration.ofMinutes(10));
        result(POS_KEY, requested.paymentId(), POS_CODE)
                .andExpect(jsonPath("$.data.status").value("EXPIRED"))
                .andExpect(jsonPath("$.data.failure.code").value("WITHDRAWAL_NOT_CONFIRMED"))
                .andExpect(jsonPath("$.data.lateConfirmPending").value(true));

        clock.advance(Duration.ofSeconds(61));
        result(POS_KEY, requested.paymentId(), POS_CODE)
                .andExpect(jsonPath("$.data.status").value("EXPIRED"))
                .andExpect(jsonPath("$.data.lateConfirmPending").value(false));
    }

    @Test
    void 승인된_결제는_마감이_지나도_APPROVED_그대로다() throws Exception {
        Requested requested = requested(15_000, null, null);
        approve(requested.paymentId(), creditCard).andExpect(status().isOk());
        clock.advance(Duration.ofSeconds(120));

        result(POS_KEY, requested.paymentId(), POS_CODE)
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    // ───────── 동시 요청 ─────────

    @Test
    void 마감이_지난_결제를_5건_동시에_조회해도_모두_EXPIRED이고_한_번만_저장된다() throws Exception {
        Requested requested = requested(15_000, null, null);
        long version = payments.findById(requested.paymentId()).orElseThrow().getVersion();
        clock.advance(Duration.ofSeconds(61));

        List<String> statuses = Concurrently.run(5, () -> JsonPath.read(
                result(POS_KEY, requested.paymentId(), POS_CODE).andReturn().getResponse().getContentAsString(),
                "$.data.status"));

        assertThat(statuses).containsOnly("EXPIRED");
        PaymentTransaction payment = payments.findById(requested.paymentId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(payment.getVersion()).isEqualTo(version + 1);
    }

    @Test
    void 마감_뒤_앱_승인과_POS_조회가_동시에_와도_한쪽만_만료를_저장하고_둘_다_만료로_답한다() throws Exception {
        Requested requested = requested(15_000, null, null);
        long version = payments.findById(requested.paymentId()).orElseThrow().getVersion();
        clock.advance(Duration.ofSeconds(60));

        List<Callable<String>> tasks = List.of(
                () -> JsonPath.read(result(POS_KEY, requested.paymentId(), POS_CODE).andReturn().getResponse()
                        .getContentAsString(), "$.data.status"),
                () -> JsonPath.read(approve(requested.paymentId(), creditCard).andReturn().getResponse()
                        .getContentAsString(), "$.error.code"));
        List<String> results = Concurrently.run(tasks);

        assertThat(results).containsExactly("EXPIRED", "PAYMENT_EXPIRED");
        PaymentTransaction payment = payments.findById(requested.paymentId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(payment.getVersion()).isEqualTo(version + 1);
        assertThat(payment.getApprovedAt()).isNull();
    }

    // ───────── 인증·다른 POS·없는 결제 ─────────

    @Test
    void POS_키가_없거나_다르면_401이고_결제를_만료시키지_않는다() throws Exception {
        Requested requested = requested(15_000, null, null);
        clock.advance(Duration.ofSeconds(61));

        result(null, requested.paymentId(), POS_CODE)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_POS_KEY"));
        result("wrong-key", requested.paymentId(), POS_CODE)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_POS_KEY"));
        assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REQUESTED);
    }

    @Test
    void 다른_POS가_만든_결제는_같은_브랜드여도_404이고_만료시키지_않는다() throws Exception {
        Requested requested = requested(15_000, null, null);
        clock.advance(Duration.ofSeconds(61));

        result(POS_KEY, requested.paymentId(), "POS-TEST-CAFE-DEPT")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        result(POS_KEY, requested.paymentId(), "POS-UNKNOWN")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REQUESTED);
    }

    @Test
    void 없는_결제는_404다() throws Exception {
        result(POS_KEY, 999_999L, POS_CODE)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void posCode를_보내지_않으면_400이다() throws Exception {
        Requested requested = requested(15_000, null, null);

        mockMvc.perform(get("/api/pos/payment-requests/{paymentId}", requested.paymentId()).header("X-POS-Key", POS_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    // ───────── 도우미 ─────────

    private ResultActions result(String posKey, long paymentId, String posCode) throws Exception {
        var builder = get("/api/pos/payment-requests/{paymentId}", paymentId).param("posCode", posCode);
        if (posKey != null) {
            builder.header("X-POS-Key", posKey);
        }
        return mockMvc.perform(builder);
    }

    private ResultActions approve(long paymentId, long userCardId) throws Exception {
        return mockMvc.perform(post("/api/payments/{paymentId}/approve", paymentId)
                .with(AuthUsers.asUser(user))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userCardId\": " + userCardId + "}"));
    }

    /** POS 결제 요청 API로 REQUESTED 결제를 만든다. 바코드는 저장소에 직접 넣는다. */
    private Requested requested(long amount, String plan, Integer months) throws Exception {
        String token = issued(user);
        Map<String, Object> body = new HashMap<>();
        body.put("barcodeToken", token);
        body.put("merchantId", cafe);
        body.put("posCode", POS_CODE);
        body.put("amount", amount);
        body.put("orderNo", "POS-0001");
        if (plan != null) {
            body.put("plan", plan);
        }
        if (months != null) {
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
        String token = String.format("%016d", 1_000_000_000_000_000L + userId * 7 + payments.count());
        Instant now = clock.instant();
        assertThat(barcodes.issue(new Barcode(token, userId, BarcodeStatus.ISSUED, now, now.plusSeconds(60)), RETENTION))
                .isTrue();
        return token;
    }

    /**
     * 승인 원장(Task 5.2.5)이 적은 행. 실적 인정액이 있는 승인은 card_monthly_performance를 만들어 카드·결제 행을 지울 수 없게 하므로,
     * 시작할 때 지우고 끝날 때도 지워 다음 테스트 클래스가 카드를 지울 수 있게 한다.
     */
    @AfterEach
    void deleteLedgerRows() {
        jdbc.update("DELETE FROM payment_benefit");
        jdbc.update("DELETE FROM payment_limit_usage");
        jdbc.update("DELETE FROM monthly_limit_usage");
        jdbc.update("DELETE FROM card_monthly_performance");
    }

    private long product(String cardType) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return new SimpleJdbcInsert(jdbc).withTableName("card_product").usingGeneratedKeyColumns("card_product_id")
                .usingColumns("issuer_code", "product_name", "card_type", "billing_cutoff_days", "created_at", "updated_at")
                .executeAndReturnKey(Map.of("issuer_code", "SHINHAN", "product_name", UUID.randomUUID().toString(),
                        "card_type", cardType, "billing_cutoff_days", 14, "created_at", now, "updated_at", now))
                .longValue();
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
