package com.yamu.be.payment.barcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.yamu.be.auth.DeviceSignatureVerifier;
import com.yamu.be.auth.StepUpService;
import com.yamu.be.auth.StepUpTokenStore;
import com.yamu.be.auth.StepUpTokenStore.StepUpGrant;
import com.yamu.be.auth.domain.AuthMethod;
import com.yamu.be.auth.domain.DevicePlatform;
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
import com.yamu.be.support.OwnershipFixtures;
import com.yamu.be.user.domain.AppUser;
import com.yamu.be.user.domain.AppUserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
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
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

/**
 * 바코드 발급·조회를 가짜 없이 확인한다. BE 1의 실제 {@link StepUpService}·{@link StepUpTokenStore}(Redis),
 * 실제 {@link RedisBarcodeStore}, 실제 MySQL의 user_card를 쓴다. 본인 인증 토큰은 {@link StepUpTokenStore}로
 * 직접 만들어(서명·PIN 검증은 StepUpIntegrationTest가 확인) 소비 순서와 사용자·용도 검증을 본다.
 */
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BarcodeIssueIntegrationTest extends MySqlTestSupport {

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired AppUserRepository userRepository;
    @Autowired UserDeviceRepository deviceRepository;
    @Autowired StepUpTokenStore tokenStore;
    @Autowired BarcodeService barcodeService;
    @Autowired PersonalDataCipher cipher;
    @Autowired LookupHasher lookupHasher;
    @Autowired PinHasher pinHasher;

    OwnershipFixtures fixtures;
    UserDevice device;
    long product;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM user_card");
        jdbc.update("DELETE FROM linked_account");
        jdbc.update("DELETE FROM card_product");
        jdbc.update("DELETE FROM step_up_audit_log");
        jdbc.update("DELETE FROM user_device_event");
        jdbc.update("DELETE FROM user_device");
        // 승인·취소·청구 사건이 쌓은 알림(Task 9.3.2)이 사용자를 참조한다
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM app_user");
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
        fixtures = new OwnershipFixtures(jdbc);
        device = saveDevice("01012345678");
        product = fixtures.product();
    }

    // ───────── 정상 발급 ─────────

    @Test
    void 실제_본인_인증_토큰으로_발급하면_201이고_토큰은_소비되며_ACTIVE_카드만_센다() throws Exception {
        long userId = userId(device);
        fixtures.card(userId, product, null, "ACTIVE");
        fixtures.card(userId, product, null, "INACTIVE");
        fixtures.card(userId, product, null, "UNREGISTERED");
        String token = barcodeToken(device);

        MvcResult result = issue(device, token)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.data.status").value("ISSUED"))
                .andExpect(jsonPath("$.data.activeCardCount").value(1))
                .andReturn();
        String code = read(result, "$.data.barcodeToken");
        assertThat(code).matches("^[0-9]{16}$");
        assertThat(read(result, "$.data.barcodeValue")).isEqualTo(code);
        assertThat(read(result, "$.data.qrValue")).isEqualTo("yamu://pay/" + code);
        LocalDateTime issuedAt = LocalDateTime.parse(read(result, "$.data.issuedAt").replace("Z", ""));
        LocalDateTime expiresAt = LocalDateTime.parse(read(result, "$.data.expiresAt").replace("Z", ""));
        assertThat(Duration.between(issuedAt, expiresAt)).isEqualTo(Duration.ofSeconds(60));

        // 토큰은 한 번 쓰이고 지워진다. Redis에는 바코드 두 키가 10분 보관으로 들어 있다
        assertThat(redis.hasKey("auth:step-up:" + sha256Hex(token))).isFalse();
        assertThat(redis.getExpire("barcode:code:" + code, TimeUnit.SECONDS)).isBetween(590L, 600L);
        assertThat(redis.opsForValue().get("barcode:user:" + userId)).isEqualTo(code);

        fetch(device,code)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ISSUED"))
                .andExpect(jsonPath("$.data.payment").value(nullValue()));
    }

    // ───────── 인증·검증 순서 ─────────

    @Test
    void 카드가_없어_409가_나면_토큰은_남고_카드를_등록한_뒤_같은_토큰으로_발급되며_그_뒤에는_403이다() throws Exception {
        long userId = userId(device);
        String token = barcodeToken(device);

        issue(device, token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NO_ACTIVE_CARD"));
        assertThat(redis.hasKey("auth:step-up:" + sha256Hex(token))).as("카드 없음으로 실패하면 토큰을 쓰지 않는다").isTrue();

        fixtures.card(userId, product, null, "ACTIVE");
        issue(device, token).andExpect(status().isCreated());
        assertThat(redis.hasKey("auth:step-up:" + sha256Hex(token))).isFalse();

        issue(device, token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("STEP_UP_REQUIRED"));
    }

    @Test
    void 토큰이_없거나_다른_사람_토큰이거나_다른_용도_토큰이면_403이다() throws Exception {
        UserDevice other = saveDevice("01099998888");
        fixtures.card(userId(device), product, null, "ACTIVE");
        fixtures.card(userId(other), product, null, "ACTIVE");

        issue(device, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("STEP_UP_REQUIRED"));

        String othersToken = barcodeToken(other);
        issue(device, othersToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("STEP_UP_REQUIRED"));

        String cardRegisterToken = tokenStore.issue(new StepUpGrant(userId(device), device.getId(),
                StepUpPurpose.CARD_REGISTER, AuthMethod.PIN, 0L));
        issue(device, cardRegisterToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("STEP_UP_REQUIRED"));
        // BE 1 정책: 사용자·용도가 틀린 토큰도 한 번 꺼내면 버려진다(다시 본인 인증)
        assertThat(redis.hasKey("auth:step-up:" + sha256Hex(othersToken))).isFalse();
        assertThat(redis.hasKey("auth:step-up:" + sha256Hex(cardRegisterToken))).isFalse();
    }

    // ───────── 재발급·사용자 격리·조회 ─────────

    @Test
    void 새_토큰으로_다시_발급하면_이전_코드는_EXPIRED로_남고_다른_사용자_코드는_그대로다() throws Exception {
        UserDevice other = saveDevice("01099998888");
        long userId = userId(device);
        fixtures.card(userId, product, null, "ACTIVE");
        fixtures.card(userId(other), product, null, "ACTIVE");
        String othersCode = read(issue(other, barcodeToken(other)).andReturn(), "$.data.barcodeToken");

        String first = read(issue(device, barcodeToken(device)).andReturn(), "$.data.barcodeToken");
        String second = read(issue(device, barcodeToken(device)).andReturn(), "$.data.barcodeToken");

        fetch(device,first).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("EXPIRED"));
        fetch(device,second).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ISSUED"));
        fetch(other,othersCode).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ISSUED"));
        assertThat(redis.opsForValue().get("barcode:user:" + userId)).isEqualTo(second);
        // 다른 사용자가 내 코드를 조회하면 존재를 숨긴다
        fetch(other,second).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BARCODE_NOT_FOUND"));
    }

    // ───────── 동시 요청 ─────────

    @Test
    void 같은_본인_인증_토큰으로_동시에_발급하면_한_건만_성공한다() throws Exception {
        long userId = userId(device);
        fixtures.card(userId, product, null, "ACTIVE");
        String token = barcodeToken(device);
        AuthUser me = new AuthUser(userId, device.getId());

        List<Object> outcomes = runConcurrently(5, () -> {
            try {
                return barcodeService.issue(me, token).barcodeToken();
            } catch (BusinessException e) {
                return e.getErrorCode();
            }
        });

        assertThat(outcomes.stream().filter(String.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(ErrorCode.STEP_UP_REQUIRED::equals)).hasSize(4);
    }

    @Test
    void 같은_사용자가_서로_다른_토큰으로_동시에_발급해도_ISSUED_코드는_하나다() throws Exception {
        long userId = userId(device);
        fixtures.card(userId, product, null, "ACTIVE");
        AuthUser me = new AuthUser(userId, device.getId());
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tokens.add(barcodeToken(device));
        }

        List<Object> codes = runConcurrently(5, new ArrayList<>(tokens), token -> barcodeService.issue(me, token).barcodeToken());

        long issued = codes.stream()
                .filter(code -> barcodeService.get(userId, (String) code).status() == BarcodeStatus.ISSUED)
                .count();
        assertThat(codes).hasSize(5);
        assertThat(issued).isEqualTo(1);
        assertThat(redis.opsForValue().get("barcode:user:" + userId)).isIn(codes);
    }

    @Test
    void 보관_시간이_끝나_Redis에서_사라진_코드는_404다() throws Exception {
        long userId = userId(device);
        fixtures.card(userId, product, null, "ACTIVE");
        String code = read(issue(device, barcodeToken(device)).andReturn(), "$.data.barcodeToken");
        redis.delete("barcode:code:" + code); // 10분이 지나 Redis가 지운 상태를 흉내 낸다(실제 TTL은 RedisBarcodeStoreTest)

        fetch(device,code).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BARCODE_NOT_FOUND"));
    }

    // ───────── 코드 형식·존재 숨김 ─────────

    @Test
    void 형식이_다른_코드는_Redis_키를_건드리지_않고_404다() throws Exception {
        long userId = userId(device);
        fixtures.card(userId, product, null, "ACTIVE");
        issue(device, barcodeToken(device)).andExpect(status().isCreated());
        // barcode:user:{id}는 해시가 아니라 문자열 키다. 형식 검사 없이 "user:{id}"를 해시로 읽으면 WRONGTYPE으로 500이 난다
        assertThat(redis.type("barcode:user:" + userId)).isEqualTo(DataType.STRING);

        for (String code : List.of("user:" + userId, "1234-5678-9012-3456", "１２３４５６７８９０１２３４５６",
                "123456789012345", "12345678901234567")) {
            fetch(device, code)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("BARCODE_NOT_FOUND"));
        }
    }

    @Test
    void 형식_오류와_없는_코드와_다른_사용자_코드는_똑같은_404_응답이다() throws Exception {
        UserDevice other = saveDevice("01099998888");
        fixtures.card(userId(other), product, null, "ACTIVE");
        String othersCode = read(issue(other, barcodeToken(other)).andReturn(), "$.data.barcodeToken");

        String malformed = notFoundBody(fetch(device, "user:" + userId(other)));
        String missing = notFoundBody(fetch(device, "0000000000000000"));
        String othersBody = notFoundBody(fetch(device, othersCode));

        // 응답이 조금이라도 다르면 그 차이로 남의 코드가 있는지 짐작할 수 있다
        assertThat(missing).isEqualTo(malformed);
        assertThat(othersBody).isEqualTo(malformed);
        assertThat(JsonPath.<String>read(malformed, "$.error.code")).isEqualTo("BARCODE_NOT_FOUND");
    }

    @Test
    void 사용자_키_이름을_코드_자리에_넣어_조회해도_500이_아니라_404다() throws Exception {
        // 바코드 키와 사용자 키(barcode:user:{id}, 문자열)의 접두어가 같으면 HGETALL이 WRONGTYPE으로 500이 나고,
        // 404와의 차이로 다른 사용자가 바코드를 발급했는지 드러난다(!57 리뷰). 5.1.2의 형식 검사와 134의 접두어 분리가 둘 다 막는다
        long userId = userId(device);
        fixtures.card(userId, product, null, "ACTIVE");
        issue(device, barcodeToken(device)).andExpect(status().isCreated());

        fetch(device, "user:" + userId).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BARCODE_NOT_FOUND"));
    }

    // ───────── 도우미 ─────────

    private ResultActions issue(UserDevice device, String stepUpToken) throws Exception {
        var request = post("/api/payments/barcodes").with(AuthUsers.asUser(userId(device), device.getId()));
        if (stepUpToken != null) {
            request = request.header(StepUpService.HEADER, stepUpToken);
        }
        return mockMvc.perform(request);
    }

    private ResultActions fetch(UserDevice device, String code) throws Exception {
        return mockMvc.perform(get("/api/payments/barcodes/{code}", code)
                .with(AuthUsers.asUser(userId(device), device.getId())));
    }

    /** 이 기기에서 BARCODE 용도 본인 인증을 마친 것과 같은 토큰. */
    private String barcodeToken(UserDevice device) {
        return tokenStore.issue(new StepUpGrant(userId(device), device.getId(), StepUpPurpose.BARCODE,
                AuthMethod.PIN, 0L));
    }

    private UserDevice saveDevice(String phone) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        AppUser user = AppUser.pending("바코드테스터", LocalDate.of(1995, 3, 14),
                cipher.encrypt(phone, CipherField.APP_USER_PHONE), lookupHasher.phone(phone), now);
        user.changePin(pinHasher.hash("482913"), now);
        user.activate();
        userRepository.save(user);
        return deviceRepository.save(UserDevice.register(user, UUID.randomUUID().toString(), "Galaxy S24",
                DevicePlatform.ANDROID, DeviceKeys.generate().publicKey(), DeviceSignatureVerifier.EC_P256, null));
    }

    private static long userId(UserDevice device) {
        return device.getUser().getId();
    }

    private static List<Object> runConcurrently(int threads, Callable<Object> call) throws Exception {
        List<Callable<Object>> calls = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            calls.add(call);
        }
        return runAll(threads, calls);
    }

    private static List<Object> runConcurrently(int threads, List<String> inputs,
            java.util.function.Function<String, Object> call) throws Exception {
        List<Callable<Object>> calls = new ArrayList<>();
        for (String input : inputs) {
            calls.add(() -> call.apply(input));
        }
        return runAll(threads, calls);
    }

    private static List<Object> runAll(int threads, List<Callable<Object>> calls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> call : calls) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static String sha256Hex(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
    }

    private static String notFoundBody(ResultActions fetched) throws Exception {
        return fetched.andExpect(status().isNotFound()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String read(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path).toString();
    }
}
