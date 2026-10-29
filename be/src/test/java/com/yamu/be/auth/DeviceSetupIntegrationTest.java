package com.yamu.be.auth;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

/**
 * 간편 비밀번호 설정 → 기기 등록(가입 완료), 기기 재등록, 로그인 후 PIN 변경·생체 켜기를 실제 MySQL·Redis로 확인한다.
 * 계좌 연결·1원 인증은 실제 API를 거치고, SSAFY 금융망만 가짜(FinApiClient mock)다.
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

    @MockitoBean FinApiClient finApiClient;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM linked_account");
        jdbc.update("DELETE FROM step_up_audit_log");
        jdbc.update("DELETE FROM user_device_event");
        jdbc.update("DELETE FROM user_device");
        jdbc.update("DELETE FROM app_user");
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
        SsafyAccountFakes.stub(finApiClient, USER_KEY, MEMBER_LOCAL, CODE);
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
        String noUser = "Bearer " + signupToken(null, SignupMode.SIGNUP);
        setPin(noUser, PIN).andExpect(errorCode(409, "SIGNUP_STEP_INCOMPLETE"))
                .andExpect(jsonPath("$.error.details.nextStep").value("SIGNUP"));

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
    void 해제되지_않은_기기가_3대면_새_폰은_409_DEVICE_LIMIT_EXCEEDED_해제한_기기는_세지_않는다() throws Exception {
        Registered member = activeMember();
        insertDevice(member.userId(), "ACTIVE");
        long third = insertDevice(member.userId(), "LOCKED");
        insertDevice(member.userId(), "REVOKED");
        String bearer = "Bearer " + signupToken(member.userId(), SignupMode.DEVICE_RECOVERY);
        String verification = verifiedAndSent(bearer);

        registerDevice(bearer, deviceJson(UUID.randomUUID().toString(), DeviceKeys.generate(), null, verification, CODE))
                .andExpect(errorCode(409, "DEVICE_LIMIT_EXCEEDED"));
        // 이미 있는 폰의 키 교체는 대수가 늘지 않으니 된다. 맞힌 인증코드는 기억돼 다시 보내도 된다.
        registerDevice(bearer, deviceJson(member.deviceUuid(), DeviceKeys.generate(), null, verification, CODE))
                .andExpect(status().isCreated());

        jdbc.update("UPDATE user_device SET status = 'REVOKED' WHERE user_device_id = ?", third);
        String again = "Bearer " + signupToken(member.userId(), SignupMode.DEVICE_RECOVERY);
        String verification2 = verifiedAndSent(again);
        registerDevice(again, deviceJson(UUID.randomUUID().toString(), DeviceKeys.generate(), null, verification2, CODE))
                .andExpect(status().isCreated());
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

    // ───────── 준비 ─────────

    private record Registered(long userId, long deviceId, String deviceUuid, DeviceKeys keys) {
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
        return new Registered(userId, Long.parseLong(read(result, "$.data.userDeviceId")), uuid, keys);
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

    /** 계좌 확인 → 1원 → 연결(가입 진행 토큰). */
    private void linkAccount(String bearer) throws Exception {
        String verification = verifiedAndSent(bearer);
        perform(post("/api/accounts").header(HttpHeaders.AUTHORIZATION, bearer)
                        .header("Idempotency-Key", UUID.randomUUID().toString()),
                "{\"verificationToken\":\"" + verification + "\",\"authCode\":\"" + CODE + "\"}")
                .andExpect(status().isCreated());
    }

    /** 계좌 확인 + 1원 송금까지. */
    private String verifiedAndSent(String bearer) throws Exception {
        String token = read(perform(post("/api/accounts/verify").header(HttpHeaders.AUTHORIZATION, bearer),
                "{\"accountNo\":\"" + ACCOUNT_NO + "\"}").andExpect(status().isOk()).andReturn(), "$.data.verificationToken");
        perform(post("/api/accounts/one-won-auth").header(HttpHeaders.AUTHORIZATION, bearer),
                "{\"verificationToken\":\"" + token + "\"}").andExpect(status().isOk());
        return token;
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
