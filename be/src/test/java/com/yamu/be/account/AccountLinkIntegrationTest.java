package com.yamu.be.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.yamu.be.account.SsafyAccountApi.Balance;
import com.yamu.be.account.SsafyAccountApi.CheckResult;
import com.yamu.be.account.SsafyAccountApi.Holder;
import com.yamu.be.auth.SignupTokenStore;
import com.yamu.be.auth.SignupTokenStore.SignupSession;
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
import com.yamu.be.global.finapi.FinApiFailures;
import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.MySqlTestSupport;
import java.sql.Timestamp;
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
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

/**
 * 계좌 확인 → 1원 인증 요청 → 계좌 연결을 실제 MySQL·Redis로 확인한다. SSAFY 금융망은 가짜(FinApiClient mock)다.
 * 가입 중(가입 진행 토큰)과 가입 후(Access Token + 본인 인증 토큰)를 모두 본다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-account-link-integration-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountLinkIntegrationTest extends MySqlTestSupport {

    private static final String ACCOUNT_NO = "0881541149756547";
    private static final String OTHER_ACCOUNT_NO = "0881541149750001";
    private static final String USER_KEY = "2695628f-11e1-418e-b533-9ae19e0650ec";
    private static final String MEMBER_LOCAL = "yabc123456";
    private static final String CODE = "4821";

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

    @MockitoBean FinApiClient finApiClient;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM linked_account");
        jdbc.update("DELETE FROM step_up_audit_log");
        jdbc.update("DELETE FROM user_device_event");
        jdbc.update("DELETE FROM user_device");
        jdbc.update("DELETE FROM app_user");
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
        when(finApiClient.post(eq(SsafyAccountApi.HOLDER_PATH), eq(USER_KEY), any(), eq(Holder.class)))
                .thenReturn(new Holder("088", "신한은행", MEMBER_LOCAL));
        when(finApiClient.post(eq(SsafyAccountApi.CHECK_AUTH_PATH), eq(USER_KEY), any(), eq(CheckResult.class)))
                .thenAnswer(inv -> {
                    Map<?, ?> body = inv.getArgument(2);
                    if (CODE.equals(body.get("authCode"))) {
                        return new CheckResult("SUCCESS");
                    }
                    throw FinApiFailures.rejected("checkAuthCode", "A1090");
                });
        when(finApiClient.post(eq(SsafyAccountApi.BALANCE_PATH), eq(USER_KEY), any(), eq(Balance.class)))
                .thenReturn(new Balance(620000L));
    }

    // ───────── 성공 흐름 ─────────

    @Test
    void 가입_중에는_가입_진행_토큰으로_확인_1원_연결을_하고_본인_인증_기록은_비워_둔다() throws Exception {
        long userId = pendingUser();
        String bearer = "Bearer " + signupToken(userId, SignupMode.SIGNUP);

        MvcResult verified = verifyAccount(bearer, ACCOUNT_NO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bank.code").value("088"))
                .andExpect(jsonPath("$.data.bank.name").value("신한은행"))
                .andExpect(jsonPath("$.data.maskedAccountNo").value("****-****-****-6547"))
                .andExpect(jsonPath("$.data.holderName").value("김*무"))
                .andExpect(jsonPath("$.data.holderMatched").value(true))
                .andExpect(jsonPath("$.data.alreadyLinked").value(false))
                .andExpect(jsonPath("$.data.expiresAt").exists())
                .andReturn();
        String token = read(verified, "$.data.verificationToken");
        assertThat(token).startsWith("avt_");
        assertThat(verified.getResponse().getContentAsString()).doesNotContain(ACCOUNT_NO).doesNotContain(MEMBER_LOCAL);

        oneWon(bearer, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verificationToken").value(token))
                .andExpect(jsonPath("$.data.depositorNamePrefix").value("YAMU"))
                .andExpect(jsonPath("$.data.codeLength").value(4))
                .andExpect(jsonPath("$.data.authRequestedAt").exists())
                .andExpect(jsonPath("$.data.authExpiresAt").exists());
        verify(finApiClient).post(eq(SsafyAccountApi.OPEN_AUTH_PATH), eq(USER_KEY),
                eq(Map.of("accountNo", ACCOUNT_NO, "authText", "YAMU")), eq(Void.class));

        MvcResult linked = link(bearer, token, CODE, "생활비 통장", UUID.randomUUID().toString(), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accountName").value("생활비 통장"))
                .andExpect(jsonPath("$.data.maskedAccountNo").value("****-****-****-6547"))
                .andExpect(jsonPath("$.data.balance").value(620000))
                .andExpect(jsonPath("$.data.balanceAsOf").exists())
                .andExpect(jsonPath("$.data.isPrimary").value(true))
                .andExpect(jsonPath("$.data.verifiedBy").doesNotExist())
                .andExpect(jsonPath("$.data.linkedAt").exists())
                .andReturn();
        long accountId = Long.parseLong(read(linked, "$.data.accountId"));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM linked_account WHERE linked_account_id = ?", accountId);
        assertThat(row.get("user_id")).isEqualTo(userId);
        assertThat(row.get("status")).isEqualTo("CONNECTED");
        assertThat(row.get("account_last4")).isEqualTo("6547");
        assertThat(row.get("account_alias")).isEqualTo("생활비 통장");
        assertThat(row.get("step_up_audit_log_id")).isNull();
        assertThat(cipher.decrypt((byte[]) row.get("account_no_enc"), CipherField.LINKED_ACCOUNT_NO)).isEqualTo(ACCOUNT_NO);
        assertThat((byte[]) row.get("account_no_hash")).isEqualTo(lookupHasher.account("088", ACCOUNT_NO));
        // 연결이 끝나면 확인 결과는 지운다(같은 토큰으로 다시 연결할 수 없다).
        assertThat(redis.keys("account:verify:*")).isEmpty();
    }

    @Test
    void 가입_후에는_본인_인증_토큰으로_연결하고_그_기록을_남긴다() throws Exception {
        Member member = activeMember();
        String token = verifiedAndSent(member.auth());

        link(member.auth(), token, CODE, null, UUID.randomUUID().toString(), member.stepUpToken())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.verifiedBy.method").value("PIN"))
                .andExpect(jsonPath("$.data.verifiedBy.auditLogId").value(member.auditLogId()))
                .andExpect(jsonPath("$.data.accountName").doesNotExist());

        assertThat(jdbc.queryForObject("SELECT step_up_audit_log_id FROM linked_account WHERE user_id = ?", Long.class,
                member.userId())).isEqualTo(member.auditLogId());
    }

    @Test
    void 두_번째로_연결한_계좌는_기준_계좌가_아니다() throws Exception {
        long userId = pendingUser();
        String bearer = "Bearer " + signupToken(userId, SignupMode.SIGNUP);
        link(bearer, verifiedAndSent(bearer, ACCOUNT_NO), CODE, null, UUID.randomUUID().toString(), null)
                .andExpect(jsonPath("$.data.isPrimary").value(true));

        link(bearer, verifiedAndSent(bearer, OTHER_ACCOUNT_NO), CODE, null, UUID.randomUUID().toString(), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.isPrimary").value(false));
    }

    // ───────── 계좌 확인 ─────────

    @Test
    void 계좌번호가_16자리_숫자가_아니면_금융망을_부르지_않고_400_INVALID_ACCOUNT_NUMBER() throws Exception {
        String bearer = "Bearer " + signupToken(pendingUser(), SignupMode.SIGNUP);

        verifyAccount(bearer, "088-154-1149756547").andExpect(errorCode(400, "INVALID_ACCOUNT_NUMBER"));
        verifyAccount(bearer, "08815411497565").andExpect(errorCode(400, "INVALID_ACCOUNT_NUMBER"));
        verify(finApiClient, never()).post(eq(SsafyAccountApi.HOLDER_PATH), anyString(), any(), eq(Holder.class));
    }

    @Test
    void 은행에_없는_계좌는_404_ACCOUNT_NOT_FOUND_AT_BANK_원화_입출금이_아니면_400_ACCOUNT_NOT_SUPPORTED() throws Exception {
        String bearer = "Bearer " + signupToken(pendingUser(), SignupMode.SIGNUP);
        when(finApiClient.post(eq(SsafyAccountApi.HOLDER_PATH), eq(USER_KEY), any(), eq(Holder.class)))
                .thenThrow(FinApiFailures.rejected("inquireDemandDepositAccountHolderName", "A1003"))
                .thenThrow(FinApiFailures.rejected("inquireDemandDepositAccountHolderName", "A5004"));

        verifyAccount(bearer, ACCOUNT_NO).andExpect(errorCode(404, "ACCOUNT_NOT_FOUND_AT_BANK"));
        verifyAccount(bearer, ACCOUNT_NO).andExpect(errorCode(400, "ACCOUNT_NOT_SUPPORTED"));
    }

    @Test
    void 예금주가_내_SSAFY_계정이_아니면_400_HOLDER_MISMATCH() throws Exception {
        String bearer = "Bearer " + signupToken(pendingUser(), SignupMode.SIGNUP);
        when(finApiClient.post(eq(SsafyAccountApi.HOLDER_PATH), eq(USER_KEY), any(), eq(Holder.class)))
                .thenReturn(new Holder("088", "신한은행", "yother9999"));

        verifyAccount(bearer, ACCOUNT_NO).andExpect(errorCode(400, "HOLDER_MISMATCH"));
    }

    @Test
    void 이미_연결한_계좌는_확인_단계에서_409_ACCOUNT_ALREADY_LINKED() throws Exception {
        long userId = pendingUser();
        String bearer = "Bearer " + signupToken(userId, SignupMode.SIGNUP);
        link(bearer, verifiedAndSent(bearer, ACCOUNT_NO), CODE, null, UUID.randomUUID().toString(), null)
                .andExpect(status().isCreated());

        verifyAccount(bearer, ACCOUNT_NO).andExpect(errorCode(409, "ACCOUNT_ALREADY_LINKED"));
    }

    @Test
    void 끊었던_계좌를_다시_연결하면_같은_행을_다시_쓴다() throws Exception {
        long userId = pendingUser();
        String bearer = "Bearer " + signupToken(userId, SignupMode.SIGNUP);
        long first = Long.parseLong(read(link(bearer, verifiedAndSent(bearer, ACCOUNT_NO), CODE, null,
                UUID.randomUUID().toString(), null).andReturn(), "$.data.accountId"));
        // 연결 해제 API는 없다. 해제하면 기준 계좌도 같이 풀어야 한다(ck_account_primary_connected).
        jdbc.update("UPDATE linked_account SET status = 'DISCONNECTED', is_primary = 0, disconnected_at = ? "
                + "WHERE linked_account_id = ?", Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC)), first);

        link(bearer, verifiedAndSent(bearer, ACCOUNT_NO), CODE, null, UUID.randomUUID().toString(), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accountId").value(first))
                .andExpect(jsonPath("$.data.isPrimary").value(true));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM linked_account", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT disconnected_at FROM linked_account", Timestamp.class)).isNull();
    }

    // ───────── 1원 인증 요청 ─────────

    @Test
    void 같은_계좌로_1분_안에_다시_요청하면_429_금융망_실패였으면_바로_다시_요청할_수_있다() throws Exception {
        String bearer = "Bearer " + signupToken(pendingUser(), SignupMode.SIGNUP);
        String token = read(verifyAccount(bearer, ACCOUNT_NO).andReturn(), "$.data.verificationToken");
        when(finApiClient.post(eq(SsafyAccountApi.OPEN_AUTH_PATH), eq(USER_KEY), any(), eq(Void.class)))
                .thenThrow(FinApiFailures.notSent("openAccountAuth"))
                .thenReturn(null);

        oneWon(bearer, token).andExpect(errorCode(502, "FIN_API_UNAVAILABLE"));
        oneWon(bearer, token).andExpect(status().isOk());
        oneWon(bearer, token).andExpect(errorCode(429, "AUTH_REQUEST_TOO_FREQUENT"));
    }

    @Test
    void 남의_토큰이나_만료된_토큰은_400_VERIFICATION_TOKEN_EXPIRED() throws Exception {
        String mine = "Bearer " + signupToken(pendingUser(), SignupMode.SIGNUP);
        String token = read(verifyAccount(mine, ACCOUNT_NO).andReturn(), "$.data.verificationToken");
        String other = "Bearer " + signupToken(otherPendingUser(), SignupMode.SIGNUP);

        oneWon(other, token).andExpect(errorCode(400, "VERIFICATION_TOKEN_EXPIRED"));
        oneWon(mine, "avt_nope").andExpect(errorCode(400, "VERIFICATION_TOKEN_EXPIRED"));
        link(other, token, CODE, null, UUID.randomUUID().toString(), null)
                .andExpect(errorCode(400, "VERIFICATION_TOKEN_EXPIRED"));
    }

    // ───────── 인증코드 ─────────

    @Test
    void 인증코드를_틀리면_남은_횟수를_주고_3번_다_틀리면_SSAFY를_부르지_않고_1원_인증부터_다시() throws Exception {
        String bearer = "Bearer " + signupToken(pendingUser(), SignupMode.SIGNUP);
        String token = verifiedAndSent(bearer, ACCOUNT_NO);

        link(bearer, token, "0000", null, UUID.randomUUID().toString(), null)
                .andExpect(errorCode(400, "AUTH_CODE_MISMATCH"))
                .andExpect(jsonPath("$.error.details.remainingAttempts").value(2));
        link(bearer, token, "0001", null, UUID.randomUUID().toString(), null)
                .andExpect(jsonPath("$.error.details.remainingAttempts").value(1));
        link(bearer, token, "0002", null, UUID.randomUUID().toString(), null)
                .andExpect(jsonPath("$.error.details.remainingAttempts").value(0));
        link(bearer, token, CODE, null, UUID.randomUUID().toString(), null)
                .andExpect(errorCode(400, "AUTH_CODE_EXPIRED"));
        verify(finApiClient, times(3)).post(eq(SsafyAccountApi.CHECK_AUTH_PATH), eq(USER_KEY), any(), eq(CheckResult.class));

        // 1원을 다시 보내면 처음부터 센다(재요청 간격은 테스트에서 비운다).
        redis.delete(redis.keys("onewon:resend:*"));
        oneWon(bearer, token).andExpect(status().isOk());
        link(bearer, token, CODE, null, UUID.randomUUID().toString(), null).andExpect(status().isCreated());
    }

    @Test
    void 일원을_보내기_전이거나_SSAFY가_만료라고_하면_400_AUTH_CODE_EXPIRED() throws Exception {
        String bearer = "Bearer " + signupToken(pendingUser(), SignupMode.SIGNUP);
        String token = read(verifyAccount(bearer, ACCOUNT_NO).andReturn(), "$.data.verificationToken");

        link(bearer, token, CODE, null, UUID.randomUUID().toString(), null)
                .andExpect(errorCode(400, "AUTH_CODE_EXPIRED"));

        oneWon(bearer, token).andExpect(status().isOk());
        when(finApiClient.post(eq(SsafyAccountApi.CHECK_AUTH_PATH), eq(USER_KEY), any(), eq(CheckResult.class)))
                .thenThrow(FinApiFailures.rejected("checkAuthCode", "A1087"));
        link(bearer, token, CODE, null, UUID.randomUUID().toString(), null)
                .andExpect(errorCode(400, "AUTH_CODE_EXPIRED"));
    }

    // ───────── 본인 인증 ─────────

    @Test
    void 가입_후_본인_인증_토큰이_없으면_SSAFY를_부르기_전에_403_STEP_UP_REQUIRED() throws Exception {
        Member member = activeMember();
        String token = verifiedAndSent(member.auth());

        link(member.auth(), token, CODE, null, UUID.randomUUID().toString(), null)
                .andExpect(errorCode(403, "STEP_UP_REQUIRED"));
        verify(finApiClient, never()).post(eq(SsafyAccountApi.CHECK_AUTH_PATH), anyString(), any(), eq(CheckResult.class));
    }

    @Test
    void 인증코드는_맞았는데_본인_인증_토큰이_틀리면_다시_본인_인증만_하고_코드는_다시_확인하지_않는다() throws Exception {
        Member member = activeMember();
        String token = verifiedAndSent(member.auth());

        link(member.auth(), token, CODE, null, UUID.randomUUID().toString(), "sut_wrong")
                .andExpect(errorCode(403, "STEP_UP_REQUIRED"));
        link(member.auth(), token, "9999", null, UUID.randomUUID().toString(), member.stepUpToken())
                .andExpect(status().isCreated());
        verify(finApiClient, times(1)).post(eq(SsafyAccountApi.CHECK_AUTH_PATH), eq(USER_KEY), any(), eq(CheckResult.class));
    }

    // ───────── 멱등성 ─────────

    @Test
    void 같은_Idempotency_Key로_다시_부르면_같은_계좌를_돌려주고_다른_요청에_쓰면_409() throws Exception {
        long userId = pendingUser();
        String bearer = "Bearer " + signupToken(userId, SignupMode.SIGNUP);
        String token = verifiedAndSent(bearer, ACCOUNT_NO);
        String key = UUID.randomUUID().toString();

        String first = read(link(bearer, token, CODE, null, key, null).andExpect(status().isCreated()).andReturn(),
                "$.data.accountId");
        link(bearer, token, CODE, null, key, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accountId").value(Long.parseLong(first)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM linked_account", Integer.class)).isEqualTo(1);

        String another = verifiedAndSent(bearer, OTHER_ACCOUNT_NO);
        link(bearer, another, CODE, null, key, null).andExpect(errorCode(409, "IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void Idempotency_Key가_없으면_400() throws Exception {
        String bearer = "Bearer " + signupToken(pendingUser(), SignupMode.SIGNUP);
        String token = verifiedAndSent(bearer, ACCOUNT_NO);

        link(bearer, token, CODE, null, null, null)
                .andExpect(errorCode(400, "INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("Idempotency-Key"));
    }

    // ───────── 잔액·인증·기기 재등록 ─────────

    @Test
    void 연결_뒤_잔액_조회가_실패해도_연결은_유지하고_잔액만_비운다() throws Exception {
        String bearer = "Bearer " + signupToken(pendingUser(), SignupMode.SIGNUP);
        when(finApiClient.post(eq(SsafyAccountApi.BALANCE_PATH), eq(USER_KEY), any(), eq(Balance.class)))
                .thenThrow(FinApiFailures.timedOut("inquireDemandDepositAccountBalance"));

        link(bearer, verifiedAndSent(bearer, ACCOUNT_NO), CODE, null, UUID.randomUUID().toString(), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.balance").doesNotExist())
                .andExpect(jsonPath("$.data.balanceAsOf").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM linked_account", Integer.class)).isEqualTo(1);
    }

    @Test
    void 토큰이_없으면_401_UNAUTHORIZED_가입_진행_토큰이_만료면_401_SIGNUP_TOKEN_INVALID() throws Exception {
        mockMvc.perform(post("/api/accounts/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountNo\":\"" + ACCOUNT_NO + "\"}"))
                .andExpect(errorCode(401, "UNAUTHORIZED"));
        verifyAccount("Bearer sgt_expired", ACCOUNT_NO).andExpect(errorCode(401, "SIGNUP_TOKEN_INVALID"));
    }

    @Test
    void 기기_재등록은_연결된_계좌만_확인하고_연결_API는_쓰지_않는다() throws Exception {
        long userId = pendingUser();
        String signup = "Bearer " + signupToken(userId, SignupMode.SIGNUP);
        link(signup, verifiedAndSent(signup, ACCOUNT_NO), CODE, null, UUID.randomUUID().toString(), null)
                .andExpect(status().isCreated());
        String recovery = "Bearer " + signupToken(userId, SignupMode.DEVICE_RECOVERY);

        verifyAccount(recovery, OTHER_ACCOUNT_NO).andExpect(errorCode(400, "ACCOUNT_NOT_LINKED"));
        String token = read(verifyAccount(recovery, ACCOUNT_NO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alreadyLinked").value(true))
                .andReturn(), "$.data.verificationToken");
        oneWon(recovery, token).andExpect(status().isOk());
        link(recovery, token, CODE, null, UUID.randomUUID().toString(), null)
                .andExpect(errorCode(409, "ACCOUNT_ALREADY_LINKED"));
    }

    // ───────── 도우미 ─────────

    private record Member(long userId, long deviceId, long auditLogId, String stepUpToken) {

        RequestPostProcessor auth() {
            return AuthUsers.asUser(userId, deviceId);
        }
    }

    /** 회원가입까지 마친 PENDING 사용자(SSAFY 계정 있음). 이름은 김야무, 가짜 SSAFY가 이 사람을 예금주로 돌려준다. */
    private long pendingUser() {
        return insertUser("01012345678", MEMBER_LOCAL, "PENDING");
    }

    /** 다른 사람. 예금주 확인에 쓰는 SSAFY 계정 ID가 달라야 한다. */
    private long otherPendingUser() {
        return insertUser("01099998888", "yother9999", "PENDING");
    }

    private long insertUser(String phone, String memberLocal, String status) {
        Map<String, Object> values = new HashMap<>(Map.of(
                "name", "김야무",
                "birth_date", LocalDate.of(1999, 3, 15),
                "phone_enc", cipher.encrypt(phone, CipherField.APP_USER_PHONE),
                "phone_hash", lookupHasher.phone(phone),
                "identity_verified_at", now(),
                "ssafy_member_id", memberLocal + "@yamu.app",
                "ssafy_user_key", cipher.encrypt(USER_KEY, CipherField.APP_USER_SSAFY_USER_KEY),
                "status", status,
                "created_at", now(),
                "updated_at", now()));
        if ("ACTIVE".equals(status)) {
            values.put("pin_hash", "$2a$10$test");
            values.put("pin_updated_at", now());
        }
        return insert("app_user", "user_id", values);
    }

    /** 가입을 마친 사용자 + 기기 + ACCOUNT_LINK 본인 인증 토큰. */
    private Member activeMember() {
        long userId = insertUser("01012345678", MEMBER_LOCAL, "ACTIVE");
        long deviceId = insert("user_device", "user_device_id", Map.of("user_id", userId,
                "device_uuid", UUID.randomUUID().toString(), "platform", "ANDROID", "public_key", "test-key",
                "key_algorithm", "SHA256withECDSA", "created_at", now(), "updated_at", now()));
        long auditLogId = insert("step_up_audit_log", "step_up_audit_log_id", Map.of("user_id", userId,
                "user_device_id", deviceId, "auth_method", "PIN", "purpose", "ACCOUNT_LINK", "result", "SUCCESS",
                "created_at", now()));
        String stepUpToken = stepUpTokenStore.issue(
                new StepUpGrant(userId, deviceId, StepUpPurpose.ACCOUNT_LINK, AuthMethod.PIN, auditLogId));
        return new Member(userId, deviceId, auditLogId, stepUpToken);
    }

    /** 넘긴 칸만 넣는다. 나머지(version·status 등)는 DB 기본값. */
    private long insert(String table, String keyColumn, Map<String, Object> values) {
        return new SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns(keyColumn)
                .usingColumns(values.keySet().toArray(String[]::new))
                .executeAndReturnKey(values).longValue();
    }

    private String signupToken(long userId, SignupMode mode) {
        String phone = "01012345678";
        VerifiedIdentity identity = new VerifiedIdentity("김야무", LocalDate.of(1999, 3, 15),
                cipher.encrypt(phone, CipherField.APP_USER_PHONE), lookupHasher.phone(phone));
        return signupTokenStore.issue(new SignupSession(mode, identity, LocalDateTime.now(ZoneOffset.UTC), userId)).token();
    }

    /** 계좌 확인 + 1원 송금까지. */
    private String verifiedAndSent(String bearer, String accountNo) throws Exception {
        String token = read(verifyAccount(bearer, accountNo).andExpect(status().isOk()).andReturn(), "$.data.verificationToken");
        oneWon(bearer, token).andExpect(status().isOk());
        return token;
    }

    private String verifiedAndSent(RequestPostProcessor auth) throws Exception {
        String token = read(perform(post("/api/accounts/verify").with(auth),
                "{\"accountNo\":\"" + ACCOUNT_NO + "\"}").andExpect(status().isOk()).andReturn(),
                "$.data.verificationToken");
        perform(post("/api/accounts/one-won-auth").with(auth), "{\"verificationToken\":\"" + token + "\"}")
                .andExpect(status().isOk());
        return token;
    }

    private ResultActions verifyAccount(String bearer, String accountNo) throws Exception {
        return perform(post("/api/accounts/verify").header(HttpHeaders.AUTHORIZATION, bearer),
                "{\"accountNo\":\"" + accountNo + "\"}");
    }

    private ResultActions oneWon(String bearer, String token) throws Exception {
        return perform(post("/api/accounts/one-won-auth").header(HttpHeaders.AUTHORIZATION, bearer),
                "{\"verificationToken\":\"" + token + "\"}");
    }

    private ResultActions link(String bearer, String token, String code, String name, String idempotencyKey,
            String stepUpToken) throws Exception {
        return link(post("/api/accounts").header(HttpHeaders.AUTHORIZATION, bearer), token, code, name,
                idempotencyKey, stepUpToken);
    }

    private ResultActions link(RequestPostProcessor auth, String token,
            String code, String name, String idempotencyKey, String stepUpToken) throws Exception {
        return link(post("/api/accounts").with(auth), token, code, name, idempotencyKey, stepUpToken);
    }

    private ResultActions link(MockHttpServletRequestBuilder builder, String token, String code, String name,
            String idempotencyKey, String stepUpToken) throws Exception {
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        if (stepUpToken != null) {
            builder.header("X-Step-Up-Token", stepUpToken);
        }
        String nameJson = name == null ? "" : ",\"accountName\":\"" + name + "\"";
        return perform(builder, "{\"verificationToken\":\"" + token + "\",\"authCode\":\"" + code + "\"" + nameJson + "}");
    }

    private ResultActions perform(MockHttpServletRequestBuilder builder, String json) throws Exception {
        return mockMvc.perform(builder.contentType(MediaType.APPLICATION_JSON).content(json));
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

    private static String read(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path).toString();
    }
}
