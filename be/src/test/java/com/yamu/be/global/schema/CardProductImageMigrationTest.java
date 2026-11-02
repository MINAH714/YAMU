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

@TestPropertySource(properties = {
        "spring.application.name=yamu-card-product-image-migration-test",
        "spring.flyway.target=20261102.1",
        "spring.jpa.hibernate.ddl-auto=none"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CardProductImageMigrationTest extends MySqlTestSupport {

    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;

    @Test
    void 기존_상품과_마이그레이션_기록을_보존하고_이미지는_null로_추가한다() {
        jdbc.update("""
                INSERT INTO card_product (card_product_id, issuer_code, product_name, card_type,
                    billing_cutoff_days, terms_url, created_at, updated_at)
                VALUES (1, 'SHINHAN', '기존 카드', 'CREDIT', 14, 'https://example.com/terms',
                    '2026-10-01 00:00:00', '2026-10-01 00:00:00')
                """);
        var before = jdbc.queryForMap("SELECT * FROM card_product WHERE card_product_id=1");
        var history = jdbc.queryForList("SELECT version, checksum FROM flyway_schema_history ORDER BY installed_rank");
        Flyway upgrade = Flyway.configure().dataSource(flyway.getConfiguration().getDataSource())
                .locations(flyway.getConfiguration().getLocations())
                .target(MigrationVersion.fromVersion("20261102.2")).load();

        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        var after = jdbc.queryForMap("SELECT * FROM card_product WHERE card_product_id=1");
        assertThat(after).containsAllEntriesOf(before).containsEntry("image_key", null);
        assertThat(jdbc.queryForMap("""
                SELECT column_type, is_nullable FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='card_product' AND column_name='image_key'
                """)).containsEntry("COLUMN_TYPE", "varchar(1024)").containsEntry("IS_NULLABLE", "YES");
        assertThat(jdbc.queryForList("SELECT version, checksum FROM flyway_schema_history ORDER BY installed_rank"))
                .containsAll(history);
        String key = "cards/shinhan/static-front/image.png";
        jdbc.update("UPDATE card_product SET image_key=? WHERE card_product_id=1", key);
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT image_key FROM card_product WHERE card_product_id=1", String.class))
                .isEqualTo(key);
    }
}
