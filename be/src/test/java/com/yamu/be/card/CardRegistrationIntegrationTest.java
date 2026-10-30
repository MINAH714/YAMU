package com.yamu.be.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.yamu.be.support.Concurrently;
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
 * 사용자 명의 카드는 시드처럼 user_card PENDING 행(카드번호 암호문·조회 해시)과 카드사가 정한 계좌·결제일로 미리 넣는다.
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
    void 체크카드를_확인하면_카드사가_정한_계좌를_보여_주고_등록하면_그_계좌로_연결된다() throws Exception {
        Member member = member(StepUpPurpose.CARD_REGISTER);
        long account = account(member.userId(), "5934");
        long card = pendingCard(member.userId(), checkProduct, CHECK_NO, "5934", null);

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
                .andExpect(jsonPath("$.data.issuerAccount.bankCode").value("088"))
                .andExpect(jsonPath("$.data.issuerAccount.maskedAccountNo").value("****-****-****-5934"))
                .andExpect(jsonPath("$.data.issuerAccount.linkedAccountId").value(account))
                .andExpect(jsonPath("$.data.issuerAccount.linked").value(true))
                .andExpect(jsonPath("$.data.paymentDay").doesNotExist())
                .andExpect(jsonPath("$.data.alreadyRegistered").value(false))
                .andExpect(jsonPath("$.data.expiresAt").exists())
                .andReturn();
        String token = read(looked, "$.data.verificationToken");
        assertThat(token).startsWith("cvt_");
        assertThat(looked.getResponse().getContentAsString()).doesNotContain(CHECK_NO).doesNotContain(accountNo("5934"));

        MvcResult registered = register(member, member.stepUpToken(), "{\"verificationToken\":\"" + token + "\"}")
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
    void 신용카드는_카드사가_정한_결제일과_결제계좌로_등록하고_집계된_실적이_있으면_SEED로_보여_준다() throws Exception {
        Member member = member(StepUpPurpose.CARD_REGISTER);
        account(member.userId(), "7777");
        long settlement = account(member.userId(), "1111");
        long card = pendingCard(member.userId(), creditProduct, CREDIT_NO, "1111", 25);
        YearMonth current = YearMonth.now(ZoneId.of("Asia/Seoul"));
        performance(card, current.minusMonths(1), 320000L);
        performance(card, current, 180000L);

        String token = read(lookup(member, CREDIT_NO, "01", "30")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.issuer.closingDayOffset").value(14))
                .andExpect(jsonPath("$.data.product.cardType").value("CREDIT"))
                .andExpect(jsonPath("$.data.issuerAccount.maskedAccountNo").value("****-****-****-1111"))
                .andExpect(jsonPath("$.data.issuerAccount.linkedAccountId").value(settlement))
                .andExpect(jsonPath("$.data.paymentDay").value(25))
                .andReturn(), "$.data.verificationToken");

        register(member, member.stepUpToken(), "{\"verificationToken\":\"" + token + "\",\"nickname\":\" 생활비 카드 \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.nickname").value("생활비 카드"))
                .andExpect(jsonPath("$.data.cardType").value("CREDIT"))
                .andExpect(jsonPath("$.data.linkedAccountId").value(settlement))
                .andExpect(jsonPath("$.data.billing.paymentDay").value(25))
                .andExpect(jsonPath("$.data.billing.closingDayOffset").value(14))
                .andExpect(jsonPath("$.data.billing.settlementAccountId").value(settlement))
                .andExpect(jsonPath("$.data.billing.creditLimit").doesNotExist())
                .andExpect(jsonPath("$.data.initialPerformance.source").value("SEED"))
                .andExpect(jsonPath("$.data.initialPerformance.prevMonth.yearMonth").value(current.minusMonths(1).toString()))
                .andExpect(jsonPath("$.data.initialPerformance.prevMonth.recognizedAmount").value(320000))
                .andExpect(jsonPath("$.data.initialPerformance.currentMonth.recognizedAmount").value(180000));

        assertThat(jdbc.queryForObject("SELECT payment_day FROM user_card WHERE user_card_id = ?", Integer.class, card))
                .isEqualTo(25);
        assertThat(jdbc.queryForObject("SELECT linked_account_id FROM user_card WHERE user_card_id = ?", Long.class, card))
                .isEqualTo(settlement);
    }

    @Test
    void 예전_앱이_계좌나_결제일을_보내도_쓰지_않고_카드사가_정한_값으로_등록한다() throws Exception {
        Member member = member(StepUpPurpose.CARD_REGISTER);
        long settlement = account(member.userId(), "1111");
        long other = account(member.userId(), "7777");
        long card = pendingCard(member.userId(), creditProduct, CREDIT_NO, "1111", 25);
        String token = verified(member, CREDIT_NO);

        register(member, member.stepUpToken(), "{\"verificationToken\":\"" + token + "\",\"paymentDay\":3,"
                + "\"settlementAccountId\":" + other + ",\"linkedAccountId\":" + other + "}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.linkedAccountId").value(settlement))
                .andExpect(jsonPath("$.data.billing.paymentDay").value(25));
        assertThat(jdbc.queryForMap("SELECT linked_account_id, payment_day FROM user_card WHERE user_card_id = ?", card))
                .containsEntry("payment_day", 25);
    }

    @Test
    void 등록_해제했던_카드는_같은_행을_다시_ACTIVE로_만들고_처음_등록_시각과_결제일은_그대로_둔다() throws Exception {
        Member member = member(StepUpPurpose.CARD_REGISTER);
        account(member.userId(), "1111");
        long card = pendingCard(member.userId(), creditProduct, CREDIT_NO, "1111", 25);
        Timestamp first = Timestamp.valueOf(LocalDateTime.of(2026, 9, 1, 3, 0));
        // 해제 전에 카드 관리에서 결제일을 10일로 바꿔 둔 카드(3개월 제한 시계가 돌고 있음)
        jdbc.update("UPDATE user_card SET status = 'UNREGISTERED', payment_day = 10, registered_at = ?,"
                + " payment_day_changed_at = ? WHERE user_card_id = ?", first, Timestamp.valueOf(LocalDateTime.now()), card);

        String token = verified(member, CREDIT_NO);
        register(member, member.stepUpToken(), "{\"verificationToken\":\"" + token + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.userCardId").value(card))
                .andExpect(jsonPath("$.data.billing.paymentDay").value(10));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_card", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT registered_at FROM user_card WHERE user_card_id = ?", Timestamp.class, card))
                .isEqualTo(first);
        // 다시 등록한 카드는 새 등록이라 예전 결제일 변경 제한을 물려받지 않는다.
        assertThat(jdbc.queryForObject("SELECT payment_day_changed_at FROM user_card WHERE user_card_id = ?",
                Timestamp.class, card)).isNull();
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
        pendingCard(member.userId(), checkProduct, CHECK_NO, "5934", null);
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
        pendingCard(stranger, checkProduct, CHECK_NO, "5934", null);

        lookup(member, UNKNOWN_NO, "12", "29").andExpect(errorCode(404, "CARD_NOT_FOUND"));
        lookup(member, CHECK_NO, "12", "29").andExpect(errorCode(404, "CARD_NOT_FOUND"));
        assertThat(redis.keys("card:verify:*")).isEmpty();
    }

    @Test
    void 이미_등록한_카드는_CARD_ALREADY_REGISTERED다() throws Exception {
        Member member = member(StepUpPurpose.CARD_REGISTER);
        long card = pendingCard(member.userId(), checkProduct, CHECK_NO, "5934", null);
        jdbc.update("UPDATE user_card SET status = 'INACTIVE' WHERE user_card_id = ?", card);

        lookup(member, CHECK_NO, "12", "29").andExpect(errorCode(409, "CARD_ALREADY_REGISTERED"));
    }

    @Test
    void 카드사_결제_정보가_빠진_카드는_확인부터_CARD_ACCOUNT_NOT_SET이고_빠진_값을_알려_준다() throws Exception {
        Member member = member(StepUpPurpose.CARD_REGISTER);
        account(member.userId(), "1111");
        pendingCard(member.userId(), checkProduct, CHECK_NO, null, null);
        long credit = pendingCard(member.userId(), creditProduct, CREDIT_NO, "1111", null);

        lookup(member, CHECK_NO, "12", "29")
                .andExpect(errorCode(409, "CARD_ACCOUNT_NOT_SET"))
                .andExpect(jsonPath("$.error.details.missing[0]").value("account"));
        lookup(member, CREDIT_NO, "12", "29")
                .andExpect(errorCode(409, "CARD_ACCOUNT_NOT_SET"))
                .andExpect(jsonPath("$.error.details.missing[0]").value("paymentDay"));
        assertThat(redis.keys("card:verify:*")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM user_card WHERE user_card_id = ?", String.class, credit))
                .isEqualTo("PENDING");
    }

    @Test
    void 카드사_계좌_세_칸은_함께_있거나_함께_없어야_한다() {
        long owner = user("01012345678");
        long card = pendingCard(owner, checkProduct, CHECK_NO, null, null);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE user_card SET issuer_account_bank_code = '088' WHERE user_card_id = ?", card))
                .hasMessageContaining("ck_card_issuer_account");
    }

    // ───────── 카드 등록 실패 ─────────

    @Test
    void 카드사가_정한_계좌를_아직_연결하지_않았으면_409로_알려_주고_연결한_뒤_다시_보내면_등록된다() throws Exception {
        Member member = member(StepUpPurpose.CARD_REGISTER);
        account(member.userId(), "7777");
        long card = pendingCard(member.userId(), checkProduct, CHECK_NO, "5934", null);

        String token = read(lookup(member, CHECK_NO, "12", "29")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.issuerAccount.maskedAccountNo").value("****-****-****-5934"))
                .andExpect(jsonPath("$.data.issuerAccount.linkedAccountId").doesNotExist())
                .andExpect(jsonPath("$.data.issuerAccount.linked").value(false))
                .andReturn(), "$.data.verificationToken");

        register(member, member.stepUpToken(), "{\"verificationToken\":\"" + token + "\"}")
                .andExpect(errorCode(409, "CARD_ACCOUNT_NOT_LINKED"))
                .andExpect(jsonPath("$.error.details.bankCode").value("088"))
                .andExpect(jsonPath("$.error.details.accountLast4").value("5934"));
        // 토큰은 둘 다 남아 있어 계좌를 연결하고 그대로 다시 보내면 된다.
        assertThat(redis.keys("card:verify:*")).hasSize(1);
        long account = account(member.userId(), "5934");
        register(member, member.stepUpToken(), "{\"verificationToken\":\"" + token + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.linkedAccountId").value(account));
        assertThat(jdbc.queryForObject("SELECT linked_account_id FROM user_card WHERE user_card_id = ?", Long.class, card))
                .isEqualTo(account);
    }

    @Test
    void 남이_연결한_같은_계좌나_내가_연결_해제한_계좌는_연결된_것으로_보지_않는다() throws Exception {
        Member member = member(StepUpPurpose.CARD_REGISTER);
        pendingCard(member.userId(), checkProduct, CHECK_NO, "5934", null);
        // 다른 사용자가 같은 계좌번호를 연결해 두었다(해시가 같아도 user_id가 달라 찾지 않는다).
        account(user("01055556666"), "5934");
        String token = verified(member, CHECK_NO);

        register(member, member.stepUpToken(), "{\"verificationToken\":\"" + token + "\"}")
                .andExpect(errorCode(409, "CARD_ACCOUNT_NOT_LINKED"));

        long mine = account(member.userId(), "5934");
        jdbc.update("UPDATE linked_account SET status = 'DISCONNECTED', disconnected_at = ? WHERE linked_account_id = ?",
                now(), mine);
        register(member, member.stepUpToken(), "{\"verificationToken\":\"" + token + "\"}")
                .andExpect(errorCode(409, "CARD_ACCOUNT_NOT_LINKED"));
    }

    @Test
    void 본인_인증_토큰이_없거나_다른_용도면_STEP_UP_REQUIRED이고_확인_토큰은_남는다() throws Exception {
        Member member = member(StepUpPurpose.ACCOUNT_LINK);
        account(member.userId(), "5934");
        pendingCard(member.userId(), checkProduct, CHECK_NO, "5934", null);
        String token = verified(member, CHECK_NO);
        String body = "{\"verificationToken\":\"" + token + "\"}";

        register(member, null, body).andExpect(errorCode(403, "STEP_UP_REQUIRED"));
        register(member, member.stepUpToken(), body).andExpect(errorCode(403, "STEP_UP_REQUIRED"));

        register(member, stepUp(member, StepUpPurpose.CARD_REGISTER), body).andExpect(status().isCreated());
    }

    @Test
    void 확인_토큰은_한_번만_쓰고_남의_토큰이나_없는_토큰은_VERIFICATION_TOKEN_EXPIRED다() throws Exception {
        Member member = member(StepUpPurpose.CARD_REGISTER);
        account(member.userId(), "5934");
        pendingCard(member.userId(), checkProduct, CHECK_NO, "5934", null);
        String token = verified(member, CHECK_NO);
        String body = "{\"verificationToken\":\"" + token + "\"}";

        Member stranger = otherMember();
        register(stranger, stranger.stepUpToken(), body).andExpect(errorCode(400, "VERIFICATION_TOKEN_EXPIRED"));
        register(member, member.stepUpToken(), "{\"verificationToken\":\"cvt_nothing\"}")
                .andExpect(errorCode(400, "VERIFICATION_TOKEN_EXPIRED"));

        register(member, member.stepUpToken(), body).andExpect(status().isCreated());
        register(member, stepUp(member, StepUpPurpose.CARD_REGISTER), body)
                .andExpect(errorCode(400, "VERIFICATION_TOKEN_EXPIRED"));
    }

    @Test
    void 같은_확인_토큰으로_동시에_두_번_등록하면_한_번만_성공한다() throws Exception {
        Member member = member(StepUpPurpose.CARD_REGISTER);
        account(member.userId(), "5934");
        long card = pendingCard(member.userId(), checkProduct, CHECK_NO, "5934", null);
        String token = verified(member, CHECK_NO);
        String body = "{\"verificationToken\":\"" + token + "\"}";
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

    // ───────── 리뷰 반영(10/30) ─────────

    @Test
    void 확인_토큰_두_개로_동시에_등록하면_하나는_201이고_늦은_쪽은_409_CARD_ALREADY_REGISTERED다() throws Exception {
        Member member = member(StepUpPurpose.CARD_REGISTER);
        account(member.userId(), "5934");
        long card = pendingCard(member.userId(), checkProduct, CHECK_NO, "5934", null);
        String first = verified(member, CHECK_NO);
        String second = verified(member, CHECK_NO);
        List<String> stepUps = List.of(member.stepUpToken(), stepUp(member, StepUpPurpose.CARD_REGISTER));
        List<String> tokens = List.of(first, second);

        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String body = "{\"verificationToken\":\"" + tokens.get(i) + "\"}";
            String stepUp = stepUps.get(i);
            calls.add(() -> register(member, stepUp, body).andReturn().getResponse().getStatus());
        }
        assertThat(Concurrently.run(calls)).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("SELECT status FROM user_card WHERE user_card_id = ?", String.class, card))
                .isEqualTo("ACTIVE");
    }

    @Test
    void 카드사가_정지하거나_만료한_카드는_확인도_등록도_CARD_VERIFICATION_FAILED다() throws Exception {
        Member member = member(StepUpPurpose.CARD_REGISTER);
        account(member.userId(), "5934");
        long card = pendingCard(member.userId(), checkProduct, CHECK_NO, "5934", null);

        for (String status : List.of("SUSPENDED", "EXPIRED")) {
            jdbc.update("UPDATE user_card SET issuer_status = ? WHERE user_card_id = ?", status, card);
            lookup(member, CHECK_NO, "12", "29")
                    .andExpect(errorCode(400, "CARD_VERIFICATION_FAILED"))
                    .andExpect(jsonPath("$.error.details.reason").value("ISSUER_" + status));
        }
        // 확인한 뒤에 정지되면 등록 단계에서 다시 걸러지고, 토큰은 쓰지 않는다.
        jdbc.update("UPDATE user_card SET issuer_status = 'NORMAL' WHERE user_card_id = ?", card);
        String token = verified(member, CHECK_NO);
        jdbc.update("UPDATE user_card SET issuer_status = 'SUSPENDED' WHERE user_card_id = ?", card);
        register(member, member.stepUpToken(), "{\"verificationToken\":\"" + token + "\"}")
                .andExpect(errorCode(400, "CARD_VERIFICATION_FAILED"))
                .andExpect(jsonPath("$.error.details.reason").value("ISSUER_SUSPENDED"));
        assertThat(redis.keys("card:verify:*")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT status FROM user_card WHERE user_card_id = ?", String.class, card))
                .isEqualTo("PENDING");
    }

    @Test
    void 카드사_표에_없는_카드사의_상품이면_ISSUER_NOT_SUPPORTED다() throws Exception {
        Member member = member(StepUpPurpose.CARD_REGISTER);
        long unknown = insert("card_product", "card_product_id", new HashMap<>(Map.of("issuer_code", "NOWHERE",
                "product_name", "모르는 카드", "card_type", "CHECK", "created_at", now(), "updated_at", now())));
        pendingCard(member.userId(), unknown, CHECK_NO, "5934", null);

        lookup(member, CHECK_NO, "12", "29").andExpect(errorCode(404, "ISSUER_NOT_SUPPORTED"));
        assertThat(redis.keys("card:verify:*")).isEmpty();
    }

    @Test
    void 카드_확인은_사용자별로_1분에_5번까지이고_넘으면_429와_다시_시도할_초를_준다() throws Exception {
        Member member = member(StepUpPurpose.CARD_REGISTER);

        for (int i = 0; i < 5; i++) {
            lookup(member, UNKNOWN_NO, "12", "29").andExpect(errorCode(404, "CARD_NOT_FOUND"));
        }
        lookup(member, UNKNOWN_NO, "12", "29")
                .andExpect(errorCode(429, "TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.error.details.retryAfterSeconds").isNumber());
        // 다른 사용자는 따로 센다.
        Member other = otherMember();
        lookup(other, UNKNOWN_NO, "12", "29").andExpect(errorCode(404, "CARD_NOT_FOUND"));
    }

    @Test
    void 별칭은_앞뒤_공백을_먼저_빼고_길이를_보며_공백뿐이면_별칭_없음이다() throws Exception {
        Member member = member(StepUpPurpose.CARD_REGISTER);
        account(member.userId(), "5934");
        long card = pendingCard(member.userId(), checkProduct, CHECK_NO, "5934", null);
        String token = verified(member, CHECK_NO);
        String twenty = "가".repeat(20);

        register(member, member.stepUpToken(), "{\"verificationToken\":\"" + token + "\",\"nickname\":\"" + "가".repeat(21) + "\"}")
                .andExpect(errorCode(400, "INVALID_REQUEST"));
        register(member, member.stepUpToken(), "{\"verificationToken\":\"" + token + "\",\"nickname\":\"  " + twenty + "  \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.nickname").value(twenty));
        assertThat(jdbc.queryForObject("SELECT card_alias FROM user_card WHERE user_card_id = ?", String.class, card))
                .isEqualTo(twenty);
    }

    @Test
    void 공백뿐인_별칭은_별칭_없음으로_보고_상품명을_보여_준다() throws Exception {
        Member member = member(StepUpPurpose.CARD_REGISTER);
        account(member.userId(), "5934");
        long card = pendingCard(member.userId(), checkProduct, CHECK_NO, "5934", null);
        String token = verified(member, CHECK_NO);

        register(member, member.stepUpToken(), "{\"verificationToken\":\"" + token + "\",\"nickname\":\"   \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.nickname").value("신한 카페 체크"));
        assertThat(jdbc.queryForObject("SELECT card_alias FROM user_card WHERE user_card_id = ?", String.class, card))
                .isNull();
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

    private static String accountNo(String last4) {
        return "088154114975" + last4;
    }

    private long account(long owner, String last4) {
        String accountNo = accountNo(last4);
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

    /**
     * 시드처럼 미리 넣어 둔 사용자 명의 카드(PENDING). 카드사가 정한 계좌(신한 088 + 끝 4자리)와 결제일을 함께 적는다.
     *
     * @param issuerLast4 카드사가 정한 계좌의 끝 4자리. null이면 계좌 정보가 없는 카드
     * @param paymentDay  카드사가 정한 결제일(신용카드). null이면 없음
     */
    private long pendingCard(long owner, long product, String cardNo, String issuerLast4, Integer paymentDay) {
        Map<String, Object> values = new HashMap<>(Map.of("user_id", owner, "card_product_id", product,
                "card_no_enc", cipher.encrypt(cardNo, CipherField.USER_CARD_NO),
                "card_no_hash", lookupHasher.card(cardNo), "card_last4", cardNo.substring(12),
                "status", "PENDING", "created_at", now(), "updated_at", now()));
        if (issuerLast4 != null) {
            values.put("issuer_account_bank_code", "088");
            values.put("issuer_account_hash", lookupHasher.account("088", accountNo(issuerLast4)));
            values.put("issuer_account_last4", issuerLast4);
        }
        if (paymentDay != null) {
            values.put("payment_day", paymentDay);
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
