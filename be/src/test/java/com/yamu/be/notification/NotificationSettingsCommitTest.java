package com.yamu.be.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.Concurrently;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 커밋이 실제로 일어나야 보이는 동작(동시 수정, 실패 시 되돌림)을 확인한다. 그래서 테스트 트랜잭션을 쓰지 않는다.
 * 행은 테스트마다 새 사용자로 만들고, 클래스가 끝나면 데이터베이스째 지운다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-notification-settings-commit-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationSettingsCommitTest extends MySqlTestSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    long owner;

    @BeforeEach
    void setUp() {
        owner = new OwnershipFixtures(jdbc).user();
    }

    @Test
    void 다른_항목을_동시에_바꿔도_모두_남는다() throws Exception {
        List<String> bodies = List.of(
                "{ \"performance\": { \"enabled\": false } }",
                "{ \"balance\": { \"enabled\": false } }",
                "{ \"payment\": { \"result\": false } }",
                "{ \"performance\": { \"time\": \"07:00\" } }",
                "{ \"balance\": { \"time\": \"22:10\" } }");
        for (int round = 0; round < 3; round++) {
            jdbc.update("UPDATE app_user SET notification_settings = NULL WHERE user_id = ?", owner);
            List<Callable<Integer>> tasks = bodies.stream().<Callable<Integer>>map(body -> () -> mockMvc
                    .perform(patch("/api/notifications/settings").with(AuthUsers.asUser(owner))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse().getStatus()).toList();

            assertThat(Concurrently.run(tasks)).containsOnly(200);
            mockMvc.perform(get("/api/notifications/settings").with(AuthUsers.asUser(owner)))
                    .andExpect(jsonPath("$.data.performance.enabled").value(false))
                    .andExpect(jsonPath("$.data.balance.enabled").value(false))
                    .andExpect(jsonPath("$.data.payment.result").value(false))
                    .andExpect(jsonPath("$.data.performance.time").value("07:00"))
                    .andExpect(jsonPath("$.data.balance.time").value("22:10"));
        }
    }

    @Test
    void 저장값이_깨졌으면_기본값으로_덮지_않고_실패하며_수정을_되돌린다() throws Exception {
        jdbc.update("UPDATE app_user SET notification_settings = '{\"performanceEnabled\":\"yes\"}' WHERE user_id = ?",
                owner);

        mockMvc.perform(get("/api/notifications/settings").with(AuthUsers.asUser(owner)))
                .andExpect(status().isInternalServerError());
        mockMvc.perform(patch("/api/notifications/settings").with(AuthUsers.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"payment\": { \"result\": false } }"))
                .andExpect(status().isInternalServerError());

        assertThat(jdbc.queryForObject("SELECT CAST(notification_settings AS CHAR) FROM app_user WHERE user_id = ?",
                String.class, owner)).isEqualTo("{\"performanceEnabled\": \"yes\"}");
    }
}
