package com.yamu.be.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.yamu.be.account.SsafyAccountFakes;
import com.yamu.be.auth.PhoneVerificationStore;
import com.yamu.be.auth.PhoneVerificationStore.IssuedVerification;
import com.yamu.be.auth.SignupTokenStore;
import com.yamu.be.auth.SignupTokenStore.SignupSession;
import com.yamu.be.auth.StepUpService;
import com.yamu.be.auth.StepUpTokenStore;
import com.yamu.be.auth.StepUpTokenStore.StepUpGrant;
import com.yamu.be.auth.VerifiedIdentity;
import com.yamu.be.auth.domain.AuthMethod;
import com.yamu.be.auth.domain.SignupMode;
import com.yamu.be.auth.domain.StepUpPurpose;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.LookupHasher;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.finapi.FinApiClient;
import com.yamu.be.support.Concurrently;
import com.yamu.be.support.DeviceKeys;
import com.yamu.be.support.MySqlTestSupport;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import org.springframework.http.HttpHeaders;
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
 * 회원 탈퇴와 재가입을 실제 MySQL·Redis로 확인한다. 가입은 실제 API(계좌 목록 → 1원 → 연결 → PIN → 기기 등록)로 마치고,
 * Access Token도 기기 등록 응답의 진짜 JWT를 쓴다. SSAFY 금융망만 가짜다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-withdrawal-integration-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WithdrawalIntegrationTest extends MySqlTestSupport {

    private static final String ACCOUNT_NO = "0881541149756547";
    private static final String USER_KEY = "2695628f-11e1-418e-b533-9ae19e0650ec";
    private static final String MEMBER_ID = "ywithdraw01@yamu.app";
    private static final String PHONE = "01012345678";
    private static final LocalDate BIRTH = LocalDate.of(1999, 3, 15);
    private static final String CODE = "4821";
    private static final String PIN = "482913";
    private static final String CARD_NO = "9410000000000011";

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PersonalDataCipher cipher;
    @Autowired LookupHasher lookupHasher;
    @Autowired SignupTokenStore signupTokenStore;
    @Autowired StepUpTokenStore stepUpTokenStore;
    @Autowired PhoneVerificationStore verificationStore;

    @MockitoBean FinApiClient finApiClient;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM card_bill");
        jdbc.update("DELETE FROM card_monthly_performance");
        jdbc.update("DELETE FROM user_card");
        jdbc.update("DELETE FROM card_product");
        jdbc.update("DELETE FROM linked_account");
        jdbc.update("DELETE FROM step_up_audit_log");
        jdbc.update("DELETE FROM user_device_event");
        jdbc.update("DELETE FROM user_device");
        jdbc.update("DELETE FROM app_user");
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
        SsafyAccountFakes.stub(finApiClient, USER_KEY, ACCOUNT_NO, CODE);
    }

    // ───────── 탈퇴 ─────────

    @Test
    void 탈퇴하면_개인정보를_비우고_재가입_열쇠와_카드사_데이터만_남긴다() throws Exception {
        Member member = activeMember();
        long card = card(member.userId(), "ACTIVE");
        byte[] accountHashBefore = accountHash(member.userId());
        String stepUp = stepUpToken(member, StepUpPurpose.WITHDRAW_MEMBER);

        withdraw(member, stepUp).andExpect(status().isNoContent());

        Map<String, Object> user = jdbc.queryForMap("SELECT * FROM app_user WHERE user_id = ?", member.userId());
        assertThat(user.get("status")).isEqualTo("WITHDRAWN");
        assertThat(user.get("deleted_at")).isNotNull();
        assertThat(user.get("name")).isNull();
        assertThat(user.get("phone_enc")).isNull();
        assertThat(user.get("pin_hash")).isNull();
        assertThat(user.get("pin_updated_at")).isNull();
        assertThat(user.get("identity_verified_at")).isNull();
        assertThat((byte[]) user.get("phone_hash")).isEqualTo(lookupHasher.phone(PHONE));
        assertThat(user.get("birth_date")).isNotNull();
        assertThat(user.get("ssafy_member_id")).isEqualTo(MEMBER_ID);
        assertThat(user.get("ssafy_user_key")).isNotNull();
        // 이름 원문은 지우고 재가입 본인 확인용 해시만 남는다.
        assertThat((byte[]) user.get("withdrawn_name_hash")).isEqualTo(lookupHasher.name("김야무"));

        Map<String, Object> account = jdbc.queryForMap("SELECT * FROM linked_account WHERE user_id = ?", member.userId());
        assertThat(account.get("status")).isEqualTo("DISCONNECTED");
        assertThat(account.get("is_primary")).isEqualTo(false);
        assertThat((byte[]) account.get("account_no_hash")).isNotEqualTo(accountHashBefore);
        assertThat(cipher.decrypt((byte[]) account.get("account_no_enc"), CipherField.LINKED_ACCOUNT_NO))
                .isNotEqualTo(ACCOUNT_NO);

        Map<String, Object> device = jdbc.queryForMap("SELECT * FROM user_device WHERE user_id = ?", member.userId());
        assertThat(device.get("status")).isEqualTo("REVOKED");
        assertThat((String) device.get("public_key")).startsWith("withdrawn:");
        assertThat(jdbc.queryForList("SELECT event_type FROM user_device_event WHERE user_device_id = ?", String.class,
                member.deviceId())).contains("REVOKED");

        Map<String, Object> cardRow = jdbc.queryForMap("SELECT * FROM user_card WHERE user_card_id = ?", card);
        assertThat(cardRow.get("status")).isEqualTo("UNREGISTERED");
        assertThat((byte[]) cardRow.get("card_no_hash")).isEqualTo(lookupHasher.card(CARD_NO));
        assertThat(cardRow.get("issuer_account_bank_code")).isEqualTo("088");

        assertThat(jdbc.queryForList("SELECT ip_address FROM step_up_audit_log WHERE user_id = ?", String.class,
                member.userId())).containsOnlyNulls();
        assertThat(redis.hasKey("auth:refresh:" + member.deviceId())).isFalse();
        assertThat(redis.keys("signup:*")).isEmpty();
    }

    @Test
    void 탈퇴_뒤_남은_Access_Token은_401이고_재발급과_로그인도_안_된다() throws Exception {
        Member member = activeMember();
        mockMvc.perform(get("/api/auth/devices").header(HttpHeaders.AUTHORIZATION, "Bearer " + member.accessToken()))
                .andExpect(status().isOk());

        withdraw(member, stepUpToken(member, StepUpPurpose.WITHDRAW_MEMBER)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/auth/devices").header(HttpHeaders.AUTHORIZATION, "Bearer " + member.accessToken()))
                .andExpect(status().isUnauthorized());
        perform(post("/api/auth/refresh"), "{\"refreshToken\":\"" + member.refreshToken() + "\"}")
                .andExpect(status().isUnauthorized());
        perform(post("/api/auth/login/challenge"), "{\"userDeviceId\":" + member.deviceId() + "}")
                .andExpect(status().is4xxClientError());
    }

    @Test
    void 끝나지_않은_청구가_있으면_409이고_아무것도_바뀌지_않으며_본인_인증_토큰도_쓰지_않는다() throws Exception {
        Member member = activeMember();
        long card = card(member.userId(), "ACTIVE");
        long bill = bill(member.userId(), card, "CONFIRMED");
        String stepUp = stepUpToken(member, StepUpPurpose.WITHDRAW_MEMBER);

        withdraw(member, stepUp).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BILL_OUTSTANDING"))
                .andExpect(jsonPath("$.error.details.billIds[0]").value(bill));
        assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE user_id = ?", String.class, member.userId()))
                .isEqualTo("ACTIVE");

        jdbc.update("UPDATE card_bill SET status = 'PAID' WHERE card_bill_id = ?", bill);
        withdraw(member, stepUp).andExpect(status().isNoContent());
    }

    @Test
    void 본인_인증_없이_탈퇴하면_403이다() throws Exception {
        Member member = activeMember();

        mockMvc.perform(delete("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + member.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("STEP_UP_REQUIRED"));
        mockMvc.perform(delete("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + member.accessToken())
                        .header(StepUpService.HEADER, stepUpToken(member, StepUpPurpose.CARD_DELETE)))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE user_id = ?", String.class, member.userId()))
                .isEqualTo("ACTIVE");
    }

    @Test
    void 동시에_두_번_탈퇴해도_한_번만_처리되고_500이_나지_않는다() throws Exception {
        Member member = activeMember();
        List<String> tokens = List.of(stepUpToken(member, StepUpPurpose.WITHDRAW_MEMBER),
                stepUpToken(member, StepUpPurpose.WITHDRAW_MEMBER));

        List<Integer> statuses = Concurrently.run(List.of(
                () -> withdraw(member, tokens.get(0)).andReturn().getResponse().getStatus(),
                () -> withdraw(member, tokens.get(1)).andReturn().getResponse().getStatus()));

        assertThat(statuses).contains(204).allMatch(code -> code == 204 || code == 401 || code == 403);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_device_event WHERE user_device_id = ? "
                + "AND event_type = 'REVOKED'", Integer.class, member.deviceId())).isEqualTo(1);
    }

    // ───────── 재가입 ─────────

    @Test
    void 이름이_다른_사람이_먼저_재가입을_시도해도_404이고_아무것도_바뀌지_않아_본인은_재가입한다() throws Exception {
        Member member = activeMember();
        withdraw(member, stepUpToken(member, StepUpPurpose.WITHDRAW_MEMBER)).andExpect(status().isNoContent());

        // 번호·생년월일만 아는 사람. 시드에 없는 사람과 같은 응답이라 탈퇴 여부도 알 수 없다.
        confirm(verificationStore.issue(identity("홍길동")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DEMO_USER_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE status = 'PENDING'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT ssafy_member_id FROM app_user WHERE user_id = ?", String.class,
                member.userId())).isEqualTo(MEMBER_ID);

        confirm(verificationStore.issue(identity("김야무")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("SIGNUP"));
        assertThat(jdbc.queryForObject("SELECT name FROM app_user WHERE status = 'PENDING'", String.class))
                .isEqualTo("김야무");
    }

    @Test
    void 탈퇴한_사람이_본인인증을_하면_새_사용자를_만들고_열쇠와_카드를_옮긴다() throws Exception {
        Member member = activeMember();
        long oldCard = card(member.userId(), "ACTIVE");
        long paidBill = bill(member.userId(), oldCard, "PAID");
        withdraw(member, stepUpToken(member, StepUpPurpose.WITHDRAW_MEMBER)).andExpect(status().isNoContent());

        MvcResult confirmed = confirm(verificationStore.issue(identity("김야무")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("SIGNUP"))
                .andExpect(jsonPath("$.data.nextStep").value("ACCOUNT_LINK"))
                .andReturn();
        String signupToken = read(confirmed, "$.data.signupToken");

        long fresh = jdbc.queryForObject("SELECT user_id FROM app_user WHERE status = 'PENDING'", Long.class);
        assertThat(fresh).isNotEqualTo(member.userId());
        Map<String, Object> newUser = jdbc.queryForMap("SELECT * FROM app_user WHERE user_id = ?", fresh);
        assertThat(newUser.get("name")).isEqualTo("김야무");
        assertThat(newUser.get("ssafy_member_id")).isEqualTo(MEMBER_ID);
        assertThat(cipher.decrypt((byte[]) newUser.get("ssafy_user_key"), CipherField.APP_USER_SSAFY_USER_KEY))
                .isEqualTo(USER_KEY);
        assertThat(newUser.get("identity_verified_at")).isNotNull();

        Map<String, Object> oldUser = jdbc.queryForMap("SELECT * FROM app_user WHERE user_id = ?", member.userId());
        assertThat(oldUser.get("status")).isEqualTo("WITHDRAWN");
        assertThat(oldUser.get("phone_hash")).isNull();
        assertThat(oldUser.get("birth_date")).isNull();
        assertThat(oldUser.get("ssafy_member_id")).isNull();
        assertThat(oldUser.get("ssafy_user_key")).isNull();
        assertThat(oldUser.get("withdrawn_name_hash")).isNull();

        Map<String, Object> newCard = jdbc.queryForMap("SELECT * FROM user_card WHERE user_id = ?", fresh);
        assertThat(newCard.get("status")).isEqualTo("PENDING");
        assertThat(newCard.get("linked_account_id")).isNull();
        assertThat(newCard.get("registered_at")).isNull();
        assertThat((byte[]) newCard.get("card_no_hash")).isEqualTo(lookupHasher.card(CARD_NO));
        assertThat(newCard.get("issuer_account_last4")).isEqualTo("6547");
        assertThat((byte[]) jdbc.queryForObject("SELECT card_no_hash FROM user_card WHERE user_card_id = ?",
                byte[].class, oldCard)).isNotEqualTo(lookupHasher.card(CARD_NO));
        assertThat(jdbc.queryForObject("SELECT user_id FROM card_bill WHERE card_bill_id = ?", Long.class, paidBill))
                .isEqualTo(member.userId());

        // 재가입한 사용자도 같은 SSAFY 계좌를 목록에서 골라 1원으로 다시 연결한다.
        linkAccount("Bearer " + signupToken);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM linked_account WHERE user_id = ? AND status = 'CONNECTED'",
                Integer.class, fresh)).isEqualTo(1);
    }

    @Test
    void 같은_탈퇴자가_동시에_재가입해도_새_사용자는_하나다() throws Exception {
        Member member = activeMember();
        withdraw(member, stepUpToken(member, StepUpPurpose.WITHDRAW_MEMBER)).andExpect(status().isNoContent());
        IssuedVerification first = verificationStore.issue(identity("김야무"));
        IssuedVerification second = verificationStore.issue(identity("김야무"));

        List<Integer> statuses = Concurrently.run(List.of(
                () -> confirm(first).andReturn().getResponse().getStatus(),
                () -> confirm(second).andReturn().getResponse().getStatus()));

        assertThat(statuses).containsOnly(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE ssafy_member_id = ?", Integer.class,
                MEMBER_ID)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE status = 'PENDING'", Integer.class))
                .isEqualTo(1);
    }

    // ───────── 도우미 ─────────

    private record Member(long userId, long deviceId, String accessToken, String refreshToken) {
    }

    /** 실제 API로 가입을 마친 사용자(계좌 연결 + PIN + 기기 1대). */
    private Member activeMember() throws Exception {
        long userId = insert("app_user", "user_id", new HashMap<>(Map.of(
                "name", "김야무", "birth_date", BIRTH,
                "phone_enc", cipher.encrypt(PHONE, CipherField.APP_USER_PHONE),
                "phone_hash", lookupHasher.phone(PHONE), "identity_verified_at", now(),
                "ssafy_member_id", MEMBER_ID,
                "ssafy_user_key", cipher.encrypt(USER_KEY, CipherField.APP_USER_SSAFY_USER_KEY),
                "status", "PENDING", "created_at", now(), "updated_at", now())));
        String bearer = "Bearer " + signupTokenStore.issue(new SignupSession(SignupMode.SIGNUP, identity("김야무"),
                LocalDateTime.now(ZoneOffset.UTC), userId)).token();
        linkAccount(bearer);
        perform(post("/api/auth/pin").header(HttpHeaders.AUTHORIZATION, bearer), "{\"pin\":\"" + PIN + "\"}")
                .andExpect(status().isNoContent());
        DeviceKeys keys = DeviceKeys.generate();
        MvcResult registered = perform(post("/api/auth/devices").header(HttpHeaders.AUTHORIZATION, bearer), """
                {"deviceId":"%s","deviceName":"Galaxy S24","platform":"ANDROID","devicePublicKey":"%s"}
                """.formatted(UUID.randomUUID(), keys.publicKey()))
                .andExpect(status().isCreated()).andReturn();
        return new Member(userId, Long.parseLong(read(registered, "$.data.userDeviceId")),
                read(registered, "$.data.accessToken"), read(registered, "$.data.refreshToken"));
    }

    private VerifiedIdentity identity(String name) {
        return new VerifiedIdentity(name, BIRTH, cipher.encrypt(PHONE, CipherField.APP_USER_PHONE),
                lookupHasher.phone(PHONE));
    }

    /** 계좌 목록 → 1원 → 연결(가입 진행 토큰). */
    private void linkAccount(String bearer) throws Exception {
        MvcResult listed = mockMvc.perform(get("/api/accounts/candidates").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk()).andReturn();
        String candidateId = read(listed, "$.data.accounts[0].candidateId");
        String verification = read(perform(post("/api/accounts/one-won-auth").header(HttpHeaders.AUTHORIZATION, bearer),
                "{\"candidateIds\":[\"" + candidateId + "\"],\"primaryCandidateId\":\"" + candidateId + "\"}")
                .andExpect(status().isOk()).andReturn(), "$.data.verificationToken");
        perform(post("/api/accounts").header(HttpHeaders.AUTHORIZATION, bearer)
                        .header("Idempotency-Key", UUID.randomUUID().toString()),
                "{\"verificationToken\":\"" + verification + "\",\"authCode\":\"" + CODE + "\"}")
                .andExpect(status().isCreated());
    }

    private ResultActions withdraw(Member member, String stepUp) throws Exception {
        return mockMvc.perform(delete("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + member.accessToken())
                .header(StepUpService.HEADER, stepUp));
    }

    private ResultActions confirm(IssuedVerification issued) throws Exception {
        return perform(post("/api/auth/phone-verifications/{id}/confirm", issued.verificationId()),
                "{\"code\":\"" + issued.code() + "\"}");
    }

    /** 본인 인증을 마친 것으로 기록(IP·User-Agent 포함)하고 토큰을 만든다. */
    private String stepUpToken(Member member, StepUpPurpose purpose) {
        long auditLogId = insert("step_up_audit_log", "step_up_audit_log_id", new HashMap<>(Map.of(
                "user_id", member.userId(), "user_device_id", member.deviceId(), "auth_method", "PIN",
                "purpose", purpose.name(), "result", "SUCCESS", "ip_address", "10.0.0.7",
                "user_agent", "YAMU-Android/1.0", "created_at", now())));
        return stepUpTokenStore.issue(new StepUpGrant(member.userId(), member.deviceId(), purpose, AuthMethod.PIN,
                auditLogId));
    }

    /** 카드사(시드)가 계좌·결제일을 정해 둔 신용카드. */
    private long card(long userId, String status) {
        long product = insert("card_product", "card_product_id", new HashMap<>(Map.of("issuer_code", "SHINHAN",
                "product_name", "신한 " + UUID.randomUUID(), "card_type", "CREDIT", "billing_cutoff_days", 14,
                "created_at", now(), "updated_at", now())));
        Map<String, Object> values = new HashMap<>(Map.of("user_id", userId, "card_product_id", product,
                "card_no_enc", cipher.encrypt(CARD_NO, CipherField.USER_CARD_NO),
                "card_no_hash", lookupHasher.card(CARD_NO), "card_last4", "0011", "status", status,
                "payment_day", 14, "issuer_account_bank_code", "088",
                "issuer_account_hash", lookupHasher.account("088", ACCOUNT_NO), "issuer_account_last4", "6547"));
        values.put("created_at", now());
        values.put("updated_at", now());
        if ("ACTIVE".equals(status)) {
            values.put("linked_account_id", jdbc.queryForObject(
                    "SELECT linked_account_id FROM linked_account WHERE user_id = ?", Long.class, userId));
            values.put("registered_at", now());
        }
        return insert("user_card", "user_card_id", values);
    }

    private long bill(long userId, long card, String status) {
        LocalDate month = LocalDate.of(2026, 10, 1);
        return insert("card_bill", "card_bill_id", new HashMap<>(Map.ofEntries(
                Map.entry("user_id", userId), Map.entry("user_card_id", card),
                Map.entry("linked_account_id", jdbc.queryForObject(
                        "SELECT linked_account_id FROM linked_account WHERE user_id = ?", Long.class, userId)),
                Map.entry("billing_month", Date.valueOf(month)),
                Map.entry("period_start", Date.valueOf(month.minusMonths(1).plusDays(1))),
                Map.entry("period_end", Date.valueOf(month)),
                Map.entry("withdrawal_date", Date.valueOf(month.plusDays(13))), Map.entry("status", status),
                Map.entry("confirmed_amount", 128500L), Map.entry("confirmed_at", now()),
                Map.entry("created_at", now()), Map.entry("updated_at", now()))));
    }

    private byte[] accountHash(long userId) {
        return jdbc.queryForObject("SELECT account_no_hash FROM linked_account WHERE user_id = ?", byte[].class, userId);
    }

    private long insert(String table, String keyColumn, Map<String, Object> values) {
        return new SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns(keyColumn)
                .usingColumns(values.keySet().toArray(String[]::new))
                .executeAndReturnKey(values).longValue();
    }

    private ResultActions perform(MockHttpServletRequestBuilder builder, String json) throws Exception {
        return mockMvc.perform(builder.contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String read(MvcResult result, String path) throws Exception {
        Object value = JsonPath.read(result.getResponse().getContentAsString(), path);
        return String.valueOf(value);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
