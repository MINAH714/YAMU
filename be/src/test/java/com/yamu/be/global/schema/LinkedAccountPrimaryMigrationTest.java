package com.yamu.be.global.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.support.MySqlTestSupport;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * 기준 계좌 칸(V20261029_2)을 추가할 때 이미 있던 연결 계좌는 사용자마다 "가장 먼저 연결한 계좌"가 기준 계좌가 된다.
 * 해제한 계좌는 기준 계좌가 되지 않고, 연결 계좌가 없는 사용자는 그대로 둔다.
 */
@TestPropertySource(properties = {
        "spring.application.name=yamu-linked-account-primary-migration-test",
        "spring.flyway.target=20261029.1",
        "spring.jpa.hibernate.ddl-auto=none"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LinkedAccountPrimaryMigrationTest extends MySqlTestSupport {

    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;

    @Test
    void 기존_연결_계좌는_사용자마다_가장_먼저_연결한_계좌가_기준_계좌가_된다() {
        jdbc.update("INSERT INTO app_user (user_id, created_at, updated_at) VALUES "
                + "(1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)), (2, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)), "
                + "(3, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
        // 사용자 1: 해제한 계좌(10)가 가장 먼저지만 연결된 것 중 가장 먼저인 11이 기준 계좌. 사용자 2: 연결 하나(20).
        // 사용자 3: 해제한 계좌만 있어 기준 계좌 없음.
        insert(10, 1, "DISCONNECTED", "01");
        insert(11, 1, "CONNECTED", "02");
        insert(12, 1, "CONNECTED", "03");
        insert(20, 2, "CONNECTED", "04");
        insert(30, 3, "DISCONNECTED", "05");

        Flyway upgrade = Flyway.configure().dataSource(flyway.getConfiguration().getDataSource())
                .locations(flyway.getConfiguration().getLocations())
                .target(MigrationVersion.fromVersion("20261029.2")).load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);

        List<Long> primaries = jdbc.queryForList(
                "SELECT linked_account_id FROM linked_account WHERE is_primary = 1 ORDER BY linked_account_id", Long.class);
        assertThat(primaries).containsExactly(11L, 20L);
    }

    private void insert(long id, long userId, String status, String hashByte) {
        jdbc.update("""
                INSERT INTO linked_account (linked_account_id, user_id, bank_code, account_no_enc, account_no_hash,
                    status, connected_at, disconnected_at, created_at, updated_at)
                VALUES (?, ?, '088', X'01', UNHEX(REPEAT(?, 32)), ?, UTC_TIMESTAMP(6),
                    IF(? = 'DISCONNECTED', UTC_TIMESTAMP(6), NULL), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, id, userId, hashByte, status, status);
    }
}
