package com.yamu.be.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.yamu.be.account.SsafyAccountFakes;
import com.yamu.be.auth.SignupTokenStore.SignupSession;
import com.yamu.be.auth.StepUpTokenStore.StepUpGrant;
import com.yamu.be.auth.domain.AuthMethod;
import com.yamu.be.auth.domain.SignupMode;
import com.yamu.be.auth.domain.StepUpPurpose;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.LookupHasher;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.crypto.PinHasher;
import com.yamu.be.global.finapi.FinApiClient;
import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.Concurrently;
import com.yamu.be.support.DeviceKeys;
import com.yamu.be.support.MySqlTestSupport;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

/**
 * 간편 비밀번호 설정 → 기기 등록(가입 완료), 기기 재등록, 로그인 후 PIN 변경·생체 켜기를 실제 MySQL·Redis로 확인한다.
 * 계좌 목록 → 1원 인증 → 연결은 실제 API를 거치고, SSAFY 금융망만 가짜(FinApiClient mock)다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-device-setup-integration-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeviceSetupIntegrationTest extends MySqlTestSupport {

    private static final String ACCOUNT_NO = "0881541149756547";
    private static final String USER_KEY = "2695628f-11e1-418e-b533-9ae19e0650ec";
    private static final String MEMBER_LOCAL = "yabc123456";
    private static final String PHONE = "01012345678";
    private static final String CODE = "4821";
    private static final String PIN = "482913";
    private static final String NEW_PIN = "730518";

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PersonalDataCipher cipher;
    @Autowired LookupHasher lookupHasher;
    @Autowired PinHasher pinHasher;
    @Autowired SignupTokenStore signupTokenStore;
    @Autowired StepUpTokenStore stepUpTokenStore;
    @Autowired RefreshTokenStore refreshTokenStore;
    @Autowired TransactionTemplate transaction;

    @MockitoBean FinApiClient finApiClient;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM linked_account");
        jdbc.update("DELETE FROM step_up_audit_log");
        jdbc.update("DELETE FROM user_device_event");
        jdbc.update("DELETE FROM user_device");
        jdbc.update("DELETE FROM app_user");
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
        SsafyAccountFakes.stub(finApiClient, USER_KEY, ACCOUNT_NO, CODE);
    }

    // ───────── 가입 완료 ─────────

    @Test
    void 계좌_연결_PIN_설정_기기_등록을_마치면_ACTIVE가_되고_토큰을_받아_그_기기로_로그인한다() throws Exception {
        long userId = pendingUser();
        String signupToken = signupToken(userId, SignupMode.SIGNUP);
        String bearer = "Bearer " + signupToken;
        linkAccount(bearer);
        DeviceKeys keys = DeviceKeys.generate();
        DeviceKeys biometric = DeviceKeys.generate();

        setPin(bearer, PIN).andExpect(status().isNoContent());
        MvcResult registered = registerDevice(bearer, deviceJson(UUID.randomUUID().toString(), keys, biometric, null, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.userDeviceId").isNumber())
                .andExpect(jsonPath("$.data.deviceName").value("Galaxy S24"))
                .andExpect(jsonPath("$.data.biometricEnabled").value(true))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.registeredAt").exists())
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andExpect(jsonPath("$.data.refreshToken").isString())
                .andExpect(jsonPath("$.data.expiresIn").value(1800))
                .andReturn();
        long deviceId = Long.parseLong(read(registered, "$.data.userDeviceId"));

        assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE user_id = ?", String.class, userId))
                .isEqualTo("ACTIVE");
        assertThat(pinHasher.matches(PIN, pinHashOf(userId))).isTrue();
        assertThat(jdbc.queryForObject("SELECT key_algorithm FROM user_device WHERE user_device_id = ?", String.class,
                deviceId)).isEqualTo(DeviceSignatureVerifier.EC_P256);
        assertThat(eventTypes(deviceId)).containsExactly("REGISTERED");
        // 가입 진행 토큰은 폐기된다.
        assertThat(signupTokenStore.find(signupToken)).isEmpty();
        registerDevice(bearer, deviceJson(UUID.randomUUID().toString(), keys, null, null, null))
                .andExpect(errorCode(401, "SIGNUP_TOKEN_INVALID"));

        login(deviceId, keys, PIN).andExpect(status().isOk()).andExpect(jsonPath("$.data.user.userId").value(userId));
    }

    @Test
    void 가입_순서를_건너뛰면_409_SIGNUP_STEP_INCOMPLETE와_다음_단계() throws Exception {
        long userId = pendingUser();
        String bearer = "Bearer " + signupToken(userId, SignupMode.SIGNUP);
        DeviceKeys keys = DeviceKeys.generate();
        setPin(bearer, PIN).andExpect(errorCode(409, "SIGNUP_STEP_INCOMPLETE"))
                .andExpect(jsonPath("$.error.details.nextStep").value("ACCOUNT_LINK"));
        registerDevice(bearer, deviceJson(UUID.randomUUID().toString(), keys, null, null, null))
                .andExpect(errorCode(409, "SIGNUP_STEP_INCOMPLETE"))
                .andExpect(jsonPath("$.error.details.nextStep").value("ACCOUNT_LINK"));

        linkAccount(bearer);
        registerDevice(bearer, deviceJson(UUID.randomUUID().toString(), keys, null, null, null))
                .andExpect(errorCode(409, "SIGNUP_STEP_INCOMPLETE"))
                .andExpect(jsonPath("$.error.details.nextStep").value("PIN_SETUP"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_device", Integer.class)).isZero();
    }

    @Test
    void 같은_숫자_반복_연속_숫자_6자리가_아닌_PIN은_400_INVALID_PIN_FORMAT() throws Exception {
        long userId = pendingUser();
        String bearer = "Bearer " + signupToken(userId, SignupMode.SIGNUP);
        linkAccount(bearer);

        for (String pin : List.of("12345", "1234567", "12a456", "111111", "123456", "987654", "890123x")) {
            setPin(bearer, pin).andExpect(errorCode(400, "INVALID_PIN_FORMAT"));
        }
        perform(post("/api/auth/pin").header(HttpHeaders.AUTHORIZATION, bearer), "{}")
                .andExpect(errorCode(400, "INVALID_PIN_FORMAT"));
        assertThat(pinHashOf(userId)).isNull();
        setPin(bearer, "890123").andExpect(status().isNoContent());
    }

    @Test
    void EC_P256이_아닌_공개키는_400_INVALID_PUBLIC_KEY_생체_키에_종류가_없으면_400() throws Exception {
        String bearer = readyForDevice();
        DeviceKeys keys = DeviceKeys.generate();
        String uuid = UUID.randomUUID().toString();

        for (String bad : List.of("not-base64!", Base64.getEncoder().encodeToString("hello".getBytes()), rsaKey(),
                ecKey("secp384r1"))) {
            registerDevice(bearer, json(uuid, bad, null, null, null, null))
                    .andExpect(errorCode(400, "INVALID_PUBLIC_KEY"));
        }
        registerDevice(bearer, json(uuid, keys.publicKey(), rsaKey(), "[\"FINGERPRINT\"]", null, null))
                .andExpect(errorCode(400, "INVALID_PUBLIC_KEY"));
        registerDevice(bearer, json(uuid, keys.publicKey(), DeviceKeys.generate().publicKey(), null, null, null))
                .andExpect(errorCode(400, "INVALID_REQUEST"));
        registerDevice(bearer, "{\"deviceId\":\"not-a-uuid\",\"platform\":\"ANDROID\",\"devicePublicKey\":\""
                + keys.publicKey() + "\"}").andExpect(errorCode(400, "INVALID_REQUEST"));
        assertThat(jdbc.queryForObject("SELECT status FROM app_user", String.class)).isEqualTo("PENDING");
    }

    @Test
    void 같은_가입_진행_토큰으로_기기_등록이_동시에_두_번_와도_사용자와_기기는_하나씩이다() throws Exception {
        String bearer = readyForDevice();
        DeviceKeys keys = DeviceKeys.generate();
        String body = deviceJson(UUID.randomUUID().toString(), keys, null, null, null);

        List<Integer> statuses = runTogether(2, () -> registerDevice(bearer, body).andReturn().getResponse().getStatus());

        // 늦은 쪽은 사용자 행 잠금을 기다렸다가 이미 ACTIVE라 409, 또는 토큰이 이미 폐기돼 401이다.
        assertThat(statuses).hasSize(2).containsOnlyOnce(201);
        assertThat(statuses).anyMatch(status -> status == 409 || status == 401);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_device", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM app_user", String.class)).isEqualTo("ACTIVE");
    }

    // ───────── 기기 재등록 ─────────

    @Test
    void 기기_재등록은_1원_인증코드가_맞아야_새_기기를_등록하고_맡겨_둔_새_PIN을_저장한다() throws Exception {
        Registered member = activeMember();
        String bearer = "Bearer " + signupToken(member.userId(), SignupMode.DEVICE_RECOVERY);
        DeviceKeys newKeys = DeviceKeys.generate();
        String newUuid = UUID.randomUUID().toString();

        // PIN은 1원 인증 전이라 바로 바뀌지 않는다.
        setPin(bearer, NEW_PIN).andExpect(status().isNoContent());
        assertThat(pinHasher.matches(PIN, pinHashOf(member.userId()))).isTrue();

        String verification = verifiedAndSent(bearer);
        registerDevice(bearer, deviceJson(newUuid, newKeys, null, null, null))
                .andExpect(errorCode(400, "INVALID_REQUEST"));
        registerDevice(bearer, deviceJson(newUuid, newKeys, null, verification, "0000"))
                .andExpect(errorCode(400, "AUTH_CODE_MISMATCH"))
                .andExpect(jsonPath("$.error.details.remainingAttempts").value(2));
        assertThat(pinHasher.matches(PIN, pinHashOf(member.userId()))).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_device", Integer.class)).isEqualTo(1);

        MvcResult registered = registerDevice(bearer, deviceJson(newUuid, newKeys, null, verification, CODE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andReturn();
        long newDeviceId = Long.parseLong(read(registered, "$.data.userDeviceId"));

        assertThat(newDeviceId).isNotEqualTo(member.deviceId());
        assertThat(pinHasher.matches(NEW_PIN, pinHashOf(member.userId()))).isTrue();
        login(newDeviceId, newKeys, NEW_PIN).andExpect(status().isOk());
        // 계좌 확인 토큰도 다 썼다.
        registerDevice(bearer, deviceJson(newUuid, newKeys, null, verification, CODE))
                .andExpect(errorCode(401, "SIGNUP_TOKEN_INVALID"));
    }

    @Test
    void 기기_재등록에서_PIN을_다시_정하지_않으면_기존_PIN을_그대로_쓴다() throws Exception {
        Registered member = activeMember();
        String bearer = "Bearer " + signupToken(member.userId(), SignupMode.DEVICE_RECOVERY);
        String verification = verifiedAndSent(bearer);

        registerDevice(bearer, deviceJson(UUID.randomUUID().toString(), DeviceKeys.generate(), null, verification, CODE))
                .andExpect(status().isCreated());

        assertThat(pinHasher.matches(PIN, pinHashOf(member.userId()))).isTrue();
    }

    @Test
    void 같은_폰을_다시_등록하면_새_행을_만들지_않고_키를_바꾸며_잠금도_풀린다() throws Exception {
        Registered member = activeMember();
        jdbc.update("UPDATE user_device SET status = 'LOCKED', failed_count = 5, locked_until = ? WHERE user_device_id = ?",
                Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(30)), member.deviceId());
        String bearer = "Bearer " + signupToken(member.userId(), SignupMode.DEVICE_RECOVERY);
        String verification = verifiedAndSent(bearer);
        DeviceKeys rotated = DeviceKeys.generate();

        registerDevice(bearer, deviceJson(member.deviceUuid().toUpperCase(), rotated, null, verification, CODE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.userDeviceId").value(member.deviceId()))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_device", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT public_key FROM user_device", String.class)).isEqualTo(rotated.publicKey());
        assertThat(jdbc.queryForObject("SELECT failed_count FROM user_device", Integer.class)).isZero();
        assertThat(eventTypes(member.deviceId())).containsExactly("REGISTERED", "KEY_ROTATED");
        login(member.deviceId(), rotated, PIN).andExpect(status().isOk());
    }

    @Test
    void 해제되지_않은_기기가_여러_대_남아_있어도_기기_재등록은_되고_나머지_기기는_해제된다() throws Exception {
        Registered member = activeMember();
        long second = insertDevice(member.userId(), "ACTIVE");
        long third = insertDevice(member.userId(), "LOCKED");
        long revokedBefore = insertDevice(member.userId(), "REVOKED");
        String bearer = "Bearer " + signupToken(member.userId(), SignupMode.DEVICE_RECOVERY);
        String verification = verifiedAndSent(bearer);

        MvcResult registered = registerDevice(bearer,
                deviceJson(UUID.randomUUID().toString(), DeviceKeys.generate(), null, verification, CODE))
                .andExpect(status().isCreated()).andReturn();
        long newDeviceId = Long.parseLong(read(registered, "$.data.userDeviceId"));

        assertThat(jdbc.queryForList("SELECT user_device_id FROM user_device WHERE status <> 'REVOKED'", Long.class))
                .containsExactly(newDeviceId);
        for (long revoked : List.of(member.deviceId(), second, third)) {
            assertThat(eventTypes(revoked)).last().isEqualTo("REVOKED");
        }
        // 이미 해제돼 있던 기기에는 이력을 더 남기지 않는다.
        assertThat(eventTypes(revokedBefore)).isEmpty();
    }

    // ───────── 가입 후 ─────────

    @Test
    void 로그인_후_PIN_변경은_본인_인증_토큰이_필요하고_형식이_틀리면_토큰을_쓰지_않는다() throws Exception {
        Registered member = activeMember();
        var me = AuthUsers.asUser(member.userId(), member.deviceId());
        String stepUp = stepUpToken(member, StepUpPurpose.SECURITY_SETTINGS);

        perform(post("/api/auth/pin").with(me), "{\"pin\":\"" + NEW_PIN + "\"}")
                .andExpect(errorCode(403, "STEP_UP_REQUIRED"));
        perform(post("/api/auth/pin").with(me).header(StepUpService.HEADER, stepUpToken(member, StepUpPurpose.CARD_REGISTER)),
                "{\"pin\":\"" + NEW_PIN + "\"}").andExpect(errorCode(403, "STEP_UP_REQUIRED"));
        perform(post("/api/auth/pin").with(me).header(StepUpService.HEADER, stepUp), "{\"pin\":\"111111\"}")
                .andExpect(errorCode(400, "INVALID_PIN_FORMAT"));
        perform(post("/api/auth/pin").with(me).header(StepUpService.HEADER, stepUp), "{\"pin\":\"" + NEW_PIN + "\"}")
                .andExpect(status().isNoContent());

        assertThat(pinHasher.matches(NEW_PIN, pinHashOf(member.userId()))).isTrue();
        perform(post("/api/auth/pin").with(me).header(StepUpService.HEADER, stepUp), "{\"pin\":\"" + PIN + "\"}")
                .andExpect(errorCode(403, "STEP_UP_REQUIRED"));
    }

    @Test
    void 로그인_후_생체를_켜면_이_기기의_키만_바꾸고_토큰은_주지_않는다() throws Exception {
        Registered member = activeMember();
        var me = AuthUsers.asUser(member.userId(), member.deviceId());
        String stepUp = stepUpToken(member, StepUpPurpose.SECURITY_SETTINGS);
        DeviceKeys biometric = DeviceKeys.generate();

        // 다른 폰의 deviceId는 400이고 본인 인증 토큰을 쓰지 않는다.
        registerDevice(post("/api/auth/devices").with(me).header(StepUpService.HEADER, stepUp),
                deviceJson(UUID.randomUUID().toString(), member.keys(), biometric, null, null))
                .andExpect(errorCode(400, "INVALID_REQUEST"));
        registerDevice(post("/api/auth/devices").with(me),
                deviceJson(member.deviceUuid(), member.keys(), biometric, null, null))
                .andExpect(errorCode(403, "STEP_UP_REQUIRED"));
        registerDevice(post("/api/auth/devices").with(me).header(StepUpService.HEADER, stepUp),
                deviceJson(member.deviceUuid(), member.keys(), biometric, null, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.userDeviceId").value(member.deviceId()))
                .andExpect(jsonPath("$.data.biometricEnabled").value(true))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist());

        assertThat(jdbc.queryForObject("SELECT biometric_public_key FROM user_device", String.class))
                .isEqualTo(biometric.publicKey());
    }

    @Test
    void 토큰이_없으면_401_UNAUTHORIZED_만료된_가입_진행_토큰은_401_SIGNUP_TOKEN_INVALID() throws Exception {
        perform(post("/api/auth/pin"), "{\"pin\":\"" + PIN + "\"}").andExpect(errorCode(401, "UNAUTHORIZED"));
        setPin("Bearer sgt_expired", PIN).andExpect(errorCode(401, "SIGNUP_TOKEN_INVALID"));
        registerDevice("Bearer sgt_expired", deviceJson(UUID.randomUUID().toString(), DeviceKeys.generate(), null, null, null))
                .andExpect(errorCode(401, "SIGNUP_TOKEN_INVALID"));
    }

    // ───────── 리뷰 반영 ─────────

    @Test
    void 계좌를_연결해_두고_멈춘_가입을_새_토큰으로_이어가면_1원_인증을_다시_해야_PIN_기기_등록을_받는다() throws Exception {
        long userId = pendingUser();
        String first = "Bearer " + signupToken(userId, SignupMode.SIGNUP);
        linkAccount(first);
        setPin(first, PIN).andExpect(status().isNoContent());

        // 다른 사람이 번호·생년월일·이름으로 본인인증(연출)만 해서 새 토큰을 받은 상황.
        String other = "Bearer " + signupToken(userId, SignupMode.SIGNUP);
        setPin(other, NEW_PIN).andExpect(errorCode(409, "SIGNUP_STEP_INCOMPLETE"))
                .andExpect(jsonPath("$.error.details.nextStep").value("ACCOUNT_LINK"));
        registerDevice(other, deviceJson(UUID.randomUUID().toString(), DeviceKeys.generate(), null, null, null))
                .andExpect(errorCode(409, "SIGNUP_STEP_INCOMPLETE"))
                .andExpect(jsonPath("$.error.details.nextStep").value("ACCOUNT_LINK"));
        assertThat(pinHasher.matches(PIN, pinHashOf(userId))).isTrue();

        // 연결해 둔 계좌로 1원 인증을 다시 하면(새로 연결할 계좌는 없음) 이어갈 수 있다.
        linkAccount(other);
        registerDevice(other, deviceJson(UUID.randomUUID().toString(), DeviceKeys.generate(), null, null, null))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM linked_account", Integer.class)).isEqualTo(1);
    }

    @Test
    void 가입을_마치면_그_사용자의_다른_가입_진행_토큰도_모두_지운다() throws Exception {
        long userId = pendingUser();
        String token = signupToken(userId, SignupMode.SIGNUP);
        String spare = signupToken(userId, SignupMode.SIGNUP);
        String bearer = "Bearer " + token;
        linkAccount(bearer);
        setPin(bearer, PIN).andExpect(status().isNoContent());

        registerDevice(bearer, deviceJson(UUID.randomUUID().toString(), DeviceKeys.generate(), null, null, null))
                .andExpect(status().isCreated());

        assertThat(signupTokenStore.find(token)).isEmpty();
        assertThat(signupTokenStore.find(spare)).isEmpty();
        setPin("Bearer " + spare, NEW_PIN).andExpect(errorCode(401, "SIGNUP_TOKEN_INVALID"));
    }

    @Test
    void 가입_중_PIN을_동시에_두_번_보내도_500_없이_둘_다_처리된다() throws Exception {
        long userId = pendingUser();
        String bearer = "Bearer " + signupToken(userId, SignupMode.SIGNUP);
        linkAccount(bearer);

        List<Integer> statuses = Concurrently.run(List.of(
                () -> setPin(bearer, PIN).andReturn().getResponse().getStatus(),
                () -> setPin(bearer, NEW_PIN).andReturn().getResponse().getStatus()));

        assertThat(statuses).containsOnly(204);
        assertThat(pinHasher.matches(PIN, pinHashOf(userId)) || pinHasher.matches(NEW_PIN, pinHashOf(userId))).isTrue();
    }

    @Test
    void 기기_재등록에_성공하면_다른_기기를_해제하고_로그인을_끊는다() throws Exception {
        Registered member = activeMember();
        long other = insertDevice(member.userId(), "ACTIVE");
        refreshTokenStore.issue(other);
        assertThat(redis.hasKey("auth:refresh:" + member.deviceId())).isTrue();
        String bearer = "Bearer " + signupToken(member.userId(), SignupMode.DEVICE_RECOVERY);
        String verification = verifiedAndSent(bearer);

        MvcResult registered = registerDevice(bearer,
                deviceJson(UUID.randomUUID().toString(), DeviceKeys.generate(), null, verification, CODE))
                .andExpect(status().isCreated()).andReturn();
        long newDeviceId = Long.parseLong(read(registered, "$.data.userDeviceId"));

        assertThat(redis.hasKey("auth:refresh:" + member.deviceId())).isFalse();
        assertThat(redis.hasKey("auth:refresh:" + other)).isFalse();
        assertThat(redis.hasKey("auth:refresh:" + newDeviceId)).isTrue();
        // 은행 앱의 기기 변경처럼 이 폰만 남는다. 해제한 기기는 목록에 이력으로 남는다.
        assertThat(jdbc.queryForList("SELECT user_device_id FROM user_device WHERE status <> 'REVOKED'", Long.class))
                .containsExactly(newDeviceId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_device", Integer.class)).isEqualTo(3);
        loginChallenge(member.deviceId()).andExpect(errorCode(401, "DEVICE_NOT_REGISTERED"));
    }

    @Test
    void 로그인_후_PIN을_바꾸면_지금_기기_말고_다른_기기의_로그인을_끊는다() throws Exception {
        Registered member = activeMember();
        long other = insertDevice(member.userId(), "ACTIVE");
        refreshTokenStore.issue(other);

        perform(post("/api/auth/pin").with(AuthUsers.asUser(member.userId(), member.deviceId()))
                        .header(StepUpService.HEADER, stepUpToken(member, StepUpPurpose.SECURITY_SETTINGS)),
                "{\"pin\":\"" + NEW_PIN + "\"}").andExpect(status().isNoContent());

        assertThat(redis.hasKey("auth:refresh:" + member.deviceId())).isTrue();
        assertThat(redis.hasKey("auth:refresh:" + other)).isFalse();
    }

    @Test
    void 기기_재등록_1원_토큰은_한_번만_쓴다_동시에_보내도_기기는_하나만_등록된다() throws Exception {
        Registered member = activeMember();
        String bearer = "Bearer " + signupToken(member.userId(), SignupMode.DEVICE_RECOVERY);
        String verification = verifiedAndSent(bearer);

        List<Integer> statuses = Concurrently.run(List.of(
                () -> registerDevice(bearer, deviceJson(UUID.randomUUID().toString(), DeviceKeys.generate(), null,
                        verification, CODE)).andReturn().getResponse().getStatus(),
                () -> registerDevice(bearer, deviceJson(UUID.randomUUID().toString(), DeviceKeys.generate(), null,
                        verification, CODE)).andReturn().getResponse().getStatus()));

        assertThat(statuses).containsOnlyOnce(201);
        assertThat(statuses).anyMatch(status -> status == 400 || status == 401);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_device", Integer.class)).isEqualTo(2);
    }

    @Test
    void 기기_재등록_중_1원을_받은_계좌_연결을_끊었으면_400_VERIFICATION_TOKEN_EXPIRED() throws Exception {
        Registered member = activeMember();
        String bearer = "Bearer " + signupToken(member.userId(), SignupMode.DEVICE_RECOVERY);
        String verification = verifiedAndSent(bearer);
        jdbc.update("UPDATE linked_account SET status = 'DISCONNECTED', is_primary = 0, disconnected_at = ?", now());

        registerDevice(bearer, deviceJson(UUID.randomUUID().toString(), DeviceKeys.generate(), null, verification, CODE))
                .andExpect(errorCode(400, "VERIFICATION_TOKEN_EXPIRED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_device", Integer.class)).isEqualTo(1);
    }

    @Test
    void 서로_다른_재등록_두_건이_동시에_와도_차례로_처리되고_쓸_수_있는_기기는_하나만_남는다() throws Exception {
        Registered member = activeMember();
        insertDevice(member.userId(), "ACTIVE");
        String first = "Bearer " + signupToken(member.userId(), SignupMode.DEVICE_RECOVERY);
        String firstVerification = verifiedAndSent(first);
        redis.delete(redis.keys("onewon:resend:*"));
        String second = "Bearer " + signupToken(member.userId(), SignupMode.DEVICE_RECOVERY);
        String secondVerification = verifiedAndSent(second);

        List<Integer> statuses = Concurrently.run(List.of(
                () -> registerDevice(first, deviceJson(UUID.randomUUID().toString(), DeviceKeys.generate(), null,
                        firstVerification, CODE)).andReturn().getResponse().getStatus(),
                () -> registerDevice(second, deviceJson(UUID.randomUUID().toString(), DeviceKeys.generate(), null,
                        secondVerification, CODE)).andReturn().getResponse().getStatus()));

        // 사용자 행 잠금으로 차례로 처리된다. 늦은 쪽은 먼저 끝난 쪽이 가입 진행 토큰을 지워 401이거나,
        // 이미 토큰 확인을 지났으면 201로 먼저 등록된 폰까지 해제한다. 어느 쪽이든 쓸 수 있는 기기는 하나다.
        assertThat(statuses).contains(201);
        assertThat(statuses).allMatch(status -> status == 201 || status == 401);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_device WHERE status <> 'REVOKED'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void 해제했던_폰을_재등록하면_같은_행을_다시_살린다() throws Exception {
        Registered member = activeMember();
        jdbc.update("UPDATE user_device SET status = 'REVOKED' WHERE user_device_id = ?", member.deviceId());
        String bearer = "Bearer " + signupToken(member.userId(), SignupMode.DEVICE_RECOVERY);
        String verification = verifiedAndSent(bearer);

        registerDevice(bearer, deviceJson(member.deviceUuid(), DeviceKeys.generate(), null, verification, CODE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.userDeviceId").value(member.deviceId()))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        assertThat(eventTypes(member.deviceId())).containsExactly("REGISTERED", "REGISTERED");
    }

    @Test
    void 기기_재등록_1원은_하루_3번까지만_보낸다() throws Exception {
        Registered member = activeMember();
        String bearer = "Bearer " + signupToken(member.userId(), SignupMode.DEVICE_RECOVERY);
        // 가입 때 1번 보냈다. 재등록에서 2번 더 보내면 하루 3번이 찬다.
        for (int i = 0; i < 2; i++) {
            redis.delete(redis.keys("onewon:resend:*"));
            verifiedAndSent(bearer);
        }
        redis.delete(redis.keys("onewon:resend:*"));
        MvcResult listed = mockMvc.perform(get("/api/accounts/candidates").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk()).andReturn();
        String candidateId = read(listed, "$.data.accounts[0].candidateId");
        perform(post("/api/accounts/one-won-auth").header(HttpHeaders.AUTHORIZATION, bearer),
                "{\"candidateIds\":[\"" + candidateId + "\"],\"primaryCandidateId\":\"" + candidateId + "\"}")
                .andExpect(errorCode(429, "ONE_WON_DAILY_LIMIT_EXCEEDED"));
    }

    @Test
    void 지원하지_않는_플랫폼_IOS와_기기_키와_같은_생체_키는_400() throws Exception {
        String bearer = readyForDevice();
        DeviceKeys keys = DeviceKeys.generate();

        registerDevice(bearer, "{\"deviceId\":\"" + UUID.randomUUID() + "\",\"platform\":\"IOS\",\"devicePublicKey\":\""
                + keys.publicKey() + "\"}").andExpect(errorCode(400, "INVALID_REQUEST"));
        registerDevice(bearer, json(UUID.randomUUID().toString(), keys.publicKey(), keys.publicKey(), "[\"FINGERPRINT\"]",
                null, null)).andExpect(errorCode(400, "INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("biometricPublicKey"));
        registerDevice(bearer, json(UUID.randomUUID().toString(), keys.publicKey(), DeviceKeys.generate().publicKey(),
                "[null]", null, null)).andExpect(errorCode(400, "INVALID_REQUEST"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_device", Integer.class)).isZero();
    }

    // ───────── 준비 ─────────

    // ───────── 기기 관리(목록·해제) ─────────

    @Test
    void 기기_목록은_내_기기만_지금_기기를_먼저_보여_주고_잠금_시각이_지난_기기는_ACTIVE다() throws Exception {
        Registered member = activeMember();
        long locked = insertDevice(member.userId(), "LOCKED");
        jdbc.update("UPDATE user_device SET locked_until = ? WHERE user_device_id = ?",
                Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10)), locked);
        long expiredLock = insertDevice(member.userId(), "LOCKED");
        jdbc.update("UPDATE user_device SET locked_until = ? WHERE user_device_id = ?",
                Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1)), expiredLock);
        long revoked = insertDevice(member.userId(), "REVOKED");
        long stranger = insert("app_user", "user_id", Map.of("status", "PENDING", "created_at", now(), "updated_at", now()));
        insertDevice(stranger, "ACTIVE");

        mockMvc.perform(get("/api/auth/devices").with(AuthUsers.asUser(member.userId(), member.deviceId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.maxDevices").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(4))
                .andExpect(jsonPath("$.data.items[0].userDeviceId").value(member.deviceId()))
                .andExpect(jsonPath("$.data.items[0].isCurrent").value(true))
                .andExpect(jsonPath("$.data.items[0].deviceName").value("Galaxy S24"))
                .andExpect(jsonPath("$.data.items[0].methods[0]").value("PIN"))
                .andExpect(jsonPath("$.data.items[0].biometricTypes").doesNotExist())
                .andExpect(jsonPath("$.data.items[?(@.userDeviceId == " + locked + ")].status").value("LOCKED"))
                .andExpect(jsonPath("$.data.items[?(@.userDeviceId == " + locked + ")].lockedUntil").exists())
                .andExpect(jsonPath("$.data.items[?(@.userDeviceId == " + expiredLock + ")].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.items[3].userDeviceId").value(revoked))
                .andExpect(jsonPath("$.data.items[3].status").value("REVOKED"))
                .andExpect(jsonPath("$.data.items[3].methods.length()").value(0));
    }

    @Test
    void 다른_기기를_해제하면_REVOKED와_이력이_남고_그_기기는_재발급할_수_없다() throws Exception {
        Registered member = activeMember();
        long other = insertDevice(member.userId(), "ACTIVE");
        String otherRefresh = refreshTokenStore.issue(other);

        mockMvc.perform(delete("/api/auth/devices/" + other).with(AuthUsers.asUser(member.userId(), member.deviceId()))
                        .header(StepUpService.HEADER, stepUpToken(member, StepUpPurpose.APP_UNLOCK)))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT status FROM user_device WHERE user_device_id = ?", String.class, other))
                .isEqualTo("REVOKED");
        assertThat(eventTypes(other)).containsExactly("REVOKED");
        perform(post("/api/auth/refresh"), "{\"refreshToken\":\"" + otherRefresh + "\"}")
                .andExpect(errorCode(401, "INVALID_REFRESH_TOKEN"));
        loginChallenge(other).andExpect(errorCode(401, "DEVICE_NOT_REGISTERED"));
        // 지금 기기는 그대로 쓴다.
        perform(post("/api/auth/refresh"), "{\"refreshToken\":\"" + member.refreshToken() + "\"}")
                .andExpect(status().isOk());
    }

    @Test
    void 지금_기기도_해제할_수_있고_그_폰은_기기_재등록_전까지_로그인할_수_없다() throws Exception {
        Registered member = activeMember();

        mockMvc.perform(delete("/api/auth/devices/" + member.deviceId())
                        .with(AuthUsers.asUser(member.userId(), member.deviceId()))
                        .header(StepUpService.HEADER, stepUpToken(member, StepUpPurpose.APP_UNLOCK)))
                .andExpect(status().isNoContent());

        loginChallenge(member.deviceId()).andExpect(errorCode(401, "DEVICE_NOT_REGISTERED"));
        perform(post("/api/auth/refresh"), "{\"refreshToken\":\"" + member.refreshToken() + "\"}")
                .andExpect(errorCode(401, "INVALID_REFRESH_TOKEN"));
    }

    @Test
    void 남의_기기와_없는_기기는_404이고_본인_인증_토큰을_쓰지_않으며_토큰이_없으면_403() throws Exception {
        Registered member = activeMember();
        var me = AuthUsers.asUser(member.userId(), member.deviceId());
        long stranger = insert("app_user", "user_id", Map.of("status", "PENDING", "created_at", now(), "updated_at", now()));
        long strangerDevice = insertDevice(stranger, "ACTIVE");
        long mine = insertDevice(member.userId(), "ACTIVE");
        String stepUp = stepUpToken(member, StepUpPurpose.APP_UNLOCK);

        mockMvc.perform(delete("/api/auth/devices/" + strangerDevice).with(me).header(StepUpService.HEADER, stepUp))
                .andExpect(errorCode(404, "RESOURCE_NOT_FOUND"));
        mockMvc.perform(delete("/api/auth/devices/999999").with(me).header(StepUpService.HEADER, stepUp))
                .andExpect(errorCode(404, "RESOURCE_NOT_FOUND"));
        mockMvc.perform(delete("/api/auth/devices/" + mine).with(me))
                .andExpect(errorCode(403, "STEP_UP_REQUIRED"));
        mockMvc.perform(delete("/api/auth/devices/" + mine).with(me)
                        .header(StepUpService.HEADER, stepUpToken(member, StepUpPurpose.SECURITY_SETTINGS)))
                .andExpect(errorCode(403, "STEP_UP_REQUIRED"));
        // 404에서는 토큰을 쓰지 않았으니 그대로 쓸 수 있다.
        mockMvc.perform(delete("/api/auth/devices/" + mine).with(me).header(StepUpService.HEADER, stepUp))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT status FROM user_device WHERE user_device_id = ?", String.class,
                strangerDevice)).isEqualTo("ACTIVE");
    }

    @Test
    void 이미_해제한_기기를_다시_해제하면_204이고_본인_인증_토큰을_쓰지_않는다() throws Exception {
        Registered member = activeMember();
        var me = AuthUsers.asUser(member.userId(), member.deviceId());
        long revoked = insertDevice(member.userId(), "REVOKED");
        long active = insertDevice(member.userId(), "ACTIVE");
        String stepUp = stepUpToken(member, StepUpPurpose.APP_UNLOCK);

        mockMvc.perform(delete("/api/auth/devices/" + revoked).with(me).header(StepUpService.HEADER, stepUp))
                .andExpect(status().isNoContent());
        assertThat(eventTypes(revoked)).isEmpty();
        mockMvc.perform(delete("/api/auth/devices/" + active).with(me).header(StepUpService.HEADER, stepUp))
                .andExpect(status().isNoContent());
    }

    @Test
    void 같은_기기를_동시에_두_번_해제해도_500_없이_이력은_하나다() throws Exception {
        Registered member = activeMember();
        long other = insertDevice(member.userId(), "ACTIVE");
        String first = stepUpToken(member, StepUpPurpose.APP_UNLOCK);
        String second = stepUpToken(member, StepUpPurpose.APP_UNLOCK);

        List<Integer> statuses = Concurrently.run(List.of(
                () -> revokeStatus(member, other, first),
                () -> revokeStatus(member, other, second)));

        assertThat(statuses).containsOnly(204);
        assertThat(eventTypes(other)).containsExactly("REVOKED");
    }

    @Test
    void 기기_해제와_기기_재등록이_동시에_와도_500_없이_쓸_수_있는_기기는_새_폰_하나다() throws Exception {
        Registered member = activeMember();
        long other = insertDevice(member.userId(), "ACTIVE");
        String stepUp = stepUpToken(member, StepUpPurpose.APP_UNLOCK);
        String bearer = "Bearer " + signupToken(member.userId(), SignupMode.DEVICE_RECOVERY);
        String verification = verifiedAndSent(bearer);
        String newUuid = UUID.randomUUID().toString();

        List<Integer> statuses = Concurrently.run(List.of(
                () -> revokeStatus(member, other, stepUp),
                () -> registerDevice(bearer, deviceJson(newUuid, DeviceKeys.generate(), null, verification, CODE))
                        .andReturn().getResponse().getStatus()));

        // 재등록이 먼저 끝나면 해제를 보낸 기기(member)도 해제돼 401이다. 해제가 먼저면 204.
        assertThat(statuses.get(0)).isIn(204, 401);
        assertThat(statuses.get(1)).isEqualTo(201);
        assertThat(jdbc.queryForList("SELECT device_uuid FROM user_device WHERE status <> 'REVOKED'", String.class))
                .containsExactly(newUuid);
        assertThat(eventTypes(other)).containsExactly("REVOKED");
    }

    @Test
    void 기기_재등록_중_옛_기기_로그인이_먼저_커밋해도_409_없이_재등록된다() throws Exception {
        Registered member = activeMember();
        String bearer = "Bearer " + signupToken(member.userId(), SignupMode.DEVICE_RECOVERY);
        String verification = verifiedAndSent(bearer);
        CountDownLatch loginLocked = new CountDownLatch(1);
        CountDownLatch finishLogin = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 옛 폰 로그인 성공처럼 기기 행을 잠그고 버전을 올린 채 멈춰 둔다.
            Future<?> login = pool.submit(() -> transaction.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT version FROM user_device WHERE user_device_id = ? FOR UPDATE",
                        Long.class, member.deviceId());
                jdbc.update("UPDATE user_device SET version = version + 1, last_verified_at = ? WHERE user_device_id = ?",
                        now(), member.deviceId());
                loginLocked.countDown();
                try {
                    finishLogin.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(loginLocked.await(30, TimeUnit.SECONDS)).isTrue();
            Future<Integer> recovery = pool.submit(() -> registerDevice(bearer,
                    deviceJson(UUID.randomUUID().toString(), DeviceKeys.generate(), null, verification, CODE))
                    .andReturn().getResponse().getStatus());
            // 재등록이 옛 기기를 읽는 지점까지 가게 둔 뒤 로그인을 커밋한다. 잠그지 않고 읽었다면 버전이 어긋나 409가 난다.
            Thread.sleep(500);
            finishLogin.countDown();
            login.get(30, TimeUnit.SECONDS);

            assertThat(recovery.get(30, TimeUnit.SECONDS)).isEqualTo(201);
        } finally {
            finishLogin.countDown();
            pool.shutdownNow();
        }
        assertThat(eventTypes(member.deviceId())).last().isEqualTo("REVOKED");
    }

    @Test
    void 가입_중인데_해제되지_않은_기기가_이미_있으면_409_DEVICE_LIMIT_EXCEEDED() throws Exception {
        // 정상 흐름에서는 생기지 않는 데이터다. 버그로 기기가 두 대가 되지 않게 막는 안전장치를 확인한다.
        long userId = pendingUser();
        insertDevice(userId, "ACTIVE");
        String bearer = "Bearer " + signupToken(userId, SignupMode.SIGNUP);
        linkAccount(bearer);
        setPin(bearer, PIN).andExpect(status().isNoContent());

        registerDevice(bearer, deviceJson(UUID.randomUUID().toString(), DeviceKeys.generate(), null, null, null))
                .andExpect(errorCode(409, "DEVICE_LIMIT_EXCEEDED"));
    }

    private int revokeStatus(Registered member, long deviceId, String stepUp) throws Exception {
        return mockMvc.perform(delete("/api/auth/devices/" + deviceId)
                        .with(AuthUsers.asUser(member.userId(), member.deviceId()))
                        .header(StepUpService.HEADER, stepUp))
                .andReturn().getResponse().getStatus();
    }

    private ResultActions loginChallenge(long deviceId) throws Exception {
        return perform(post("/api/auth/login/challenge"), "{\"userDeviceId\":" + deviceId + "}");
    }

    private record Registered(long userId, long deviceId, String deviceUuid, DeviceKeys keys, String refreshToken) {
    }

    /** 실제 API로 가입을 마친 사용자(계좌 연결 + PIN + 기기 1대). */
    private Registered activeMember() throws Exception {
        long userId = pendingUser();
        String bearer = "Bearer " + signupToken(userId, SignupMode.SIGNUP);
        linkAccount(bearer);
        setPin(bearer, PIN).andExpect(status().isNoContent());
        DeviceKeys keys = DeviceKeys.generate();
        String uuid = UUID.randomUUID().toString();
        MvcResult result = registerDevice(bearer, deviceJson(uuid, keys, null, null, null))
                .andExpect(status().isCreated()).andReturn();
        return new Registered(userId, Long.parseLong(read(result, "$.data.userDeviceId")), uuid, keys,
                read(result, "$.data.refreshToken"));
    }

    /** 계좌 연결까지 마치고 PIN을 정한 가입 중 사용자의 가입 진행 토큰. */
    private String readyForDevice() throws Exception {
        String bearer = "Bearer " + signupToken(pendingUser(), SignupMode.SIGNUP);
        linkAccount(bearer);
        setPin(bearer, PIN).andExpect(status().isNoContent());
        return bearer;
    }

    private long pendingUser() {
        Map<String, Object> values = new HashMap<>(Map.of(
                "name", "김야무",
                "birth_date", LocalDate.of(1999, 3, 15),
                "phone_enc", cipher.encrypt(PHONE, CipherField.APP_USER_PHONE),
                "phone_hash", lookupHasher.phone(PHONE),
                "identity_verified_at", now(),
                "ssafy_member_id", MEMBER_LOCAL + "@yamu.app",
                "ssafy_user_key", cipher.encrypt(USER_KEY, CipherField.APP_USER_SSAFY_USER_KEY),
                "status", "PENDING",
                "created_at", now(),
                "updated_at", now()));
        return insert("app_user", "user_id", values);
    }

    private long insertDevice(long userId, String status) {
        return insert("user_device", "user_device_id", Map.of("user_id", userId,
                "device_uuid", UUID.randomUUID().toString(), "platform", "ANDROID",
                "public_key", DeviceKeys.generate().publicKey(), "key_algorithm", DeviceSignatureVerifier.EC_P256,
                "status", status, "created_at", now(), "updated_at", now()));
    }

    private long insert(String table, String keyColumn, Map<String, Object> values) {
        return new SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns(keyColumn)
                .usingColumns(values.keySet().toArray(String[]::new))
                .executeAndReturnKey(values).longValue();
    }

    private String signupToken(Long userId, SignupMode mode) {
        VerifiedIdentity identity = new VerifiedIdentity("김야무", LocalDate.of(1999, 3, 15),
                cipher.encrypt(PHONE, CipherField.APP_USER_PHONE), lookupHasher.phone(PHONE));
        return signupTokenStore.issue(new SignupSession(mode, identity, LocalDateTime.now(ZoneOffset.UTC), userId)).token();
    }

    private String stepUpToken(Registered member, StepUpPurpose purpose) {
        long auditLogId = insert("step_up_audit_log", "step_up_audit_log_id", Map.of("user_id", member.userId(),
                "user_device_id", member.deviceId(), "auth_method", "PIN", "purpose", purpose.name(),
                "result", "SUCCESS", "created_at", now()));
        return stepUpTokenStore.issue(new StepUpGrant(member.userId(), member.deviceId(), purpose, AuthMethod.PIN, auditLogId));
    }

    /** 계좌 목록 → 1원 → 연결(가입 진행 토큰). 이 토큰에 1원 인증을 마쳤다고 남는다. */
    private void linkAccount(String bearer) throws Exception {
        String verification = verifiedAndSent(bearer);
        perform(post("/api/accounts").header(HttpHeaders.AUTHORIZATION, bearer)
                        .header("Idempotency-Key", UUID.randomUUID().toString()),
                "{\"verificationToken\":\"" + verification + "\",\"authCode\":\"" + CODE + "\"}")
                .andExpect(status().isCreated());
    }

    /** 계좌 목록에서 첫 계좌를 골라 1원 송금까지. 1원 인증 토큰을 돌려준다. */
    private String verifiedAndSent(String bearer) throws Exception {
        MvcResult listed = mockMvc.perform(get("/api/accounts/candidates").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk()).andReturn();
        String candidateId = read(listed, "$.data.accounts[0].candidateId");
        return read(perform(post("/api/accounts/one-won-auth").header(HttpHeaders.AUTHORIZATION, bearer),
                "{\"candidateIds\":[\"" + candidateId + "\"],\"primaryCandidateId\":\"" + candidateId + "\"}")
                .andExpect(status().isOk()).andReturn(), "$.data.verificationToken");
    }

    private ResultActions login(long deviceId, DeviceKeys keys, String pin) throws Exception {
        MvcResult challenge = perform(post("/api/auth/login/challenge"), "{\"userDeviceId\":" + deviceId + "}")
                .andExpect(status().isOk()).andReturn();
        String challengeId = read(challenge, "$.data.challengeId");
        String signature = keys.sign(read(challenge, "$.data.challenge"));
        return perform(post("/api/auth/login"), """
                {"userDeviceId":%d,"challengeId":"%s","method":"PIN","signature":"%s","pin":"%s"}
                """.formatted(deviceId, challengeId, signature, pin));
    }

    private ResultActions setPin(String bearer, String pin) throws Exception {
        return perform(post("/api/auth/pin").header(HttpHeaders.AUTHORIZATION, bearer), "{\"pin\":\"" + pin + "\"}");
    }

    private ResultActions registerDevice(String bearer, String json) throws Exception {
        return registerDevice(post("/api/auth/devices").header(HttpHeaders.AUTHORIZATION, bearer), json);
    }

    private ResultActions registerDevice(MockHttpServletRequestBuilder builder, String json) throws Exception {
        return perform(builder, json);
    }

    private static String deviceJson(String uuid, DeviceKeys keys, DeviceKeys biometric, String verification,
            String code) {
        return json(uuid, keys.publicKey(), biometric == null ? null : biometric.publicKey(),
                biometric == null ? null : "[\"FINGERPRINT\"]", verification, code);
    }

    private static String json(String uuid, String devicePublicKey, String biometricPublicKey, String biometricTypes,
            String verification, String code) {
        StringBuilder json = new StringBuilder("{\"deviceId\":\"" + uuid + "\",\"deviceName\":\"Galaxy S24\","
                + "\"platform\":\"ANDROID\",\"devicePublicKey\":\"" + devicePublicKey + "\"");
        if (biometricPublicKey != null) {
            json.append(",\"biometricPublicKey\":\"").append(biometricPublicKey).append('"');
        }
        if (biometricTypes != null) {
            json.append(",\"biometricTypes\":").append(biometricTypes);
        }
        if (verification != null) {
            json.append(",\"verificationToken\":\"").append(verification).append('"');
        }
        if (code != null) {
            json.append(",\"authCode\":\"").append(code).append('"');
        }
        return json.append('}').toString();
    }

    private static String rsaKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded());
    }

    private static String ecKey(String curve) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(curve));
        return Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded());
    }

    private String pinHashOf(long userId) {
        return jdbc.queryForObject("SELECT pin_hash FROM app_user WHERE user_id = ?", String.class, userId);
    }

    private List<String> eventTypes(long deviceId) {
        return jdbc.queryForList("SELECT event_type FROM user_device_event WHERE user_device_id = ? "
                + "ORDER BY user_device_event_id", String.class, deviceId);
    }

    private static <T> List<T> runTogether(int count, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
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
