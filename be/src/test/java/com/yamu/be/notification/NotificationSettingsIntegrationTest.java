package com.yamu.be.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/** 알림 설정 조회·수정(Task 9.3.1)을 인증 필터·실제 MySQL까지 묶어 확인한다. */
@Transactional
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-notification-settings-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationSettingsIntegrationTest extends MySqlTestSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    long owner;

    @BeforeEach
    void setUp() {
        owner = new OwnershipFixtures(jdbc).user();
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mockMvc.perform(get("/api/notifications/settings")).andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/notifications/settings").contentType(MediaType.APPLICATION_JSON)
                .content("{ \"payment\": { \"result\": false } }")).andExpect(status().isUnauthorized());
    }

    @Test
    void 저장한_적이_없으면_모두_켜짐_08시30분이고_안전_잔액은_잔고예보_기본값이다() throws Exception {
        getSettings()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.performance.enabled").value(true))
                .andExpect(jsonPath("$.data.performance.time").value("08:30"))
                .andExpect(jsonPath("$.data.balance.enabled").value(true))
                .andExpect(jsonPath("$.data.balance.alertThreshold").value(100_000))
                .andExpect(jsonPath("$.data.balance.time").value("08:30"))
                .andExpect(jsonPath("$.data.payment.result").value(true))
                .andExpect(jsonPath("$.data.securityEnabled").value(true));
    }

    @Test
    void 저장된_false와_잔고예보_안전_잔액을_그대로_보인다() throws Exception {
        // 시드와 같은 세 키 모양 + 잔고예보 설정의 안전 잔액
        jdbc.update("""
                UPDATE app_user SET notification_settings = '{"performanceEnabled":true,"balanceRiskEnabled":true,"transactionEnabled":false}',
                       forecast_settings = '{"alertThreshold":50000}' WHERE user_id = ?
                """, owner);

        getSettings()
                .andExpect(jsonPath("$.data.payment.result").value(false))
                .andExpect(jsonPath("$.data.performance.enabled").value(true))
                .andExpect(jsonPath("$.data.balance.alertThreshold").value(50_000))
                .andExpect(jsonPath("$.data.balance.time").value("08:30"));
    }

    @Test
    void JSON_null도_기본값으로_본다() throws Exception {
        jdbc.update("UPDATE app_user SET notification_settings = CAST('null' AS JSON) WHERE user_id = ?", owner);

        getSettings().andExpect(status().isOk()).andExpect(jsonPath("$.data.payment.result").value(true));
        patchSettings("{ \"payment\": { \"result\": false } }").andExpect(status().isOk());
        assertThat(stored()).isEqualTo("{\"transactionEnabled\": false}");
    }

    @Test
    void 보낸_항목만_바꾸고_나머지는_둔다() throws Exception {
        patchSettings("{ \"performance\": { \"enabled\": false } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.performance.enabled").value(false))
                .andExpect(jsonPath("$.data.balance.enabled").value(true))
                .andExpect(jsonPath("$.data.payment.result").value(true));

        // 앱이 시각을 바꿀 때 보내는 모양
        patchSettings("{ \"performance\": { \"time\": \"21:00\" }, \"balance\": { \"time\": \"21:00\" } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.performance.enabled").value(false))
                .andExpect(jsonPath("$.data.performance.time").value("21:00"))
                .andExpect(jsonPath("$.data.balance.time").value("21:00"));

        patchSettings("{ \"payment\": { \"result\": false } }").andExpect(status().isOk());
        getSettings()
                .andExpect(jsonPath("$.data.performance.enabled").value(false))
                .andExpect(jsonPath("$.data.balance.enabled").value(true))
                .andExpect(jsonPath("$.data.payment.result").value(false))
                .andExpect(jsonPath("$.data.performance.time").value("21:00"));

        // boolean은 JSON 숫자(1/0)가 아니라 true/false로 저장된다
        assertThat(jdbc.queryForObject(
                "SELECT JSON_TYPE(notification_settings -> '$.transactionEnabled') FROM app_user WHERE user_id = ?",
                String.class, owner)).isEqualTo("BOOLEAN");
        assertThat(jdbc.queryForObject(
                "SELECT notification_settings ->> '$.balanceTime' FROM app_user WHERE user_id = ?",
                String.class, owner)).isEqualTo("21:00");
    }

    @Test
    void 틀린_본문은_400이고_저장값을_바꾸지_않는다() throws Exception {
        patchSettings("{ \"performance\": { \"enabled\": false }, \"balance\": { \"alertThreshold\": 1 } }")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("balance.alertThreshold"));
        mockMvc.perform(patch("/api/notifications/settings").with(AuthUsers.asUser(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("body"));

        assertThat(stored()).isNull();
    }

    private ResultActions getSettings() throws Exception {
        return mockMvc.perform(get("/api/notifications/settings").with(AuthUsers.asUser(owner)));
    }

    private ResultActions patchSettings(String body) throws Exception {
        return mockMvc.perform(patch("/api/notifications/settings").with(AuthUsers.asUser(owner))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String stored() {
        return jdbc.queryForObject("SELECT CAST(notification_settings AS CHAR) FROM app_user WHERE user_id = ?",
                String.class, owner);
    }
}
