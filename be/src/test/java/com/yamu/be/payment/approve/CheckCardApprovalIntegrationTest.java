package com.yamu.be.payment.approve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.bank.BankDirection;
import com.yamu.be.payment.bank.BankTransaction;
import com.yamu.be.payment.bank.SsafyTransferApi;
import com.yamu.be.payment.bank.TagLookup;
import com.yamu.be.payment.bank.TransferResult;
import com.yamu.be.payment.bank.TransferResult.RejectReason;
import com.yamu.be.payment.bank.TransferResult.Status;
import com.yamu.be.payment.bank.TransferTag;
import com.yamu.be.payment.barcode.Barcode;
import com.yamu.be.payment.barcode.BarcodeStatus;
import com.yamu.be.payment.barcode.BarcodeStore;
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
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * 체크카드 승인(출금)과 결과 불명 복구를 실제 MySQL·Redis로 확인한다. 금융망 출금·거래내역 조회(SsafyTransferApi)만 가짜다.
 * 결제 요청은 POS 결제 요청 API로 만들고, 추천은 명시적인 Mock fixture라 혜택은 0원이다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-check-card-approval-test")
@Import(CheckCardApprovalIntegrationTest.ClockConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CheckCardApprovalIntegrationTest extends MySqlTestSupport {

    static final String POS_KEY = "test-pos-key";
    static final Duration RETENTION = Duration.ofMinutes(10);
    static final String ACCOUNT_NO = "0012345678901234";
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final DateTimeFormatter REQUEST_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        com.yamu.be.payment.recommend.CardRecommender approvalRecommendationFixture(
                com.yamu.be.payment.lookup.PaymentCardLookup cards, java.time.Clock clock) {
            return new com.yamu.be.payment.recommend.MockCardRecommender(cards, clock);
        }

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @MockitoBean SsafyTransferApi transfer;

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired BarcodeStore barcodes;
    @Autowired PaymentTransactionRepository payments;
    @Autowired ApprovalAttemptStore attempts;
    @Autowired WithdrawalRecovery recovery;
    @Autowired PaymentApprovalProperties properties;
    @Autowired PersonalDataCipher cipher;
    @Autowired MutableClock clock;

    final ObjectMapper json = new ObjectMapper();
    final AtomicInteger sequence = new AtomicInteger();
    OwnershipFixtures fixtures;
    long user;
    long account;
    long checkCard;
    long creditCard;
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
        sequence.set(0);
        fixtures = new OwnershipFixtures(jdbc);
        user = fixtures.user();
        jdbc.update("UPDATE app_user SET ssafy_user_key = ?, ssafy_member_id = ? WHERE user_id = ?",
                cipher.encrypt("user-key-" + user, CipherField.APP_USER_SSAFY_USER_KEY), "yamu-" + user + "@yamu.com",
                user);
        account = fixtures.account(user);
        jdbc.update("UPDATE linked_account SET account_no_enc = ? WHERE linked_account_id = ?",
                cipher.encrypt(ACCOUNT_NO, CipherField.LINKED_ACCOUNT_NO), account);
        long checkProduct = product("CHECK");
        long creditProduct = fixtures.product();
        checkCard = fixtures.card(user, checkProduct, account, "ACTIVE");
        jdbc.update("UPDATE user_card SET is_default = TRUE WHERE user_card_id = ?", checkCard);
        creditCard = fixtures.card(user, creditProduct, account, "ACTIVE");
        jdbc.update("UPDATE user_card SET payment_day = 15 WHERE user_card_id = ?", creditCard);
        cafe = merchant("테스트 카페", "TEST_CAFE", "COFFEE_SHOP");

        doAnswer(inv -> nextTransactionNo()).when(transfer).newTransactionNo();
        doReturn(success("1101")).when(transfer).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
        doReturn(new TagLookup(TagLookup.Result.NOT_FOUND, List.of()))
                .when(transfer).findByTag(anyString(), anyString(), any(), anyLong(), anyString());
    }

    // ───────── 출금 승인 ─────────

    @Test
    void 체크카드_승인은_요청_번호를_먼저_저장한_뒤_출금하고_거래_번호와_함께_APPROVED가_된다() throws Exception {
        Requested requested = requested(15_000);
        List<String> seenAtWithdraw = new ArrayList<>();
        doAnswer(inv -> {
            // 출금 호출 시점에 DB(다른 커넥션)에는 PROCESSING과 같은 요청 번호가 이미 커밋돼 있어야 한다
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT status, fin_request_no FROM payment_transaction WHERE payment_transaction_id = ?",
                    requested.paymentId());
            seenAtWithdraw.add(row.get("status") + "/" + row.get("fin_request_no") + "/" + inv.getArgument(4));
            return success("1101");
        }).when(transfer).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
        cacheBalance();  // POS 추천이 결제 직전에 채운 출금 전 잔액

        approve(requested.paymentId(), UUID.randomUUID().toString(), body(checkCard))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.selectedCard.userCardId").value(checkCard))
                .andExpect(jsonPath("$.data.selectedCard.cardType").value("CHECK"))
                .andExpect(jsonPath("$.data.chargedAmount").value(15_000));

        PaymentTransaction payment = payments.findById(requested.paymentId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment.getFinRequestNo()).matches("\\d{20}");
        assertThat(payment.getFinTransactionNo()).isEqualTo("1101");
        assertThat(payment.getApprovedAmount()).isEqualTo(15_000);
        assertThat(seenAtWithdraw).containsExactly(
                "PROCESSING/" + payment.getFinRequestNo() + "/" + payment.getFinRequestNo());
        assertThat(barcodes.find(requested.token()).orElseThrow().status()).isEqualTo(BarcodeStatus.USED);
        verify(transfer).withdraw("user-key-" + user, ACCOUNT_NO, 15_000, TransferTag.payment(requested.paymentId()),
                payment.getFinRequestNo());
        List<ApprovalAttemptStore.Attempt> history = attempts.list(requested.paymentId());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).outcome()).isEqualTo("APPROVED");
        assertThat(history.get(0).finRequestNo()).isEqualTo(payment.getFinRequestNo());
        // 돈이 나갔으니 잔고예보·계좌 목록이 다음 조회에서 금융망의 출금 뒤 잔액을 읽도록 캐시가 지워진다(S15P31E103-329)
        assertThat(balanceCached()).isFalse();
    }

    @Test
    void 신용카드는_출금하지_않고_승인된다() throws Exception {
        Requested requested = requested(15_000);

        approve(requested.paymentId(), UUID.randomUUID().toString(), body(creditCard))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        PaymentTransaction payment = payments.findById(requested.paymentId()).orElseThrow();
        assertThat(payment.getFinRequestNo()).isNull();
        assertThat(payment.getFinTransactionNo()).isNull();
        verify(transfer, never()).newTransactionNo();
        verify(transfer, never()).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
        // 신용카드는 청구 회차를 만든다(Task 5.2.4). 자세한 확인은 CreditApprovalIntegrationTest
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bill_item WHERE payment_transaction_id = ?", Long.class,
                requested.paymentId())).isEqualTo(1L);
    }

    @Test
    void 잔액_부족이면_DECLINED이고_다른_카드로_재승인할_수_있다() throws Exception {
        Requested requested = requested(15_000);
        doReturn(new TransferResult(Status.REJECTED, null, null, RejectReason.INSUFFICIENT_BALANCE, "A1014"))
                .when(transfer).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
        cacheBalance();

        approve(requested.paymentId(), UUID.randomUUID().toString(), body(checkCard))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DECLINED"))
                .andExpect(jsonPath("$.data.failure.code").value("INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$.data.retryable").value(true))
                .andExpect(jsonPath("$.data.suggestedCards[0].userCardId").value(creditCard));

        PaymentTransaction declined = payments.findById(requested.paymentId()).orElseThrow();
        assertThat(declined.getStatus()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(declined.getFailureCode()).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(declined.getFinRequestNo()).isNotNull();
        assertThat(barcodes.find(requested.token()).orElseThrow().status()).isEqualTo(BarcodeStatus.SCANNED);
        assertThat(balanceCached()).isTrue();  // 잔액이 바뀌지 않았으니 캐시는 그대로

        approve(requested.paymentId(), UUID.randomUUID().toString(), body(creditCard))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.selectedCard.userCardId").value(creditCard));
        assertThat(payments.findById(requested.paymentId()).orElseThrow().getFinRequestNo()).isNull();
        assertThat(attempts.count(requested.paymentId())).isEqualTo(2);
    }

    @Test
    void 계좌가_없다고_거절되면_ACCOUNT_UNAVAILABLE이고_연결_계좌는_건드리지_않는다() throws Exception {
        Requested requested = requested(15_000);
        doReturn(new TransferResult(Status.REJECTED, null, null, RejectReason.ACCOUNT_NOT_FOUND, "A1003"))
                .when(transfer).withdraw(anyString(), anyString(), anyLong(), any(), anyString());

        approve(requested.paymentId(), UUID.randomUUID().toString(), body(checkCard))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DECLINED"))
                .andExpect(jsonPath("$.data.failure.code").value("ACCOUNT_UNAVAILABLE"));

        assertThat(jdbc.queryForObject("SELECT status FROM linked_account WHERE linked_account_id = ?", String.class,
                account)).isEqualTo("CONNECTED");
    }

    // ───────── 결과 불명 복구 ─────────

    @Test
    void 결과_불명_뒤_재요청은_거래내역에서_태그를_찾으면_승인_결과를_돌려준다() throws Exception {
        Requested requested = requested(15_000);
        String key = UUID.randomUUID().toString();
        doReturn(new TransferResult(Status.UNKNOWN, null, null, null, null))
                .when(transfer).withdraw(anyString(), anyString(), anyLong(), any(), anyString());

        approve(requested.paymentId(), key, body(checkCard))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("FIN_API_UNAVAILABLE"));
        PaymentTransaction stuck = payments.findById(requested.paymentId()).orElseThrow();
        assertThat(stuck.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(stuck.getFinRequestNo()).isNotNull();

        // 유예 전이고 거래내역에 아직 없음 → 처리 중
        approve(requested.paymentId(), key, body(checkCard))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_IN_PROGRESS"));

        doReturn(new TagLookup(TagLookup.Result.FOUND, List.of(withdrawal(requested.paymentId(), 15_000, "1102"))))
                .when(transfer).findByTag(anyString(), anyString(), any(), anyLong(), anyString());
        String first = approve(requested.paymentId(), key, body(checkCard))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.selectedCard.userCardId").value(checkCard))
                .andExpect(jsonPath("$.data.selectedCard.userSelected").value(true))
                .andReturn().getResponse().getContentAsString();

        PaymentTransaction approved = payments.findById(requested.paymentId()).orElseThrow();
        assertThat(approved.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(approved.getFinRequestNo()).isEqualTo(stuck.getFinRequestNo());
        assertThat(approved.getFinTransactionNo()).isEqualTo("1102");
        assertThat(barcodes.find(requested.token()).orElseThrow().status()).isEqualTo(BarcodeStatus.USED);
        verify(transfer, times(1)).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
        // 유예 전 재요청(없음)과 FOUND 재요청, 두 번 조회했다
        verify(transfer, times(2)).findByTag("user-key-" + user, ACCOUNT_NO, TransferTag.payment(requested.paymentId()),
                15_000, stuck.getFinRequestNo());
        List<ApprovalAttemptStore.Attempt> history = attempts.list(requested.paymentId());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).outcome()).isEqualTo("APPROVED");

        String again = approve(requested.paymentId(), key, body(checkCard)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(again)).isEqualTo(json.readTree(first));
    }

    @Test
    void 결과_불명_뒤_유예가_지나도_거래내역에_없으면_그_요청은_거절로_끝나고_다시_누르면_앞_출금을_재조회한_뒤_재승인된다()
            throws Exception {
        Requested requested = requested(15_000);
        doReturn(new TransferResult(Status.UNKNOWN, null, null, null, "H1007"))
                .when(transfer).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
        String key = UUID.randomUUID().toString();
        approve(requested.paymentId(), key, body(checkCard))
                .andExpect(status().isBadGateway());
        String finRequestNo = payments.findById(requested.paymentId()).orElseThrow().getFinRequestNo();

        clock.advance(properties.recoveryGrace());

        // 502를 받은 앱이 같은 키로 다시 보내도, 복구가 거절로 판정한 요청은 거기서 끝난다(같은 요청 안에서 다시 출금하지 않음)
        approve(requested.paymentId(), key, body(checkCard))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DECLINED"))
                .andExpect(jsonPath("$.data.failure.code").value("WITHDRAWAL_NOT_CONFIRMED"))
                .andExpect(jsonPath("$.data.retryable").value(true))
                .andExpect(jsonPath("$.data.selectedCard.userCardId").value(checkCard));
        verify(transfer, times(1)).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.DECLINED);

        // 사용자가 다시 누르면(새 키) 앞 요청 번호의 출금을 한 번 더 확인한 뒤에 재승인한다
        approve(requested.paymentId(), UUID.randomUUID().toString(), body(creditCard))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.selectedCard.userCardId").value(creditCard));
        verify(transfer, times(2)).findByTag("user-key-" + user, ACCOUNT_NO, TransferTag.payment(requested.paymentId()),
                15_000, finRequestNo);

        PaymentTransaction approved = payments.findById(requested.paymentId()).orElseThrow();
        assertThat(approved.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(approved.getUserCardId()).isEqualTo(creditCard);
        assertThat(approved.getFinRequestNo()).isNull();
        verify(transfer, times(1)).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
        List<ApprovalAttemptStore.Attempt> history = attempts.list(requested.paymentId());
        assertThat(history).hasSize(2);
        assertThat(history.get(0).outcome()).isEqualTo("DECLINED");
        assertThat(history.get(0).failureCode()).isEqualTo("WITHDRAWAL_NOT_CONFIRMED");
        assertThat(history.get(1).outcome()).isEqualTo("APPROVED");
    }

    @Test
    void 결과_불명_뒤_마감까지_지나면_거절_뒤_만료되어_410이다() throws Exception {
        Requested requested = requested(15_000);
        doReturn(new TransferResult(Status.UNKNOWN, null, null, null, null))
                .when(transfer).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
        approve(requested.paymentId(), UUID.randomUUID().toString(), body(checkCard))
                .andExpect(status().isBadGateway());

        clock.advance(Duration.ofSeconds(61));

        approve(requested.paymentId(), UUID.randomUUID().toString(), body(creditCard))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_EXPIRED"));
        assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(barcodes.find(requested.token()).orElseThrow().status()).isEqualTo(BarcodeStatus.EXPIRED);
    }

    @Test
    void 금융망에_보내지_못하면_결제를_되돌리고_502이며_새_키로_다시_승인된다() throws Exception {
        Requested requested = requested(15_000);
        doReturn(new TransferResult(Status.NOT_SENT, null, null, null, null))
                .when(transfer).withdraw(anyString(), anyString(), anyLong(), any(), anyString());

        approve(requested.paymentId(), UUID.randomUUID().toString(), body(checkCard))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("FIN_API_UNAVAILABLE"));

        PaymentTransaction released = payments.findById(requested.paymentId()).orElseThrow();
        assertThat(released.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
        assertThat(released.getFinRequestNo()).isNull();
        assertThat(released.getUserCardId()).isEqualTo(checkCard);
        assertThat(attempts.count(requested.paymentId())).isZero();
        assertThat(barcodes.find(requested.token()).orElseThrow().status()).isEqualTo(BarcodeStatus.SCANNED);

        doReturn(success("1103")).when(transfer).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
        approve(requested.paymentId(), UUID.randomUUID().toString(), body(checkCard))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        assertThat(payments.findById(requested.paymentId()).orElseThrow().getFinTransactionNo()).isEqualTo("1103");
        assertThat(attempts.count(requested.paymentId())).isEqualTo(1);
    }

    @Test
    void 주기_작업은_오래된_PROCESSING_결제를_거래내역으로_확정한다() throws Exception {
        Requested requested = requested(15_000);
        doReturn(new TransferResult(Status.UNKNOWN, null, null, null, null))
                .when(transfer).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
        approve(requested.paymentId(), UUID.randomUUID().toString(), body(checkCard))
                .andExpect(status().isBadGateway());
        doReturn(new TagLookup(TagLookup.Result.FOUND, List.of(withdrawal(requested.paymentId(), 15_000, "1104"))))
                .when(transfer).findByTag(anyString(), anyString(), any(), anyLong(), anyString());
        WithdrawalRecoveryJob job = new WithdrawalRecoveryJob(payments, recovery, properties, clock);

        // 유예 전에는 후보가 아니다
        job.run();
        assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PROCESSING);

        // updated_at은 서버 시계라 JDBC로 늙히고, 요청 번호 시각 기준 유예는 시계를 옮겨 넘긴다
        jdbc.update("UPDATE payment_transaction SET updated_at = DATE_SUB(updated_at, INTERVAL 2 MINUTE) "
                + "WHERE payment_transaction_id = ?", requested.paymentId());
        clock.advance(properties.recoveryGrace().plusSeconds(1));
        cacheBalance();  // 결과 불명이라 출금 때 지우지 못한 캐시
        job.run();

        PaymentTransaction approved = payments.findById(requested.paymentId()).orElseThrow();
        assertThat(approved.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(approved.getFinTransactionNo()).isEqualTo("1104");
        assertThat(balanceCached()).isFalse();  // 돈이 나간 것을 확인했으니 지운다
        assertThat(barcodes.find(requested.token()).orElseThrow().status()).isEqualTo(BarcodeStatus.USED);
        assertThat(attempts.list(requested.paymentId()).get(0).outcome()).isEqualTo("APPROVED");
    }

    @Test
    void 결과_불명_결제에_같은_키_재요청이_동시에_5건_와도_승인_확정은_한_번이고_다른_키는_409다() throws Exception {
        Requested requested = requested(15_000);
        doReturn(new TransferResult(Status.UNKNOWN, null, null, null, null))
                .when(transfer).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
        String key = UUID.randomUUID().toString();
        approve(requested.paymentId(), key, body(checkCard))
                .andExpect(status().isBadGateway());
        doReturn(new TagLookup(TagLookup.Result.FOUND, List.of(withdrawal(requested.paymentId(), 15_000, "1105"))))
                .when(transfer).findByTag(anyString(), anyString(), any(), anyLong(), anyString());

        int requests = 5;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < requests; i++) {
                Callable<Integer> call = () -> {
                    ready.countDown();
                    go.await();
                    return approve(requested.paymentId(), key, body(checkCard))
                            .andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(call));
            }
            ready.await();
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : results) {
                statuses.add(future.get(20, TimeUnit.SECONDS));
            }
            assertThat(statuses).contains(200);
            assertThat(statuses).allSatisfy(s -> assertThat(s).isIn(200, 409));
        } finally {
            pool.shutdownNow();
        }
        PaymentTransaction approved = payments.findById(requested.paymentId()).orElseThrow();
        assertThat(approved.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(approved.getFinTransactionNo()).isEqualTo("1105");
        verify(transfer, times(1)).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(attempts.list(requested.paymentId())).hasSize(1);
        // 체크카드 승인은 청구서·회차를 만들지 않는다
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bill_item", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_bill", Long.class)).isZero();

        approve(requested.paymentId(), UUID.randomUUID().toString(), body(checkCard))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_ALREADY_PROCESSED"));
    }

    // ───────── 늦은 출금·응답 일관성(!69 리뷰) ─────────

    @Test
    void 출금_미확인으로_거절된_뒤_다시_누를_때_앞_출금이_늦게_처리돼_있으면_승인으로_바로잡고_다시_출금하지_않는다()
            throws Exception {
        Requested requested = requested(15_000);
        doReturn(new TransferResult(Status.UNKNOWN, null, null, null, null))
                .when(transfer).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
        String key = UUID.randomUUID().toString();
        approve(requested.paymentId(), key, body(checkCard)).andExpect(status().isBadGateway());
        clock.advance(properties.recoveryGrace());
        approve(requested.paymentId(), key, body(checkCard))
                .andExpect(jsonPath("$.data.status").value("DECLINED"));

        // 은행이 첫 출금을 유예 뒤에 처리했다
        doReturn(new TagLookup(TagLookup.Result.FOUND, List.of(withdrawal(requested.paymentId(), 15_000, "1106"))))
                .when(transfer).findByTag(anyString(), anyString(), any(), anyLong(), anyString());
        approve(requested.paymentId(), UUID.randomUUID().toString(), body(creditCard))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_ALREADY_PROCESSED"));

        PaymentTransaction approved = payments.findById(requested.paymentId()).orElseThrow();
        assertThat(approved.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(approved.getUserCardId()).isEqualTo(checkCard);
        assertThat(approved.getFinTransactionNo()).isEqualTo("1106");
        assertThat(approved.getFailureCode()).isNull();
        verify(transfer, times(1)).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(attempts.list(requested.paymentId())).hasSize(1);
        assertThat(attempts.list(requested.paymentId()).get(0).outcome()).isEqualTo("APPROVED");
    }

    @Test
    void 앞_출금을_다시_확인하지_못하면_재승인하지_않고_502다() throws Exception {
        Requested requested = requested(15_000);
        doReturn(new TransferResult(Status.UNKNOWN, null, null, null, null))
                .when(transfer).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
        String key = UUID.randomUUID().toString();
        approve(requested.paymentId(), key, body(checkCard)).andExpect(status().isBadGateway());
        clock.advance(properties.recoveryGrace());
        approve(requested.paymentId(), key, body(checkCard)).andExpect(jsonPath("$.data.status").value("DECLINED"));

        doThrow(new BusinessException(ErrorCode.FIN_API_UNAVAILABLE))
                .when(transfer).findByTag(anyString(), anyString(), any(), anyLong(), anyString());
        approve(requested.paymentId(), UUID.randomUUID().toString(), body(creditCard))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("FIN_API_UNAVAILABLE"));

        PaymentTransaction declined = payments.findById(requested.paymentId()).orElseThrow();
        assertThat(declined.getStatus()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(declined.getFailureCode()).isEqualTo("WITHDRAWAL_NOT_CONFIRMED");
        assertThat(attempts.count(requested.paymentId())).isEqualTo(1);
    }

    @Test
    void 주기_작업은_출금_미확인으로_거절_만료한_결제를_대조_기간_동안_다시_보고_늦은_출금이면_승인으로_바로잡는다() throws Exception {
        Requested requested = requested(15_000);
        doReturn(new TransferResult(Status.UNKNOWN, null, null, null, null))
                .when(transfer).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
        approve(requested.paymentId(), UUID.randomUUID().toString(), body(checkCard)).andExpect(status().isBadGateway());
        WithdrawalRecoveryJob job = new WithdrawalRecoveryJob(payments, recovery, properties, clock);
        jdbc.update("UPDATE payment_transaction SET updated_at = DATE_SUB(updated_at, INTERVAL 2 MINUTE) "
                + "WHERE payment_transaction_id = ?", requested.paymentId());
        // 마감(60초)까지 지나 거절 뒤 만료된다
        clock.advance(Duration.ofSeconds(61));
        job.run();
        assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.EXPIRED);

        doReturn(new TagLookup(TagLookup.Result.FOUND, List.of(withdrawal(requested.paymentId(), 15_000, "1107"))))
                .when(transfer).findByTag(anyString(), anyString(), any(), anyLong(), anyString());
        job.run();

        PaymentTransaction approved = payments.findById(requested.paymentId()).orElseThrow();
        assertThat(approved.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(approved.getFinTransactionNo()).isEqualTo("1107");
        verify(transfer, times(1)).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
    }

    @Test
    void 대조_기간이_지난_출금_미확인_결제는_주기_작업이_다시_보지_않는다() throws Exception {
        Requested requested = requested(15_000);
        doReturn(new TransferResult(Status.UNKNOWN, null, null, null, null))
                .when(transfer).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
        approve(requested.paymentId(), UUID.randomUUID().toString(), body(checkCard)).andExpect(status().isBadGateway());
        WithdrawalRecoveryJob job = new WithdrawalRecoveryJob(payments, recovery, properties, clock);
        jdbc.update("UPDATE payment_transaction SET updated_at = DATE_SUB(updated_at, INTERVAL 2 MINUTE) "
                + "WHERE payment_transaction_id = ?", requested.paymentId());
        clock.advance(Duration.ofSeconds(61));
        job.run();

        doReturn(new TagLookup(TagLookup.Result.FOUND, List.of(withdrawal(requested.paymentId(), 15_000, "1108"))))
                .when(transfer).findByTag(anyString(), anyString(), any(), anyLong(), anyString());
        clock.advance(properties.lateWithdrawalWindow());
        job.run();

        assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.EXPIRED);
    }

    @Test
    void 주기_작업이_먼저_승인으로_확정해도_같은_키_재요청은_요청_경로_복구와_같은_200_승인_응답이다() throws Exception {
        Requested requested = requested(15_000);
        doReturn(new TransferResult(Status.UNKNOWN, null, null, null, null))
                .when(transfer).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
        String key = UUID.randomUUID().toString();
        approve(requested.paymentId(), key, body(checkCard)).andExpect(status().isBadGateway());
        doReturn(new TagLookup(TagLookup.Result.FOUND, List.of(withdrawal(requested.paymentId(), 15_000, "1109"))))
                .when(transfer).findByTag(anyString(), anyString(), any(), anyLong(), anyString());
        jdbc.update("UPDATE payment_transaction SET updated_at = DATE_SUB(updated_at, INTERVAL 2 MINUTE) "
                + "WHERE payment_transaction_id = ?", requested.paymentId());
        clock.advance(properties.recoveryGrace().plusSeconds(1));
        new WithdrawalRecoveryJob(payments, recovery, properties, clock).run();
        assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.APPROVED);

        approve(requested.paymentId(), key, body(checkCard))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.selectedCard.userCardId").value(checkCard))
                .andExpect(jsonPath("$.data.selectedCard.userSelected").value(true));
    }

    // ───────── 도우미 ─────────

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

    private static TransferResult success(String bankTransactionNo) {
        return new TransferResult(Status.SUCCESS, bankTransactionNo, LocalDate.now(KST), null, null);
    }

    private static BankTransaction withdrawal(long paymentId, long amount, String bankTransactionNo) {
        return new BankTransaction(bankTransactionNo, LocalDate.now(KST), LocalTime.NOON, BankDirection.WITHDRAWAL,
                amount, 1_000_000, "YAMU-P" + paymentId);
    }

    /** 채번기와 같은 모양: 지금(테스트 시계, 한국 시각) 14자리 + 일련번호 6자리. 복구 유예는 이 시각으로 잰다. */
    private String nextTransactionNo() {
        return clock.instant().atZone(KST).format(REQUEST_TIME) + String.format("%06d", sequence.incrementAndGet());
    }

    /** POS 결제 요청 API로 REQUESTED 결제를 만든다. 바코드는 저장소에 직접 넣는다. */
    private Requested requested(long amount) throws Exception {
        String token = issued(user);
        Map<String, Object> body = new HashMap<>();
        body.put("barcodeToken", token);
        body.put("merchantId", cafe);
        body.put("posCode", "POS-TEST-CAFE");
        body.put("amount", amount);
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
        String token = String.format("%016d", 2_000_000_000_000_000L + userId * 7 + payments.count());
        Instant now = clock.instant();
        assertThat(barcodes.issue(new Barcode(token, userId, BarcodeStatus.ISSUED, now, now.plusSeconds(60)), RETENTION))
                .isTrue();
        return token;
    }

    private long product(String cardType) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return new SimpleJdbcInsert(jdbc).withTableName("card_product").usingGeneratedKeyColumns("card_product_id")
                .usingColumns("issuer_code", "product_name", "card_type", "billing_cutoff_days", "created_at", "updated_at")
                .executeAndReturnKey(Map.of("issuer_code", "TEST", "product_name", UUID.randomUUID().toString(),
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

    // ───────── 잔액 캐시(S15P31E103-329) ─────────

    /** 계좌 목록·잔고예보가 쓰는 연결 계좌 잔액 캐시(AccountBalanceService, 값은 "잔액|조회 시각")를 채워 둔다. 돈이 오간 뒤 지워져야 한다 */
    private void cacheBalance() {
        redis.opsForValue().set(balanceCacheKey(), "1000000|" + clock.instant().getEpochSecond());
    }

    private boolean balanceCached() {
        return Boolean.TRUE.equals(redis.hasKey(balanceCacheKey()));
    }

    private String balanceCacheKey() {
        return "account:balance:" + account;
    }
}
