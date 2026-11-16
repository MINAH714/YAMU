package com.yamu.be.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.LookupHasher;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.crypto.PinHasher;
import com.yamu.be.global.finapi.FinApiClient;
import com.yamu.be.support.Concurrently;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import com.yamu.be.user.domain.AppUser;
import com.yamu.be.user.domain.AppUserStatus;
import com.yamu.be.user.domain.AppUserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * 휴대폰 본인인증(연출) → 가입 진행 토큰을 실제 MySQL·Redis로 확인한다.
 * 시연 전용이라 시드가 넣어 둔 사람(PENDING + SSAFY 회원)만 가입할 수 있고, SSAFY 금융망은 부르지 않는다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-signup-integration-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SignupIntegrationTest extends MySqlTestSupport {

    private static final String PHONE = "01012345678";
    private static final String BIRTH = "1999-03-15";
    private static final String USER_KEY = "2695628f-11e1-418e-b533-9ae19e0650ec";
    private static final AtomicInteger MEMBER_SEQ = new AtomicInteger();

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
    }

    // ───────── 시드 사용자 ─────────

    @Test
    void 시드에_있는_가입_중_사용자는_SIGNUP_토큰을_받고_본인인증_시각이_기록된다() throws Exception {
        AppUser seeded = seed(PHONE);

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
                .andExpect(jsonPath("$.data.nextStep").value("ACCOUNT_LINK"))
                .andReturn();
        String token = read(confirmed, "$.data.signupToken");
        assertThat(token).startsWith("sgt_");
        assertThat(redis.hasKey("auth:phone-verify:" + verificationId)).isFalse();
        assertThat(redis.getExpire("signup:" + sha256Hex(token))).isBetween(1L, 1800L);
        assertThat(redis.opsForHash().get("signup:" + sha256Hex(token), "userId"))
                .isEqualTo(String.valueOf(seeded.getId()));
        assertThat(confirmed.getResponse().getContentAsString()).doesNotContain(USER_KEY);

        assertThat(userRepository.findById(seeded.getId()).orElseThrow().getIdentityVerifiedAt()).isNotNull();
        verifyNoInteractions(finApiClient);
    }

    @Test
    void 계좌를_연결해_두고_멈춘_시드_사용자도_새_토큰으로는_1원_인증부터_다시_한다() throws Exception {
        // 본인인증이 연출이라, 남이 이어받아 PIN·기기를 등록하지 못하게 이 토큰으로 1원 인증을 다시 받는다(SignupProgress).
        AppUser seeded = seed(PHONE);
        new OwnershipFixtures(jdbc).account(seeded.getId());

        confirmFor(PHONE).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("SIGNUP"))
                .andExpect(jsonPath("$.data.nextStep").value("ACCOUNT_LINK"));
    }

    @Test
    void 이미_가입한_사람은_DEVICE_RECOVERY_모드다() throws Exception {
        AppUser active = seed(PHONE);
        active.markIdentityVerified(LocalDateTime.now(ZoneOffset.UTC));
        active.changePin(pinHasher.hash("482913"), LocalDateTime.now(ZoneOffset.UTC));
        active.activate();
        userRepository.save(active);

        confirmFor(PHONE).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("DEVICE_RECOVERY"))
                .andExpect(jsonPath("$.data.nextStep").value("ACCOUNT_LINK"));
    }

    // ───────── 시드에 없는 사람 ─────────

    @Test
    void 시드에_없는_사람은_인증번호_요청에서_바로_404_DEMO_USER_NOT_FOUND이고_인증번호가_없다() throws Exception {
        // 인증번호를 응답으로 주는 동안은 요청 단계에서 대조한다(S15P31E103-532). 확인을 불러도 같은 걸 알 수 있어서다.
        requestVerification("김야무", BIRTH, "SKT", PHONE).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DEMO_USER_NOT_FOUND"))
                .andExpect(jsonPath("$.data").doesNotExist());

        assertThat(redis.keys("auth:phone-verify:*")).isEmpty();   // 인증번호도, 재요청 간격도 없다
        assertThat(redis.keys("signup:*")).isEmpty();
        assertThat(userRepository.count()).isZero();
        verifyNoInteractions(finApiClient);
    }

    @Test
    void 이름을_고쳐_바로_다시_요청하면_재요청_간격에_걸리지_않고_인증번호를_받는다() throws Exception {
        seed(PHONE);
        requestVerification("김야모", BIRTH, "SKT", PHONE).andExpect(status().isNotFound());

        requestVerification("김야무", BIRTH, "SKT", PHONE).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.demoCode").exists());
    }

    @Test
    void 요청과_확인_사이에_시드에서_빠지면_확인에서_404다() throws Exception {
        AppUser seeded = seed(PHONE);
        MvcResult requested = requestVerification("김야무", BIRTH, "SKT", PHONE).andExpect(status().isOk()).andReturn();
        jdbc.update("DELETE FROM app_user WHERE user_id = ?", seeded.getId());

        confirm(read(requested, "$.data.verificationId"), read(requested, "$.data.demoCode"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DEMO_USER_NOT_FOUND"));
        assertThat(redis.keys("signup:*")).isEmpty();
    }

    @Test
    void SSAFY_회원이_없는_시드_행은_가입을_시작하지_못하고_404_DEMO_USER_NOT_FOUND다() throws Exception {
        userRepository.save(AppUser.pending("김야무", LocalDate.parse(BIRTH),
                cipher.encrypt(PHONE, CipherField.APP_USER_PHONE), lookupHasher.phone(PHONE), null));

        requestVerification("김야무", BIRTH, "SKT", PHONE).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DEMO_USER_NOT_FOUND"));
        assertThat(redis.keys("auth:phone-verify:*")).isEmpty();
    }

    @Test
    void 회원가입_API는_없다() throws Exception {
        seed(PHONE);
        String token = read(confirmFor(PHONE).andReturn(), "$.data.signupToken");

        // 공개 경로에서 빠져 JWT 검사로 401(가입 진행 토큰은 JWT가 아님)
        mockMvc.perform(post("/api/auth/signup").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(finApiClient);
    }

    // ───────── 본인 확인 ─────────

    @Test
    void 번호와_생년월일이_같아도_이름이_다르면_시드에_없는_사람과_똑같은_404다() throws Exception {
        AppUser seeded = seed(PHONE);
        withdraw(seed("01055556666"));
        MvcResult mismatched = requestVerification("홍길동", BIRTH, "SKT", PHONE)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DEMO_USER_NOT_FOUND"))
                .andReturn();
        MvcResult notSeeded = requestVerification("김야무", BIRTH, "SKT", "01099998888")
                .andExpect(status().isNotFound()).andReturn();
        MvcResult withdrawnMismatched = requestVerification("홍길동", BIRTH, "SKT", "01055556666")
                .andExpect(status().isNotFound()).andReturn();

        // 응답 본문이 글자 하나까지 같아야 번호·생년월일만으로 가입·탈퇴 여부를 알 수 없다.
        String body = mismatched.getResponse().getContentAsString();
        assertThat(body).isEqualTo(notSeeded.getResponse().getContentAsString())
                .isEqualTo(withdrawnMismatched.getResponse().getContentAsString());
        assertThat(body).doesNotContain("김야무").doesNotContain(String.valueOf(seeded.getId())).doesNotContain("sgt_");
        assertThat(redis.keys("auth:phone-verify:*")).isEmpty();
        assertThat(redis.keys("signup:*")).isEmpty();
        assertThat(userRepository.findById(seeded.getId()).orElseThrow().getIdentityVerifiedAt()).isNull();
    }

    @Test
    void 이름_앞뒤_공백은_빼고_비교한다() throws Exception {
        seed(PHONE);
        MvcResult requested = requestVerification(" 김야무 ", BIRTH, "SKT", PHONE).andReturn();

        confirm(read(requested, "$.data.verificationId"), read(requested, "$.data.demoCode"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.mode").value("SIGNUP"));
    }

    @Test
    void 탈퇴한_사람은_본인인증_확인에서_새_사용자로_재가입한다() throws Exception {
        AppUser old = seed(PHONE);
        withdraw(old);

        confirmFor(PHONE).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("SIGNUP"))
                .andExpect(jsonPath("$.data.nextStep").value("ACCOUNT_LINK"));
        AppUser fresh = userRepository.findByPhoneHashAndBirthDate(lookupHasher.phone(PHONE), LocalDate.parse(BIRTH))
                .orElseThrow();
        assertThat(fresh.getId()).isNotEqualTo(old.getId());
        assertThat(fresh.getStatus()).isEqualTo(AppUserStatus.PENDING);
    }

    @Test
    void 탈퇴자라도_이름이_다르면_탈퇴_여부를_알려주지_않고_404다() throws Exception {
        AppUser old = seed(PHONE);
        withdraw(old);

        requestVerification("홍길동", BIRTH, "SKT", PHONE)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DEMO_USER_NOT_FOUND"));
        assertThat(redis.keys("auth:phone-verify:*")).isEmpty();
        assertThat(redis.keys("signup:*")).isEmpty();
        // 요청 단계는 읽기만 한다. 재가입(새 사용자 만들기)은 확인 단계에서 이름까지 맞을 때만
        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(userRepository.findById(old.getId()).orElseThrow().getPhoneHash()).isNotNull();
    }

    // ───────── 인증번호 ─────────

    @Test
    void 인증번호를_틀리면_남은_횟수를_주고_5번_틀리면_요청부터_다시_해야_한다() throws Exception {
        seed(PHONE);
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
        seed(PHONE);
        MvcResult requested = requestVerification("김야무", BIRTH, "LGU", PHONE).andReturn();
        String verificationId = read(requested, "$.data.verificationId");
        String code = read(requested, "$.data.demoCode");
        confirm(verificationId, code).andExpect(status().isOk());

        confirm(verificationId, code).andExpect(jsonPath("$.error.code").value("VERIFICATION_EXPIRED"));
        confirm("pv_unknown", "123456").andExpect(jsonPath("$.error.code").value("VERIFICATION_EXPIRED"));
    }

    @Test
    void 같은_인증번호로_동시에_확인하면_하나만_토큰을_받는다() throws Exception {
        seed(PHONE);
        MvcResult requested = requestVerification("김야무", BIRTH, "SKT", PHONE).andReturn();
        String verificationId = read(requested, "$.data.verificationId");
        String code = read(requested, "$.data.demoCode");

        List<MvcResult> results = Concurrently.run(5, () -> confirm(verificationId, code).andReturn());

        assertThat(results).extracting(r -> r.getResponse().getStatus())
                .containsOnly(200, 400).filteredOn(s -> s == 200).hasSize(1);
        assertThat(results).filteredOn(r -> r.getResponse().getStatus() == 400)
                .allSatisfy(r -> assertThat(read(r, "$.error.code")).isEqualTo("VERIFICATION_EXPIRED"));
        // 토큰 하나와 사용자별 토큰 목록(signup:user:{id}) 하나.
        assertThat(redis.keys("signup:*").stream().filter(key -> !key.startsWith("signup:user:"))).hasSize(1);
    }

    @Test
    void 같은_번호로_1분_안에_다시_요청하면_429_VERIFICATION_TOO_FREQUENT다() throws Exception {
        seed(PHONE);
        seed("01099998888");
        requestVerification("김야무", BIRTH, "SKT", PHONE).andExpect(status().isOk());

        requestVerification("김야무", BIRTH, "SKT", PHONE)
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

    @Test
    void 본인인증_API는_IP별로_세어_한도를_넘으면_429_TOO_MANY_REQUESTS다() throws Exception {
        // 두 API를 합쳐 1분에 30번까지(application.yml per-ip). 같은 번호 재요청은 429 VERIFICATION_TOO_FREQUENT로 세어진다.
        for (int i = 0; i < 30; i++) {
            requestVerification("김야무", BIRTH, "SKT", PHONE);
        }

        requestVerification("김야무", BIRTH, "SKT", "01099998888").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.error.details.retryAfterSeconds").isNumber());
        confirm("pv_AAAAAAAAAAAAAAAAAAAAAA", "123456").andExpect(jsonPath("$.error.code").value("TOO_MANY_REQUESTS"));
    }

    @Test
    void 형식이_다른_인증_ID는_Redis를_보지_않고_VERIFICATION_EXPIRED다() throws Exception {
        seed(PHONE);
        requestVerification("김야무", BIRTH, "SKT", PHONE).andExpect(status().isOk());

        // 재요청 간격 키(String)를 Hash 명령으로 건드리면 500이 났다.
        String recentKeySuffix = "recent:" + java.util.HexFormat.of().formatHex(lookupHasher.phone(PHONE));
        confirm(recentKeySuffix, "123456").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VERIFICATION_EXPIRED"));
        confirm("pv_short", "123456").andExpect(jsonPath("$.error.code").value("VERIFICATION_EXPIRED"));
    }

    @Test
    void 공백을_뺀_이름이_한_글자거나_번호에_하이픈이_있으면_400이다() throws Exception {
        requestVerification(" 김 ", BIRTH, "SKT", PHONE).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        // 명세: 숫자만 받는다. 정규화(+82 등)는 서비스가 하지만 하이픈·공백은 앞에서 거절한다.
        requestVerification("김야무", BIRTH, "SKT", "010-1234-5678").andExpect(status().isBadRequest());
        requestVerification("김야무", BIRTH, "SKT", "010 12345678").andExpect(status().isBadRequest());
    }

    @Test
    void 본인인증_정보의_toString에는_이름_가운데와_생년월일_번호가_없다() {
        VerifiedIdentity identity = new VerifiedIdentity("김야무", LocalDate.parse(BIRTH), new byte[] {1},
                lookupHasher.phone(PHONE));

        assertThat(identity.toString()).contains("김*무").doesNotContain("야").doesNotContain(BIRTH)
                .doesNotContain("1999");
    }

    // ───────── 도우미 ─────────

    /** 시드가 넣는 모양: 본인인증 전 PENDING + SSAFY 회원(암호화한 userKey). 개인정보는 앱과 같은 암호화·해시로 넣는다. */
    private AppUser seed(String phone) {
        AppUser user = AppUser.pending("김야무", LocalDate.parse(BIRTH),
                cipher.encrypt(phone, CipherField.APP_USER_PHONE), lookupHasher.phone(phone), null);
        user.linkFinMember("seed" + MEMBER_SEQ.incrementAndGet() + "@yamu.app",
                cipher.encrypt(USER_KEY, CipherField.APP_USER_SSAFY_USER_KEY));
        return userRepository.save(user);
    }

    /** 탈퇴 API처럼 이름은 지우고 이름 해시만 남긴다. */
    private void withdraw(AppUser user) {
        jdbc.update("UPDATE app_user SET status = 'WITHDRAWN', deleted_at = UTC_TIMESTAMP(), name = NULL, "
                + "withdrawn_name_hash = ? WHERE user_id = ?", lookupHasher.name(user.getName()), user.getId());
    }

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

    private static String sha256Hex(String token) throws Exception {
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(hash);
    }

    private static String read(MvcResult result, String path) {
        try {
            return JsonPath.read(result.getResponse().getContentAsString(), path).toString();
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
