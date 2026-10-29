package com.yamu.be.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.yamu.be.auth.StepUpTokenStore;
import com.yamu.be.auth.StepUpTokenStore.StepUpGrant;
import com.yamu.be.auth.domain.AuthMethod;
import com.yamu.be.auth.domain.StepUpPurpose;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.LookupHasher;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.MySqlTestSupport;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

/**
 * 카드 확인 → 본인 인증 → 카드 등록을 실제 MySQL·Redis로 확인한다.
 * 사용자 명의 카드는 시드처럼 user_card PENDING 행(카드번호 암호문·조회 해시)으로 미리 넣는다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-card-register-integration-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CardRegistrationIntegrationTest extends MySqlTestSupport {

    private static final String CHECK_NO = "9410123456785678";
    private static final String CREDIT_NO = "9411000011112222";
    private static final String UNKNOWN_NO = "9419999999999999";

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PersonalDataCipher cipher;
    @Autowired LookupHasher lookupHasher;
    @Autowired StepUpTokenStore stepUpTokenStore;

    long checkProduct;
    long creditProduct;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM card_monthly_performance");
        jdbc.update("DELETE FROM user_card");
        jdbc.update("DELETE FROM card_product");
        jdbc.update("DELETE FROM linked_account");
        jdbc.update("DELETE FROM step_up_audit_log");
        jdbc.update("DELETE FROM user_device_event");
        jdbc.update("DELETE FROM user_device");
        jdbc.update("DELETE FROM app_user");
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
        checkProduct = product("신한 카페 체크", "CHECK", null);
        creditProduct = product("신한카드 Mr.Life", "CREDIT", 14);
    }

    // ───────── 성공 흐름 ─────────

    @Test
    void 체크카드를_확인하고_본인_인증_후_연결_계좌와_함께_등록한다() throws Exception {
        Member member = member(StepUpPurpose.CARD_REGISTER);
        long account = account(member.userId(), "5934");
        long card = pendingCard(member.userId(), checkProduct, CHECK_NO, account);

        MvcResult looked = lookup(member, CHECK_NO, "12", "29")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.issuer.code").value("SHINHAN"))
                .andExpect(jsonPath("$.data.issuer.name").value("신한카드"))
                .andExpect(jsonPath("$.data.issuer.closingDayOffset").doesNotExist())
                .andExpect(jsonPath("$.data.product.cardProductId").value(checkProduct))
                .andExpect(jsonPath("$.data.product.cardType").value("CHECK"))
                .andExpect(jsonPath("$.data.maskedNumber").value("9410-****-****-5678"))
                .andExpect(jsonPath("$.data.holderMatched").value(true))
                .andExpect(jsonPath("$.data.hasPriorHistory").value(false))
                .andExpect(jsonPath("$.data.linkedAccount.linkedAccountId").value(account))
                .andExpect(jsonPath("$.data.linkedAccount.maskedAccountNo").value("****-****-****-5934"))
                .andExpect(jsonPath("$.data.linkedAccount.linked").value(true))
                .andExpect(jsonPath("$.data.alreadyRegistered").value(false))
                .andExpect(jsonPath("$.data.expiresAt").exists())
                .andReturn();
        String token = read(looked, "$.data.verificationToken");
        assertThat(token).startsWith("cvt_");
        assertThat(looked.getResponse().getContentAsString()).doesNotContain(CHECK_NO);

        MvcResult registered = register(member, member.stepUpToken(),
                "{\"verificationToken\":\"" + token + "\",\"linkedAccountId\":" + account + "}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.userCardId").value(card))
                .andExpect(jsonPath("$.data.nickname").value("신한 카페 체크"))
                .andExpect(jsonPath("$.data.productName").value("신한 카페 체크"))
                .andExpect(jsonPath("$.data.cardType").value("CHECK"))
                .andExpect(jsonPath("$.data.maskedNumber").value("9410-****-****-5678"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.isDefault").value(false))
                .andExpect(jsonPath("$.data.linkedAccountId").value(account))
                .andExpect(jsonPath("$.data.billing").doesNotExist())
                .andExpect(jsonPath("$.data.initialPerformance.source").value("NONE"))
                .andExpect(jsonPath("$.data.registeredAt").exists())
                .andReturn();
        assertThat(registered.getResponse().getContentAsString()).doesNotContain(CHECK_NO);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, linked_account_id, payment_day, card_alias, registered_at FROM user_card WHERE user_card_id = ?",
                card);
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(((Number) row.get("linked_account_id")).longValue()).isEqualTo(account);
        assertThat(row.get("payment_day")).isNull();
        assertThat(row.get("card_alias")).isNull();
        assertThat(row.get("registered_at")).isNotNull();
        assertThat(redis.keys("card:verify:*")).isEmpty();
    }

    @Test
    void 신용카드는_결제일과_결제계좌를_받고_집계된_실적이_있으면_SEED로_보여_준다() throws Exception {
        Member member = member(StepUpPurpose.CARD_REGISTER);
        long account = account(member.userId(), "1111");
        long card = pendingCard(member.userId(), creditProduct, CREDIT_NO, null);
        YearMonth current = YearMonth.now(ZoneId.of("Asia/Seoul"));
        performance(card, current.minusMonths(1), 320000L);
        performance(card, current, 180000L);

        String token = read(lookup(member, CREDIT_NO, "01", "30")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.issuer.closingDayOffset").value(14))
                .andExpect(jsonPath("$.data.product.cardType").value("CREDIT"))
                .andExpect(jsonPath("$.data.linkedAccount").doesNotExist())
                .andReturn(), "$.data.verificationToken");

        register(member, member.stepUpToken(), "{\"verificationToken\":\"" + token + "\",\"nickname\":\" 생활비 카드 \","
                + "\"paymentDay\":25,\"settlementAccountId\":" + account + "}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.nickname").value("생활비 카드"))
                .andExpect(jsonPath("$.data.cardType").value("CREDIT"))
                .andExpect(jsonPath("$.data.linkedAccountId").value(account))
                .andExpect(jsonPath("$.data.billing.paymentDay").value(25))
                .andExpect(jsonPath("$.data.billing.closingDayOffset").value(14))
                .andExpect(jsonPath("$.data.billing.settlementAccountId").value(account))
                .andExpect(jsonPath("$.data.billing.creditLimit").doesNotExist())
                .andExpect(jsonPath("$.data.initialPerformance.source").value("SEED"))
                .andExpect(jsonPath("$.data.initialPerformance.prevMonth.yearMonth").value(current.minusMonths(1).toString()))
                .andExpect(jsonPath("$.data.initialPerformance.prevMonth.recognizedAmount").value(320000))
                .andExpect(jsonPath("$.data.initialPerformance.currentMonth.recognizedAmount").value(180000));

        assertThat(jdbc.queryForObject("SELECT payment_day FROM user_card WHERE user_card_id = ?", Integer.class, card))
                .isEqualTo(25);
        assertThat(jdbc.queryForObject("SELECT card_alias FROM user_card WHERE user_card_id = ?", String.class, card))
                .isEqualTo("생활비 카드");
    }

    @Test
    void 등록_해제했던_카드는_같은_행을_다시_ACTIVE로_만들고_처음_등록_시각은_그대로_둔다() throws Exception {
        Member member = member(StepUpPurpose.CARD_REGISTER);
        long account = account(member.userId(), "5934");
        long card = pendingCard(member.userId(), checkProduct, CHECK_NO, account);
        Timestamp first = Timestamp.valueOf(LocalDateTime.of(2026, 9, 1, 3, 0));
        jdbc.update("UPDATE user_card SET status = 'UNREGISTERED', registered_at = ? WHERE user_card_id = ?", first, card);

        String token = read(lookup(member, CHECK_NO, "12", "29").andExpect(status().isOk()).andReturn(),
                "$.data.verificationToken");
        register(member, member.stepUpToken(), "{\"verificationToken\":\"" + token + "\",\"linkedAccountId\":" + account + "}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.userCardId").value(card));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_card", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT registered_at FROM user_card WHERE user_card_id = ?", Timestamp.class, card))
                .isEqualTo(first);
    }

    // ───────── 카드 확인 실패 ─────────

    @Test
    void 카드번호가_숫자_16자리가_아니면_INVALID_CARD_NUMBER다() throws Exception {
        Member member = member(StepUpPurpose.CARD_REGISTER);

        lookup(member, "941012345678567", "12", "29").andExpect(errorCode(400, "INVALID_CARD_NUMBER"));
        lookup(member, "9410-1234-5678-5678", "12", "29").andExpect(errorCode(400, "INVALID_CARD_NUMBER"));
    }

    @Test
    void 유효기간_형식이_틀리면_입력_오류이고_지난_유효기간은_CARD_VERIFICATION_FAILED다() throws Exception {
        Member member = member(StepUpPurpose.CARD_REGISTER);
        pendingCard(member.userId(), checkProduct, CHECK_NO, null);
        YearMonth lastMonth = YearMonth.now(ZoneId.of("Asia/Seoul")).minusMonths(1);

        lookup(member, CHECK_NO, "13", "29").andExpect(errorCode(400, "INVALID_REQUEST"));
        lookup(member, CHECK_NO, "12", "2029").andExpect(errorCode(400, "INVALID_REQUEST"));
        lookup(member, CHECK_NO, String.format("%02d", lastMonth.getMonthValue()),
                String.format("%02d", lastMonth.getYear() % 100))
                .andExpect(errorCode(400, "CARD_VERIFICATION_FAILED"));
    }

    @Test
    void 사용자_명의_카드_데이터에_없거나_남의_카드면_CARD_NOT_FOUND다() throws Exception {
        Member member = member(StepUpPurpose.CARD_REGISTER);
        long stranger = user("01055556666");
        pendingCard(stranger, checkProduct, CHECK_NO, null);

        lookup(member, UNKNOWN_NO, "12", "29").andExpect(errorCode(404, "CARD_NOT_FOUND"));
        lookup(member, CHECK_NO, "12", "29").andExpect(errorCode(404, "CARD_NOT_FOUND"));
        assertThat(redis.keys("card:verify:*")).isEmpty();
    }

    @Test
    void 이미_등록한_카드는_CARD_ALREADY_REGISTERED다() throws Exception {
        Member member = member(StepUpPurpose.CARD_REGISTER);
        long card = pendingCard(member.userId(), checkProduct, CHECK_NO, null);
        jdbc.update("UPDATE user_card SET status = 'INACTIVE' WHERE user_card_id = ?", card);

        lookup(member, CHECK_NO, "12", "29").andExpect(errorCode(409, "CARD_ALREADY_REGISTERED"));
    }

    // ───────── 카드 등록 실패 ─────────

    @Test
    void 결제일이_없거나_1부터_27_밖이면_INVALID_PAYMENT_DAY이고_고쳐서_다시_보내면_등록된다() throws Exception {
        Member member = member(StepUpPurpose.CARD_REGISTER);
        long account = account(member.userId(), "1111");
        pendingCard(member.userId(), creditProduct, CREDIT_NO, null);
        String token = verified(member, CREDIT_NO);

        for (String day : List.of("", ",\"paymentDay\":0", ",\"paymentDay\":28")) {
            register(member, member.stepUpToken(),
                    "{\"verificationToken\":\"" + token + "\",\"settlementAccountId\":" + account + day + "}")
                    .andExpect(errorCode(400, "INVALID_PAYMENT_DAY"));
        }
        // 입력 오류에서는 확인 토큰·본인 인증 토큰을 쓰지 않는다.
        register(member, member.stepUpToken(),
                "{\"verificationToken\":\"" + token + "\",\"paymentDay\":27,\"settlementAccountId\":" + account + "}")
                .andExpect(status().isCreated());
    }

    @Test
    void 출금_계좌가_없으면_ACCOUNT_REQUIRED이고_남의_계좌나_해제한_계좌는_404다() throws Exception {
        Member member = member(StepUpPurpose.CARD_REGISTER);
        pendingCard(member.userId(), checkProduct, CHECK_NO, null);
        long disconnected = account(member.userId(), "2222");
        jdbc.update("UPDATE linked_account SET status = 'DISCONNECTED', disconnected_at = ? WHERE linked_account_id = ?",
                now(), disconnected);
        long strangerAccount = account(user("01055556666"), "3333");
        String token = verified(member, CHECK_NO);

        register(member, member.stepUpToken(), "{\"verificationToken\":\"" + token + "\"}")
                .andExpect(errorCode(400, "ACCOUNT_REQUIRED"));
        // 체크카드에 결제계좌(신용카드용)만 보내도 연결 계좌가 없는 것이다.
        register(member, member.stepUpToken(), "{\"verificationToken\":\"" + token + "\",\"settlementAccountId\":" + disconnected + "}")
                .andExpect(errorCode(400, "ACCOUNT_REQUIRED"));
        register(member, member.stepUpToken(), "{\"verificationToken\":\"" + token + "\",\"linkedAccountId\":" + disconnected + "}")
                .andExpect(errorCode(404, "RESOURCE_NOT_FOUND"));
        register(member, member.stepUpToken(), "{\"verificationToken\":\"" + token + "\",\"linkedAccountId\":" + strangerAccount + "}")
                .andExpect(errorCode(404, "RESOURCE_NOT_FOUND"));
    }

    @Test
    void 본인_인증_토큰이_없거나_다른_용도면_STEP_UP_REQUIRED이고_확인_토큰은_남는다() throws Exception {
        Member member = member(StepUpPurpose.ACCOUNT_LINK);
        long account = account(member.userId(), "5934");
        pendingCard(member.userId(), checkProduct, CHECK_NO, account);
        String token = verified(member, CHECK_NO);
        String body = "{\"verificationToken\":\"" + token + "\",\"linkedAccountId\":" + account + "}";

        register(member, null, body).andExpect(errorCode(403, "STEP_UP_REQUIRED"));
        register(member, member.stepUpToken(), body).andExpect(errorCode(403, "STEP_UP_REQUIRED"));

        register(member, stepUp(member, StepUpPurpose.CARD_REGISTER), body).andExpect(status().isCreated());
    }

    @Test
    void 확인_토큰은_한_번만_쓰고_남의_토큰이나_없는_토큰은_VERIFICATION_TOKEN_EXPIRED다() throws Exception {
        Member member = member(StepUpPurpose.CARD_REGISTER);
        long account = account(member.userId(), "5934");
        pendingCard(member.userId(), checkProduct, CHECK_NO, account);
        String token = verified(member, CHECK_NO);
        String body = "{\"verificationToken\":\"" + token + "\",\"linkedAccountId\":" + account + "}";

        Member stranger = otherMember();
        register(stranger, stranger.stepUpToken(), body).andExpect(errorCode(400, "VERIFICATION_TOKEN_EXPIRED"));
        register(member, member.stepUpToken(), "{\"verificationToken\":\"cvt_nothing\",\"linkedAccountId\":" + account + "}")
                .andExpect(errorCode(400, "VERIFICATION_TOKEN_EXPIRED"));

        register(member, member.stepUpToken(), body).andExpect(status().isCreated());
        register(member, stepUp(member, StepUpPurpose.CARD_REGISTER), body)
                .andExpect(errorCode(400, "VERIFICATION_TOKEN_EXPIRED"));
    }

    @Test
    void 같은_확인_토큰으로_동시에_두_번_등록하면_한_번만_성공한다() throws Exception {
        Member member = member(StepUpPurpose.CARD_REGISTER);
        long account = account(member.userId(), "5934");
        long card = pendingCard(member.userId(), checkProduct, CHECK_NO, account);
        String token = verified(member, CHECK_NO);
        String body = "{\"verificationToken\":\"" + token + "\",\"linkedAccountId\":" + account + "}";
        List<String> stepUps = List.of(member.stepUpToken(), stepUp(member, StepUpPurpose.CARD_REGISTER));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (String stepUp : stepUps) {
                Callable<Integer> call = () -> {
                    start.await();
                    return register(member, stepUp, body).andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsExactlyInAnyOrder(201, 400);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM user_card WHERE user_card_id = ?", String.class, card))
                .isEqualTo("ACTIVE");
    }

    // ───────── 준비 ─────────

    private record Member(long userId, long deviceId, String stepUpToken) {
    }

    private Member member(StepUpPurpose purpose) {
        long userId = user("01012345678");
        long deviceId = insert("user_device", "user_device_id", Map.of("user_id", userId,
                "device_uuid", UUID.randomUUID().toString(), "platform", "ANDROID", "public_key", "test-key",
                "key_algorithm", "SHA256withECDSA", "created_at", now(), "updated_at", now()));
        Member member = new Member(userId, deviceId, null);
        return new Member(userId, deviceId, stepUp(member, purpose));
    }

    private Member otherMember() {
        long userId = user("01077778888");
        long deviceId = insert("user_device", "user_device_id", Map.of("user_id", userId,
                "device_uuid", UUID.randomUUID().toString(), "platform", "ANDROID", "public_key", "test-key",
                "key_algorithm", "SHA256withECDSA", "created_at", now(), "updated_at", now()));
        Member member = new Member(userId, deviceId, null);
        return new Member(userId, deviceId, stepUp(member, StepUpPurpose.CARD_REGISTER));
    }

    private String stepUp(Member member, StepUpPurpose purpose) {
        long auditLogId = insert("step_up_audit_log", "step_up_audit_log_id", Map.of("user_id", member.userId(),
                "user_device_id", member.deviceId(), "auth_method", "PIN", "purpose", purpose.name(),
                "result", "SUCCESS", "created_at", now()));
        return stepUpTokenStore.issue(new StepUpGrant(member.userId(), member.deviceId(), purpose, AuthMethod.PIN,
                auditLogId));
    }

    private long user(String phone) {
        return insert("app_user", "user_id", new HashMap<>(Map.of(
                "name", "김야무",
                "birth_date", LocalDate.of(1999, 3, 15),
                "phone_enc", cipher.encrypt(phone, CipherField.APP_USER_PHONE),
                "phone_hash", lookupHasher.phone(phone),
                "identity_verified_at", now(),
                "pin_hash", "$2a$10$test",
                "pin_updated_at", now(),
                "status", "ACTIVE",
                "created_at", now(),
                "updated_at", now())));
    }

    private long account(long owner, String last4) {
        String accountNo = "088154114975" + last4;
        return insert("linked_account", "linked_account_id", Map.of("user_id", owner, "bank_code", "088",
                "account_no_enc", cipher.encrypt(accountNo, CipherField.LINKED_ACCOUNT_NO),
                "account_no_hash", lookupHasher.account("088", accountNo), "account_last4", last4,
                "connected_at", now(), "created_at", now(), "updated_at", now()));
    }

    private long product(String name, String type, Integer cutoff) {
        Map<String, Object> values = new HashMap<>(Map.of("issuer_code", "SHINHAN", "product_name", name,
                "card_type", type, "created_at", now(), "updated_at", now()));
        if (cutoff != null) {
            values.put("billing_cutoff_days", cutoff);
        }
        return insert("card_product", "card_product_id", values);
    }

    /** 시드처럼 미리 넣어 둔 사용자 명의 카드(PENDING). */
    private long pendingCard(long owner, long product, String cardNo, Long account) {
        Map<String, Object> values = new HashMap<>(Map.of("user_id", owner, "card_product_id", product,
                "card_no_enc", cipher.encrypt(cardNo, CipherField.USER_CARD_NO),
                "card_no_hash", lookupHasher.card(cardNo), "card_last4", cardNo.substring(12),
                "status", "PENDING", "created_at", now(), "updated_at", now()));
        if (account != null) {
            values.put("linked_account_id", account);
        }
        return insert("user_card", "user_card_id", values);
    }

    private void performance(long card, YearMonth month, long amount) {
        jdbc.update("""
                INSERT INTO card_monthly_performance (user_card_id, performance_month, recognized_amount, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """, card, Date.valueOf(month.atDay(1)), amount, now(), now());
    }

    private long insert(String table, String keyColumn, Map<String, Object> values) {
        return new SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns(keyColumn)
                .usingColumns(values.keySet().toArray(String[]::new))
                .executeAndReturnKey(values).longValue();
    }

    private String verified(Member member, String cardNo) throws Exception {
        return read(lookup(member, cardNo, "12", "29").andExpect(status().isOk()).andReturn(),
                "$.data.verificationToken");
    }

    private ResultActions lookup(Member member, String cardNo, String month, String year) throws Exception {
        return perform(post("/api/cards/lookup").with(AuthUsers.asUser(member.userId(), member.deviceId())),
                "{\"cardNumber\":\"" + cardNo + "\",\"expiryMonth\":\"" + month + "\",\"expiryYear\":\"" + year + "\"}");
    }

    private ResultActions register(Member member, String stepUpToken, String json) throws Exception {
        MockHttpServletRequestBuilder builder = post("/api/cards")
                .with(AuthUsers.asUser(member.userId(), member.deviceId()));
        if (stepUpToken != null) {
            builder.header("X-Step-Up-Token", stepUpToken);
        }
        return perform(builder, json);
    }

    private ResultActions perform(MockHttpServletRequestBuilder builder, String json) throws Exception {
        return mockMvc.perform(builder.contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String read(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path);
    }

    private static ResultMatcher errorCode(int status, String code) {
        return result -> {
            assertThat(result.getResponse().getStatus()).as("HTTP status").isEqualTo(status);
            assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.error.code")).isEqualTo(code);
        };
    }

    private static Timestamp now() {
        return Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC));
    }
}
