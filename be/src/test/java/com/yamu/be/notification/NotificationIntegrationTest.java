package com.yamu.be.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** 알림 목록·읽음 처리(Task 9.3.1)를 인증 필터·실제 MySQL까지 묶어 확인한다. 알림 행은 SQL로 넣고 테스트마다 되돌린다. */
@Transactional
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-notification-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationIntegrationTest extends MySqlTestSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    long owner;
    long other;
    LocalDateTime now;

    @BeforeEach
    void setUp() {
        OwnershipFixtures fixtures = new OwnershipFixtures(jdbc);
        owner = fixtures.user();
        other = fixtures.user();
        now = LocalDateTime.now(ZoneOffset.UTC).withNano(0);
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mockMvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/notifications/1/read")).andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/notifications/read-all")).andExpect(status().isUnauthorized());
    }

    @Test
    void 내_알림만_최신순으로_보이고_안_읽은_수를_낸다() throws Exception {
        long payment = notification(owner, "PAYMENT_RESULT", "PAYMENT_TRANSACTION", 1024L, null, now.minusDays(2));
        long bill = notification(owner, "BILL_DUE_SOON", "CARD_BILL", 88L, null, now.minusHours(1));
        long security = notification(owner, "SECURITY", null, null, now.minusDays(3), now.minusDays(5));
        notification(other, "PAYMENT_RESULT", "PAYMENT_TRANSACTION", 9L, null, now);

        mockMvc.perform(get("/api/notifications").with(AuthUsers.asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.items[*].notificationId").value(contains(
                        (int) bill, (int) payment, (int) security)))
                .andExpect(jsonPath("$.data.items[0].type").value("BILL_DUE_SOON"))
                .andExpect(jsonPath("$.data.items[0].title").value("제목 BILL_DUE_SOON"))
                .andExpect(jsonPath("$.data.items[0].body").value("내용 BILL_DUE_SOON"))
                .andExpect(jsonPath("$.data.items[0].refType").value("CARD_BILL"))
                .andExpect(jsonPath("$.data.items[0].refId").value(88))
                .andExpect(jsonPath("$.data.items[0].isRead").value(false))
                .andExpect(jsonPath("$.data.items[0].readAt").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].createdAt").value(endsWith("Z")))
                // 대상이 없는 알림
                .andExpect(jsonPath("$.data.items[2].refType").value(nullValue()))
                .andExpect(jsonPath("$.data.items[2].refId").value(nullValue()))
                .andExpect(jsonPath("$.data.items[2].isRead").value(true))
                .andExpect(jsonPath("$.data.items[2].readAt").value(endsWith("Z")));
    }

    @Test
    void 같은_시각이면_번호가_큰_알림이_먼저고_페이지를_나눈다() throws Exception {
        long first = notification(owner, "PAYMENT_RESULT", null, null, null, now);
        long second = notification(owner, "PAYMENT_RESULT", null, null, null, now);
        long older = notification(owner, "PAYMENT_RESULT", null, null, null, now.minusMinutes(1));

        mockMvc.perform(get("/api/notifications").param("size", "2").with(AuthUsers.asUser(owner)))
                .andExpect(jsonPath("$.data.items[*].notificationId").value(contains((int) second, (int) first)))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        mockMvc.perform(get("/api/notifications").param("size", "2").param("page", "2").with(AuthUsers.asUser(owner)))
                .andExpect(jsonPath("$.data.items[*].notificationId").value(contains((int) older)))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        mockMvc.perform(get("/api/notifications").param("size", "2").param("page", "3").with(AuthUsers.asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)))
                .andExpect(jsonPath("$.data.unreadCount").value(3));
    }

    @Test
    void 유형과_안_읽은_것만_거르고_안_읽은_수는_필터와_상관없다() throws Exception {
        long payment = notification(owner, "PAYMENT_RESULT", null, null, null, now.minusMinutes(3));
        notification(owner, "PAYMENT_RESULT", null, null, now, now.minusMinutes(2));
        long bill = notification(owner, "BILL_UNPAID", null, null, null, now.minusMinutes(1));
        notification(owner, "SECURITY", null, null, null, now);

        mockMvc.perform(get("/api/notifications").param("type", "PAYMENT_RESULT,BILL_UNPAID")
                        .param("unreadOnly", "true").with(AuthUsers.asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].notificationId").value(contains((int) bill, (int) payment)))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.unreadCount").value(3));
        // 빈 type은 필터 없음
        mockMvc.perform(get("/api/notifications").param("type", "").with(AuthUsers.asUser(owner)))
                .andExpect(jsonPath("$.data.totalElements").value(4));
    }

    @Test
    void 모르는_유형이나_범위_밖_페이지는_400이다() throws Exception {
        mockMvc.perform(get("/api/notifications").param("type", "NOPE").with(AuthUsers.asUser(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/notifications").param("size", "101").with(AuthUsers.asUser(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.fieldErrors[*].reason").value(hasItem("size는 100 이하입니다.")));
        mockMvc.perform(get("/api/notifications").param("page", "0").with(AuthUsers.asUser(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.fieldErrors[*].reason").value(hasItem("page는 1 이상입니다.")));
    }

    @Test
    void 읽음_처리는_처음_읽은_시각을_남기고_다시_보내도_바꾸지_않는다() throws Exception {
        long id = notification(owner, "PAYMENT_RESULT", null, null, null, now.minusMinutes(5));

        mockMvc.perform(patch("/api/notifications/{id}/read", id).with(AuthUsers.asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notificationId").value(id))
                .andExpect(jsonPath("$.data.readAt").value(endsWith("Z")));
        LocalDateTime first = readAt(id);
        assertThat(first).isNotNull();

        // 이미 읽은 알림: 저장값을 과거로 옮겨 두고 다시 보내도 그대로다
        LocalDateTime earlier = LocalDateTime.of(2026, 11, 1, 1, 2, 3);
        jdbc.update("UPDATE notification SET read_at = ? WHERE notification_id = ?", earlier, id);
        mockMvc.perform(patch("/api/notifications/{id}/read", id).with(AuthUsers.asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.readAt").value("2026-11-01T01:02:03Z"));
        assertThat(readAt(id)).isEqualTo(earlier);
        mockMvc.perform(get("/api/notifications").with(AuthUsers.asUser(owner)))
                .andExpect(jsonPath("$.data.unreadCount").value(0))
                .andExpect(jsonPath("$.data.items[0].isRead").value(true));
    }

    @Test
    void 남의_알림이나_없는_알림은_404이고_바꾸지_않는다() throws Exception {
        long others = notification(other, "PAYMENT_RESULT", null, null, null, now);

        mockMvc.perform(patch("/api/notifications/{id}/read", others).with(AuthUsers.asUser(owner)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        assertThat(readAt(others)).isNull();
        mockMvc.perform(patch("/api/notifications/{id}/read", others + 1000).with(AuthUsers.asUser(owner)))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/notifications/abc/read").with(AuthUsers.asUser(owner)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 모두_읽음은_내_안_읽은_알림만_바꾸고_이미_읽은_시각은_두다() throws Exception {
        long a = notification(owner, "PAYMENT_RESULT", null, null, null, now.minusMinutes(2));
        long b = notification(owner, "BILL_UNPAID", null, null, null, now.minusMinutes(1));
        LocalDateTime earlier = LocalDateTime.of(2026, 11, 1, 1, 2, 3);
        long read = notification(owner, "SECURITY", null, null, earlier, now.minusDays(2));
        long others = notification(other, "PAYMENT_RESULT", null, null, null, now);

        mockMvc.perform(patch("/api/notifications/read-all").with(AuthUsers.asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updatedCount").value(2));
        assertThat(readAt(a)).isNotNull();
        assertThat(readAt(b)).isNotNull();
        assertThat(readAt(read)).isEqualTo(earlier);
        assertThat(readAt(others)).isNull();

        mockMvc.perform(patch("/api/notifications/read-all").with(AuthUsers.asUser(owner)))
                .andExpect(jsonPath("$.data.updatedCount").value(0));
    }

    private LocalDateTime readAt(long id) {
        return jdbc.queryForObject("SELECT read_at FROM notification WHERE notification_id = ?", LocalDateTime.class,
                id);
    }

    private long notification(long userId, String type, String targetType, Long targetId, LocalDateTime readAt,
            LocalDateTime createdAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("user_id", userId);
        row.put("notification_type", type);
        row.put("deduplication_key", "test-" + UUID.randomUUID());
        row.put("title", "제목 " + type);
        row.put("body", "내용 " + type);
        row.put("target_type", targetType);
        row.put("target_id", targetId);
        row.put("read_at", readAt);
        row.put("created_at", createdAt);
        row.put("updated_at", createdAt);
        return new SimpleJdbcInsert(jdbc).withTableName("notification").usingGeneratedKeyColumns("notification_id")
                .executeAndReturnKey(row).longValue();
    }
}
