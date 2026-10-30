package com.yamu.be.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.jayway.jsonpath.JsonPath;
import com.yamu.be.account.SsafyAccountApi.CheckResult;
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
import com.yamu.be.support.Concurrently;
import com.yamu.be.support.MySqlTestSupport;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
 * 계좌 목록 → 1원 인증 요청 → 계좌 연결을 실제 MySQL·Redis로 확인한다. SSAFY 금융망은 가짜(FinApiClient mock)다.
 * 가입 중(가입 진행 토큰, 1원 1번으로 여러 계좌), 가입 후(Access Token + 본인 인증, 1원 없음), 기기 재등록을 모두 본다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-account-link-integration-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountLinkIntegrationTest extends MySqlTestSupport {

    /** 김야무의 SSAFY 계좌 두 개(신한 6547, 국민 8810). */
    private static final String ACCOUNT_NO = "0881541149756547";
    private static final String OTHER_ACCOUNT_NO = "0042213345678810";
    private static final String USER_KEY = "2695628f-11e1-418e-b533-9ae19e0650ec";
    /** 다른 사람(홍길동)의 SSAFY 계좌. */
    private static final String STRANGER_ACCOUNT_NO = "0880000000001234";
    private static final String STRANGER_KEY = "9d1a0c55-0000-4000-8000-000000000000";
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
    @Autowired ObjectMapper objectMapper;

    @MockitoBean FinApiClient finApiClient;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM linked_account");
        jdbc.update("DELETE FROM step_up_audit_log");
        jdbc.update("DELETE FROM user_device_event");
        jdbc.update("DELETE FROM user_device");
        jdbc.update("DELETE FROM app_user");
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
        when(finApiClient.post(eq(SsafyAccountApi.LIST_PATH), eq(USER_KEY), any(), eq(JsonNode.class)))
                .thenReturn(accounts(row("088", "신한은행", ACCOUNT_NO, "월급통장"), row("004", "국민은행", OTHER_ACCOUNT_NO, "생활비")));
        when(finApiClient.post(eq(SsafyAccountApi.LIST_PATH), eq(STRANGER_KEY), any(), eq(JsonNode.class)))
                .thenReturn(accounts(row("088", "신한은행", STRANGER_ACCOUNT_NO, "홍길동 통장")));
        when(finApiClient.post(eq(SsafyAccountApi.CHECK_AUTH_PATH), anyString(), any(), eq(CheckResult.class)))
                .thenAnswer(inv -> {
                    Map<?, ?> body = inv.getArgument(2);
                    if (CODE.equals(body.get("authCode"))) {
                        return new CheckResult("SUCCESS");
                    }
                    throw FinApiFailures.rejected("checkAuthCode", "A1090");
                });
    }

    // ───────── 가입 중 ─────────

    @Test
    void 가입_중에는_내_SSAFY_계좌_목록에서_골라_1원_한_번으로_한꺼번에_연결한다() throws Exception {
        long userId = pendingUser();
        String bearer = signupBearer(userId, SignupMode.SIGNUP);

        MvcResult listed = candidates(bearer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accounts.length()").value(2))
                .andExpect(jsonPath("$.data.accounts[0].bank.code").value("088"))
                .andExpect(jsonPath("$.data.accounts[0].maskedAccountNo").value("****-****-****-6547"))
                .andExpect(jsonPath("$.data.accounts[0].accountName").value("월급통장"))
                .andExpect(jsonPath("$.data.accounts[0].alreadyLinked").value(false))
                .andExpect(jsonPath("$.data.accounts[0].balance").doesNotExist())
                .andExpect(jsonPath("$.data.expiresAt").exists())
                .andReturn();
        // 계좌번호 원문은 앱에 주지 않는다.
        assertThat(listed.getResponse().getContentAsString()).doesNotContain(ACCOUNT_NO).doesNotContain(OTHER_ACCOUNT_NO);
        String shinhan = candidateId(listed, "6547");
        String kookmin = candidateId(listed, "8810");
        assertThat(shinhan).startsWith("cand_");

        // 국민 계좌로 1원을 받는다. 처음 연결이면 1원 받은 계좌가 기준 계좌가 된다.
        MvcResult sent = oneWon(bearer, List.of(shinhan, kookmin), kookmin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bank.code").value("004"))
                .andExpect(jsonPath("$.data.maskedAccountNo").value("****-****-****-8810"))
                .andExpect(jsonPath("$.data.depositorNamePrefix").value("YAMU"))
                .andExpect(jsonPath("$.data.codeLength").value(4))
                .andExpect(jsonPath("$.data.deliveryUncertain").value(false))
                .andReturn();
        String token = read(sent, "$.data.verificationToken");
        verify(finApiClient, times(1)).post(eq(SsafyAccountApi.OPEN_AUTH_PATH), eq(USER_KEY),
                eq(Map.of("accountNo", OTHER_ACCOUNT_NO, "authText", "YAMU")), eq(Void.class));

        MvcResult linked = link(bearer, token, CODE, key(), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accounts.length()").value(2))
                .andExpect(jsonPath("$.data.accounts[0].maskedAccountNo").value("****-****-****-8810"))
                .andExpect(jsonPath("$.data.accounts[0].isPrimary").value(true))
                .andExpect(jsonPath("$.data.accounts[1].maskedAccountNo").value("****-****-****-6547"))
                .andExpect(jsonPath("$.data.accounts[1].isPrimary").value(false))
                .andExpect(jsonPath("$.data.skipped.length()").value(0))
                .andExpect(jsonPath("$.data.verifiedBy").doesNotExist())
                .andReturn();
        assertThat(linked.getResponse().getContentAsString()).doesNotContain(ACCOUNT_NO);

        assertThat(jdbc.queryForList("SELECT account_last4 FROM linked_account WHERE user_id = ? AND status = 'CONNECTED' "
                + "ORDER BY linked_account_id", String.class, userId)).containsExactly("8810", "6547");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM linked_account WHERE step_up_audit_log_id IS NOT NULL",
                Integer.class)).isZero();
        // 다 쓴 1원 인증은 지운다(재요청 간격·시도 횟수·토큰).
        assertThat(redis.keys("account:verify:*")).isEmpty();
        assertThat(redis.keys("onewon:resend:*")).isEmpty();
    }

    @Test
    void 고른_계좌_중_이미_연결된_것은_건너뛰고_skipped로_알린다() throws Exception {
        long userId = pendingUser();
        insertLinked(userId, "088", ACCOUNT_NO);
        String bearer = signupBearer(userId, SignupMode.SIGNUP);

        MvcResult listed = candidates(bearer)
                .andExpect(jsonPath("$.data.accounts[0].alreadyLinked").value(true))
                .andExpect(jsonPath("$.data.accounts[1].alreadyLinked").value(false))
                .andReturn();
        String token = sentToken(bearer, List.of(candidateId(listed, "6547"), candidateId(listed, "8810")),
                candidateId(listed, "8810"));

        link(bearer, token, CODE, key(), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accounts.length()").value(1))
                .andExpect(jsonPath("$.data.accounts[0].maskedAccountNo").value("****-****-****-8810"))
                .andExpect(jsonPath("$.data.skipped.length()").value(1))
                .andExpect(jsonPath("$.data.skipped[0].maskedAccountNo").value("****-****-****-6547"))
                .andExpect(jsonPath("$.data.skipped[0].reason").value("ALREADY_LINKED"));
    }

    @Test
    void 가입_중_고른_계좌가_전부_이미_연결돼_있으면_새로_연결하지_않고_이_세션의_1원_인증만_기록한다() throws Exception {
        // 계좌를 연결해 두고 멈춘 가입을 새 토큰으로 이어갈 때 1원 인증을 다시 하는 경로(PIN·기기 등록은 이 세션의 1원 인증을 요구).
        long userId = pendingUser();
        insertLinked(userId, "088", ACCOUNT_NO);
        String bearer = signupBearer(userId, SignupMode.SIGNUP);
        String shinhan = candidateId(candidates(bearer).andReturn(), "6547");
        assertThat(signupTokenStore.find(bearer.substring("Bearer ".length())).orElseThrow().oneWonVerified()).isFalse();

        link(bearer, sentToken(bearer, List.of(shinhan), shinhan), CODE, key(), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accounts.length()").value(0))
                .andExpect(jsonPath("$.data.skipped.length()").value(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM linked_account", Integer.class)).isEqualTo(1);
        assertThat(signupTokenStore.find(bearer.substring("Bearer ".length())).orElseThrow().oneWonVerified()).isTrue();
    }

    @Test
    void 끊었던_계좌를_다시_고르면_같은_행을_다시_연결한다() throws Exception {
        long userId = pendingUser();
        long old = insertLinked(userId, "088", ACCOUNT_NO);
        jdbc.update("UPDATE linked_account SET status = 'DISCONNECTED', disconnected_at = ? WHERE linked_account_id = ?",
                now(), old);
        String bearer = signupBearer(userId, SignupMode.SIGNUP);
        String shinhan = candidateId(candidates(bearer).andReturn(), "6547");

        link(bearer, sentToken(bearer, List.of(shinhan), shinhan), CODE, key(), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accounts[0].accountId").value(old));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM linked_account", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM linked_account", String.class)).isEqualTo("CONNECTED");
    }

    // ───────── 후보(candidateId) ─────────

    @Test
    void 목록에는_내_SSAFY_계좌만_나오고_남의_후보나_만료된_후보는_400_ACCOUNT_CANDIDATE_EXPIRED() throws Exception {
        String mine = signupBearer(pendingUser(), SignupMode.SIGNUP);
        String stranger = signupBearer(strangerUser(), SignupMode.SIGNUP);
        String strangerCandidate = read(candidates(stranger)
                .andExpect(jsonPath("$.data.accounts.length()").value(1))
                .andExpect(jsonPath("$.data.accounts[0].maskedAccountNo").value("****-****-****-1234"))
                .andReturn(), "$.data.accounts[0].candidateId");
        String shinhan = candidateId(candidates(mine).andReturn(), "6547");

        // 남의 후보를 섞으면 "없음"과 같다(남의 계좌가 있는지 드러내지 않는다). 1원도 보내지 않는다.
        oneWon(mine, List.of(shinhan, strangerCandidate), shinhan).andExpect(errorCode(400, "ACCOUNT_CANDIDATE_EXPIRED"));
        oneWon(mine, List.of("cand_AAAAAAAAAAAAAAAA"), "cand_AAAAAAAAAAAAAAAA")
                .andExpect(errorCode(400, "ACCOUNT_CANDIDATE_EXPIRED"));
        oneWon(mine, List.of("acct:1"), "acct:1").andExpect(errorCode(400, "ACCOUNT_CANDIDATE_EXPIRED"));
        verify(finApiClient, never()).post(eq(SsafyAccountApi.OPEN_AUTH_PATH), anyString(), any(), eq(Void.class));
    }

    @Test
    void 일원_받을_계좌가_고른_계좌에_없거나_아무것도_안_고르면_400() throws Exception {
        String bearer = signupBearer(pendingUser(), SignupMode.SIGNUP);
        MvcResult listed = candidates(bearer).andReturn();

        oneWon(bearer, List.of(candidateId(listed, "6547")), candidateId(listed, "8810"))
                .andExpect(errorCode(400, "INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("primaryCandidateId"));
        oneWon(bearer, List.of(), candidateId(listed, "8810"))
                .andExpect(errorCode(400, "INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("candidateIds"));
    }

    @Test
    void 계좌_목록_조회가_금융망에서_실패하면_그대로_502() throws Exception {
        String bearer = signupBearer(pendingUser(), SignupMode.SIGNUP);
        when(finApiClient.post(eq(SsafyAccountApi.LIST_PATH), eq(USER_KEY), any(), eq(JsonNode.class)))
                .thenThrow(FinApiFailures.notSent("inquireDemandDepositAccountList"));

        candidates(bearer).andExpect(errorCode(502, "FIN_API_UNAVAILABLE"));
    }

    // ───────── 1원 인증 요청 ─────────

    @Test
    void 같은_계좌로_1분_안에_다시_요청하면_429_금융망_실패였으면_바로_다시_요청할_수_있다() throws Exception {
        String bearer = signupBearer(pendingUser(), SignupMode.SIGNUP);
        String shinhan = candidateId(candidates(bearer).andReturn(), "6547");
        when(finApiClient.post(eq(SsafyAccountApi.OPEN_AUTH_PATH), eq(USER_KEY), any(), eq(Void.class)))
                .thenThrow(FinApiFailures.notSent("openAccountAuth"))
                .thenReturn(null);

        oneWon(bearer, List.of(shinhan), shinhan).andExpect(errorCode(502, "FIN_API_UNAVAILABLE"));
        oneWon(bearer, List.of(shinhan), shinhan).andExpect(status().isOk());
        oneWon(bearer, List.of(shinhan), shinhan)
                .andExpect(errorCode(429, "AUTH_REQUEST_TOO_FREQUENT"))
                .andExpect(jsonPath("$.error.details.retryAfterSeconds").isNumber());
        // 보내지 못한 1번은 하루 횟수에서 돌려받았고, 그때 만든 토큰은 지웠다.
        assertThat(redis.opsForValue().get(dailyKey("sent"))).isEqualTo("1");
        assertThat(redis.keys("account:verify:*")).hasSize(1);
    }

    @Test
    void 결과_불명이면_보낸_것으로_보고_deliveryUncertain과_토큰을_줘_받은_코드로_연결할_수_있다() throws Exception {
        String bearer = signupBearer(pendingUser(), SignupMode.SIGNUP);
        String shinhan = candidateId(candidates(bearer).andReturn(), "6547");
        when(finApiClient.post(eq(SsafyAccountApi.OPEN_AUTH_PATH), eq(USER_KEY), any(), eq(Void.class)))
                .thenThrow(FinApiFailures.timedOut("openAccountAuth"));

        String token = read(oneWon(bearer, List.of(shinhan), shinhan)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deliveryUncertain").value(true))
                .andReturn(), "$.data.verificationToken");
        // 바로 다시 보내면 1원이 두 번 나갈 수 있어 1분 자리를 지킨다. 하루 횟수도 1번 쓴 것으로 센다.
        oneWon(bearer, List.of(shinhan), shinhan).andExpect(errorCode(429, "AUTH_REQUEST_TOO_FREQUENT"));
        assertThat(redis.opsForValue().get(dailyKey("sent"))).isEqualTo("1");
        link(bearer, token, CODE, key(), null).andExpect(status().isCreated());
    }

    @Test
    void 한_계좌로_하루_5번까지_보내고_6번째는_자정까지_429_ONE_WON_DAILY_LIMIT_EXCEEDED() throws Exception {
        String bearer = signupBearer(pendingUser(), SignupMode.SIGNUP);
        String shinhan = candidateId(candidates(bearer).andReturn(), "6547");

        for (int i = 0; i < 5; i++) {
            redis.delete(redis.keys("onewon:resend:*"));
            oneWon(bearer, List.of(shinhan), shinhan).andExpect(status().isOk());
        }
        redis.delete(redis.keys("onewon:resend:*"));
        oneWon(bearer, List.of(shinhan), shinhan)
                .andExpect(errorCode(429, "ONE_WON_DAILY_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.error.details.retryAfterSeconds").isNumber());
        verify(finApiClient, times(5)).post(eq(SsafyAccountApi.OPEN_AUTH_PATH), eq(USER_KEY), any(), eq(Void.class));
        assertThat(redis.keys("onewon:resend:*")).isEmpty();
    }

    @Test
    void 하루에_10번_틀리면_다시_보내도_그날은_429_AUTH_CODE_LOCKED() throws Exception {
        String bearer = signupBearer(pendingUser(), SignupMode.SIGNUP);
        String shinhan = candidateId(candidates(bearer).andReturn(), "6547");

        // 다시 보낼 때마다 코드당 3번이 새로 생기지만, 하루 틀린 횟수는 이어서 센다.
        for (int sent = 0; sent < 3; sent++) {
            redis.delete(redis.keys("onewon:resend:*"));
            String token = sentToken(bearer, List.of(shinhan), shinhan);
            for (int i = 0; i < 3; i++) {
                link(bearer, token, "000" + i, key(), null).andExpect(errorCode(400, "AUTH_CODE_MISMATCH"));
            }
        }
        redis.delete(redis.keys("onewon:resend:*"));
        String token = sentToken(bearer, List.of(shinhan), shinhan);
        link(bearer, token, "0009", key(), null)
                .andExpect(errorCode(429, "AUTH_CODE_LOCKED"))
                .andExpect(jsonPath("$.error.details.retryAfterSeconds").isNumber());

        // 맞는 코드도, 새 1원도 그날은 받지 않는다. SSAFY도 부르지 않는다.
        link(bearer, token, CODE, key(), null).andExpect(errorCode(429, "AUTH_CODE_LOCKED"));
        redis.delete(redis.keys("onewon:resend:*"));
        oneWon(bearer, List.of(shinhan), shinhan).andExpect(errorCode(429, "AUTH_CODE_LOCKED"));
        verify(finApiClient, times(10)).post(eq(SsafyAccountApi.CHECK_AUTH_PATH), eq(USER_KEY), any(), eq(CheckResult.class));
        verify(finApiClient, times(4)).post(eq(SsafyAccountApi.OPEN_AUTH_PATH), eq(USER_KEY), any(), eq(Void.class));
    }

    // ───────── 인증코드 ─────────

    @Test
    void 남의_토큰이나_없는_토큰은_400_VERIFICATION_TOKEN_EXPIRED() throws Exception {
        String mine = signupBearer(pendingUser(), SignupMode.SIGNUP);
        String shinhan = candidateId(candidates(mine).andReturn(), "6547");
        String token = sentToken(mine, List.of(shinhan), shinhan);
        String stranger = signupBearer(strangerUser(), SignupMode.SIGNUP);

        link(stranger, token, CODE, key(), null).andExpect(errorCode(400, "VERIFICATION_TOKEN_EXPIRED"));
        link(mine, "avt_nope", CODE, key(), null).andExpect(errorCode(400, "VERIFICATION_TOKEN_EXPIRED"));
    }

    @Test
    void 인증코드를_틀리면_남은_횟수를_주고_3번_다_틀리면_SSAFY를_부르지_않고_1원_인증부터_다시() throws Exception {
        String bearer = signupBearer(pendingUser(), SignupMode.SIGNUP);
        String shinhan = candidateId(candidates(bearer).andReturn(), "6547");
        String token = sentToken(bearer, List.of(shinhan), shinhan);

        link(bearer, token, "0000", key(), null)
                .andExpect(errorCode(400, "AUTH_CODE_MISMATCH"))
                .andExpect(jsonPath("$.error.details.remainingAttempts").value(2));
        link(bearer, token, "0001", key(), null).andExpect(jsonPath("$.error.details.remainingAttempts").value(1));
        link(bearer, token, "0002", key(), null).andExpect(jsonPath("$.error.details.remainingAttempts").value(0));
        link(bearer, token, CODE, key(), null).andExpect(errorCode(400, "AUTH_CODE_EXPIRED"));
        verify(finApiClient, times(3)).post(eq(SsafyAccountApi.CHECK_AUTH_PATH), eq(USER_KEY), any(), eq(CheckResult.class));

        // 1원을 다시 보내면 처음부터 센다(재요청 간격은 테스트에서 비운다).
        redis.delete(redis.keys("onewon:resend:*"));
        link(bearer, sentToken(bearer, List.of(shinhan), shinhan), CODE, key(), null).andExpect(status().isCreated());
    }

    @Test
    void 시도_횟수는_1원_인증_토큰과_같은_10분_동안_기억한다() throws Exception {
        String bearer = signupBearer(pendingUser(), SignupMode.SIGNUP);
        String shinhan = candidateId(candidates(bearer).andReturn(), "6547");

        link(bearer, sentToken(bearer, List.of(shinhan), shinhan), "0000", key(), null)
                .andExpect(errorCode(400, "AUTH_CODE_MISMATCH"));
        // 5분(안내한 코드 유효시간)이면 SSAFY 코드가 살아 있는 동안 키가 먼저 사라져 3번을 더 넣을 수 있었다.
        String tryKey = redis.keys("onewon:try:*").iterator().next();
        assertThat(redis.getExpire(tryKey, TimeUnit.SECONDS)).isGreaterThan(9 * 60);
    }

    @Test
    void 인증코드가_숫자_4자리가_아니면_400이고_SSAFY도_시도_횟수도_쓰지_않는다() throws Exception {
        String bearer = signupBearer(pendingUser(), SignupMode.SIGNUP);
        String shinhan = candidateId(candidates(bearer).andReturn(), "6547");
        String token = sentToken(bearer, List.of(shinhan), shinhan);

        link(bearer, token, "12a4", key(), null)
                .andExpect(errorCode(400, "INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("authCode"));
        verify(finApiClient, never()).post(eq(SsafyAccountApi.CHECK_AUTH_PATH), anyString(), any(), eq(CheckResult.class));
        assertThat(redis.keys("onewon:try:*")).isEmpty();
    }

    @Test
    void SSAFY가_인증코드가_만료라고_하면_400_AUTH_CODE_EXPIRED() throws Exception {
        String bearer = signupBearer(pendingUser(), SignupMode.SIGNUP);
        String shinhan = candidateId(candidates(bearer).andReturn(), "6547");
        String token = sentToken(bearer, List.of(shinhan), shinhan);
        when(finApiClient.post(eq(SsafyAccountApi.CHECK_AUTH_PATH), eq(USER_KEY), any(), eq(CheckResult.class)))
                .thenThrow(FinApiFailures.rejected("checkAuthCode", "A1087"));

        link(bearer, token, CODE, key(), null).andExpect(errorCode(400, "AUTH_CODE_EXPIRED"));
    }

    // ───────── 가입 후 추가 ─────────

    @Test
    void 가입_후에는_아직_연결_안_한_계좌만_보여주고_본인_인증만으로_1원_없이_연결한다() throws Exception {
        Member member = activeMember();
        long first = insertLinked(member.userId(), "088", ACCOUNT_NO);
        // 가입을 마친 사용자는 기준 계좌가 이미 있다(가입 중 첫 연결 때 정해짐).
        jdbc.update("UPDATE linked_account SET is_primary = 1 WHERE linked_account_id = ?", first);

        MvcResult listed = candidates(member.auth())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accounts.length()").value(1))
                .andExpect(jsonPath("$.data.accounts[0].maskedAccountNo").value("****-****-****-8810"))
                .andReturn();
        String kookmin = candidateId(listed, "8810");

        linkMember(member.auth(), List.of(kookmin), key(), member.stepUpToken())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accounts.length()").value(1))
                .andExpect(jsonPath("$.data.accounts[0].isPrimary").value(false))
                .andExpect(jsonPath("$.data.verifiedBy.method").value("PIN"))
                .andExpect(jsonPath("$.data.verifiedBy.auditLogId").value(member.auditLogId()));
        assertThat(jdbc.queryForObject("SELECT step_up_audit_log_id FROM linked_account WHERE account_last4 = '8810'",
                Long.class)).isEqualTo(member.auditLogId());
        verify(finApiClient, never()).post(eq(SsafyAccountApi.OPEN_AUTH_PATH), anyString(), any(), eq(Void.class));
        verify(finApiClient, never()).post(eq(SsafyAccountApi.CHECK_AUTH_PATH), anyString(), any(), eq(CheckResult.class));
    }

    @Test
    void 가입_후_본인_인증_토큰이_없으면_403이고_1원_인증_요청은_400() throws Exception {
        Member member = activeMember();
        MvcResult listed = candidates(member.auth()).andReturn();
        String shinhan = candidateId(listed, "6547");

        linkMember(member.auth(), List.of(shinhan), key(), null).andExpect(errorCode(403, "STEP_UP_REQUIRED"));
        linkMember(member.auth(), List.of(shinhan), key(), "sut_wrong").andExpect(errorCode(403, "STEP_UP_REQUIRED"));
        oneWon(member.auth(), List.of(shinhan), shinhan).andExpect(errorCode(400, "INVALID_REQUEST"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM linked_account", Integer.class)).isZero();
    }

    @Test
    void 가입_후_고른_계좌가_전부_이미_연결이면_본인_인증을_쓰기_전에_409() throws Exception {
        Member member = activeMember();
        String shinhan = candidateId(candidates(member.auth()).andReturn(), "6547");
        insertLinked(member.userId(), "088", ACCOUNT_NO);

        linkMember(member.auth(), List.of(shinhan), key(), member.stepUpToken())
                .andExpect(errorCode(409, "ACCOUNT_ALREADY_LINKED"));
        // 본인 인증 토큰은 쓰지 않았다.
        linkMember(member.auth(), List.of(candidateId(candidates(member.auth()).andReturn(), "8810")), key(),
                member.stepUpToken()).andExpect(status().isCreated());
    }

    @Test
    void 가입_중에_받은_후보는_가입_후_연결에_쓸_수_없다() throws Exception {
        Member member = activeMember();
        jdbc.update("UPDATE app_user SET status = 'PENDING', pin_hash = NULL, pin_updated_at = NULL WHERE user_id = ?",
                member.userId());
        String signupCandidate = candidateId(candidates(signupBearer(member.userId(), SignupMode.SIGNUP)).andReturn(), "6547");
        activate(member.userId());

        linkMember(member.auth(), List.of(signupCandidate), key(), member.stepUpToken())
                .andExpect(errorCode(400, "ACCOUNT_CANDIDATE_EXPIRED"));
    }

    // ───────── 멱등성·동시 요청 ─────────

    @Test
    void 같은_Idempotency_Key로_다시_부르면_같은_결과를_돌려주고_다른_요청에_쓰면_409() throws Exception {
        String bearer = signupBearer(pendingUser(), SignupMode.SIGNUP);
        String shinhan = candidateId(candidates(bearer).andReturn(), "6547");
        String token = sentToken(bearer, List.of(shinhan), shinhan);
        String key = key();

        String first = read(link(bearer, token, CODE, key, null).andExpect(status().isCreated()).andReturn(),
                "$.data.accounts[0].accountId");
        link(bearer, token, CODE, key, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accounts[0].accountId").value(Long.parseLong(first)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM linked_account", Integer.class)).isEqualTo(1);
        verify(finApiClient, times(1)).post(eq(SsafyAccountApi.CHECK_AUTH_PATH), eq(USER_KEY), any(), eq(CheckResult.class));

        link(bearer, "avt_another", CODE, key, null).andExpect(errorCode(409, "IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void 같은_Idempotency_Key가_동시에_오면_하나만_연결하고_다른_하나는_409_끝난_뒤_다시_부르면_처음_결과() throws Exception {
        String bearer = signupBearer(pendingUser(), SignupMode.SIGNUP);
        MvcResult listed = candidates(bearer).andReturn();
        String token = sentToken(bearer, List.of(candidateId(listed, "6547"), candidateId(listed, "8810")),
                candidateId(listed, "6547"));
        slowCodeCheck();
        String key = key();

        List<Integer> statuses = Concurrently.run(2,
                () -> link(bearer, token, CODE, key, null).andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM linked_account", Integer.class)).isEqualTo(2);
        link(bearer, token, CODE, key, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accounts.length()").value(2));
        verify(finApiClient, times(1)).post(eq(SsafyAccountApi.CHECK_AUTH_PATH), eq(USER_KEY), any(), eq(CheckResult.class));
    }

    @Test
    void 같은_계좌를_다른_1원_토큰으로_동시에_연결해도_계좌는_한_번만_연결되고_늦은_쪽은_skipped() throws Exception {
        String bearer = signupBearer(pendingUser(), SignupMode.SIGNUP);
        String shinhan = candidateId(candidates(bearer).andReturn(), "6547");
        String first = sentToken(bearer, List.of(shinhan), shinhan);
        redis.delete(redis.keys("onewon:resend:*"));
        String second = sentToken(bearer, List.of(shinhan), shinhan);
        slowCodeCheck();

        List<MvcResult> results = Concurrently.run(List.of(
                () -> link(bearer, first, CODE, key(), null).andReturn(),
                () -> link(bearer, second, CODE, key(), null).andReturn()));

        // 가입 중에는 이미 연결된 계좌를 실패로 보지 않는다(1원 재인증 경로). 둘 다 201이고 새로 연결한 쪽은 하나뿐이다.
        assertThat(results).allSatisfy(r -> assertThat(r.getResponse().getStatus()).isEqualTo(201));
        int linkedTotal = 0;
        for (MvcResult r : results) {
            linkedTotal += JsonPath.<Integer>read(r.getResponse().getContentAsString(), "$.data.accounts.length()");
        }
        assertThat(linkedTotal).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM linked_account", Integer.class)).isEqualTo(1);
    }

    @Test
    void 가입_후_같은_계좌를_다른_키로_동시에_연결해도_한_번만_연결된다() throws Exception {
        Member member = activeMember();
        String kookmin = candidateId(candidates(member.auth()).andReturn(), "8810");
        String secondStepUp = stepUpTokenStore.issue(new StepUpGrant(member.userId(), member.deviceId(),
                StepUpPurpose.ACCOUNT_LINK, AuthMethod.PIN, member.auditLogId()));

        List<Integer> statuses = Concurrently.run(List.of(
                () -> linkMember(member.auth(), List.of(kookmin), key(), member.stepUpToken()).andReturn().getResponse().getStatus(),
                () -> linkMember(member.auth(), List.of(kookmin), key(), secondStepUp).andReturn().getResponse().getStatus()));

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM linked_account", Integer.class)).isEqualTo(1);
    }

    @Test
    void Idempotency_Key가_없거나_형식이_틀리면_400() throws Exception {
        String bearer = signupBearer(pendingUser(), SignupMode.SIGNUP);
        String shinhan = candidateId(candidates(bearer).andReturn(), "6547");
        String token = sentToken(bearer, List.of(shinhan), shinhan);

        link(bearer, token, CODE, null, null)
                .andExpect(errorCode(400, "INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("Idempotency-Key"));
        link(bearer, token, CODE, "short", null).andExpect(errorCode(400, "INVALID_REQUEST"));
        link(bearer, token, CODE, "a:b:c:d:e:f", null).andExpect(errorCode(400, "INVALID_REQUEST"));
        verify(finApiClient, never()).post(eq(SsafyAccountApi.CHECK_AUTH_PATH), anyString(), any(), eq(CheckResult.class));
    }

    // ───────── 토큰·기기 재등록 ─────────

    @Test
    void 가입_진행_토큰은_모드와_사용자_상태가_맞을_때만_받는다() throws Exception {
        // 가입을 마친 사람의 남은 SIGNUP 토큰: 본인 인증 없이 계좌를 연결하는 길이 되므로 막는다.
        long active = insertUser("01012345678", USER_KEY, "ACTIVE");
        candidates(signupBearer(active, SignupMode.SIGNUP)).andExpect(errorCode(401, "SIGNUP_TOKEN_INVALID"));
        // 가입 중인 사람의 기기 재등록 토큰도 받지 않는다(재등록은 가입을 마친 사람만).
        candidates(signupBearer(strangerUser(), SignupMode.DEVICE_RECOVERY)).andExpect(errorCode(401, "SIGNUP_TOKEN_INVALID"));
        verify(finApiClient, never()).post(eq(SsafyAccountApi.LIST_PATH), anyString(), any(), eq(JsonNode.class));
    }

    @Test
    void 토큰이_없으면_401_UNAUTHORIZED_가입_진행_토큰이_만료면_401_SIGNUP_TOKEN_INVALID() throws Exception {
        mockMvc.perform(get("/api/accounts/candidates")).andExpect(errorCode(401, "UNAUTHORIZED"));
        candidates("Bearer sgt_expired").andExpect(errorCode(401, "SIGNUP_TOKEN_INVALID"));
    }

    @Test
    void 기기_재등록은_연결해_둔_계좌만_보여주고_하나로만_1원_인증하며_연결_API는_409() throws Exception {
        long userId = pendingUser();
        insertLinked(userId, "088", ACCOUNT_NO);
        activate(userId);
        String recovery = signupBearer(userId, SignupMode.DEVICE_RECOVERY);

        MvcResult listed = candidates(recovery)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accounts.length()").value(1))
                .andExpect(jsonPath("$.data.accounts[0].maskedAccountNo").value("****-****-****-6547"))
                .andExpect(jsonPath("$.data.accounts[0].alreadyLinked").value(true))
                .andReturn();
        String shinhan = candidateId(listed, "6547");

        String token = sentToken(recovery, List.of(shinhan), shinhan);
        link(recovery, token, CODE, key(), null).andExpect(errorCode(409, "ACCOUNT_ALREADY_LINKED"));
        // 가입 중 후보(전체 목록)는 기기 재등록에 쓸 수 없다.
        jdbc.update("UPDATE app_user SET status = 'PENDING', pin_hash = NULL, pin_updated_at = NULL WHERE user_id = ?", userId);
        String kookmin = candidateId(candidates(signupBearer(userId, SignupMode.SIGNUP)).andReturn(), "8810");
        activate(userId);
        oneWon(recovery, List.of(kookmin), kookmin).andExpect(errorCode(400, "ACCOUNT_CANDIDATE_EXPIRED"));
    }

    // ───────── 도우미 ─────────

    private record Member(long userId, long deviceId, long auditLogId, String stepUpToken) {

        RequestPostProcessor auth() {
            return AuthUsers.asUser(userId, deviceId);
        }
    }

    /** 시드로 들어온 가입 중(PENDING) 사용자. 이름은 김야무, SSAFY 계좌 두 개. */
    private long pendingUser() {
        return insertUser("01012345678", USER_KEY, "PENDING");
    }

    /** 다른 시드 사용자(SSAFY 계좌 하나). */
    private long strangerUser() {
        return insertUser("01099998888", STRANGER_KEY, "PENDING");
    }

    private long insertUser(String phone, String userKey, String status) {
        Map<String, Object> values = new HashMap<>(Map.of(
                "name", "김야무",
                "birth_date", LocalDate.of(1999, 3, 15),
                "phone_enc", cipher.encrypt(phone, CipherField.APP_USER_PHONE),
                "phone_hash", lookupHasher.phone(phone),
                "identity_verified_at", now(),
                "ssafy_member_id", "y" + phone.substring(3) + "@yamu.app",
                "ssafy_user_key", cipher.encrypt(userKey, CipherField.APP_USER_SSAFY_USER_KEY),
                "status", status,
                "created_at", now(),
                "updated_at", now()));
        if ("ACTIVE".equals(status)) {
            values.put("pin_hash", "$2a$10$test");
            values.put("pin_updated_at", now());
        }
        return insert("app_user", "user_id", values);
    }

    /** 이미 연결된 계좌. */
    private long insertLinked(long userId, String bankCode, String accountNo) {
        return insert("linked_account", "linked_account_id", Map.of("user_id", userId, "bank_code", bankCode,
                "account_no_enc", cipher.encrypt(accountNo, CipherField.LINKED_ACCOUNT_NO),
                "account_no_hash", lookupHasher.account(bankCode, accountNo),
                "account_last4", accountNo.substring(accountNo.length() - 4), "status", "CONNECTED",
                "connected_at", now(), "created_at", now(), "updated_at", now()));
    }

    /** 가입 중이던 사용자를 가입 완료(ACTIVE)로 바꾼다. ACTIVE는 PIN이 있어야 한다(ck_user_active). */
    private void activate(long userId) {
        jdbc.update("UPDATE app_user SET status = 'ACTIVE', pin_hash = '$2a$10$test', pin_updated_at = ? WHERE user_id = ?",
                now(), userId);
    }

    /** 인증코드 확인을 잠깐 늦춰 두 요청이 확실히 겹치게 한다. */
    private void slowCodeCheck() {
        when(finApiClient.post(eq(SsafyAccountApi.CHECK_AUTH_PATH), eq(USER_KEY), any(), eq(CheckResult.class)))
                .thenAnswer(inv -> {
                    Thread.sleep(300);
                    return new CheckResult("SUCCESS");
                });
    }

    /** 오늘(한국 날짜) 하루 횟수 키. 테스트마다 1원을 받는 계좌가 하나라 키도 하나다. */
    private String dailyKey(String kind) {
        return redis.keys("onewon:" + kind + ":*:" + LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.BASIC_ISO_DATE))
                .iterator().next();
    }

    /** 가입을 마친 사용자 + 기기 + ACCOUNT_LINK 본인 인증 토큰. SSAFY 계좌는 김야무와 같은 두 개. */
    private Member activeMember() {
        long userId = insertUser("01012345678", USER_KEY, "ACTIVE");
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

    private String signupBearer(long userId, SignupMode mode) {
        String phone = "01012345678";
        VerifiedIdentity identity = new VerifiedIdentity("김야무", LocalDate.of(1999, 3, 15),
                cipher.encrypt(phone, CipherField.APP_USER_PHONE), lookupHasher.phone(phone));
        return "Bearer " + signupTokenStore.issue(
                new SignupSession(mode, identity, LocalDateTime.now(ZoneOffset.UTC), userId)).token();
    }

    private JsonNode accounts(JsonNode... rows) {
        ArrayNode array = objectMapper.createArrayNode();
        for (JsonNode row : rows) {
            array.add(row);
        }
        return array;
    }

    private JsonNode row(String bankCode, String bankName, String accountNo, String accountName) {
        return objectMapper.createObjectNode().put("bankCode", bankCode).put("bankName", bankName)
                .put("accountNo", accountNo).put("accountName", accountName).put("accountBalance", "1250000");
    }

    /** 목록에서 끝 4자리가 같은 계좌의 candidateId. */
    private static String candidateId(MvcResult listed, String last4) throws Exception {
        List<Map<String, Object>> rows = JsonPath.read(listed.getResponse().getContentAsString(), "$.data.accounts");
        return rows.stream().filter(r -> r.get("maskedAccountNo").toString().endsWith(last4))
                .map(r -> r.get("candidateId").toString()).findFirst().orElseThrow();
    }

    /** 1원까지 보내고 verificationToken을 돌려준다. */
    private String sentToken(String bearer, List<String> ids, String primary) throws Exception {
        return read(oneWon(bearer, ids, primary).andExpect(status().isOk()).andReturn(), "$.data.verificationToken");
    }

    private ResultActions candidates(String bearer) throws Exception {
        return mockMvc.perform(get("/api/accounts/candidates").header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private ResultActions candidates(RequestPostProcessor auth) throws Exception {
        return mockMvc.perform(get("/api/accounts/candidates").with(auth));
    }

    private ResultActions oneWon(String bearer, List<String> ids, String primary) throws Exception {
        return perform(post("/api/accounts/one-won-auth").header(HttpHeaders.AUTHORIZATION, bearer), oneWonJson(ids, primary));
    }

    private ResultActions oneWon(RequestPostProcessor auth, List<String> ids, String primary) throws Exception {
        return perform(post("/api/accounts/one-won-auth").with(auth), oneWonJson(ids, primary));
    }

    private String oneWonJson(List<String> ids, String primary) throws Exception {
        return objectMapper.writeValueAsString(Map.of("candidateIds", ids, "primaryCandidateId", primary));
    }

    private ResultActions link(String bearer, String token, String code, String idempotencyKey, String stepUpToken)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("verificationToken", token);
        body.put("authCode", code);
        return link(post("/api/accounts").header(HttpHeaders.AUTHORIZATION, bearer), body, idempotencyKey, stepUpToken);
    }

    private ResultActions linkMember(RequestPostProcessor auth, List<String> ids, String idempotencyKey,
            String stepUpToken) throws Exception {
        return link(post("/api/accounts").with(auth), Map.of("candidateIds", new ArrayList<>(ids)), idempotencyKey,
                stepUpToken);
    }

    private ResultActions link(MockHttpServletRequestBuilder builder, Map<String, Object> body, String idempotencyKey,
            String stepUpToken) throws Exception {
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        if (stepUpToken != null) {
            builder.header("X-Step-Up-Token", stepUpToken);
        }
        return perform(builder, objectMapper.writeValueAsString(body));
    }

    private ResultActions perform(MockHttpServletRequestBuilder builder, String json) throws Exception {
        return mockMvc.perform(builder.contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String key() {
        return UUID.randomUUID().toString();
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
