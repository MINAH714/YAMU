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

/** 기존 행이 있는 초기 스키마를 새 버전으로 올려도 한도 사용·복구 기록이 보존되는지 확인한다. */
@TestPropertySource(properties = {
        "spring.application.name=yamu-usage-rename-test",
        "spring.flyway.target=20261026.1"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentLimitUsageMigrationTest extends MySqlTestSupport {

    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;

    @Test
    void renamingKeepsExistingUsageAndRestorationDataWithoutReapplyingMigrations() {
        jdbc.batchUpdate(
                "INSERT INTO app_user (user_id, created_at, updated_at) VALUES (1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                """
                INSERT INTO card_product (card_product_id, issuer_code, product_name, card_type, created_at, updated_at)
                VALUES (1, 'TEST', 'migration-test', 'CREDIT', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                """
                INSERT INTO merchant (merchant_id, merchant_name, brand_code, category_code, created_at, updated_at)
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
                    group_name, period_type, limit_unit, base_limit_value, created_at)
                VALUES (1, 1, 'CAFE_MONTHLY', 1, 'test', 'MONTHLY', 'AMOUNT', 3000, UTC_TIMESTAMP(6))
                """,
                """
                INSERT INTO payment_limit_impact (payment_limit_impact_id, payment_transaction_id,
                    card_product_id, limit_group_id, remaining_before, consumed_value, usage_date, usage_month,
                    restored_at, created_at, updated_at, version)
                VALUES (42, 1, 1, 1, 1000, 300, '2026-10-27', '2026-10-01',
                    '2026-10-27 00:00:00', '2026-10-26 00:00:00', '2026-10-27 00:00:00', 2)
                """);
        Integer originalChecksum = jdbc.queryForObject(
                "SELECT checksum FROM flyway_schema_history WHERE version = '20261026.1'", Integer.class);

        Flyway upgrade = Flyway.configure()
                .dataSource(flyway.getConfiguration().getDataSource())
                .locations(flyway.getConfiguration().getLocations())
                .target(MigrationVersion.LATEST)
                .load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForMap("""
                SELECT payment_limit_usage_id, payment_transaction_id, card_product_id, limit_group_id,
                    remaining_before, consumed_value, CAST(restored_at AS CHAR) AS restored_time, version
                FROM payment_limit_usage WHERE payment_limit_usage_id = 42
                """)).containsEntry("payment_limit_usage_id", 42L)
                .containsEntry("payment_transaction_id", 1L)
                .containsEntry("card_product_id", 1L)
                .containsEntry("limit_group_id", 1L)
                .containsEntry("remaining_before", 1000L)
                .containsEntry("consumed_value", 300L)
                .containsEntry("restored_time", "2026-10-27 00:00:00.000000")
                .containsEntry("version", 2L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'payment_limit_impact'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version = '20261026.1'",
                Integer.class)).isEqualTo(originalChecksum);
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
    }
}
