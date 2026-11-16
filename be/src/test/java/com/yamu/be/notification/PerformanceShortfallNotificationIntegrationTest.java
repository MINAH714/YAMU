package com.yamu.be.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

/**
 * 실적 부족 알림(Task 9.3.3)을 실제 MySQL·운영 API·알림 목록 API까지 묶어 확인한다. 서버 시계는 2026-11-25 10:00(한국)으로 고정한다:
 * 30일인 달이라 5일 남았고, 추세 = 실적 × 30 ÷ 25다. 상품은 기준 30만·50만이다.
 *
 * <p>운영 API로 작업을 돌리면 {@code JobLocks}가 Redis 잠금을 잡으므로 Redis 컨테이너도 띄운다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-performance-shortfall-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PerformanceShortfallNotificationIntegrationTest extends MySqlTestSupport {

    static final String RUN = "/api/admin/jobs/performance-shortfall-notify/run";
    static final String ADMIN_KEY = "test-admin-key";
    static final YearMonth NOVEMBER = YearMonth.of(2026, 11);

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock shortfallClock() {
            return Clock.fixed(Instant.parse("2026-11-25T01:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    OwnershipFixtures fixtures;
    long tiered;

    @BeforeEach
    void setUp() {
        fixtures = new OwnershipFixtures(jdbc);
        tiered = fixtures.product();
        benefit(tiered, "생활 10% 할인", 300_000);
        benefit(tiered, "카페 20% 할인", 500_000);
    }

    @Test
    void 첫_구간을_못_채울_카드에만_한_건씩_쌓고_다시_돌려도_한_건이며_알림_목록에_보인다() throws Exception {
        long owner = activeUser();
        long shortCard = card(owner, "ACTIVE", "생활카드", 100_000);   // 추세 12만 < 30만
        card(owner, "ACTIVE", null, 350_000);                          // 첫 구간 넘음(다음 구간 기준 AT_RISK)
        card(owner, "ACTIVE", null, 280_000);                          // 추세 33.6만 ≥ 30만
        card(owner, "INACTIVE", null, 100_000);                        // 추천에서 뺀 카드

        run(owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobName").value("performance-shortfall-notify"))
                .andExpect(jsonPath("$.data.summary.targets").value(1))
                .andExpect(jsonPath("$.data.results[0].refType").value("USER_CARD"))
                .andExpect(jsonPath("$.data.results[0].refId").value(shortCard))
                .andExpect(jsonPath("$.data.results[0].result").value("CREATED"))
                .andExpect(jsonPath("$.data.results[0].amount").value(200_000));
        run(owner).andExpect(jsonPath("$.data.results[0].result").value("DUPLICATE"));

        List<Map<String, Object>> rows = notifications(owner);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("notification_type")).isEqualTo("PERFORMANCE_SHORTFALL");
            assertThat(row.get("deduplication_key")).isEqualTo("PERFORMANCE_SHORTFALL:" + shortCard + ":2026-11");
            assertThat(row.get("target_type")).isEqualTo("USER_CARD");
            assertThat(((Number) row.get("target_id")).longValue()).isEqualTo(shortCard);
        });
        mockMvc.perform(get("/api/notifications").with(AuthUsers.asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value(1))
                .andExpect(jsonPath("$.data.items[0].type").value("PERFORMANCE_SHORTFALL"))
                .andExpect(jsonPath("$.data.items[0].title").value("실적 부족 예상"))
                .andExpect(jsonPath("$.data.items[0].body").value("생활카드 이번 달 실적이 30만원 이상 기준까지 200,000원 "
                        + "남았어요. 이번 달이 5일 남았고 지금 추세면 기준에 못 미칠 수 있어요"))
                .andExpect(jsonPath("$.data.items[0].refType").value("USER_CARD"))
                .andExpect(jsonPath("$.data.items[0].refId").value(shortCard));
    }

    @Test
    void 사용자마다_자기_카드로만_쌓는다() throws Exception {
        long owner = activeUser();
        long other = activeUser();
        long mine = card(owner, "ACTIVE", null, 0);
        long theirs = card(other, "ACTIVE", null, 50_000);

        run(null).andExpect(status().isOk());

        assertThat(notifications(owner)).extracting(row -> ((Number) row.get("target_id")).longValue())
                .containsExactly(mine);
        assertThat(notifications(other)).extracting(row -> ((Number) row.get("target_id")).longValue())
                .containsExactly(theirs);
    }

    @Test
    void 실적_알림을_끈_사용자는_건너뛰고_쌓지_않는다() throws Exception {
        long owner = activeUser();
        card(owner, "ACTIVE", null, 0);
        jdbc.update("UPDATE app_user SET notification_settings = '{\"performanceEnabled\": false}' WHERE user_id = ?",
                owner);

        run(owner)
                .andExpect(jsonPath("$.data.results[0].refType").value("APP_USER"))
                .andExpect(jsonPath("$.data.results[0].result").value("SKIPPED"));

        assertThat(notifications(owner)).isEmpty();
    }

    @Test
    void 실적_판단에서_알림을_멈춘_카드는_건너뛰고_되돌리면_쌓는다() throws Exception {
        long owner = activeUser();
        long skipped = card(owner, "ACTIVE", null, 100_000L);
        long decision = decide(owner, skipped, "SKIP", true);

        run(owner)
                .andExpect(jsonPath("$.data.results[0].refType").value("USER_CARD"))
                .andExpect(jsonPath("$.data.results[0].refId").value(skipped))
                .andExpect(jsonPath("$.data.results[0].result").value("SKIPPED"));
        assertThat(notifications(owner)).isEmpty();

        // "다시 판단하기": 판단을 비우면 알림 멈춤도 풀린다
        jdbc.update("UPDATE performance_decision SET decision = NULL, notifications_muted = FALSE "
                + "WHERE performance_decision_id = ?", decision);
        run(owner).andExpect(jsonPath("$.data.results[0].result").value("CREATED"));
        assertThat(notifications(owner)).hasSize(1);
    }

    @Test
    void 가입을_마치지_않은_사용자는_대상이_아니다() throws Exception {
        long pending = fixtures.user();
        card(pending, "ACTIVE", null, 0);

        run(pending).andExpect(jsonPath("$.data.summary.targets").value(0));

        assertThat(notifications(pending)).isEmpty();
    }

    // ── 픽스처 ──

    private ResultActions run(Long userId) throws Exception {
        String body = userId == null ? "{}" : "{\"userId\":" + userId + "}";
        return mockMvc.perform(post(RUN).header("X-Admin-Key", ADMIN_KEY).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private List<Map<String, Object>> notifications(long user) {
        return jdbc.queryForList("SELECT * FROM notification WHERE user_id = ? ORDER BY notification_id", user);
    }

    /** 가입을 마친 사용자(DB CHECK ck_user_active의 칸을 채운다). */
    private long activeUser() {
        long user = fixtures.user();
        jdbc.update("UPDATE app_user SET status = 'ACTIVE', phone_enc = ?, pin_hash = 'x', "
                + "pin_updated_at = UTC_TIMESTAMP(6), identity_verified_at = UTC_TIMESTAMP(6) WHERE user_id = ?",
                new byte[] {1, 2}, user);
        return user;
    }

    /** 11월 실적 행이 있는 카드. */
    private long card(long user, String status, String alias, long recognized) {
        long id = fixtures.card(user, tiered, null, status);
        jdbc.update("UPDATE user_card SET card_alias = ?, registered_at = ? WHERE user_card_id = ?", alias, now(), id);
        jdbc.update("""
                INSERT INTO card_monthly_performance (user_card_id, performance_month, recognized_amount, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """, id, Date.valueOf(NOVEMBER.atDay(1)), recognized, now(), now());
        return id;
    }

    /** 11월 실적 판단 한 줄. */
    private long decide(long user, long card, String decision, boolean muted) {
        Map<String, Object> row = Map.of("user_id", user, "user_card_id", card, "performance_month",
                Date.valueOf(NOVEMBER.atDay(1)), "decision", decision, "notifications_muted", muted,
                "decided_at", now(), "created_at", now(), "updated_at", now());
        return new SimpleJdbcInsert(jdbc).withTableName("performance_decision")
                .usingGeneratedKeyColumns("performance_decision_id").usingColumns(row.keySet().toArray(String[]::new))
                .executeAndReturnKey(row).longValue();
    }

    private void benefit(long product, String name, long minPerformance) {
        Map<String, Object> row = Map.of("card_product_id", product, "benefit_code", "B-" + UUID.randomUUID(),
                "policy_version", 1, "benefit_name", name, "benefit_type", "DISCOUNT", "calculation_type", "RATE",
                "rate", new BigDecimal("0.1"), "value_unit", "KRW", "min_performance_amount", minPerformance,
                "created_at", now());
        // 넣는 칸만 적는다. 적지 않으면 나머지 칸에 NULL을 넣어 DB 기본값을 덮는다
        new SimpleJdbcInsert(jdbc).withTableName("card_benefit").usingGeneratedKeyColumns("card_benefit_id")
                .usingColumns(row.keySet().toArray(String[]::new)).executeAndReturnKey(row);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
