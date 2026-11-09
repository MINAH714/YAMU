package com.yamu.be.global.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yamu.be.support.MySqlTestSupport;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/** Unit migration preserves existing policies, usage, restoration and foreign keys. */
@TestPropertySource(properties = {
        "spring.application.name=yamu-limit-unit-migration-test",
        "spring.flyway.target=20261028.2",
        "spring.jpa.hibernate.ddl-auto=none"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LimitUnitMigrationTest extends MySqlTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;

    @Test
    void upgradingChangesOnlyAmountUnitAndEnforcesExplicitUnits() {
        jdbc.batchUpdate(
                "INSERT INTO app_user (user_id, created_at, updated_at) VALUES (1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                """
                INSERT INTO card_product (card_product_id, issuer_code, product_name, card_type, created_at, updated_at)
                VALUES (1, 'TEST', 'limit-unit-test', 'CREDIT', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                """
                INSERT INTO merchant (merchant_id, merchant_name, brand_code, merchant_category_code, created_at, updated_at)
                VALUES (1, 'test', 'TEST', 'CAFE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                """
                INSERT INTO user_card (user_card_id, user_id, card_product_id, card_no_enc, card_no_hash,
                    card_last4, status, created_at, updated_at)
                VALUES (1, 1, 1, X'01', UNHEX(REPEAT('00', 32)), '1234', 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                """
                INSERT INTO payment_transaction (payment_transaction_id, user_id, user_card_id, card_product_id,
                    merchant_id, request_key, request_fingerprint, source, status, requested_amount,
                    requested_at, created_at, updated_at)
                VALUES (1, 1, 1, 1, 1, '00000000-0000-0000-0000-000000000001', UNHEX(REPEAT('00', 32)),
                    'SEED', 'REQUESTED', 10000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                """
                INSERT INTO limit_group (limit_group_id, card_product_id, group_code, policy_version,
                    group_name, period_type, limit_unit, base_limit_value, valid_from, created_at)
                VALUES (1, 1, 'MONTHLY_AMOUNT', 1, 'amount cap', 'MONTHLY', 'AMOUNT', NULL, NULL, '2026-10-27'),
                    (2, 1, 'DAILY_COUNT', 1, 'count cap', 'DAILY', 'COUNT', 3, '2026-10-01', '2026-10-27')
                """,
                """
                INSERT INTO tier_limit (limit_group_id, min_performance_amount, max_performance_amount, limit_value, created_at)
                VALUES (1, 300000, 500000, 5000, '2026-10-27')
                """,
                """
                INSERT INTO monthly_limit_usage (user_card_id, card_product_id, limit_group_id, usage_month,
                    used_value, created_at, updated_at, version)
                VALUES (1, 1, 1, '2026-10-01', 1200, '2026-10-27', '2026-10-27', 2)
                """,
                """
                INSERT INTO payment_limit_usage (payment_transaction_id, card_product_id, limit_group_id,
                    remaining_before, consumed_value, usage_date, usage_month, restored_at, created_at, updated_at, version)
                VALUES (1, 1, 1, 3800, 500, '2026-10-27', '2026-10-01', '2026-10-27 01:00:00',
                    '2026-10-27', '2026-10-27 01:00:00', 3)
                """);
        var groups = jdbc.queryForList("SELECT * FROM limit_group ORDER BY limit_group_id");
        groups.get(0).put("limit_unit", "KRW");
        var tiers = jdbc.queryForList("SELECT * FROM tier_limit");
        var monthly = jdbc.queryForList("SELECT * FROM monthly_limit_usage");
        var payments = jdbc.queryForList("SELECT * FROM payment_limit_usage");
        var checksums = jdbc.queryForList("SELECT version, checksum FROM flyway_schema_history ORDER BY installed_rank");

        Flyway upgrade = Flyway.configure().dataSource(flyway.getConfiguration().getDataSource())
                .locations(flyway.getConfiguration().getLocations())
                .target(MigrationVersion.fromVersion("20261028.3")).load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM limit_group ORDER BY limit_group_id")).isEqualTo(groups);
        assertThat(jdbc.queryForList("SELECT * FROM tier_limit")).isEqualTo(tiers);
        assertThat(jdbc.queryForList("SELECT * FROM monthly_limit_usage")).isEqualTo(monthly);
        assertThat(jdbc.queryForList("SELECT * FROM payment_limit_usage")).isEqualTo(payments);
        assertThat(jdbc.queryForList("SELECT version, checksum FROM flyway_schema_history WHERE version <> '20261028.3' ORDER BY installed_rank"))
                .isEqualTo(checksums);

        for (String unit : new String[] {"KRW", "POINT", "COUNT"}) {
            jdbc.update("UPDATE limit_group SET limit_unit = ? WHERE limit_group_id = 2", unit);
            assertThat(jdbc.queryForObject("SELECT limit_unit FROM limit_group WHERE limit_group_id = 2", String.class))
                    .isEqualTo(unit);
        }
        for (String unit : new String[] {"AMOUNT", "DISCOUNT", "krw", "", "UNKNOWN"}) {
            assertConstraintFailure(() -> jdbc.update("UPDATE limit_group SET limit_unit = ? WHERE limit_group_id = 2", unit), 3819);
        }
        assertConstraintFailure(() -> jdbc.update("UPDATE limit_group SET limit_unit = NULL WHERE limit_group_id = 2"), 1048);
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
    }

    private static void assertConstraintFailure(Runnable operation, int errorCode) {
        assertThatThrownBy(operation::run).isInstanceOf(DataAccessException.class).satisfies(failure -> {
            Throwable cause = ((DataAccessException) failure).getMostSpecificCause();
            assertThat(cause).isInstanceOf(SQLException.class);
            assertThat(((SQLException) cause).getErrorCode()).isEqualTo(errorCode);
        });
    }
}
