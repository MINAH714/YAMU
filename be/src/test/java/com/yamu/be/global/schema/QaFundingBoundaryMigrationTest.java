package com.yamu.be.global.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/** Upgrade the observed production version without replacing existing users or QA ownership. */
@TestPropertySource(properties = {
        "spring.application.name=yamu-qa-funding-migration-test",
        "spring.flyway.target=20261112.2",
        "spring.jpa.hibernate.ddl-auto=none"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QaFundingBoundaryMigrationTest extends MySqlTestSupport {

    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;

    @Test
    void upgradesExistingDataThroughRefundVersionThenFundingVersionWithoutRepairOrReapplication() {
        OwnershipFixtures fixtures = new OwnershipFixtures(jdbc);
        long owner = fixtures.user();
        long stranger = fixtures.user();
        long account = fixtures.account(owner);
        fixtures.account(stranger);
        long product = fixtures.product();
        fixtures.card(owner, product, account, "ACTIVE");
        String dataset = "qa-preserve-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO qa_seed_dataset(dataset_key,seed_version,base_date,input_hash,run_id,created_at)
                VALUES (?,2,'2026-11-12',?,?,UTC_TIMESTAMP(6))
                """, dataset, new byte[32], UUID.randomUUID().toString());
        jdbc.update("INSERT INTO qa_seed_member(dataset_key,persona_key,user_id,enriched) VALUES (?,?,?,?)",
                dataset, "owner", owner, true);
        Map<String, List<Map<String, Object>>> before = existingRows();
        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT version,checksum FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank");

        Flyway refund = Flyway.configure().configuration(flyway.getConfiguration())
                .target(MigrationVersion.fromVersion("20261116.3")).load();
        assertThat(refund.migrate().migrationsExecuted).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT script FROM flyway_schema_history WHERE version='20261116.3' AND success=1
                """, String.class)).isEqualTo("V20261116_3__add_payment_refund_account.sql");
        assertThat(existingRows()).usingRecursiveComparison().isEqualTo(before);

        Flyway latest = Flyway.configure().configuration(flyway.getConfiguration())
                .target(MigrationVersion.LATEST).load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT script FROM flyway_schema_history WHERE version='20261116.4' AND success=1
                """, String.class)).isEqualTo("V20261116_4__add_qa_funding_boundary.sql");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM qa_seed_funding_boundary", Integer.class)).isZero();
        assertThat(existingRows()).usingRecursiveComparison().isEqualTo(before);
        assertThat(jdbc.queryForList(
                "SELECT version,checksum FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank"))
                .containsAll(history);
        assertThat(latest.validateWithResult().validationSuccessful).isTrue();
        assertThat(latest.migrate().migrationsExecuted).isZero();
        assertThat(existingRows()).usingRecursiveComparison().isEqualTo(before);
    }

    private Map<String, List<Map<String, Object>>> existingRows() {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        for (String table : List.of("app_user", "linked_account", "qa_seed_dataset", "qa_seed_member")) {
            rows.put(table, jdbc.queryForList("SELECT * FROM " + table));
        }
        rows.put("user_card", jdbc.queryForList("""
                SELECT user_card_id,user_id,linked_account_id,card_no_hash,status,
                       registered_at,created_at,updated_at,version FROM user_card ORDER BY user_card_id
                """));
        return rows;
    }
}
