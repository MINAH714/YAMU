package com.yamu.be.global.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.support.MySqlTestSupport;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class BaseTimeEntityTest extends MySqlTestSupport {

    @Autowired
    EntityManager em;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 서버가_뜰_때_Flyway가_마이그레이션을_적용한다() {
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class);

        assertThat(applied).isPositive();
    }

    @Test
    void 저장하면_created_at과_updated_at이_UTC로_채워진다() {
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1);

        Long id = tx.execute(status -> {
            AuditSample sample = new AuditSample("처음");
            em.persist(sample);
            return sample.getId();
        });

        AuditSample saved = tx.execute(status -> em.find(AuditSample.class, id));
        LocalDateTime after = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1);

        assertThat(saved.getCreatedAt()).isBetween(before, after);
        assertThat(saved.getUpdatedAt()).isBetween(before, after);
    }

    @Test
    void 수정하면_updated_at만_바뀌고_version이_올라간다() throws InterruptedException {
        Long id = tx.execute(status -> {
            AuditSample sample = new AuditSample("처음");
            em.persist(sample);
            return sample.getId();
        });
        AuditSample created = tx.execute(status -> em.find(AuditSample.class, id));

        Thread.sleep(20);
        tx.executeWithoutResult(status -> em.find(AuditSample.class, id).rename("바뀜"));
        AuditSample updated = tx.execute(status -> em.find(AuditSample.class, id));

        assertThat(updated.getName()).isEqualTo("바뀜");
        assertThat(updated.getCreatedAt()).isEqualTo(created.getCreatedAt());
        assertThat(updated.getUpdatedAt()).isAfter(created.getUpdatedAt());
        assertThat(updated.getVersion()).isEqualTo(created.getVersion() + 1);
    }
}
