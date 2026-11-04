package com.yamu.be.payment.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.yamu.be.payment.barcode.Barcode;
import com.yamu.be.payment.barcode.BarcodeService;
import com.yamu.be.payment.barcode.BarcodeStatus;
import com.yamu.be.payment.barcode.BarcodeStore;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.redis.RedisLocks;
import com.yamu.be.payment.recommend.CardRecommender;
import com.yamu.be.payment.recommend.RecommendationResult;
import com.yamu.be.payment.recommend.RecommendationSnapshotStore;
import com.yamu.be.payment.request.PaymentRequestView;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

/**
 * POS 결제 요청 생성을 실제 MySQL·Redis로 확인한다. 추천은 명시적인 Mock fixture이고 금융망은 부르지 않는다.
 * 바코드는 발급 API 대신 저장소에 직접 넣는다(발급·본인 인증은 BarcodeIssueIntegrationTest가 확인).
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-pos-payment-request-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PosPaymentRequestIntegrationTest extends MySqlTestSupport {
    @org.springframework.boot.test.context.TestConfiguration
    static class RecommendationFixtureConfig {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        CardRecommender posRecommendationFixture(com.yamu.be.payment.lookup.PaymentCardLookup cards, java.time.Clock clock) {
            return new com.yamu.be.payment.recommend.MockCardRecommender(cards, clock);
        }
    }

    static final String POS_KEY = "test-pos-key";
    static final Duration RETENTION = Duration.ofMinutes(10);

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired BarcodeStore barcodes;
    @Autowired BarcodeService barcodeService;
    @Autowired PaymentTransactionRepository payments;
    @Autowired RedisLocks locks;
    @MockitoSpyBean RecommendationSnapshotStore snapshots;
    @MockitoSpyBean CardRecommender recommender;
    @MockitoSpyBean PosIdempotencyStore idempotency;

    OwnershipFixtures fixtures;
    long user;
    long other;
    long cafe;
    long shop;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM payment_transaction");
        jdbc.update("DELETE FROM user_card");
        jdbc.update("DELETE FROM linked_account");
        jdbc.update("DELETE FROM card_product");
        jdbc.update("DELETE FROM merchant");
        jdbc.update("DELETE FROM app_user");
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
        reset(snapshots, recommender, idempotency);
        fixtures = new OwnershipFixtures(jdbc);
        user = fixtures.user();
        other = fixtures.user();
        long product = fixtures.product();
        fixtures.card(user, product, fixtures.account(user), "ACTIVE");
        cafe = merchant("테스트 카페", "TEST_CAFE", "COFFEE_SHOP");
        shop = merchant("테스트 쇼핑몰", "TEST_SHOP", "ONLINE_SHOPPING");
    }

    // ───────── 정상 ─────────

    @Test
    void 유효한_바코드로_요청하면_201이고_결제가_REQUESTED로_저장되며_바코드는_SCANNED가_된다() throws Exception {
        String token = issued(user);
        String key = UUID.randomUUID().toString();

        MvcResult result = request(POS_KEY, key, body(token, cafe, "POS-TEST-CAFE", 15000, null, null, "POS-0001"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("REQUESTED"))
                .andExpect(jsonPath("$.data.orderNo").value("POS-0001"))
                .andReturn();
        long paymentId = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.data.paymentId")).longValue();

        PaymentTransaction payment = payments.findById(paymentId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
        assertThat(payment.getUserId()).isEqualTo(user);
        assertThat(payment.getMerchantId()).isEqualTo(cafe);
        assertThat(payment.getRequestedAmount()).isEqualTo(15_000);
        assertThat(payment.getInstallmentMonths()).isEqualTo(1);
        assertThat(payment.getRequestKey()).isEqualTo(key);
        assertThat(payment.getPaymentChannel().name()).isEqualTo("OFFLINE");
        assertThat(payment.getFacilityType().name()).isEqualTo("GENERAL");
        // MySQL이 JSON을 다시 정리해 저장하므로(공백 등) 문자열이 아니라 값으로 비교한다
        com.fasterxml.jackson.databind.JsonNode snapshot = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(payment.getMerchantContextSnapshot());
        assertThat(snapshot.get("posCode").asText()).isEqualTo("POS-TEST-CAFE");
        assertThat(snapshot.get("orderNo").asText()).isEqualTo("POS-0001");
        assertThat(snapshot.get("brandCode").asText()).isEqualTo("TEST_CAFE");
        assertThat(snapshot.get("amount").asLong()).isEqualTo(15_000);
        // 응답 시각은 초 단위로 내려간다(UtcLocalDateTimeSerializer). DB 시각은 마이크로초라 초로 맞춰 비교한다
        String deadline = JsonPath.read(result.getResponse().getContentAsString(), "$.data.confirmDeadline");
        assertThat(LocalDateTime.parse(deadline.replace("Z", "")))
                .isEqualTo(payment.getRequestedAt().plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));

        Barcode barcode = barcodes.find(token).orElseThrow();
        assertThat(barcode.status()).isEqualTo(BarcodeStatus.SCANNED);
        assertThat(barcode.paymentId()).isEqualTo(paymentId);
        assertThat(snapshots.find(paymentId)).isPresent();
    }

    @Test
    void 스캔된_뒤_앱의_바코드_조회에는_결제_조건과_추천_상위_2장이_보인다() throws Exception {
        String token = issued(user);
        request(POS_KEY, UUID.randomUUID().toString(), body(token, cafe, "POS-TEST-CAFE-DEPT", 100000, "INSTALLMENT", 3, null))
                .andExpect(status().isCreated());

        BarcodeService.State state = barcodeService.get(user, token);

        assertThat(state.status()).isEqualTo(BarcodeStatus.SCANNED);
        PaymentRequestView payment = state.payment();
        assertThat(payment).isNotNull();
        assertThat(payment.status()).isEqualTo(PaymentStatus.REQUESTED);
        assertThat(payment.amount()).isEqualTo(100_000);
        assertThat(payment.plan().name()).isEqualTo("INSTALLMENT");
        assertThat(payment.installmentMonths()).isEqualTo(3);
        assertThat(payment.merchant().merchantId()).isEqualTo(cafe);
        assertThat(payment.merchant().name()).isEqualTo("테스트 카페");
        assertThat(payment.merchant().categoryCode()).isEqualTo("COFFEE_SHOP");
        assertThat(payment.recommendation()).isNotNull();
        assertThat(payment.recommendation().status()).isEqualTo(RecommendationResult.Status.READY);
        assertThat(payment.recommendation().uncomparedCards()).isEmpty();
        assertThat(payment.recommendation().noBenefitNotice()).isNull();
        assertThat(payment.recommendation().candidates()).hasSize(1);
        assertThat(payment.recommendation().candidates().get(0).rank()).isEqualTo(1);
        assertThat(payment.recommendation().candidates().get(0).maskedNumber()).isEqualTo("****-****-****-1234");
        // 결제할 금액은 결제 조건으로 서버가 계산한다(Mock은 즉시할인 0원)
        assertThat(payment.recommendation().candidates().get(0).chargedAmount()).isEqualTo(100_000);
    }

    @Test
    void 남의_바코드는_조회해도_결제가_보이지_않는다() throws Exception {
        String token = issued(user);
        request(POS_KEY, UUID.randomUUID().toString(), body(token, cafe, "POS-TEST-CAFE", 15000, null, null, null))
                .andExpect(status().isCreated());

        assertThatThrownBy(() -> barcodeService.get(other, token))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BARCODE_NOT_FOUND));
    }

    @Test
    void 할부인데_체크카드만_가진_사용자는_201이고_화면_후보는_0장_NO_ELIGIBLE_CARD다() throws Exception {
        long checkOnly = fixtures.user();
        long checkProduct = fixtures.product();
        jdbc.update("UPDATE card_product SET card_type = 'CHECK', billing_cutoff_days = NULL WHERE card_product_id = ?",
                checkProduct);
        fixtures.card(checkOnly, checkProduct, fixtures.account(checkOnly), "ACTIVE");
        String token = issued(checkOnly);

        request(POS_KEY, UUID.randomUUID().toString(), body(token, cafe, "POS-TEST-CAFE", 100000, "INSTALLMENT", 3, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("REQUESTED"));

        PaymentRequestView.Recommendation recommendation = barcodeService.get(checkOnly, token).payment()
                .recommendation();
        assertThat(recommendation.status()).isEqualTo(RecommendationResult.Status.NO_ELIGIBLE_CARD);
        assertThat(recommendation.candidates()).isEmpty();
        assertThat(recommendation.excludedCount()).isEqualTo(1);
    }

    @Test
    void 추천_스냅샷이_만료되면_화면의_recommendation은_null이다() throws Exception {
        String token = issued(user);
        long paymentId = paymentId(request(POS_KEY, UUID.randomUUID().toString(),
                body(token, cafe, "POS-TEST-CAFE", 15000, null, null, null)).andExpect(status().isCreated()));

        snapshots.delete(paymentId);

        PaymentRequestView payment = barcodeService.get(user, token).payment();
        assertThat(payment).isNotNull();
        assertThat(payment.recommendation()).isNull();
    }

    // ───────── 멱등 ─────────

    @Test
    void 같은_키_같은_본문을_다시_보내면_같은_paymentId이고_결제는_하나다() throws Exception {
        String token = issued(user);
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = body(token, cafe, "POS-TEST-CAFE", 15000, null, null, "POS-0002");

        String first = request(POS_KEY, key, body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = request(POS_KEY, key, body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<Object>read(second, "$.data.paymentId")).isEqualTo(JsonPath.read(first, "$.data.paymentId"));
        assertThat(JsonPath.<String>read(second, "$.data.orderNo")).isEqualTo("POS-0002");
        assertThat(payments.count()).isEqualTo(1);
    }

    @Test
    void 같은_키에_다른_본문이면_409_IDEMPOTENCY_KEY_REUSED() throws Exception {
        String token = issued(user);
        String key = UUID.randomUUID().toString();
        request(POS_KEY, key, body(token, cafe, "POS-TEST-CAFE", 15000, null, null, null)).andExpect(status().isCreated());

        request(POS_KEY, key, body(token, cafe, "POS-TEST-CAFE", 16000, null, null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void 결과_기억이_실패해도_201이고_같은_키_재전송은_DB_기록으로_같은_결과다() throws Exception {
        String token = issued(user);
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = body(token, cafe, "POS-TEST-CAFE", 15000, null, null, "POS-0003");
        doThrow(new IllegalStateException("redis down")).when(idempotency).remember(any(), any(), any());

        long first = paymentId(request(POS_KEY, key, body).andExpect(status().isCreated()));
        reset(idempotency);

        // Redis에는 기록이 없고 바코드는 이미 SCANNED지만, 같은 키·같은 본문이라 409가 아니라 처음 결과다
        request(POS_KEY, key, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.paymentId").value(first))
                .andExpect(jsonPath("$.data.orderNo").value("POS-0003"));
        assertThat(payments.count()).isEqualTo(1);
    }

    @Test
    void Redis_기록이_없을_때_같은_키에_다른_본문이면_DB_대조로_409_IDEMPOTENCY_KEY_REUSED() throws Exception {
        String token = issued(user);
        String key = UUID.randomUUID().toString();
        request(POS_KEY, key, body(token, cafe, "POS-TEST-CAFE", 15000, null, null, null)).andExpect(status().isCreated());
        redis.delete(redis.keys("pos:payreq:idem:*"));

        request(POS_KEY, key, body(token, cafe, "POS-TEST-CAFE", 16000, null, null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(payments.count()).isEqualTo(1);
    }

    @Test
    void 다른_POS가_같은_키를_쓰면_409_IDEMPOTENCY_KEY_REUSED() throws Exception {
        String key = UUID.randomUUID().toString();
        request(POS_KEY, key, body(issued(user), cafe, "POS-TEST-CAFE", 15000, null, null, null))
                .andExpect(status().isCreated());

        // POS별 Redis 기록·잠금은 따로지만 request_key는 전체에서 하나라 DB에서 걸린다
        request(POS_KEY, key, body(issued(user), cafe, "POS-TEST-CAFE-DEPT", 15000, null, null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(payments.count()).isEqualTo(1);
    }

    @Test
    void 같은_키를_처리_중이면_409_IDEMPOTENCY_IN_PROGRESS() throws Exception {
        String token = issued(user);
        String key = UUID.randomUUID().toString();
        try (RedisLocks.Lock held = locks.tryLock(idempotency.lockKey("POS-TEST-CAFE", key), Duration.ofSeconds(15))
                .orElseThrow()) {
            request(POS_KEY, key, body(token, cafe, "POS-TEST-CAFE", 15000, null, null, null))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_IN_PROGRESS"));
        }
        assertThat(payments.count()).isZero();
        assertThat(barcodes.find(token).orElseThrow().status()).isEqualTo(BarcodeStatus.ISSUED);
    }

    @Test
    void Idempotency_Key가_없거나_UUID가_아니면_400() throws Exception {
        String token = issued(user);

        request(POS_KEY, null, body(token, cafe, "POS-TEST-CAFE", 15000, null, null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        request(POS_KEY, "not-a-uuid", body(token, cafe, "POS-TEST-CAFE", 15000, null, null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    // ───────── 바코드 ─────────

    @Test
    void 같은_바코드로_동시에_요청하면_한_건만_성공하고_결제도_하나다() throws Exception {
        String token = issued(user);
        int attempts = 5;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> results = new java.util.ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                Callable<MvcResult> call = () -> {
                    ready.countDown();
                    go.await();
                    return request(POS_KEY, UUID.randomUUID().toString(),
                            body(token, cafe, "POS-TEST-CAFE", 15000, null, null, null)).andReturn();
                };
                results.add(pool.submit(call));
            }
            ready.await();
            go.countDown();
            List<Integer> statuses = new java.util.ArrayList<>();
            List<String> conflictCodes = new java.util.ArrayList<>();
            for (Future<MvcResult> f : results) {
                MvcResult result = f.get();
                statuses.add(result.getResponse().getStatus());
                if (result.getResponse().getStatus() == 409) {
                    conflictCodes.add(JsonPath.read(result.getResponse().getContentAsString(), "$.error.code"));
                }
            }
            assertThat(statuses).containsOnlyOnce(201);
            assertThat(conflictCodes).hasSize(attempts - 1).containsOnly("BARCODE_ALREADY_USED");
        } finally {
            pool.shutdownNow();
        }
        assertThat(payments.count()).isEqualTo(1);
        assertThat(barcodes.find(token).orElseThrow().status()).isEqualTo(BarcodeStatus.SCANNED);
    }

    @Test
    void 이미_스캔된_바코드는_409_BARCODE_ALREADY_USED() throws Exception {
        String token = issued(user);
        request(POS_KEY, UUID.randomUUID().toString(), body(token, cafe, "POS-TEST-CAFE", 15000, null, null, null))
                .andExpect(status().isCreated());

        request(POS_KEY, UUID.randomUUID().toString(), body(token, cafe, "POS-TEST-CAFE", 15000, null, null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BARCODE_ALREADY_USED"));
        assertThat(payments.count()).isEqualTo(1);
    }

    @Test
    void 만료된_바코드는_410이고_없는_바코드는_404이며_결제가_생기지_않는다() throws Exception {
        String expired = "1111222233334444";
        Instant issuedAt = Instant.now().minusSeconds(120);
        barcodes.issue(new Barcode(expired, user, BarcodeStatus.ISSUED, issuedAt, issuedAt.plusSeconds(60)), RETENTION);

        request(POS_KEY, UUID.randomUUID().toString(), body(expired, cafe, "POS-TEST-CAFE", 15000, null, null, null))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("BARCODE_EXPIRED"));
        request(POS_KEY, UUID.randomUUID().toString(), body("9999888877776666", cafe, "POS-TEST-CAFE", 15000, null, null, null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BARCODE_NOT_FOUND"));
        request(POS_KEY, UUID.randomUUID().toString(), body("not-a-token", cafe, "POS-TEST-CAFE", 15000, null, null, null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BARCODE_NOT_FOUND"));
        assertThat(payments.count()).isZero();
    }

    // ───────── 실패 뒤 되돌리기 ─────────

    @Test
    void 추천_계산이_예외로_끝나면_결제는_롤백되고_바코드는_ISSUED로_돌아온다() throws Exception {
        String token = issued(user);
        doThrow(new IllegalStateException("추천 엔진 오류")).when(recommender).recommend(any());

        request(POS_KEY, UUID.randomUUID().toString(), body(token, cafe, "POS-TEST-CAFE", 15000, null, null, null))
                .andExpect(status().isInternalServerError());

        assertThat(payments.count()).isZero();
        Barcode barcode = barcodes.find(token).orElseThrow();
        assertThat(barcode.status()).isEqualTo(BarcodeStatus.ISSUED);
        assertThat(barcode.paymentId()).isNull();
    }

    @Test
    void 추천_스냅샷_저장이_실패하면_결제는_롤백되고_바코드는_ISSUED로_돌아온다() throws Exception {
        String token = issued(user);
        doThrow(new IllegalStateException("redis down")).when(snapshots).save(anyLong(), any(), any());

        request(POS_KEY, UUID.randomUUID().toString(), body(token, cafe, "POS-TEST-CAFE", 15000, null, null, null))
                .andExpect(status().isInternalServerError());

        assertThat(payments.count()).isZero();
        assertThat(barcodes.find(token).orElseThrow().status()).isEqualTo(BarcodeStatus.ISSUED);

        // 되돌린 바코드로 다시 요청하면 정상 처리된다
        reset(snapshots);
        request(POS_KEY, UUID.randomUUID().toString(), body(token, cafe, "POS-TEST-CAFE", 15000, null, null, null))
                .andExpect(status().isCreated());
        assertThat(payments.count()).isEqualTo(1);
    }

    // ───────── POS 인증·가맹점 ─────────

    @Test
    void POS_키가_없으면_본문_검증보다_먼저_401이다() throws Exception {
        Map<String, Object> missing = new HashMap<>();
        missing.put("posCode", "POS-TEST-CAFE");

        request(null, UUID.randomUUID().toString(), missing)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_POS_KEY"));
    }

    @Test
    void POS_키가_없거나_다르면_401이고_결제가_생기지_않는다() throws Exception {
        String token = issued(user);

        request(null, UUID.randomUUID().toString(), body(token, cafe, "POS-TEST-CAFE", 15000, null, null, null))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_POS_KEY"));
        request("wrong-key", UUID.randomUUID().toString(), body(token, cafe, "POS-TEST-CAFE", 15000, null, null, null))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_POS_KEY"));
        assertThat(payments.count()).isZero();
        assertThat(barcodes.find(token).orElseThrow().status()).isEqualTo(BarcodeStatus.ISSUED);
    }

    @Test
    void posCode가_설정에_없거나_merchantId가_그_시나리오의_브랜드와_다르면_404() throws Exception {
        String token = issued(user);

        request(POS_KEY, UUID.randomUUID().toString(), body(token, cafe, "POS-UNKNOWN", 15000, null, null, null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("MERCHANT_NOT_FOUND"));
        request(POS_KEY, UUID.randomUUID().toString(), body(token, shop, "POS-TEST-CAFE", 15000, null, null, null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("MERCHANT_NOT_FOUND"));
        assertThat(payments.count()).isZero();
    }

    @Test
    void 할부_조건_위반은_400_INVALID_INSTALLMENT이고_fieldErrors가_있다() throws Exception {
        String token = issued(user);

        request(POS_KEY, UUID.randomUUID().toString(), body(token, cafe, "POS-TEST-CAFE", 49999, "INSTALLMENT", 3, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INSTALLMENT"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("amount"));
        request(POS_KEY, UUID.randomUUID().toString(), body(token, cafe, "POS-TEST-CAFE", 0, null, null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_AMOUNT"));
        assertThat(payments.count()).isZero();
        assertThat(barcodes.find(token).orElseThrow().status()).isEqualTo(BarcodeStatus.ISSUED);
    }

    @Test
    void 필수값이_빠지면_400_INVALID_REQUEST() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("merchantId", cafe);
        body.put("posCode", "POS-TEST-CAFE");
        body.put("amount", 15000);

        request(POS_KEY, UUID.randomUUID().toString(), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("barcodeToken"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    // ───────── 도우미 ─────────

    private static long paymentId(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.data.paymentId"))
                .longValue();
    }

    private ResultActions request(String posKey, String idempotencyKey, Map<String, Object> body) throws Exception {
        var builder = post("/api/pos/payment-requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body));
        if (posKey != null) {
            builder.header("X-POS-Key", posKey);
        }
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(builder);
    }

    private static Map<String, Object> body(String token, long merchantId, String posCode, long amount, String plan,
            Integer months, String orderNo) {
        Map<String, Object> body = new HashMap<>();
        body.put("barcodeToken", token);
        body.put("merchantId", merchantId);
        body.put("posCode", posCode);
        body.put("amount", amount);
        if (plan != null) {
            body.put("plan", plan);
        }
        if (months != null) {
            body.put("installmentMonths", months);
        }
        if (orderNo != null) {
            body.put("orderNo", orderNo);
        }
        return body;
    }

    /** 발급 API 대신 저장소에 직접 넣은 ISSUED 바코드. 토큰은 사용자마다 다르게. */
    private String issued(long userId) {
        String token = String.format("%016d", 1_000_000_000_000_000L + userId * 7 + payments.count());
        Instant now = Instant.now();
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
}
