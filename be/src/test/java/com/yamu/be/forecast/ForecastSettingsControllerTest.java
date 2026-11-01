package com.yamu.be.forecast;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/** 잔고예보 설정 API를 인증 필터·실제 MySQL까지 묶어 확인한다. */
@Transactional
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-forecast-settings-api-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ForecastSettingsControllerTest extends MySqlTestSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    long owner;

    @BeforeEach
    void setUp() {
        owner = new OwnershipFixtures(jdbc).user();
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mockMvc.perform(get("/api/forecasts/settings"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(patch("/api/forecasts/settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"alertThreshold\": 1 }"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 조회는_명세_이름의_기본값이고_수정하면_UTC_시각을_돌려준다() throws Exception {
        mockMvc.perform(get("/api/forecasts/settings").with(AuthUsers.asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.baseAccountId").value(nullValue()))
                .andExpect(jsonPath("$.data.horizon").value("NEXT_INCOME"))
                .andExpect(jsonPath("$.data.defaultDays").value(30))
                .andExpect(jsonPath("$.data.alertThreshold").value(100000))
                .andExpect(jsonPath("$.data.income").value(nullValue()))
                .andExpect(jsonPath("$.data.dailyLivingCostTarget").value(nullValue()))
                .andExpect(jsonPath("$.data.includeCandidates").value(true))
                .andExpect(jsonPath("$.data.conservativeLine").value(true))
                .andExpect(jsonPath("$.data.updatedAt").value(nullValue()));

        mockMvc.perform(patchAs(owner, "{ \"alertThreshold\": 0, \"conservativeLine\": false }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alertThreshold").value(0))
                .andExpect(jsonPath("$.data.conservativeLine").value(false))
                .andExpect(jsonPath("$.data.updatedAt").value(endsWith("Z")));
    }

    @Test
    void 틀린_필드는_한_번에_필드별_사유와_400이다() throws Exception {
        mockMvc.perform(patchAs(owner, """
                        { "horizon": "WEEK", "defaultDays": 10, "alertThreshold": -1, "dailyLivingCostTarget": 1.5,
                          "includeCandidates": null, "conservativeLine": "yes", "incomeCashflowItemId": 0,
                          "baseAccountId": 3, "accountIds": [3] }"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.fieldErrors[*].field", containsInAnyOrder("horizon",
                        "defaultDays", "alertThreshold", "dailyLivingCostTarget", "includeCandidates",
                        "conservativeLine", "incomeCashflowItemId", "baseAccountId", "accountIds")));
    }

    @Test
    void 문자열_숫자와_int를_넘는_금액과_null_안전_잔액은_400이다() throws Exception {
        for (String body : new String[] {"{ \"alertThreshold\": \"100000\" }", "{ \"alertThreshold\": 2147483648 }",
                "{ \"alertThreshold\": null }", "{ \"dailyLivingCostTarget\": 0 }"}) {
            mockMvc.perform(patchAs(owner, body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        // int 최댓값은 받는다.
        mockMvc.perform(patchAs(owner, "{ \"alertThreshold\": 2147483647 }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alertThreshold").value(2147483647L));
    }

    @Test
    void 빈_본문과_JSON이_아닌_본문은_400이다() throws Exception {
        mockMvc.perform(patchAs(owner, "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.fieldErrors[*].field", hasItem("body")));
        mockMvc.perform(patchAs(owner, "[1, 2]"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patchAs(owner, "{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    private static MockHttpServletRequestBuilder patchAs(long userId, String body) {
        return patch("/api/forecasts/settings").with(AuthUsers.asUser(userId))
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
