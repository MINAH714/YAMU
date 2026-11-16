package com.yamu.be.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.yamu.be.auth.StepUpTokenStore.StepUpGrant;
import com.yamu.be.auth.domain.AuthMethod;
import com.yamu.be.auth.domain.DevicePlatform;
import com.yamu.be.auth.domain.StepUpAuditLog;
import com.yamu.be.auth.domain.StepUpAuditLogRepository;
import com.yamu.be.auth.domain.StepUpPurpose;
import com.yamu.be.auth.domain.UserDevice;
import com.yamu.be.auth.domain.UserDeviceRepository;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.LookupHasher;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.crypto.PinHasher;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.security.AuthUser;
import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.DeviceKeys;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.user.domain.AppUser;
import com.yamu.be.user.domain.AppUserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

/**
 * 본인 인증(step-up) 준비·검증과 토큰 소비를 실제 MySQL·Redis로 확인한다.
 * 로그인한 사용자는 {@link AuthUsers}로 넣고, 서명은 {@link DeviceKeys}가 폰 보안 칩 대신 만든다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.application.name=yamu-step-up-integration-test",
        "yamu.auth.rate-limit.per-device=100",
        "yamu.auth.rate-limit.per-ip=500"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StepUpIntegrationTest extends MySqlTestSupport {

    private static final String PIN = "482913";
    private static final String WRONG_PIN = "000000";

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired AppUserRepository userRepository;
    @Autowired UserDeviceRepository deviceRepository;
    @Autowired StepUpAuditLogRepository auditRepository;
    @Autowired StepUpService stepUpService;
    @Autowired PersonalDataCipher cipher;
    @Autowired LookupHasher lookupHasher;
    @Autowired PinHasher pinHasher;

    DeviceKeys deviceKey;
    DeviceKeys biometricKey;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM step_up_audit_log");
        jdbc.update("DELETE FROM user_device_event");
        jdbc.update("DELETE FROM user_device");
        jdbc.update("DELETE FROM app_user");
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
        deviceKey = DeviceKeys.generate();
        biometricKey = DeviceKeys.generate();
    }

    // ───────── 성공과 토큰 소비 ─────────

    @Test
    void PIN으로_본인_인증하면_용도별_토큰과_인증_기록_번호를_받고_토큰은_한_번만_쓴다() throws Exception {
        UserDevice device = saveDevice("01012345678", deviceKey, null);

        MvcResult result = stepUp(device, "BARCODE", "PIN", deviceKey, PIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.purpose").value("BARCODE"))
                .andExpect(jsonPath("$.data.method").value("PIN"))
                .andExpect(jsonPath("$.data.expiresAt").exists())
                .andReturn();
        String token = read(result, "$.data.stepUpToken");
        long auditLogId = Long.parseLong(read(result, "$.data.auditLogId"));

        StepUpAuditLog log = auditRepository.findById(auditLogId).orElseThrow();
        assertThat(log.isSuccess()).isTrue();
        assertThat(log.getPurpose()).isEqualTo(StepUpPurpose.BARCODE);
        assertThat(redis.getExpire("auth:step-up:" + sha256Hex(token))).isBetween(1L, 300L);

        StepUpGrant grant = stepUpService.requireStepUp(me(device), StepUpPurpose.BARCODE, token);
        assertThat(grant.auditLogId()).isEqualTo(auditLogId);
        assertThat(grant.method()).isEqualTo(AuthMethod.PIN);
        assertStepUpRequired(() -> stepUpService.requireStepUp(me(device), StepUpPurpose.BARCODE, token));
    }

    @Test
    void 생체를_켠_기기는_생체_키_서명만으로_인증되고_allowedMethods에_생체가_있다() throws Exception {
        UserDevice withBio = saveDevice("01012345678", deviceKey, biometricKey);
        UserDevice pinOnly = saveDevice("01099998888", DeviceKeys.generate(), null);

        challenge(withBio, "CARD_REGISTER").andExpect(jsonPath("$.data.allowedMethods[0]").value("BIOMETRIC"))
                .andExpect(jsonPath("$.data.allowedMethods[1]").value("PIN"));
        challenge(pinOnly, "CARD_REGISTER").andExpect(jsonPath("$.data.allowedMethods.length()").value(1))
                .andExpect(jsonPath("$.data.allowedMethods[0]").value("PIN"));

        stepUp(withBio, "CARD_REGISTER", "BIOMETRIC", biometricKey, null).andExpect(status().isOk());
    }

    @Test
    void 결제_코드를_간편_비밀번호만으로_정하면_BARCODE는_생체를_받지_않고_다른_용도는_그대로다() throws Exception {
        UserDevice device = saveDevice("01012345678", deviceKey, biometricKey);
        jdbc.update("UPDATE app_user SET barcode_auth_method = 'PIN_ONLY' WHERE user_id = ?", device.getUser().getId());

        challenge(device, "BARCODE").andExpect(jsonPath("$.data.allowedMethods.length()").value(1))
                .andExpect(jsonPath("$.data.allowedMethods[0]").value("PIN"));
        challenge(device, "CARD_REGISTER").andExpect(jsonPath("$.data.allowedMethods[0]").value("BIOMETRIC"));

        // 생체로 보내면 서명 확인 전에 400. 인증 기록·실패 횟수는 남지 않는다
        stepUp(device, "BARCODE", "BIOMETRIC", biometricKey, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("method"));
        assertThat(auditRepository.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT failed_count FROM user_device WHERE user_device_id = ?",
                Integer.class, device.getId())).isZero();

        String pinToken = read(stepUp(device, "BARCODE", "PIN", deviceKey, PIN).andExpect(status().isOk()).andReturn(),
                "$.data.stepUpToken");
        assertThat(stepUpService.requireStepUp(me(device), StepUpPurpose.BARCODE, pinToken).method())
                .isEqualTo(AuthMethod.PIN);
        stepUp(device, "CARD_REGISTER", "BIOMETRIC", biometricKey, null).andExpect(status().isOk());
    }

    @Test
    void 생체로_받은_결제_코드_토큰은_간편_비밀번호만으로_바꾼_뒤에는_쓸_수_없다() throws Exception {
        UserDevice device = saveDevice("01012345678", deviceKey, biometricKey);
        String bioToken = read(stepUp(device, "BARCODE", "BIOMETRIC", biometricKey, null).andReturn(),
                "$.data.stepUpToken");
        String bioCardToken = read(stepUp(device, "CARD_REGISTER", "BIOMETRIC", biometricKey, null).andReturn(),
                "$.data.stepUpToken");
        jdbc.update("UPDATE app_user SET barcode_auth_method = 'PIN_ONLY' WHERE user_id = ?", device.getUser().getId());

        assertStepUpRequired(() -> stepUpService.requireStepUp(me(device), StepUpPurpose.BARCODE, bioToken));
        assertThat(redis.hasKey("auth:step-up:" + sha256Hex(bioToken))).isFalse();
        assertThat(stepUpService.requireStepUp(me(device), StepUpPurpose.CARD_REGISTER, bioCardToken).method())
                .isEqualTo(AuthMethod.BIOMETRIC);
    }

    @Test
    void 용도가_다르거나_다른_사람의_토큰이면_403이고_토큰은_버려진다() throws Exception {
        UserDevice mine = saveDevice("01012345678", deviceKey, null);
        UserDevice other = saveDevice("01099998888", DeviceKeys.generate(), null);
        String barcode = read(stepUp(mine, "BARCODE", "PIN", deviceKey, PIN).andReturn(), "$.data.stepUpToken");
        String barcode2 = read(stepUp(mine, "BARCODE", "PIN", deviceKey, PIN).andReturn(), "$.data.stepUpToken");

        assertStepUpRequired(() -> stepUpService.requireStepUp(me(mine), StepUpPurpose.ACCOUNT_LINK, barcode));
        assertStepUpRequired(() -> stepUpService.requireStepUp(me(mine), StepUpPurpose.BARCODE, barcode));
        assertStepUpRequired(() -> stepUpService.requireStepUp(me(other), StepUpPurpose.BARCODE, barcode2));
        assertStepUpRequired(() -> stepUpService.requireStepUp(me(mine), StepUpPurpose.BARCODE, null));
        assertStepUpRequired(() -> stepUpService.requireStepUp(me(mine), StepUpPurpose.BARCODE, "sut_forged"));
    }

    @Test
    void 토큰을_받은_뒤_기기가_해제되면_DEVICE_NOT_REGISTERED이고_토큰은_버려진다() throws Exception {
        UserDevice device = saveDevice("01012345678", deviceKey, null);
        String token = read(stepUp(device, "BARCODE", "PIN", deviceKey, PIN).andReturn(), "$.data.stepUpToken");
        jdbc.update("UPDATE user_device SET status = 'REVOKED' WHERE user_device_id = ?", device.getId());

        assertErrorCode(() -> stepUpService.requireStepUp(me(device), StepUpPurpose.BARCODE, token),
                ErrorCode.DEVICE_NOT_REGISTERED);
        assertThat(redis.hasKey("auth:step-up:" + sha256Hex(token))).isFalse();
    }

    @Test
    void 토큰을_받은_뒤_기기가_잠기면_DEVICE_LOCKED와_잠금_해제_시각이다() throws Exception {
        UserDevice device = saveDevice("01012345678", deviceKey, null);
        String token = read(stepUp(device, "BARCODE", "PIN", deviceKey, PIN).andReturn(), "$.data.stepUpToken");
        jdbc.update("UPDATE user_device SET status = 'LOCKED', locked_until = ? WHERE user_device_id = ?",
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(30), device.getId());

        assertThatThrownBy(() -> stepUpService.requireStepUp(me(device), StepUpPurpose.BARCODE, token))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DEVICE_LOCKED);
                    assertThat(e.getDetails()).containsKey("lockedUntil");
                });
    }

    @Test
    void 앞서_받은_인증을_다시_쓸_때도_간편_비밀번호만_설정과_기기_잠금을_다시_본다() {
        UserDevice device = saveDevice("01012345678", deviceKey, biometricKey);
        stepUpService.requireReusable(me(device), StepUpPurpose.BARCODE, AuthMethod.BIOMETRIC);

        jdbc.update("UPDATE app_user SET barcode_auth_method = 'PIN_ONLY' WHERE user_id = ?", device.getUser().getId());
        assertErrorCode(() -> stepUpService.requireReusable(me(device), StepUpPurpose.BARCODE, AuthMethod.BIOMETRIC),
                ErrorCode.STEP_UP_REQUIRED);
        stepUpService.requireReusable(me(device), StepUpPurpose.BARCODE, AuthMethod.PIN);

        jdbc.update("UPDATE user_device SET status = 'LOCKED', locked_until = ? WHERE user_device_id = ?",
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(30), device.getId());
        assertErrorCode(() -> stepUpService.requireReusable(me(device), StepUpPurpose.BARCODE, AuthMethod.PIN),
                ErrorCode.DEVICE_LOCKED);

        jdbc.update("UPDATE user_device SET status = 'REVOKED' WHERE user_device_id = ?", device.getId());
        assertErrorCode(() -> stepUpService.requireReusable(me(device), StepUpPurpose.BARCODE, AuthMethod.PIN),
                ErrorCode.DEVICE_NOT_REGISTERED);
    }

    // ───────── 실패 ─────────

    @Test
    void 틀린_PIN은_STEP_UP_FAILED와_남은_횟수_틀린_서명은_횟수에_안_들어간다() throws Exception {
        UserDevice device = saveDevice("01012345678", deviceKey, null);

        stepUp(device, "BARCODE", "PIN", deviceKey, WRONG_PIN)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("STEP_UP_FAILED"))
                .andExpect(jsonPath("$.error.details.remainingAttempts").value(4));
        stepUp(device, "BARCODE", "PIN", DeviceKeys.generate(), PIN)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("STEP_UP_FAILED"))
                .andExpect(jsonPath("$.error.details").doesNotExist());

        assertThat(deviceRepository.findById(device.getId()).orElseThrow().getFailedCount()).isEqualTo(1);
        assertThat(auditRepository.findByUserDeviceIdOrderByIdAsc(device.getId()))
                .extracting(StepUpAuditLog::getFailReason)
                .containsExactly("PIN_MISMATCH", "SIGNATURE_INVALID");
    }

    @Test
    void 본인_인증과_로그인은_실패_횟수를_같이_세서_합쳐_5번이면_잠긴다() throws Exception {
        UserDevice device = saveDevice("01012345678", deviceKey, null);
        for (int i = 0; i < 4; i++) {
            stepUp(device, "BARCODE", "PIN", deviceKey, WRONG_PIN).andExpect(jsonPath("$.error.code").value("STEP_UP_FAILED"));
        }

        login(device, WRONG_PIN)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("DEVICE_LOCKED"));
        challenge(device, "BARCODE")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("DEVICE_LOCKED"))
                .andExpect(jsonPath("$.error.details.lockedUntil").exists());
    }

    @Test
    void 잘못된_용도는_INVALID_PURPOSE이고_로그인은_본인_인증_용도가_아니다() throws Exception {
        UserDevice device = saveDevice("01012345678", deviceKey, null);

        challenge(device, "NOPE").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_PURPOSE"));
        challenge(device, "LOGIN").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_PURPOSE"));
    }

    // ───────── challenge ─────────

    @Test
    void 로그인_challenge와_본인_인증_challenge는_서로_쓸_수_없다() throws Exception {
        UserDevice device = saveDevice("01012345678", deviceKey, null);

        MvcResult loginChallenge = mockMvc.perform(post("/api/auth/login/challenge")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userDeviceId\":" + device.getId() + "}")).andReturn();
        verify(device, read(loginChallenge, "$.data.challengeId"), "PIN",
                deviceKey.sign(read(loginChallenge, "$.data.challenge")), PIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CHALLENGE_EXPIRED"));

        MvcResult stepUpChallenge = challenge(device, "BARCODE").andReturn();
        loginWith(device, read(stepUpChallenge, "$.data.challengeId"),
                deviceKey.sign(read(stepUpChallenge, "$.data.challenge")), PIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CHALLENGE_EXPIRED"));
    }

    @Test
    void 같은_challenge로_두_번_인증하거나_다른_기기의_challenge를_쓸_수_없다() throws Exception {
        UserDevice mine = saveDevice("01012345678", deviceKey, null);
        DeviceKeys otherKey = DeviceKeys.generate();
        UserDevice other = saveDevice("01099998888", otherKey, null);

        MvcResult c = challenge(mine, "BARCODE").andReturn();
        String challengeId = read(c, "$.data.challengeId");
        String signature = deviceKey.sign(read(c, "$.data.challenge"));
        verify(mine, challengeId, "PIN", signature, PIN).andExpect(status().isOk());
        verify(mine, challengeId, "PIN", signature, PIN).andExpect(jsonPath("$.error.code").value("CHALLENGE_EXPIRED"));

        MvcResult otherChallenge = challenge(other, "BARCODE").andReturn();
        verify(mine, read(otherChallenge, "$.data.challengeId"), "PIN",
                deviceKey.sign(read(otherChallenge, "$.data.challenge")), PIN)
                .andExpect(jsonPath("$.error.code").value("CHALLENGE_EXPIRED"));
    }

    @Test
    void 로그인하지_않으면_401이다() throws Exception {
        mockMvc.perform(post("/api/auth/step-up/challenge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"BARCODE\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ───────── helpers ─────────

    private UserDevice saveDevice(String phone, DeviceKeys key, DeviceKeys bioKey) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        AppUser user = AppUser.pending("김야무", LocalDate.of(1995, 3, 14),
                cipher.encrypt(phone, CipherField.APP_USER_PHONE), lookupHasher.phone(phone), now);
        user.changePin(pinHasher.hash(PIN), now);
        user.activate();
        userRepository.save(user);
        return deviceRepository.save(UserDevice.register(user, UUID.randomUUID().toString(), "Galaxy S24",
                DevicePlatform.ANDROID, key.publicKey(), DeviceSignatureVerifier.EC_P256,
                bioKey == null ? null : bioKey.publicKey()));
    }

    private static AuthUser me(UserDevice device) {
        return new AuthUser(device.getUser().getId(), device.getId());
    }

    private ResultActions challenge(UserDevice device, String purpose) throws Exception {
        return mockMvc.perform(post("/api/auth/step-up/challenge")
                .with(AuthUsers.asUser(device.getUser().getId(), device.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"purpose\":\"" + purpose + "\"}"));
    }

    private ResultActions stepUp(UserDevice device, String purpose, String method, DeviceKeys signer, String pin)
            throws Exception {
        MvcResult c = challenge(device, purpose).andExpect(status().isOk()).andReturn();
        return verify(device, read(c, "$.data.challengeId"), method, signer.sign(read(c, "$.data.challenge")), pin);
    }

    private ResultActions verify(UserDevice device, String challengeId, String method, String signature, String pin)
            throws Exception {
        String pinJson = pin == null ? "null" : "\"" + pin + "\"";
        return mockMvc.perform(post("/api/auth/step-up/verify")
                .with(AuthUsers.asUser(device.getUser().getId(), device.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"challengeId":"%s","method":"%s","signature":"%s","pin":%s}
                        """.formatted(challengeId, method, signature, pinJson)));
    }

    private ResultActions login(UserDevice device, String pin) throws Exception {
        MvcResult c = mockMvc.perform(post("/api/auth/login/challenge")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userDeviceId\":" + device.getId() + "}")).andReturn();
        return loginWith(device, read(c, "$.data.challengeId"), deviceKey.sign(read(c, "$.data.challenge")), pin);
    }

    private ResultActions loginWith(UserDevice device, String challengeId, String signature, String pin) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"userDeviceId":%d,"challengeId":"%s","method":"PIN","signature":"%s","pin":"%s"}
                        """.formatted(device.getId(), challengeId, signature, pin)));
    }

    private static void assertStepUpRequired(Runnable call) {
        assertErrorCode(call, ErrorCode.STEP_UP_REQUIRED);
    }

    private static void assertErrorCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
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
