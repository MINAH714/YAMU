package com.yamu.be.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.yamu.be.auth.SsafyMemberRegistrar.MemberResponse;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.LookupHasher;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.crypto.PinHasher;
import com.yamu.be.global.finapi.FinApiClient;
import com.yamu.be.global.finapi.FinApiFailures;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import com.yamu.be.user.domain.AppUser;
import com.yamu.be.user.domain.AppUserRepository;
import com.yamu.be.user.domain.AppUserStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
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
 * 휴대폰 본인인증(연출) → 가입 진행 토큰 → 회원가입을 실제 MySQL·Redis로 확인한다. SSAFY 금융망은 가짜(FinApiClient mock)다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-signup-integration-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SignupIntegrationTest extends MySqlTestSupport {

    private static final String PHONE = "01012345678";
    private static final String BIRTH = "1999-03-15";
    private static final String USER_KEY = "2695628f-11e1-418e-b533-9ae19e0650ec";

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired AppUserRepository userRepository;
    @Autowired PersonalDataCipher cipher;
    @Autowired LookupHasher lookupHasher;
    @Autowired PinHasher pinHasher;

    @MockitoBean FinApiClient finApiClient;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM linked_account");
        jdbc.update("DELETE FROM user_device");
        jdbc.update("DELETE FROM app_user");
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
        when(finApiClient.postWithApiKey(eq("/member"), any(), eq(MemberResponse.class)))
                .thenAnswer(inv -> new MemberResponse(memberIdOf(inv.getArgument(1)), USER_KEY));
    }

    // ───────── 성공 흐름 ─────────

    @Test
    void 본인인증_요청_확인_회원가입을_하면_PENDING_사용자와_SSAFY_계정이_만들어진다() throws Exception {
        MvcResult requested = requestVerification("김야무", BIRTH, "SKT", PHONE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verificationId").exists())
                .andExpect(jsonPath("$.data.expiresAt").exists())
                .andReturn();
        String verificationId = read(requested, "$.data.verificationId");
        String code = read(requested, "$.data.demoCode");
        assertThat(code).matches("\\d{6}");
        assertThat(redis.getExpire("auth:phone-verify:" + verificationId)).isBetween(1L, 180L);

        MvcResult confirmed = confirm(verificationId, code)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresIn").value(1800))
                .andExpect(jsonPath("$.data.mode").value("SIGNUP"))
                .andExpect(jsonPath("$.data.nextStep").value("SIGNUP"))
                .andReturn();
        String token = read(confirmed, "$.data.signupToken");
        assertThat(token).startsWith("sgt_");
        assertThat(redis.hasKey("auth:phone-verify:" + verificationId)).isFalse();
        assertThat(redis.getExpire("signup:" + sha256Hex(token))).isBetween(1L, 1800L);

        MvcResult signedUp = signup(token)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("김야무"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.finAccountLinked").value(true))
                .andExpect(jsonPath("$.data.nextSteps[0]").value("ACCOUNT_LINK"))
                .andExpect(jsonPath("$.data.nextSteps[1]").value("PIN_SETUP"))
                .andExpect(jsonPath("$.data.nextSteps[2]").value("DEVICE_REGISTER"))
                .andExpect(jsonPath("$.data.createdAt").exists())
                .andReturn();
        long userId = Long.parseLong(read(signedUp, "$.data.userId"));

        AppUser user = userRepository.findById(userId).orElseThrow();
        assertThat(user.getStatus()).isEqualTo(AppUserStatus.PENDING);
        assertThat(user.getBirthDate()).isEqualTo(LocalDate.parse(BIRTH));
        assertThat(user.getIdentityVerifiedAt()).isNotNull();
        assertThat(user.getSsafyMemberId()).matches("y[a-z0-9]{9}@yamu\\.app");
        assertThat(cipher.decrypt(user.getPhoneEnc(), CipherField.APP_USER_PHONE)).isEqualTo(PHONE);
        assertThat(user.getPhoneHash()).isEqualTo(lookupHasher.phone(PHONE));
        assertThat(cipher.decrypt(user.getSsafyUserKey(), CipherField.APP_USER_SSAFY_USER_KEY)).isEqualTo(USER_KEY);
        assertThat(signedUp.getResponse().getContentAsString()).doesNotContain(USER_KEY);
        assertThat(redis.opsForHash().get("signup:" + sha256Hex(token), "userId")).isEqualTo(String.valueOf(userId));
    }

    @Test
    void 같은_토큰으로_회원가입을_다시_부르면_새로_만들지_않고_같은_사용자를_돌려준다() throws Exception {
        String token = verifiedToken(PHONE);
        String first = read(signup(token).andReturn(), "$.data.userId");

        signup(token).andExpect(status().isCreated()).andExpect(jsonPath("$.data.userId").value(Long.valueOf(first)));

        assertThat(userRepository.count()).isEqualTo(1);
        verify(finApiClient, times(1)).postWithApiKey(eq("/member"), any(), eq(MemberResponse.class));
    }

    // ───────── 기존 사용자 ─────────

    @Test
    void 가입_중이던_사람은_SIGNUP_이어하기로_멈춘_단계를_받는다() throws Exception {
        AppUser pending = savePending(PHONE);
        new OwnershipFixtures(jdbc).account(pending.getId());

        String token = read(confirmFor(PHONE).andExpect(jsonPath("$.data.mode").value("SIGNUP"))
                .andExpect(jsonPath("$.data.nextStep").value("PIN_SETUP")).andReturn(), "$.data.signupToken");

        signup(token).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.userId").value(pending.getId()))
                .andExpect(jsonPath("$.data.nextSteps.length()").value(2))
                .andExpect(jsonPath("$.data.nextSteps[0]").value("PIN_SETUP"));
        verify(finApiClient, never()).postWithApiKey(any(), any(), any());
    }

    @Test
    void 이미_가입한_사람은_DEVICE_RECOVERY_모드이고_회원가입은_409_ALREADY_REGISTERED다() throws Exception {
        AppUser active = savePending(PHONE);
        active.changePin(pinHasher.hash("482913"), LocalDateTime.now(ZoneOffset.UTC));
        active.activate();
        userRepository.save(active);

        String token = read(confirmFor(PHONE).andExpect(jsonPath("$.data.mode").value("DEVICE_RECOVERY"))
                .andExpect(jsonPath("$.data.nextStep").value("ACCOUNT_LINK")).andReturn(), "$.data.signupToken");

        signup(token).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ALREADY_REGISTERED"));
        verify(finApiClient, never()).postWithApiKey(any(), any(), any());
    }

    // ───────── 인증번호 ─────────

    @Test
    void 인증번호를_틀리면_남은_횟수를_주고_5번_틀리면_요청부터_다시_해야_한다() throws Exception {
        MvcResult requested = requestVerification("김야무", BIRTH, "KT", PHONE).andReturn();
        String verificationId = read(requested, "$.data.verificationId");
        String code = read(requested, "$.data.demoCode");
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int remaining = 4; remaining >= 0; remaining--) {
            confirm(verificationId, wrong).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VERIFICATION_CODE_MISMATCH"))
                    .andExpect(jsonPath("$.error.details.remainingAttempts").value(remaining));
        }
        confirm(verificationId, code).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VERIFICATION_EXPIRED"));
    }

    @Test
    void 성공한_인증번호를_다시_쓰거나_없는_번호로_확인하면_VERIFICATION_EXPIRED다() throws Exception {
        MvcResult requested = requestVerification("김야무", BIRTH, "LGU", PHONE).andReturn();
        String verificationId = read(requested, "$.data.verificationId");
        String code = read(requested, "$.data.demoCode");
        confirm(verificationId, code).andExpect(status().isOk());

        confirm(verificationId, code).andExpect(jsonPath("$.error.code").value("VERIFICATION_EXPIRED"));
        confirm("pv_unknown", "123456").andExpect(jsonPath("$.error.code").value("VERIFICATION_EXPIRED"));
    }

    @Test
    void 같은_번호로_1분_안에_다시_요청하면_429_VERIFICATION_TOO_FREQUENT다() throws Exception {
        requestVerification("김야무", BIRTH, "SKT", PHONE).andExpect(status().isOk());

        requestVerification("김야무", BIRTH, "SKT", "010-1234-5678".replace("-", ""))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("VERIFICATION_TOO_FREQUENT"))
                .andExpect(jsonPath("$.error.details.retryAfterSeconds").isNumber());
        requestVerification("김야무", BIRTH, "SKT", "01099998888").andExpect(status().isOk());
    }

    @Test
    void 형식이_틀린_요청은_400_INVALID_REQUEST다() throws Exception {
        requestVerification("김", BIRTH, "SKT", PHONE).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        requestVerification("김야무", BIRTH, "SKB", PHONE).andExpect(status().isBadRequest());
        requestVerification("김야무", BIRTH, "SKT", "0212345678").andExpect(status().isBadRequest());
        requestVerification("김야무", "2999-01-01", "SKT", PHONE).andExpect(status().isBadRequest());
        confirm("pv_any", "12a456").andExpect(status().isBadRequest());
    }

    // ───────── 가입 진행 토큰·금융망 ─────────

    @Test
    void 가입_진행_토큰이_없거나_엉터리면_401_SIGNUP_TOKEN_INVALID다() throws Exception {
        mockMvc.perform(post("/api/auth/signup")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("SIGNUP_TOKEN_INVALID"));
        signup("sgt_forged").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("SIGNUP_TOKEN_INVALID"));
        // Access Token(JWT)을 넣어도 가입 진행 토큰이 아니다
        signup("eyJhbGciOiJIUzI1NiJ9.e30.x").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("SIGNUP_TOKEN_INVALID"));
    }

    @Test
    void SSAFY_계정_ID가_겹치면_새_ID로_다시_만든다() throws Exception {
        when(finApiClient.postWithApiKey(eq("/member"), any(), eq(MemberResponse.class)))
                .thenThrow(FinApiFailures.rejected("member", "E4002"))
                .thenAnswer(inv -> new MemberResponse(memberIdOf(inv.getArgument(1)), USER_KEY));

        signup(verifiedToken(PHONE)).andExpect(status().isCreated());

        ArgumentCaptor<Object> bodies = ArgumentCaptor.forClass(Object.class);
        verify(finApiClient, times(2)).postWithApiKey(eq("/member"), bodies.capture(), eq(MemberResponse.class));
        List<String> ids = bodies.getAllValues().stream().map(SignupIntegrationTest::memberIdOf).toList();
        assertThat(ids.get(0)).isNotEqualTo(ids.get(1));
        assertThat(userRepository.findAll()).singleElement()
                .satisfies(user -> assertThat(user.getSsafyMemberId()).isEqualTo(ids.get(1)));
    }

    @Test
    void SSAFY_계정_생성이_실패하면_502이고_사용자를_만들지_않는다() throws Exception {
        when(finApiClient.postWithApiKey(eq("/member"), any(), eq(MemberResponse.class)))
                .thenThrow(FinApiFailures.notSent("member"));
        String token = verifiedToken(PHONE);

        signup(token).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("FIN_API_UNAVAILABLE"));

        assertThat(userRepository.count()).isZero();
        assertThat(redis.opsForHash().get("signup:" + sha256Hex(token), "userId")).isNull();
    }

    @Test
    void SSAFY가_다른_오류로_거절하면_다시_만들지_않고_502다() throws Exception {
        when(finApiClient.postWithApiKey(eq("/member"), any(), eq(MemberResponse.class)))
                .thenThrow(FinApiFailures.rejected("member", "E4001"));

        signup(verifiedToken(PHONE)).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("FIN_API_ERROR"));

        verify(finApiClient, times(1)).postWithApiKey(eq("/member"), any(), eq(MemberResponse.class));
        assertThat(userRepository.count()).isZero();
    }

    // ───────── 도우미 ─────────

    private ResultActions requestVerification(String name, String birthDate, String carrier, String phone)
            throws Exception {
        String body = """
                {"name":"%s","birthDate":"%s","carrier":"%s","phoneNumber":"%s"}
                """.formatted(name, birthDate, carrier, phone);
        return mockMvc.perform(post("/api/auth/phone-verifications").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions confirm(String verificationId, String code) throws Exception {
        return mockMvc.perform(post("/api/auth/phone-verifications/{id}/confirm", verificationId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"));
    }

    private ResultActions confirmFor(String phone) throws Exception {
        MvcResult requested = requestVerification("김야무", BIRTH, "SKT", phone).andReturn();
        return confirm(read(requested, "$.data.verificationId"), read(requested, "$.data.demoCode"));
    }

    private String verifiedToken(String phone) throws Exception {
        return read(confirmFor(phone).andReturn(), "$.data.signupToken");
    }

    private ResultActions signup(String token) throws Exception {
        return mockMvc.perform(post("/api/auth/signup").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private AppUser savePending(String phone) {
        return userRepository.save(AppUser.pending("김야무", LocalDate.parse(BIRTH),
                cipher.encrypt(phone, CipherField.APP_USER_PHONE), lookupHasher.phone(phone),
                LocalDateTime.now(ZoneOffset.UTC)));
    }

    private static String memberIdOf(Object body) {
        return (String) ((Map<?, ?>) body).get("userId");
    }

    private static String sha256Hex(String token) throws Exception {
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(hash);
    }

    private static String read(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path).toString();
    }
}
