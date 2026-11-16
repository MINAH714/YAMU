package com.yamu.be.payment.approve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.barcode.Barcode;
import com.yamu.be.payment.barcode.BarcodeService;
import com.yamu.be.payment.barcode.BarcodeStatus;
import com.yamu.be.payment.barcode.BarcodeStore;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import com.yamu.be.payment.recommend.RecommendationSnapshotStore;
import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.MutableClock;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
 * 결제 승인을 실제 MySQL·Redis로 확인한다. 추천은 명시적인 Mock fixture이고, 승인 실행 포트는 테스트가 바꿔 끼운다
 * (기본은 임시 승인과 같은 동작, 거절·실패 시나리오는 그 테스트에서 지정).
 * 결제 요청은 POS 결제 요청 API로 만든다(바코드는 저장소에 직접 넣는다).
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-payment-approval-test")
@Import(PaymentApprovalIntegrationTest.ClockConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentApprovalIntegrationTest extends MySqlTestSupport {

    static final String POS_KEY = "test-pos-key";
    static final Duration RETENTION = Duration.ofMinutes(10);

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

    @MockitoBean PaymentApprover approver;

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired BarcodeStore barcodes;
    @Autowired BarcodeService barcodeService;
    @Autowired PaymentTransactionRepository payments;
    @Autowired RecommendationSnapshotStore snapshots;
    @Autowired ApprovalAttemptStore attempts;
    @Autowired MutableClock clock;

    final ObjectMapper json = new ObjectMapper();
    OwnershipFixtures fixtures;
    long user;
    long other;
    long creditProduct;
    long checkProduct;
    long creditCard;
    long checkCard;
    long otherCard;
    long cafe;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM bill_item");
        jdbc.update("DELETE FROM card_bill");
        jdbc.update("DELETE FROM payment_transaction");
        jdbc.update("DELETE FROM card_monthly_performance");
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
        other = fixtures.user();
        creditProduct = fixtures.product();
        checkProduct = product("CHECK");
        long account = fixtures.account(user);
        creditCard = fixtures.card(user, creditProduct, account, "ACTIVE");
        jdbc.update("UPDATE user_card SET is_default = TRUE WHERE user_card_id = ?", creditCard);
        checkCard = fixtures.card(user, checkProduct, account, "ACTIVE");
        otherCard = fixtures.card(other, creditProduct, fixtures.account(other), "ACTIVE");
        cafe = merchant("테스트 카페", "TEST_CAFE", "COFFEE_SHOP");
        when(approver.approve(any())).thenAnswer(inv -> approvedLike(inv.getArgument(0)));
    }

    // ───────── 승인 ─────────

    @Test
    void 카드를_지정해_승인하면_200_APPROVED이고_행_바코드_시도가_반영된다() throws Exception {
        Requested requested = requested(15_000, null, null);

        MvcResult result = approve(user, requested.paymentId(), UUID.randomUUID().toString(), body(creditCard))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.selectedCard.userCardId").value(creditCard))
                .andExpect(jsonPath("$.data.selectedCard.userSelected").value(true))
                .andExpect(jsonPath("$.data.selectedCard.recommendedRank").value(1))
                .andExpect(jsonPath("$.data.selectedCard.maskedNumber").value("****-****-****-1234"))
                .andExpect(jsonPath("$.data.amount").value(15_000))
                .andExpect(jsonPath("$.data.plan").value("LUMP_SUM"))
                .andExpect(jsonPath("$.data.chargedAmount").value(15_000))
                .andExpect(jsonPath("$.data.failure").value((Object) null))
                .andReturn();

        String approvalNo = JsonPath.read(result.getResponse().getContentAsString(), "$.data.approvalNo");
        assertThat(approvalNo).matches("Y\\d{8}" + String.format("%010d", requested.paymentId()));

        PaymentTransaction payment = payments.findById(requested.paymentId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment.getUserCardId()).isEqualTo(creditCard);
        assertThat(payment.getCardProductId()).isEqualTo(creditProduct);
        assertThat(payment.getApprovedAmount()).isEqualTo(15_000);
        assertThat(payment.getApprovedAt()).isNotNull();
        assertThat(payment.getRecognizedPerformanceAmount()).isZero();
        assertThat(payment.getPerformanceMonth()).isNotNull();
        assertThat(payment.getFailureCode()).isNull();
        assertThat(barcodes.find(requested.token()).orElseThrow().status()).isEqualTo(BarcodeStatus.USED);
        assertThat(attempts.count(requested.paymentId())).isEqualTo(1);
        assertThat(attempts.list(requested.paymentId()).get(0).outcome()).isEqualTo("APPROVED");
    }

    @Test
    void 승인_응답과_상세의_누적_실적은_카드_목록의_이번_달_실적과_같다() throws Exception {
        // 이번 달 앞선 실적 120,000원 + 이 결제 30,000원
        LocalDate month = ApprovalDates.performanceMonth(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        jdbc.update("""
                INSERT INTO card_monthly_performance (user_card_id, performance_month, recognized_amount,
                                                      created_at, updated_at, version)
                VALUES (?, ?, 120000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0)
                """, creditCard, month);
        doAnswer(inv -> approvedWithPerformance(inv.getArgument(0), 30_000L)).when(approver).approve(any());
        Requested requested = requested(30_000, null, null);
        String key = UUID.randomUUID().toString();

        approve(user, requested.paymentId(), key, body(creditCard))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.performance.recognizedAmount").value(30_000))
                .andExpect(jsonPath("$.data.performance.recognizedTotalAfter").value(150_000))
                // 실적 조건이 없는 카드(픽스처 상품에 혜택 없음)는 목표가 없다
                .andExpect(jsonPath("$.data.performance.targetAmount").value(nullValue()));
        // 같은 키 재전송은 승인 직후의 누적을 그대로 돌려준다
        approve(user, requested.paymentId(), key, body(creditCard))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.performance.recognizedTotalAfter").value(150_000));
        detail(requested.paymentId())
                .andExpect(jsonPath("$.data.performance.recognizedTotalAfter").value(150_000))
                .andExpect(jsonPath("$.data.performance.targetAmount").value(nullValue()));
        // 카드 목록 API가 이번 달 실적으로 읽는 행과 같은 값이다(그 읽기는 CardManagementIntegrationTest가 확인한다.
        // 카드 목록 서비스는 clock.withZone을 써서 이 테스트의 MutableClock으로는 부를 수 없다)
        assertThat(jdbc.queryForObject("SELECT recognized_amount FROM card_monthly_performance"
                + " WHERE user_card_id = ? AND performance_month = ?", Long.class, creditCard, month)).isEqualTo(150_000L);
    }

    @Test
    void 실적_인정액을_모르는_승인은_상세처럼_실적을_비운다() throws Exception {
        // S15P31E103-568: 0·recognized=false로 보내면 앱이 "이 결제는 빠져요"로 그린다(상세는 줄이 없다)
        doAnswer(inv -> approvedWithPerformance(inv.getArgument(0), null)).when(approver).approve(any());
        Requested requested = requested(30_000, null, null);
        String key = UUID.randomUUID().toString();

        approve(user, requested.paymentId(), key, body(creditCard))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.performance").value(nullValue()));
        approve(user, requested.paymentId(), key, body(creditCard))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.performance").value(nullValue()));
        detail(requested.paymentId())
                .andExpect(jsonPath("$.data.performance").value(nullValue()));
    }

    @Test
    void 실적_제외로_확정된_승인은_0원_미인정으로_보낸다() throws Exception {
        doAnswer(inv -> approvedWithPerformance(inv.getArgument(0), 0L)).when(approver).approve(any());
        Requested requested = requested(30_000, null, null);

        approve(user, requested.paymentId(), UUID.randomUUID().toString(), body(creditCard))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.performance.recognizedAmount").value(0))
                .andExpect(jsonPath("$.data.performance.recognized").value(false));
        detail(requested.paymentId())
                .andExpect(jsonPath("$.data.performance.recognizedAmount").value(0))
                .andExpect(jsonPath("$.data.performance.recognized").value(false));
    }

    @Test
    void 카드를_생략하면_1순위_기본_카드로_승인한다() throws Exception {
        Requested requested = requested(15_000, null, null);

        approve(user, requested.paymentId(), UUID.randomUUID().toString(), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.selectedCard.userCardId").value(creditCard))
                .andExpect(jsonPath("$.data.selectedCard.userSelected").value(false))
                .andExpect(jsonPath("$.data.selectedCard.recommendedRank").value(1));
    }

    @Test
    void 결제_상세는_승인_응답과_같은_추천_순위_직접_선택_업종_이름을_보여준다() throws Exception {
        Requested requested = requested(15_000, null, null);
        approve(user, requested.paymentId(), UUID.randomUUID().toString(), body(creditCard))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.selectedCard.recommendedRank").value(1))
                .andExpect(jsonPath("$.data.selectedCard.userSelected").value(true));

        detail(requested.paymentId())
                .andExpect(jsonPath("$.data.selectedCard.userCardId").value(creditCard))
                .andExpect(jsonPath("$.data.selectedCard.recommendedRank").value(1))
                .andExpect(jsonPath("$.data.selectedCard.userSelected").value(true))
                .andExpect(jsonPath("$.data.merchant.categoryCode").value("COFFEE_SHOP"))
                .andExpect(jsonPath("$.data.merchant.categoryName").value("카페"));
    }

    @Test
    void 거절_뒤_다른_카드로_재승인하면_상세의_순위와_직접_선택도_마지막_시도로_바뀐다() throws Exception {
        Requested requested = requested(15_000, null, null);
        doReturn(ApprovalOutcome.Declined.of(FailureCode.ISSUER_DECLINED))
                .doAnswer(inv -> approvedLike(inv.getArgument(0)))
                .when(approver).approve(any());
        approve(user, requested.paymentId(), UUID.randomUUID().toString(), "{}")
                .andExpect(jsonPath("$.data.status").value("DECLINED"));
        detail(requested.paymentId())
                .andExpect(jsonPath("$.data.status").value("DECLINED"))
                .andExpect(jsonPath("$.data.selectedCard.userCardId").value(creditCard))
                .andExpect(jsonPath("$.data.selectedCard.recommendedRank").value(1))
                .andExpect(jsonPath("$.data.selectedCard.userSelected").value(false));

        approve(user, requested.paymentId(), UUID.randomUUID().toString(), body(checkCard))
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.selectedCard.recommendedRank").value(2))
                .andExpect(jsonPath("$.data.selectedCard.userSelected").value(true));
        detail(requested.paymentId())
                .andExpect(jsonPath("$.data.selectedCard.userCardId").value(checkCard))
                .andExpect(jsonPath("$.data.selectedCard.recommendedRank").value(2))
                .andExpect(jsonPath("$.data.selectedCard.userSelected").value(true));
    }

    @Test
    void 추천_스냅샷이_사라졌으면_다시_계산해_승인한다() throws Exception {
        Requested requested = requested(15_000, null, null);
        snapshots.delete(requested.paymentId());

        approve(user, requested.paymentId(), UUID.randomUUID().toString(), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.selectedCard.userCardId").value(creditCard));
        assertThat(snapshots.find(requested.paymentId())).isPresent();
    }

    @Test
    void 추천을_다시_계산해_승인해도_결제_행의_접수_당시_추천은_그대로다() throws Exception {
        Requested requested = requested(15_000, null, null);
        String recommendedAtRequest = storedRecommendationId(requested.paymentId());
        assertThat(recommendedAtRequest).isNotNull();
        snapshots.delete(requested.paymentId());

        // 접수 트랜잭션이 JDBC로 version을 올렸어도 승인은 새로 읽은 엔티티로 고치므로 낙관적 잠금 충돌이 없다
        approve(user, requested.paymentId(), UUID.randomUUID().toString(), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.APPROVED);
        // 재계산한 스냅샷은 새 추천 번호지만 결제 행은 접수 때 값이다
        assertThat(snapshots.find(requested.paymentId()).orElseThrow().recommendationId())
                .isNotEqualTo(recommendedAtRequest);
        assertThat(storedRecommendationId(requested.paymentId())).isEqualTo(recommendedAtRequest);
    }

    // ───────── 멱등 ─────────

    @Test
    void 같은_키_같은_본문을_다시_보내면_처음_응답_그대로이고_다시_처리하지_않는다() throws Exception {
        Requested requested = requested(15_000, null, null);
        String key = UUID.randomUUID().toString();

        String first = approve(user, requested.paymentId(), key, body(creditCard)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Long version = payments.findById(requested.paymentId()).orElseThrow().getVersion();
        String second = approve(user, requested.paymentId(), key, body(creditCard)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(second)).isEqualTo(json.readTree(first));
        assertThat(payments.findById(requested.paymentId()).orElseThrow().getVersion()).isEqualTo(version);
        assertThat(attempts.count(requested.paymentId())).isEqualTo(1);
        verify(approver, times(1)).approve(any());
    }

    @Test
    void 같은_키에_다른_카드면_409_IDEMPOTENCY_KEY_REUSED() throws Exception {
        Requested requested = requested(15_000, null, null);
        String key = UUID.randomUUID().toString();
        approve(user, requested.paymentId(), key, body(creditCard)).andExpect(status().isOk());

        approve(user, requested.paymentId(), key, "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void Idempotency_Key가_없거나_UUID가_아니면_400이고_결제는_그대로다() throws Exception {
        Requested requested = requested(15_000, null, null);

        approve(user, requested.paymentId(), null, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        approve(user, requested.paymentId(), "not-a-uuid", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REQUESTED);
    }

    @Test
    void 기억된_응답이_없어도_같은_키는_시도_기록의_처음_응답을_돌려주고_다시_처리하지_않는다() throws Exception {
        Requested requested = requested(15_000, null, null);
        String key = UUID.randomUUID().toString();
        String first = approve(user, requested.paymentId(), key, body(creditCard)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 승인 뒤 응답 기억이 실패한 것과 같다
        redis.delete(ApprovalIdempotencyStore.KEY_PREFIX + user + ":" + key);

        String second = approve(user, requested.paymentId(), key, body(creditCard)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(second)).isEqualTo(json.readTree(first));
        approve(user, requested.paymentId(), key, "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        verify(approver, times(1)).approve(any());
    }

    @Test
    void 응답과_시도_기록이_모두_사라진_뒤_같은_키로_다시_오면_상태_검사가_답한다() throws Exception {
        Requested requested = requested(15_000, null, null);
        String key = UUID.randomUUID().toString();
        approve(user, requested.paymentId(), key, body(creditCard)).andExpect(status().isOk());

        redis.delete(ApprovalIdempotencyStore.KEY_PREFIX + user + ":" + key);
        redis.delete(ApprovalAttemptStore.KEY_PREFIX + requested.paymentId());

        approve(user, requested.paymentId(), key, body(creditCard))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_ALREADY_PROCESSED"));
    }

    // ───────── 동시 승인·상태 ─────────

    @Test
    void 다른_키로_동시에_5건_승인하면_한_건만_승인되고_나머지는_409_IDEMPOTENCY_IN_PROGRESS다() throws Exception {
        Requested requested = requested(15_000, null, null);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            release.await(10, TimeUnit.SECONDS);
            return approvedLike(inv.getArgument(0));
        }).when(approver).approve(any());
        int requests = 5;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MvcResult>> results = new ArrayList<>();
        try {
            for (int i = 0; i < requests; i++) {
                Callable<MvcResult> call = () -> {
                    ready.countDown();
                    go.await();
                    return approve(user, requested.paymentId(), UUID.randomUUID().toString(), "{}").andReturn();
                };
                results.add(pool.submit(call));
            }
            ready.await();
            go.countDown();
            // 선점한 한 건은 승인 실행에서 멈춰 있고, 나머지 넷이 먼저 끝나야 한다
            long deadline = System.currentTimeMillis() + 10_000;
            while (results.stream().filter(Future::isDone).count() < requests - 1
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            release.countDown();

            List<Integer> statuses = new ArrayList<>();
            List<String> codes = new ArrayList<>();
            for (Future<MvcResult> future : results) {
                MvcResult result = future.get(10, TimeUnit.SECONDS);
                statuses.add(result.getResponse().getStatus());
                if (result.getResponse().getStatus() != 200) {
                    codes.add(JsonPath.read(result.getResponse().getContentAsString(), "$.error.code"));
                }
            }
            assertThat(statuses).containsOnlyOnce(200);
            assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(requests - 1);
            assertThat(codes).containsOnly("IDEMPOTENCY_IN_PROGRESS");
        } finally {
            pool.shutdownNow();
        }
        assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.APPROVED);
        assertThat(attempts.count(requested.paymentId())).isEqualTo(1);
        assertThat(barcodes.find(requested.token()).orElseThrow().status()).isEqualTo(BarcodeStatus.USED);
        verify(approver, times(1)).approve(any());
    }

    @Test
    void 같은_결제의_시작_잠금이_잡혀_있으면_409이고_행과_시도_기록은_그대로다() throws Exception {
        Requested requested = requested(15_000, null, null);
        // 다른 키 요청이 시도 예약 ~ PROCESSING 선점 사이에 있는 것과 같다
        redis.opsForValue().set(ApprovalAttemptStore.START_LOCK_PREFIX + requested.paymentId(), "other");

        approve(user, requested.paymentId(), UUID.randomUUID().toString(), "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_IN_PROGRESS"));

        assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REQUESTED);
        assertThat(attempts.count(requested.paymentId())).isZero();
        verify(approver, never()).approve(any());
    }

    @Test
    void 승인된_결제를_새_키로_다시_승인하면_409_PAYMENT_ALREADY_PROCESSED() throws Exception {
        Requested requested = requested(15_000, null, null);
        approve(user, requested.paymentId(), UUID.randomUUID().toString(), "{}").andExpect(status().isOk());

        approve(user, requested.paymentId(), UUID.randomUUID().toString(), body(checkCard))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_ALREADY_PROCESSED"));
    }

    @Test
    void 마감_60초가_지나면_410이고_결제와_바코드가_EXPIRED가_된다() throws Exception {
        Requested requested = requested(15_000, null, null);
        clock.advance(Duration.ofSeconds(60));

        approve(user, requested.paymentId(), UUID.randomUUID().toString(), "{}")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_EXPIRED"));

        assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(barcodes.find(requested.token()).orElseThrow().status()).isEqualTo(BarcodeStatus.EXPIRED);
        BarcodeService.State state = barcodeService.get(user, requested.token());
        assertThat(state.status()).isEqualTo(BarcodeStatus.EXPIRED);
        assertThat(state.payment().status()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(attempts.count(requested.paymentId())).isZero();
    }

    @Test
    void 마감_직전_59초에는_승인된다() throws Exception {
        Requested requested = requested(15_000, null, null);
        clock.advance(Duration.ofSeconds(59));

        approve(user, requested.paymentId(), UUID.randomUUID().toString(), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    @Test
    void 바코드_조회만_해도_마감이_지난_결제는_EXPIRED로_바뀐다() throws Exception {
        Requested requested = requested(15_000, null, null);
        clock.advance(Duration.ofSeconds(61));

        BarcodeService.State state = barcodeService.get(user, requested.token());

        assertThat(state.payment().status()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(state.status()).isEqualTo(BarcodeStatus.EXPIRED);
    }

    // ───────── 거절·재승인 ─────────

    @Test
    void 거절되면_200_DECLINED이고_새_키로_다른_카드를_다시_승인할_수_있다() throws Exception {
        Requested requested = requested(15_000, null, null);
        doReturn(new ApprovalOutcome.Declined(FailureCode.INSUFFICIENT_BALANCE, null,
                Map.of("availableBalance", 8_000)))
                .doAnswer(inv -> approvedLike(inv.getArgument(0)))
                .when(approver).approve(any());

        approve(user, requested.paymentId(), UUID.randomUUID().toString(), body(creditCard))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DECLINED"))
                .andExpect(jsonPath("$.data.approvalNo").value((Object) null))
                .andExpect(jsonPath("$.data.failure.code").value("INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$.data.failure.message").value("연결 계좌 잔액이 부족합니다."))
                .andExpect(jsonPath("$.data.failure.details.availableBalance").value(8_000))
                .andExpect(jsonPath("$.data.retryable").value(true))
                .andExpect(jsonPath("$.data.remainingAttempts").value(2))
                .andExpect(jsonPath("$.data.suggestedCards[0].userCardId").value(checkCard))
                .andExpect(jsonPath("$.data.suggestedCards.length()").value(1));

        PaymentTransaction declined = payments.findById(requested.paymentId()).orElseThrow();
        assertThat(declined.getStatus()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(declined.getFailureCode()).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(declined.getUserCardId()).isEqualTo(creditCard);
        assertThat(declined.getApprovedAt()).isNull();
        assertThat(barcodes.find(requested.token()).orElseThrow().status()).isEqualTo(BarcodeStatus.SCANNED);

        approve(user, requested.paymentId(), UUID.randomUUID().toString(), body(checkCard))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.selectedCard.userCardId").value(checkCard));

        PaymentTransaction approved = payments.findById(requested.paymentId()).orElseThrow();
        assertThat(approved.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(approved.getUserCardId()).isEqualTo(checkCard);
        assertThat(approved.getFailureCode()).isNull();
        assertThat(attempts.count(requested.paymentId())).isEqualTo(2);
        assertThat(barcodes.find(requested.token()).orElseThrow().status()).isEqualTo(BarcodeStatus.USED);
    }

    @Test
    void 세_번_거절되면_더_시도할_수_없고_네_번째는_409_RETRY_LIMIT_EXCEEDED() throws Exception {
        Requested requested = requested(15_000, null, null);
        long thirdCard = fixtures.card(user, creditProduct, fixtures.account(user), "ACTIVE");
        doReturn(ApprovalOutcome.Declined.of(FailureCode.ISSUER_DECLINED)).when(approver).approve(any());

        approve(user, requested.paymentId(), UUID.randomUUID().toString(), body(creditCard))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remainingAttempts").value(2));
        approve(user, requested.paymentId(), UUID.randomUUID().toString(), body(checkCard))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remainingAttempts").value(1))
                .andExpect(jsonPath("$.data.suggestedCards.length()").value(0));
        approve(user, requested.paymentId(), UUID.randomUUID().toString(), body(thirdCard))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DECLINED"))
                .andExpect(jsonPath("$.data.retryable").value(false))
                .andExpect(jsonPath("$.data.remainingAttempts").value(0));

        approve(user, requested.paymentId(), UUID.randomUUID().toString(), body(checkCard))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RETRY_LIMIT_EXCEEDED"));
        assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(attempts.count(requested.paymentId())).isEqualTo(3);
    }

    @Test
    void 이_결제에서_거절된_카드를_다시_고르면_400이고_시도를_세지_않으며_생략하면_다음_후보로_승인한다() throws Exception {
        Requested requested = requested(15_000, null, null);
        doReturn(ApprovalOutcome.Declined.of(FailureCode.ISSUER_DECLINED))
                .doAnswer(inv -> approvedLike(inv.getArgument(0)))
                .when(approver).approve(any());
        approve(user, requested.paymentId(), UUID.randomUUID().toString(), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DECLINED"))
                .andExpect(jsonPath("$.data.selectedCard.userCardId").value(creditCard));

        approve(user, requested.paymentId(), UUID.randomUUID().toString(), body(creditCard))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CARD_NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.error.details.reason").value("ALREADY_DECLINED"));
        assertThat(attempts.count(requested.paymentId())).isEqualTo(1);

        approve(user, requested.paymentId(), UUID.randomUUID().toString(), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.selectedCard.userCardId").value(checkCard))
                .andExpect(jsonPath("$.data.selectedCard.recommendedRank").value(2));
    }

    // ───────── 실행 실패(결과 불명) ─────────

    @Test
    void 승인_실행이_실패하면_PROCESSING에_남고_유예_안_요청은_409이며_출금_번호_없이_유예와_마감이_지나면_풀려_만료된다()
            throws Exception {
        Requested requested = requested(15_000, null, null);
        doThrow(new BusinessException(ErrorCode.FIN_API_UNAVAILABLE)).when(approver).approve(any());
        String key = UUID.randomUUID().toString();

        approve(user, requested.paymentId(), key, body(creditCard))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("FIN_API_UNAVAILABLE"));

        PaymentTransaction processing = payments.findById(requested.paymentId()).orElseThrow();
        assertThat(processing.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(processing.getUserCardId()).isEqualTo(creditCard);
        assertThat(barcodes.find(requested.token()).orElseThrow().status()).isEqualTo(BarcodeStatus.SCANNED);

        approve(user, requested.paymentId(), UUID.randomUUID().toString(), body(creditCard))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_IN_PROGRESS"));
        approve(user, requested.paymentId(), key, body(creditCard))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_IN_PROGRESS"));

        // 출금 요청 번호가 없는 PROCESSING은 금융망에 간 적이 없다. 유예가 지나면 복구가 되돌리고, 마감도 지났으니 만료된다(Task 5.2.3)
        clock.advance(Duration.ofSeconds(120));
        approve(user, requested.paymentId(), UUID.randomUUID().toString(), body(creditCard))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_EXPIRED"));
        assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.EXPIRED);
    }

    // ───────── 소유·적격·본문 ─────────

    @Test
    void 남의_결제나_남의_카드는_404다() throws Exception {
        Requested requested = requested(15_000, null, null);

        approve(other, requested.paymentId(), UUID.randomUUID().toString(), "{}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        approve(user, requested.paymentId(), UUID.randomUUID().toString(), body(otherCard))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        long pending = fixtures.card(user, creditProduct, null, "PENDING");
        approve(user, requested.paymentId(), UUID.randomUUID().toString(), body(pending))
                .andExpect(status().isNotFound());
        long unregistered = fixtures.card(user, creditProduct, null, "UNREGISTERED");
        approve(user, requested.paymentId(), UUID.randomUUID().toString(), body(unregistered))
                .andExpect(status().isNotFound());
        assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REQUESTED);
    }

    @Test
    void 추천_제외_정지_만료_카드는_400_CARD_NOT_ELIGIBLE이다() throws Exception {
        Requested requested = requested(15_000, null, null);
        long inactive = fixtures.card(user, creditProduct, null, "INACTIVE");
        long suspended = fixtures.card(user, creditProduct, null, "ACTIVE");
        jdbc.update("UPDATE user_card SET issuer_status = 'SUSPENDED' WHERE user_card_id = ?", suspended);
        long expired = fixtures.card(user, creditProduct, null, "ACTIVE");
        jdbc.update("UPDATE user_card SET issuer_status = 'EXPIRED' WHERE user_card_id = ?", expired);

        for (long card : List.of(inactive, suspended, expired)) {
            approve(user, requested.paymentId(), UUID.randomUUID().toString(), body(card))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("CARD_NOT_ELIGIBLE"));
        }
        assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REQUESTED);
        assertThat(attempts.count(requested.paymentId())).isZero();
    }

    @Test
    void 할부_결제에_체크카드를_고르면_400_INVALID_INSTALLMENT다() throws Exception {
        Requested requested = requested(100_000, "INSTALLMENT", 3);

        approve(user, requested.paymentId(), UUID.randomUUID().toString(), body(checkCard))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INSTALLMENT"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("userCardId"));

        approve(user, requested.paymentId(), UUID.randomUUID().toString(), body(creditCard))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan").value("INSTALLMENT"))
                .andExpect(jsonPath("$.data.installmentMonths").value(3));
    }

    @Test
    void 생략했는데_결제할_수_있는_카드가_없으면_400이고_시도를_세지_않는다() throws Exception {
        long checkOnly = fixtures.user();
        fixtures.card(checkOnly, checkProduct, fixtures.account(checkOnly), "ACTIVE");
        Requested requested = requested(checkOnly, 100_000, "INSTALLMENT", 3);

        approve(checkOnly, requested.paymentId(), UUID.randomUUID().toString(), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CARD_NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.error.details.reason").value("NO_ELIGIBLE_CARD"));
        assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REQUESTED);
        assertThat(attempts.count(requested.paymentId())).isZero();
    }

    @Test
    void 본문에_plan이나_installmentMonths를_보내면_값이_null이어도_400_INVALID_INSTALLMENT다() throws Exception {
        Requested requested = requested(15_000, null, null);

        approve(user, requested.paymentId(), UUID.randomUUID().toString(), "{\"installmentMonths\": null}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INSTALLMENT"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("installmentMonths"));
        approve(user, requested.paymentId(), UUID.randomUUID().toString(), "{\"plan\": \"LUMP_SUM\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INSTALLMENT"));
        assertThat(payments.findById(requested.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REQUESTED);
    }

    @Test
    void 로그인하지_않으면_401이다() throws Exception {
        Requested requested = requested(15_000, null, null);

        mockMvc.perform(post("/api/payments/{paymentId}/approve", requested.paymentId())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ───────── 도우미 ─────────

    private ResultActions detail(long paymentId) throws Exception {
        return mockMvc.perform(get("/api/payments/" + paymentId).with(AuthUsers.asUser(user)))
                .andExpect(status().isOk());
    }

    private ResultActions approve(long userId, long paymentId, String idempotencyKey, String body) throws Exception {
        MockHttpServletRequestBuilder builder = post("/api/payments/{paymentId}/approve", paymentId)
                .with(AuthUsers.asUser(userId))
                .contentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        if (body != null) {
            builder.content(body);
        }
        return mockMvc.perform(builder);
    }

    /** 실적 인정액만 정한 승인. {@code recognized}가 null이면 인정액 미확인. */
    static ApprovalOutcome.Approved approvedWithPerformance(ApprovalCommand command, Long recognized) {
        long amount = command.terms().amount();
        return new ApprovalOutcome.Approved(amount, amount, recognized, ApprovalDates.performanceMonth(command.now()),
                List.of(), null, null, null, null);
    }

    /** 체크카드·혜택 0원 승인과 같은 모양: 승인액 = 원금, 실적 0, 청구 계획 없음. */
    static ApprovalOutcome.Approved approvedLike(ApprovalCommand command) {
        long amount = command.terms().amount();
        return new ApprovalOutcome.Approved(amount, amount, 0, ApprovalDates.performanceMonth(command.now()),
                List.of(), null, null, null);
    }

    /** 결제 행에 보관한 추천 결과의 추천 번호. 보관하지 않았으면 null. */
    private String storedRecommendationId(long paymentId) {
        return jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(recommendation_result_json, '$.recommendationId'))"
                + " FROM payment_transaction WHERE payment_transaction_id = ?", String.class, paymentId);
    }

    private static String body(long userCardId) {
        return "{\"userCardId\": " + userCardId + "}";
    }

    private Requested requested(long amount, String plan, Integer months) throws Exception {
        return requested(user, amount, plan, months);
    }

    /** POS 결제 요청 API로 REQUESTED 결제를 만든다. 바코드는 저장소에 직접 넣는다. */
    private Requested requested(long userId, long amount, String plan, Integer months) throws Exception {
        String token = issued(userId);
        Map<String, Object> body = new HashMap<>();
        body.put("barcodeToken", token);
        body.put("merchantId", cafe);
        body.put("posCode", "POS-TEST-CAFE");
        body.put("amount", amount);
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
}
