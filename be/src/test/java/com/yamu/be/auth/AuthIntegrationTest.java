package com.yamu.be.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.yamu.be.auth.domain.DevicePlatform;
import com.yamu.be.auth.domain.DeviceStatus;
import com.yamu.be.auth.domain.StepUpAuditLog;
import com.yamu.be.auth.domain.StepUpAuditLogRepository;
import com.yamu.be.auth.domain.UserDevice;
import com.yamu.be.auth.domain.UserDeviceEventRepository;
import com.yamu.be.auth.domain.UserDeviceRepository;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.LookupHasher;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.crypto.PinHasher;
import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.DeviceKeys;
import com.yamu.be.support.MutableClock;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.user.domain.AppUser;
import com.yamu.be.user.domain.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

/**
 * 로그인 준비·로그인·재발급·로그아웃을 실제 MySQL(Flyway 스키마)과 Redis로 확인한다.
 * 서명은 {@link DeviceKeys}가 폰 보안 칩 대신 만든다.
 */
@AutoConfigureMockMvc
@Import(AuthIntegrationTest.ClockConfig.class)
@TestPropertySource(properties = {
        "spring.application.name=yamu-auth-integration-test",
        // 재사용 감지를 바로 확인하려고 동시 재발급 유예를 끈다.
        "yamu.auth.refresh-reuse-grace=0s",
        // 잠금·동시성 테스트가 한 기기로 요청을 10번 넘게 보내므로 호출 횟수 제한을 넉넉히 둔다. 제한 자체는 LoginRateLimiterTest가 본다.
        "yamu.auth.rate-limit.per-device=30",
        "yamu.auth.rate-limit.per-ip=200"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthIntegrationTest extends MySqlTestSupport {

    private static final String PIN = "482913";
    private static final String WRONG_PIN = "000000";

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired MutableClock clock;
    @Autowired AppUserRepository userRepository;
    @Autowired UserDeviceRepository deviceRepository;
    @Autowired UserDeviceEventRepository eventRepository;
    @Autowired StepUpAuditLogRepository auditRepository;
    @Autowired PersonalDataCipher cipher;
    @Autowired LookupHasher lookupHasher;
    @Autowired PinHasher pinHasher;
    @Autowired JwtEncoder jwtEncoder;

    DeviceKeys deviceKey;
    DeviceKeys biometricKey;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM step_up_audit_log");
        jdbc.update("DELETE FROM user_device_event");
        jdbc.update("DELETE FROM user_device");
        jdbc.update("DELETE FROM app_user");
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
        clock.reset();
        deviceKey = DeviceKeys.generate();
        biometricKey = DeviceKeys.generate();
    }

    // ───────── 로그인 성공 ─────────

    @Test
    void PIN으로_로그인하면_토큰을_받고_그_토큰으로_인증된다() throws Exception {
        UserDevice device = activeDevice(false);

        MvcResult result = login(device, "PIN", deviceKey, PIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(1800))
                .andExpect(jsonPath("$.data.user.name").value("김야무"))
                .andExpect(jsonPath("$.data.user.onboardingCompleted").value(false))
                .andReturn();
        String access = read(result, "$.data.accessToken");
        String refresh = read(result, "$.data.refreshToken");

        assertThat(refresh).startsWith(device.getId() + ".");
        logout(access, refresh).andExpect(status().isNoContent());
        List<StepUpAuditLog> logs = auditRepository.findByUserDeviceIdOrderByIdAsc(device.getId());
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).isSuccess()).isTrue();
        assertThat(logs.get(0).getPurpose().name()).isEqualTo("LOGIN");
    }

    @Test
    void 생체_로그인은_생체_키_서명만으로_된다() throws Exception {
        UserDevice device = activeDevice(true);

        challenge(device).andExpect(jsonPath("$.data.biometricEnabled").value(true));
        login(device, "BIOMETRIC", biometricKey, null).andExpect(status().isOk());
    }

    // ───────── 실패 ─────────

    @Test
    void PIN이_틀리면_남은_횟수를_알려주고_실패를_기록한다() throws Exception {
        UserDevice device = activeDevice(false);

        login(device, "PIN", deviceKey, WRONG_PIN)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("PIN_MISMATCH"))
                .andExpect(jsonPath("$.error.details.remainingAttempts").value(4));

        assertThat(deviceRepository.findById(device.getId()).orElseThrow().getFailedCount()).isEqualTo(1);
        StepUpAuditLog log = auditRepository.findByUserDeviceIdOrderByIdAsc(device.getId()).get(0);
        assertThat(log.isSuccess()).isFalse();
        assertThat(log.getFailReason()).isEqualTo("PIN_MISMATCH");
    }

    @Test
    void 다른_기기의_키로_서명하면_PIN이_맞아도_실패한다() throws Exception {
        UserDevice device = activeDevice(false);

        login(device, "PIN", DeviceKeys.generate(), PIN)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("SIGNATURE_INVALID"));
    }

    @Test
    void 엉터리_서명은_몇_번이_와도_기기를_잠그지_않고_기록만_남긴다() throws Exception {
        UserDevice device = activeDevice(false);
        DeviceKeys stranger = DeviceKeys.generate();
        for (int i = 0; i < 6; i++) {
            login(device, "PIN", stranger, WRONG_PIN).andExpect(jsonPath("$.error.code").value("SIGNATURE_INVALID"));
        }

        UserDevice after = deviceRepository.findById(device.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(DeviceStatus.ACTIVE);
        assertThat(after.getFailedCount()).isZero();
        assertThat(auditRepository.findByUserDeviceIdOrderByIdAsc(device.getId()))
                .hasSize(6)
                .allSatisfy(log -> assertThat(log.getFailReason()).isEqualTo("SIGNATURE_INVALID"));
        login(device, "PIN", deviceKey, PIN).andExpect(status().isOk());
    }

    @Test
    void PIN_로그인에_생체_키로_서명하면_실패한다() throws Exception {
        UserDevice device = activeDevice(true);

        login(device, "PIN", biometricKey, PIN).andExpect(jsonPath("$.error.code").value("SIGNATURE_INVALID"));
    }

    @Test
    void 생체를_켜지_않은_기기는_생체_로그인이_안된다() throws Exception {
        UserDevice device = activeDevice(false);

        login(device, "BIOMETRIC", biometricKey, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BIOMETRIC_NOT_ENABLED"));
    }

    @Test
    void PIN_방식에_PIN이_없으면_400이고_challenge는_남는다() throws Exception {
        UserDevice device = activeDevice(false);
        MvcResult c = challenge(device).andReturn();

        loginWith(device, read(c, "$.data.challengeId"), "PIN", deviceKey.sign(read(c, "$.data.challenge")), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("pin"));
        loginWith(device, read(c, "$.data.challengeId"), "PIN", deviceKey.sign(read(c, "$.data.challenge")), PIN)
                .andExpect(status().isOk());
    }

    // ───────── challenge ─────────

    @Test
    void 같은_challenge로_두_번_로그인할_수_없다() throws Exception {
        UserDevice device = activeDevice(false);
        MvcResult c = challenge(device).andReturn();
        String challengeId = read(c, "$.data.challengeId");
        String signature = deviceKey.sign(read(c, "$.data.challenge"));

        loginWith(device, challengeId, "PIN", signature, PIN).andExpect(status().isOk());
        loginWith(device, challengeId, "PIN", signature, PIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CHALLENGE_EXPIRED"));
    }

    @Test
    void 다른_기기용_challenge는_쓸_수_없다() throws Exception {
        UserDevice mine = activeDevice(false);
        UserDevice other = otherActiveDevice();
        MvcResult c = challenge(other).andReturn();

        loginWith(mine, read(c, "$.data.challengeId"), "PIN", deviceKey.sign(read(c, "$.data.challenge")), PIN)
                .andExpect(jsonPath("$.error.code").value("CHALLENGE_EXPIRED"));
    }

    @Test
    void 해제된_기기는_challenge를_받을_수_없다() throws Exception {
        UserDevice device = activeDevice(false);
        UserDevice saved = deviceRepository.findById(device.getId()).orElseThrow();
        saved.revoke();
        deviceRepository.save(saved);

        challenge(device)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("DEVICE_NOT_REGISTERED"));
    }

    // ───────── 잠금 ─────────

    @Test
    void 다섯_번_틀리면_30분_잠기고_시간이_지나면_풀린다() throws Exception {
        UserDevice device = activeDevice(false);
        for (int i = 1; i <= 4; i++) {
            login(device, "PIN", deviceKey, WRONG_PIN).andExpect(jsonPath("$.error.code").value("PIN_MISMATCH"));
        }
        login(device, "PIN", deviceKey, WRONG_PIN)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("DEVICE_LOCKED"))
                .andExpect(jsonPath("$.error.details.lockedUntil").exists());

        challenge(device).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("DEVICE_LOCKED"));

        clock.advance(Duration.ofMinutes(31));
        login(device, "PIN", deviceKey, PIN).andExpect(status().isOk());
        UserDevice after = deviceRepository.findById(device.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(DeviceStatus.ACTIVE);
        assertThat(after.getFailedCount()).isZero();
        assertThat(eventRepository.findByUserDeviceIdOrderByIdAsc(device.getId()))
                .extracting(e -> e.getEventType().name())
                .containsExactly("LOCKED", "UNLOCKED");
    }

    @Test
    void 틀린_PIN이_동시에_와도_실패_횟수가_빠지지_않고_정확히_잠긴다() throws Exception {
        UserDevice device = activeDevice(false);
        int attempts = 5;
        List<String[]> prepared = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            MvcResult c = challenge(device).andReturn();
            prepared.add(new String[] {read(c, "$.data.challengeId"), deviceKey.sign(read(c, "$.data.challenge"))});
        }
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> codes = new ArrayList<>();
        for (String[] p : prepared) {
            codes.add(pool.submit(() -> {
                start.await();
                return JsonPath.read(loginWith(device, p[0], "PIN", p[1], WRONG_PIN).andReturn()
                        .getResponse().getContentAsString(), "$.error.code");
            }));
        }
        start.countDown();
        List<String> results = new ArrayList<>();
        for (Future<String> f : codes) {
            results.add(f.get());
        }
        pool.shutdown();

        UserDevice after = deviceRepository.findById(device.getId()).orElseThrow();
        assertThat(after.getFailedCount()).isEqualTo(attempts);
        assertThat(after.getStatus()).isEqualTo(DeviceStatus.LOCKED);
        assertThat(results).filteredOn("DEVICE_LOCKED"::equals).hasSize(1);
        assertThat(results).filteredOn("PIN_MISMATCH"::equals).hasSize(attempts - 1);
        assertThat(auditRepository.findByUserDeviceIdOrderByIdAsc(device.getId())).hasSize(attempts);
    }

    // ───────── 호출 횟수 제한 ─────────

    @Test
    void 같은_기기로_로그인_준비를_너무_많이_부르면_429다() throws Exception {
        UserDevice device = activeDevice(false);
        for (int i = 0; i < 30; i++) {
            challenge(device).andExpect(status().isOk());
        }

        challenge(device)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.error.details.retryAfterSeconds").isNumber());
    }

    @Test
    void 없는_기기_번호도_횟수에_들어가_하나씩_넣어_보기가_막힌다() throws Exception {
        for (long id = 1_000_000; id < 1_000_200; id++) {
            challengeFor(id).andExpect(status().isUnauthorized());
        }

        challengeFor(2_000_000L).andExpect(status().isTooManyRequests());
    }

    // ───────── 재발급·로그아웃 ─────────

    @Test
    void 재발급하면_토큰이_바뀌고_직전_토큰을_다시_쓰면_세션이_끊긴다() throws Exception {
        UserDevice device = activeDevice(false);
        String first = read(login(device, "PIN", deviceKey, PIN).andReturn(), "$.data.refreshToken");

        MvcResult rotated = refresh(first).andExpect(status().isOk()).andReturn();
        String second = read(rotated, "$.data.refreshToken");
        assertThat(second).isNotEqualTo(first);

        refresh(first).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
        refresh(second).andExpect(status().isUnauthorized());
    }

    @Test
    void 엉터리_토큰은_거절만_하고_진짜_세션은_그대로다() throws Exception {
        UserDevice device = activeDevice(false);
        String real = read(login(device, "PIN", deviceKey, PIN).andReturn(), "$.data.refreshToken");

        refresh(device.getId() + ".garbage").andExpect(status().isUnauthorized());
        refresh("not-a-token").andExpect(status().isUnauthorized());
        refresh(real).andExpect(status().isOk());
    }

    @Test
    void 로그아웃하면_재발급이_안된다() throws Exception {
        UserDevice device = activeDevice(false);
        MvcResult result = login(device, "PIN", deviceKey, PIN).andReturn();
        String access = read(result, "$.data.accessToken");
        String refresh = read(result, "$.data.refreshToken");

        logout(access, refresh).andExpect(status().isNoContent());
        refresh(refresh).andExpect(status().isUnauthorized());
    }

    @Test
    void 테스트_유틸로_넣은_사용자가_AuthUser로_들어간다() throws Exception {
        UserDevice device = activeDevice(false);
        String refresh = read(login(device, "PIN", deviceKey, PIN).andReturn(), "$.data.refreshToken");

        mockMvc.perform(post("/api/auth/logout")
                        .with(AuthUsers.asUser(device.getUser().getId(), device.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isNoContent());
        refresh(refresh).andExpect(status().isUnauthorized());
    }

    @Test
    void 해제된_기기의_refresh는_거절하고_세션을_지운다() throws Exception {
        UserDevice device = activeDevice(false);
        String refresh = read(login(device, "PIN", deviceKey, PIN).andReturn(), "$.data.refreshToken");
        UserDevice saved = deviceRepository.findById(device.getId()).orElseThrow();
        saved.revoke();
        deviceRepository.save(saved);

        refresh(refresh).andExpect(status().isUnauthorized());
        assertThat(redis.hasKey("auth:refresh:" + device.getId())).isFalse();
    }

    // ───────── Access Token 검사 ─────────

    @Test
    void 토큰이_없거나_위조되면_UNAUTHORIZED_만료되면_TOKEN_EXPIRED() throws Exception {
        UserDevice device = activeDevice(false);

        logout(null, "x").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        logout("eyJhbGciOiJIUzI1NiJ9.e30.forged", "x").andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        logout(expiredAccessToken(device), "x")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"));
    }

    @Test
    void Access_Token은_30분과_허용_오차가_지나면_만료된다() throws Exception {
        UserDevice device = activeDevice(false);
        MvcResult result = login(device, "PIN", deviceKey, PIN).andReturn();
        String access = read(result, "$.data.accessToken");

        clock.advance(Duration.ofMinutes(32));
        logout(access, "x").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"));
    }

    @Test
    void 헬스_체크는_만료된_토큰이_붙어도_열린다() throws Exception {
        UserDevice device = activeDevice(false);

        mockMvc.perform(get("/api/health").header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredAccessToken(device)))
                .andExpect(status().isOk());
    }

    @Test
    void 만료된_Access_Token을_붙인_채로도_재발급은_된다() throws Exception {
        UserDevice device = activeDevice(false);
        String refresh = read(login(device, "PIN", deviceKey, PIN).andReturn(), "$.data.refreshToken");

        mockMvc.perform(post("/api/auth/refresh")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredAccessToken(device))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isOk());
    }

    // ───────── helpers ─────────

    private UserDevice activeDevice(boolean biometric) {
        return saveDevice("01012345678", deviceKey, biometric ? biometricKey : null);
    }

    private UserDevice otherActiveDevice() {
        return saveDevice("01099998888", DeviceKeys.generate(), null);
    }

    private UserDevice saveDevice(String phone, DeviceKeys key, DeviceKeys bioKey) {
        LocalDateTime now = LocalDateTime.now(clock);
        AppUser user = AppUser.pending("김야무", LocalDate.of(1995, 3, 14),
                cipher.encrypt(phone, CipherField.APP_USER_PHONE), lookupHasher.phone(phone), now);
        user.changePin(pinHasher.hash(PIN), now);
        user.activate();
        userRepository.save(user);
        return deviceRepository.save(UserDevice.register(user, UUID.randomUUID().toString(), "Galaxy S24",
                DevicePlatform.ANDROID, key.publicKey(), DeviceSignatureVerifier.EC_P256,
                bioKey == null ? null : bioKey.publicKey()));
    }

    private ResultActions challenge(UserDevice device) throws Exception {
        return mockMvc.perform(post("/api/auth/login/challenge")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userDeviceId\":" + device.getId() + "}"));
    }

    private ResultActions challengeFor(long userDeviceId) throws Exception {
        return mockMvc.perform(post("/api/auth/login/challenge")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userDeviceId\":" + userDeviceId + "}"));
    }

    private ResultActions login(UserDevice device, String method, DeviceKeys signer, String pin) throws Exception {
        MvcResult c = challenge(device).andExpect(status().isOk()).andReturn();
        return loginWith(device, read(c, "$.data.challengeId"), method, signer.sign(read(c, "$.data.challenge")), pin);
    }

    private ResultActions loginWith(UserDevice device, String challengeId, String method, String signature, String pin)
            throws Exception {
        String pinJson = pin == null ? "null" : "\"" + pin + "\"";
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"userDeviceId":%d,"challengeId":"%s","method":"%s","signature":"%s","pin":%s}
                        """.formatted(device.getId(), challengeId, method, signature, pinJson)));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"));
    }

    private ResultActions logout(String accessToken, String refreshToken) throws Exception {
        var request = post("/api/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}");
        if (accessToken != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        }
        return mockMvc.perform(request);
    }

    private String expiredAccessToken(UserDevice device) {
        Instant past = clock.instant().minus(Duration.ofHours(2));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(String.valueOf(device.getUser().getId()))
                .claim("did", device.getId())
                .issuedAt(past)
                .expiresAt(past.plus(Duration.ofMinutes(30)))
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private static String read(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path).toString();
    }
}
