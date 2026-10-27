package com.yamu.be.global.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.support.MySqlTestSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/** 기존 반복 규칙·금액·상태를 보존하고 첫 발생 건만 이관한다. */
@TestPropertySource(properties = {
        "spring.application.name=yamu-cashflow-migration-test",
        "spring.flyway.target=20261027.3",
        "spring.jpa.hibernate.ddl-auto=none"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CashflowMigrationTest extends MySqlTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;

    @Test
    void upgradingPreservesRulesAndBackfillsOnlyTheirFirstOccurrences() {
        jdbc.update("INSERT INTO app_user (user_id, created_at, updated_at) VALUES (1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
        jdbc.update("""
                INSERT INTO cashflow_item (user_id, title, direction, amount, scheduled_date,
                    recurrence_type, recurrence_end_date, status, memo, created_at, updated_at, version)
                VALUES (1, 'rent', 'EXPENSE', 500000, '2026-10-10', 'MONTHLY', '2027-10-10',
                    'CONFIRMED', 'existing', '2026-10-01 00:00:00', '2026-10-02 00:00:00', 3)
                """);
        jdbc.update("""
                INSERT INTO cashflow_item (user_id, title, direction, amount, scheduled_date,
                    recurrence_type, status, created_at, updated_at)
                VALUES (1, 'income', 'INCOME', 100000, '2026-10-20', 'NONE', 'ACTIVE',
                    '2026-10-01 00:00:00', '2026-10-01 00:00:00')
                """);
        String columns = "cashflow_item_id, user_id, title, direction, amount, scheduled_date, recurrence_type, recurrence_end_date, status, memo, created_at, updated_at, version";
        var before = jdbc.queryForList("SELECT " + columns + " FROM cashflow_item ORDER BY cashflow_item_id");
        var checksums = jdbc.queryForList("SELECT version, checksum FROM flyway_schema_history ORDER BY installed_rank");
        Flyway upgrade = Flyway.configure().dataSource(flyway.getConfiguration().getDataSource())
                .locations(flyway.getConfiguration().getLocations())
                .target(MigrationVersion.fromVersion("20261027.4")).load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT " + columns + " FROM cashflow_item ORDER BY cashflow_item_id")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cashflow_occurrence", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForMap("""
                SELECT amount, status, memo, version FROM cashflow_occurrence WHERE title = 'rent'
                """)).containsEntry("amount", 500000L).containsEntry("status", "CONFIRMED")
                .containsEntry("memo", "existing").containsEntry("version", 3L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cashflow_occurrence WHERE occurrence_date <> scheduled_date", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cashflow_payment_match", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT version, checksum FROM flyway_schema_history WHERE version <> '20261027.4' ORDER BY installed_rank")).isEqualTo(checksums);
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
    }
}
