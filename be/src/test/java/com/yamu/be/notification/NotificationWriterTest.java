package com.yamu.be.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/** 알림 쓰기(Task 9.3.2)의 설정 묶음·중복 방지·길이 처리를 실제 MySQL로 확인한다. */
@TestPropertySource(properties = "spring.application.name=yamu-notification-writer-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationWriterTest extends MySqlTestSupport {

    @Autowired NotificationWriter writer;
    @Autowired JdbcTemplate jdbc;

    long user;

    @BeforeEach
    void setUp() {
        user = new OwnershipFixtures(jdbc).user();
    }

    @Test
    void 쌓고_같은_키는_다시_쌓지_않는다() {
        NotificationWriter.Draft draft = draft(NotificationType.PAYMENT_RESULT, "PAYMENT_RESULT:1:APPROVED");

        assertThat(writer.write(draft)).isEqualTo(NotificationWriter.Result.CREATED);
        assertThat(writer.write(draft)).isEqualTo(NotificationWriter.Result.DUPLICATE);

        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT created_at IS NOT NULL AND updated_at IS NOT NULL FROM notification "
                + "WHERE user_id = ?", Boolean.class, user)).isTrue();
    }

    @Test
    void 같은_키를_동시에_쌓아도_하나만_남는다() throws Exception {
        NotificationWriter.Draft draft = draft(NotificationType.BILL_CONFIRMED, "BILL_CONFIRMED:9");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<NotificationWriter.Result>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                Callable<NotificationWriter.Result> task = () -> {
                    start.await();
                    return writer.write(draft);
                };
                results.add(pool.submit(task));
            }
            start.countDown();
            List<NotificationWriter.Result> outcomes = new ArrayList<>();
            for (Future<NotificationWriter.Result> result : results) {
                outcomes.add(result.get());
            }
            assertThat(outcomes).containsOnly(NotificationWriter.Result.CREATED, NotificationWriter.Result.DUPLICATE);
            assertThat(outcomes).filteredOn(r -> r == NotificationWriter.Result.CREATED).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void 같은_키라도_사용자가_다르면_따로_쌓는다() {
        long other = new OwnershipFixtures(jdbc).user();

        writer.write(draft(NotificationType.PAYMENT_RESULT, "PAYMENT_RESULT:1:APPROVED"));
        writer.write(new NotificationWriter.Draft(other, NotificationType.PAYMENT_RESULT, "PAYMENT_RESULT:1:APPROVED",
                "제목", "내용", null, null));

        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id = ?", Long.class, other))
                .isEqualTo(1);
    }

    @Test
    void 꺼진_묶음만_쌓지_않는다() {
        jdbc.update("UPDATE app_user SET notification_settings = JSON_OBJECT('transactionEnabled', false, "
                + "'balanceRiskEnabled', false) WHERE user_id = ?", user);

        assertThat(writer.write(draft(NotificationType.BILL_UNPAID, "BILL_UNPAID:1")))
                .isEqualTo(NotificationWriter.Result.DISABLED);
        assertThat(writer.write(draft(NotificationType.BALANCE_RISK, "BALANCE_RISK:2026-11-20")))
                .isEqualTo(NotificationWriter.Result.DISABLED);
        // 실적 묶음은 켜져 있고(키 없음 = 켜짐), 보안은 설정과 상관없다
        assertThat(writer.write(draft(NotificationType.PERFORMANCE_SHORTFALL, "PERFORMANCE_SHORTFALL:1")))
                .isEqualTo(NotificationWriter.Result.CREATED);
        assertThat(writer.write(draft(NotificationType.SECURITY, "SECURITY:1")))
                .isEqualTo(NotificationWriter.Result.CREATED);

        assertThat(count()).isEqualTo(2);
    }

    @Test
    void 없는_사용자는_404다() {
        assertThatThrownBy(() -> writer.write(new NotificationWriter.Draft(Long.MAX_VALUE,
                NotificationType.PAYMENT_RESULT, "PAYMENT_RESULT:1:APPROVED", "제목", "내용", null, null)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 긴_제목과_내용은_칸_길이에_맞춰_자른다() {
        String longTitle = "가".repeat(200);
        String longBody = "😀".repeat(1200);

        writer.write(new NotificationWriter.Draft(user, NotificationType.PAYMENT_RESULT, "PAYMENT_RESULT:2:APPROVED",
                longTitle, longBody, "PAYMENT_TRANSACTION", 2L));

        String title = jdbc.queryForObject("SELECT title FROM notification WHERE user_id = ?", String.class, user);
        String body = jdbc.queryForObject("SELECT body FROM notification WHERE user_id = ?", String.class, user);
        assertThat(title.codePointCount(0, title.length())).isEqualTo(150);
        assertThat(title).endsWith("…");
        assertThat(body.codePointCount(0, body.length())).isEqualTo(1000);
    }

    @Test
    void 키와_이동_대상_모양이_틀리면_만들지_않는다() {
        assertThatThrownBy(() -> new NotificationWriter.Draft(user, NotificationType.SECURITY, "키", "제목", "내용",
                null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationWriter.Draft(user, NotificationType.SECURITY, "x".repeat(192), "제목",
                "내용", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationWriter.Draft(user, NotificationType.SECURITY, "SECURITY:1", "제목",
                "내용", "CARD_BILL", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationWriter.Draft(user, NotificationType.SECURITY, "SECURITY:1", " ",
                "내용", null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    private NotificationWriter.Draft draft(NotificationType type, String key) {
        return new NotificationWriter.Draft(user, type, key, "제목 " + type, "내용 " + type, null, null);
    }

    private long count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id = ?", Long.class, user);
    }
}
