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

/** 업종 코드 이름 변경은 기존 값을 보존하고 미확보 물품 정보는 NULL로 둔다. */
@TestPropertySource(properties = {
        "spring.application.name=yamu-category-product-migration-test",
        "spring.flyway.target=20261027.4",
        "spring.jpa.hibernate.ddl-auto=none"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CategoryProductMigrationTest extends MySqlTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;

    @Test
    void upgradingKeepsIndustryCodesTitlesAndIndexesWithoutInventingProducts() {
        jdbc.update("INSERT INTO app_user (user_id, created_at, updated_at) VALUES (1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
        jdbc.update("""
                INSERT INTO merchant (merchant_id, merchant_name, brand_code, category_code, created_at, updated_at)
                VALUES (1, 'test store', 'TEST', 'EXISTING_INDUSTRY', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        jdbc.update("""
                INSERT INTO cashflow_item (cashflow_item_id, user_id, title, direction, amount, scheduled_date,
                    recurrence_type, status, category_code, created_at, updated_at, version)
                VALUES (1, 1, 'existing schedule', 'EXPENSE', 50000, '2026-11-01',
                    'MONTHLY', 'CONFIRMED', 'EXISTING_INDUSTRY', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 2)
                """);
        jdbc.update("""
                INSERT INTO cashflow_occurrence (cashflow_item_id, user_id, occurrence_date, scheduled_date,
                    title, direction, amount, category_code, status, created_at, updated_at, version)
                VALUES (1, 1, '2026-11-01', '2026-11-02', 'existing occurrence', 'EXPENSE', 43000,
                    'OVERRIDDEN_INDUSTRY', 'CONFIRMED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 3)
                """);
        var before = jdbc.queryForList("""
                SELECT title, category_code AS merchant_category_code, amount, scheduled_date, version,
                    created_at, updated_at FROM cashflow_occurrence
                """);
        var checksums = jdbc.queryForList("SELECT version, checksum FROM flyway_schema_history ORDER BY installed_rank");
        Flyway upgrade = Flyway.configure().dataSource(flyway.getConfiguration().getDataSource())
                .locations(flyway.getConfiguration().getLocations())
                .target(MigrationVersion.fromVersion("20261027.5")).load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT title, merchant_category_code, amount, scheduled_date, version,
                    created_at, updated_at FROM cashflow_occurrence
                """)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT merchant_category_code FROM merchant WHERE merchant_id = 1", String.class))
                .isEqualTo("EXISTING_INDUSTRY");
        assertThat(jdbc.queryForMap("SELECT title, merchant_category_code, product_category_code, product_name FROM cashflow_item WHERE cashflow_item_id = 1"))
                .containsEntry("title", "existing schedule")
                .containsEntry("merchant_category_code", "EXISTING_INDUSTRY")
                .containsEntry("product_category_code", null).containsEntry("product_name", null);
        assertThat(jdbc.queryForMap("SELECT product_category_code, product_name FROM cashflow_occurrence"))
                .containsEntry("product_category_code", null).containsEntry("product_name", null);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
                  AND table_name IN ('merchant','cashflow_item','cashflow_occurrence') AND column_name = 'category_code'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT column_name FROM information_schema.statistics WHERE table_schema = DATABASE()
                  AND table_name = 'merchant' AND index_name = 'ix_merchant_category'
                """, String.class)).isEqualTo("merchant_category_code");
        assertThat(jdbc.queryForList("SELECT version, checksum FROM flyway_schema_history WHERE version <> '20261027.5' ORDER BY installed_rank"))
                .isEqualTo(checksums);
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
    }
}
