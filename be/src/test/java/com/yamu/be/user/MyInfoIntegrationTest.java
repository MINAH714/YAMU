package com.yamu.be.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.LookupHasher;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.MySqlTestSupport;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 내 정보 조회(GET /api/users/me)를 실제 MySQL로 확인한다. 사용자·카드·계좌·기기는 행으로 바로 넣는다.
 */
@AutoConfigureMockMvc
class MyInfoIntegrationTest extends MySqlTestSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PersonalDataCipher cipher;
    @Autowired LookupHasher lookupHasher;

    long owner;
    long deviceId;
    long product;

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
        owner = user("01012345678", "ACTIVE");
        deviceId = device(owner, "Galaxy S24", true);
        product = product();
    }

    @Test
    void 카드와_기준_계좌가_있으면_초기_설정_완료이고_내_정보와_설정_요약이_보인다() throws Exception {
        long account = account(owner, "6547", "CONNECTED", true);
        long card = card(owner, "9410123456785678", "ACTIVE", account, true);

        me().andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value(owner))
                .andExpect(jsonPath("$.data.name").value("김야무"))
                .andExpect(jsonPath("$.data.maskedPhone").value("010-****-5678"))
                .andExpect(jsonPath("$.data.createdAt").value(Matchers.endsWith("Z")))
                .andExpect(jsonPath("$.data.onboarding.cardRegistered").value(true))
                .andExpect(jsonPath("$.data.onboarding.primaryAccountSet").value(true))
                .andExpect(jsonPath("$.data.onboarding.completed").value(true))
                .andExpect(jsonPath("$.data.securitySettings.pinSet").value(true))
                .andExpect(jsonPath("$.data.securitySettings.biometricEnabled").value(true))
                .andExpect(jsonPath("$.data.securitySettings.currentDevice.userDeviceId").value(deviceId))
                .andExpect(jsonPath("$.data.securitySettings.currentDevice.deviceName").value("Galaxy S24"))
                // 임시 기본값(저장 칸 없음)
                .andExpect(jsonPath("$.data.securitySettings.autoLockSeconds").value(60))
                .andExpect(jsonPath("$.data.securitySettings.setupGraceSeconds").value(300))
                .andExpect(jsonPath("$.data.securitySettings.barcodeAuthGraceSeconds").value(60))
                .andExpect(jsonPath("$.data.securitySettings.barcodeAuthMethod").value("BIOMETRIC_OR_PIN"))
                .andExpect(jsonPath("$.data.paymentSettings.autoSelect").value(true))
                .andExpect(jsonPath("$.data.paymentSettings.defaultUserCardId").value(card));
    }

    @Test
    void 추천에서_뺀_INACTIVE_카드만_있어도_등록된_카드로_본다() throws Exception {
        long account = account(owner, "6547", "CONNECTED", true);
        card(owner, "9410123456785678", "INACTIVE", account, false);

        me().andExpect(jsonPath("$.data.onboarding.cardRegistered").value(true))
                .andExpect(jsonPath("$.data.onboarding.completed").value(true))
                .andExpect(jsonPath("$.data.paymentSettings.defaultUserCardId").doesNotExist());
    }

    @Test
    void 계좌는_연결됐지만_기준_계좌가_없으면_초기_설정_미완료다() throws Exception {
        long account = account(owner, "6547", "CONNECTED", false);
        card(owner, "9410123456785678", "ACTIVE", account, false);

        me().andExpect(jsonPath("$.data.onboarding.cardRegistered").value(true))
                .andExpect(jsonPath("$.data.onboarding.primaryAccountSet").value(false))
                .andExpect(jsonPath("$.data.onboarding.completed").value(false));
    }

    @Test
    void 해제한_카드와_끊은_계좌는_세지_않는다() throws Exception {
        // 끊은 계좌는 기준 계좌가 될 수 없다(ck_account_primary_connected).
        long account = account(owner, "6547", "DISCONNECTED", false);
        card(owner, "9410123456785678", "UNREGISTERED", account, false);

        me().andExpect(jsonPath("$.data.onboarding.cardRegistered").value(false))
                .andExpect(jsonPath("$.data.onboarding.primaryAccountSet").value(false))
                .andExpect(jsonPath("$.data.onboarding.completed").value(false));
    }

    @Test
    void 다른_사람의_카드와_계좌는_내_초기_설정에_섞이지_않는다() throws Exception {
        long stranger = user("01099998888", "ACTIVE");
        long theirs = account(stranger, "8888", "CONNECTED", true);
        card(stranger, "9410000044445555", "ACTIVE", theirs, true);

        me().andExpect(jsonPath("$.data.userId").value(owner))
                .andExpect(jsonPath("$.data.onboarding.cardRegistered").value(false))
                .andExpect(jsonPath("$.data.onboarding.primaryAccountSet").value(false))
                .andExpect(jsonPath("$.data.paymentSettings.defaultUserCardId").doesNotExist());
    }

    @Test
    void 토큰의_기기가_남의_기기거나_해제됐으면_지금_기기는_비우고_생체인증은_꺼짐으로_본다() throws Exception {
        long stranger = user("01099998888", "ACTIVE");
        long theirDevice = device(stranger, "남의 폰", true);
        mockMvc.perform(MockMvcRequestBuilders.get("/api/users/me").with(AuthUsers.asUser(owner, theirDevice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.securitySettings.currentDevice").doesNotExist())
                .andExpect(jsonPath("$.data.securitySettings.biometricEnabled").value(false));

        jdbc.update("UPDATE user_device SET status = 'REVOKED' WHERE user_device_id = ?", deviceId);
        me().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.securitySettings.currentDevice").doesNotExist());
    }

    @Test
    void 토큰_검사_뒤_탈퇴한_사용자는_401이다() throws Exception {
        jdbc.update("UPDATE app_user SET status = 'WITHDRAWN', deleted_at = ? WHERE user_id = ?", now(), owner);

        me().andExpect(errorCode(401, "UNAUTHORIZED"));
    }

    @Test
    void 로그인하지_않으면_401이다() throws Exception {
        ResultActions result = mockMvc.perform(MockMvcRequestBuilders.get("/api/users/me"));

        assertThat(result.andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    // ───────── 보안 설정 수정 ─────────

    @Test
    void 보안_설정을_바꾸면_보낸_칸만_저장되고_내_정보에도_나온다() throws Exception {
        patchSecurity("{\"autoLockSeconds\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.autoLockSeconds").value(0))
                .andExpect(jsonPath("$.data.barcodeAuthMethod").value("BIOMETRIC_OR_PIN"))
                .andExpect(jsonPath("$.data.setupGraceSeconds").value(300))
                .andExpect(jsonPath("$.data.barcodeAuthGraceSeconds").value(60))
                .andExpect(jsonPath("$.data.updatedAt").value(Matchers.endsWith("Z")));
        patchSecurity("{\"barcodeAuthMethod\":\"PIN_ONLY\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.autoLockSeconds").value(0))
                .andExpect(jsonPath("$.data.barcodeAuthMethod").value("PIN_ONLY"));

        me().andExpect(jsonPath("$.data.securitySettings.autoLockSeconds").value(0))
                .andExpect(jsonPath("$.data.securitySettings.barcodeAuthMethod").value("PIN_ONLY"));
        assertThat(jdbc.queryForMap("SELECT auto_lock_seconds, barcode_auth_method FROM app_user WHERE user_id = ?", owner))
                .containsEntry("auto_lock_seconds", 0).containsEntry("barcode_auth_method", "PIN_ONLY");

        // 빈 요청은 아무것도 바꾸지 않는다
        patchSecurity("{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.autoLockSeconds").value(0))
                .andExpect(jsonPath("$.data.barcodeAuthMethod").value("PIN_ONLY"));
    }

    @Test
    void 허용_값_밖이면_400이고_아무것도_바뀌지_않으며_남의_설정에는_영향이_없다() throws Exception {
        long stranger = user("01099998888", "ACTIVE");

        patchSecurity("{\"autoLockSeconds\":30}").andExpect(errorCode(400, "INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("autoLockSeconds"));
        patchSecurity("{\"autoLockSeconds\":300,\"barcodeAuthMethod\":\"FACE\"}")
                .andExpect(errorCode(400, "INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("barcodeAuthMethod"));
        assertThat(jdbc.queryForObject("SELECT auto_lock_seconds FROM app_user WHERE user_id = ?", Integer.class, owner))
                .isEqualTo(60);

        patchSecurity("{\"autoLockSeconds\":300}").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT auto_lock_seconds FROM app_user WHERE user_id = ?", Integer.class,
                stranger)).isEqualTo(60);
        mockMvc.perform(MockMvcRequestBuilders.patch("/api/users/me/security-settings")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"autoLockSeconds\":0}"))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions patchSecurity(String json) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.patch("/api/users/me/security-settings")
                .with(AuthUsers.asUser(owner, deviceId))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json));
    }

    // ───────── 준비 ─────────

    private ResultActions me() throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get("/api/users/me").with(AuthUsers.asUser(owner, deviceId)));
    }

    private long user(String phone, String status) {
        return insert("app_user", "user_id", new HashMap<>(Map.of(
                "name", "김야무",
                "birth_date", LocalDate.of(1999, 3, 15),
                "phone_enc", cipher.encrypt(phone, CipherField.APP_USER_PHONE),
                "phone_hash", lookupHasher.phone(phone),
                "identity_verified_at", now(),
                "pin_hash", "$2a$10$test",
                "pin_updated_at", now(),
                "status", status,
                "created_at", now(),
                "updated_at", now())));
    }

    private long device(long userId, String name, boolean biometric) {
        Map<String, Object> values = new HashMap<>(Map.of("user_id", userId,
                "device_uuid", UUID.randomUUID().toString(), "device_name", name, "platform", "ANDROID",
                "public_key", "test-key", "key_algorithm", "SHA256withECDSA", "biometric_enabled", biometric,
                "created_at", now()));
        values.put("updated_at", now());
        if (biometric) {
            values.put("biometric_public_key", "test-biometric-key"); // ck_device_biometric: 생체를 켜면 생체 키 필수
        }
        return insert("user_device", "user_device_id", values);
    }

    private long account(long userId, String last4, String status, boolean primary) {
        String accountNo = "088154114975" + last4;
        Map<String, Object> values = new HashMap<>(Map.of("user_id", userId, "bank_code", "088",
                "account_no_enc", cipher.encrypt(accountNo, CipherField.LINKED_ACCOUNT_NO),
                "account_no_hash", lookupHasher.account("088", accountNo), "account_last4", last4,
                "status", status, "is_primary", primary,
                "connected_at", now(), "created_at", now(), "updated_at", now()));
        if ("DISCONNECTED".equals(status)) {
            values.put("disconnected_at", now());
        }
        return insert("linked_account", "linked_account_id", values);
    }

    private long product() {
        return insert("card_product", "card_product_id", new HashMap<>(Map.of("issuer_code", "SHINHAN",
                "product_name", "신한 카페 체크", "card_type", "CHECK", "created_at", now(), "updated_at", now())));
    }

    private long card(long userId, String cardNo, String status, long linkedAccount, boolean isDefault) {
        Map<String, Object> values = new HashMap<>(Map.of("user_id", userId, "card_product_id", product,
                "card_no_enc", cipher.encrypt(cardNo, CipherField.USER_CARD_NO),
                "card_no_hash", lookupHasher.card(cardNo), "card_last4", cardNo.substring(12),
                "status", status, "linked_account_id", linkedAccount, "is_default", isDefault,
                "registered_at", now()));
        values.put("created_at", now());
        values.put("updated_at", now());
        return insert("user_card", "user_card_id", values);
    }

    private long insert(String table, String keyColumn, Map<String, Object> values) {
        return new SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns(keyColumn)
                .usingColumns(values.keySet().toArray(String[]::new))
                .executeAndReturnKey(values).longValue();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).withNano(0);
    }

    private static ResultMatcher errorCode(int status, String code) {
        return result -> {
            assertThat(result.getResponse().getStatus()).as("HTTP status").isEqualTo(status);
            assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.error.code")).isEqualTo(code);
        };
    }
}
