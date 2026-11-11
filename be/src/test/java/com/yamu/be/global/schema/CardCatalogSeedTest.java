package com.yamu.be.global.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yamu.be.support.MySqlTestSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/** 실제 MySQL에서 공유 한도 관계와 재배포 시 기존 정책 보존을 확인한다. */
@TestPropertySource(properties = "spring.application.name=yamu-card-catalog-seed-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CardCatalogSeedTest extends MySqlTestSupport {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    @Test
    void catalogKeepsIndependentAndSharedLimitsAndDoesNotOverwriteExistingPolicies() throws Exception {
        jdbc.update("""
                INSERT INTO card_product (issuer_code, product_name, card_type, created_at, updated_at)
                VALUES ('OTHER', 'unrelated product', 'CHECK', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        applySeed();

        assertThat(count("card_product")).isEqualTo(4);
        assertThat(count("card_benefit")).isEqualTo(13);
        assertThat(count("limit_group")).isEqualTo(31);
        assertThat(count("benefit_limit_link")).isEqualTo(42);
        assertThat(count("tier_limit")).isEqualTo(12);
        assertThat(count("benefit_target")).isEqualTo(178);
        assertThat(count("app_user")).isZero();
        assertThat(count("merchant")).isEqualTo(30);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_product WHERE product_name='다이소 신한카드'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM card_benefit b JOIN card_product p USING (card_product_id)
                WHERE p.product_name='신한카드 처음' AND b.benefit_type='POINT' AND b.value_unit='POINT'
                  AND b.rate=0.05 AND b.point_krw_rate=1 AND b.min_performance_amount=300000
                  AND b.application_timing='POINT_ACCRUAL'
                """, Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM limit_group g JOIN card_product p USING (card_product_id)
                WHERE p.product_name='신한카드 처음' AND g.limit_unit='POINT'
                  AND g.period_type='PER_TRANSACTION' AND g.base_limit_value=500
                """, Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM benefit_limit_link l JOIN limit_group g USING (limit_group_id)
                WHERE g.group_code='TODAY_DAILY' AND g.period_type='DAILY'
                  AND g.limit_unit='POINT' AND g.base_limit_value=1000
                """, Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM limit_group g WHERE g.group_code='TODAY_MONTHLY'
                  AND g.limit_unit='POINT' AND g.base_limit_value IS NULL
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM benefit_target t JOIN card_benefit b USING (card_benefit_id)
                WHERE b.benefit_code='TODAY_CAFE' AND t.target_type='CONDITION'
                  AND t.target_value='RULE:CHEOEUM_CAFE_ROUTE' AND t.is_excluded=FALSE
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM benefit_target t JOIN card_benefit b USING (card_benefit_id)
                WHERE b.benefit_code='TODAY_CAFE' AND t.target_type='CONDITION'
                  AND t.target_value LIKE 'FACILITY:%' AND t.is_excluded=TRUE
                """, Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM benefit_target t JOIN card_benefit b USING (card_benefit_id)
                WHERE b.benefit_code='TODAY_ONLINE_SHOPPING'
                  AND t.target_value='TRANSACTION_KIND:MEMBERSHIP_FEE' AND t.is_excluded=TRUE
                """, Integer.class)).isEqualTo(1);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(DISTINCT card_benefit_id) FROM benefit_target
                WHERE is_excluded = FALSE AND target_type IN ('ALL', 'BRAND', 'CATEGORY')
                """, Integer.class)).isEqualTo(13);
        // Industry-wide discount and named-brand discount must not be conflated.
        assertThat(jdbc.queryForList("""
                SELECT t.target_value FROM benefit_target t
                JOIN card_benefit b USING (card_benefit_id)
                JOIN card_product p USING (card_product_id)
                WHERE p.product_name = '신한카드 Mr.Life' AND b.benefit_code = 'CONVENIENCE'
                  AND t.target_type = 'CATEGORY' AND t.is_excluded = FALSE
                """, String.class)).containsExactly("CONVENIENCE_STORE");
        assertThat(jdbc.queryForList("""
                SELECT m.brand_code FROM benefit_target t
                JOIN merchant m USING (merchant_id)
                JOIN card_benefit b USING (card_benefit_id)
                JOIN card_product p USING (card_product_id)
                WHERE p.product_name = '신한카드 처음' AND b.benefit_code = 'TODAY_CONVENIENCE'
                  AND t.target_type = 'BRAND' ORDER BY m.brand_code
                """, String.class)).containsExactly("CU", "EMART24", "GS25", "SEVEN_ELEVEN");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM benefit_target t JOIN card_benefit b USING (card_benefit_id)
                WHERE b.benefit_code = 'HOSPITAL_PHARMACY' AND t.target_value = 'VETERINARY_CLINIC'
                  AND EXISTS (SELECT 1 FROM benefit_target_group_member gm
                    JOIN benefit_target_group g USING (benefit_target_group_id)
                    WHERE gm.benefit_target_id=t.benefit_target_id AND g.is_excluded=TRUE)
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM merchant m JOIN benefit_target t
                  ON t.target_type = 'CATEGORY' AND t.target_value = m.merchant_category_code
                JOIN card_benefit b USING (card_benefit_id)
                WHERE m.brand_code = 'YAMU_TEST_RESTAURANT' AND b.benefit_code = 'NIGHT_FOOD'
                """, Integer.class)).isEqualTo(1);

        // ZERO has no limits. A limit of zero would mean exhausted, not unlimited.
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM limit_group g JOIN card_product p USING (card_product_id)
                WHERE p.issuer_code = 'HYUNDAI'
                """, Integer.class)).isZero();

        // Each TIME area keeps its own counts but all six share the monetary cap.
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM benefit_limit_link l JOIN limit_group g USING (limit_group_id)
                WHERE g.group_code = 'TIME_MONTHLY'
                """, Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM benefit_limit_link l JOIN limit_group g USING (limit_group_id)
                WHERE g.group_code = 'TODAY_MONTHLY'
                """, Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM benefit_limit_link l
                JOIN card_benefit b USING (card_benefit_id)
                JOIN limit_group g ON g.limit_group_id = l.limit_group_id
                WHERE b.benefit_code = 'NIGHT_FOOD'
                  AND g.group_code IN ('UTILITY_MONTHLY', 'WEEKEND_MONTHLY')
                """, Integer.class)).isZero();

        // The two cards have independent KRW and POINT tier limits; do not sum mixed units.
        assertThat(tierValues(400000)).containsExactly(10000L, 5000L);
        assertThat(tierValues(300000)).containsExactly(10000L, 5000L);
        assertThat(tierValues(299999)).isEmpty();
        assertThat(tierValues(499999)).containsExactly(10000L, 5000L);
        assertThat(tierValues(500000)).containsExactly(20000L, 10000L);
        assertThat(tierValues(999999)).containsExactly(20000L, 10000L);
        assertThat(tierValues(1000000)).containsExactly(30000L, 20000L);
        assertThat(tierValues(700000)).containsExactly(20000L, 10000L);
        assertThat(tierValues(1500000)).containsExactly(30000L, 20000L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM card_benefit
                WHERE rounding_mode IS NOT NULL OR rounding_unit IS NOT NULL OR rounding_stage IS NOT NULL
                   OR valid_from IS NOT NULL OR valid_until IS NOT NULL
                """, Integer.class)).isZero();

        // Rerun must not rewrite an edited existing version or its original timestamp.
        jdbc.update("""
                UPDATE card_benefit SET rate = 0.009, created_at = '2026-01-01 00:00:00'
                WHERE benefit_code = 'BASE_DISCOUNT'
                """);
        var before = jdbc.queryForList("SELECT * FROM card_benefit ORDER BY card_benefit_id");
        applySeed();
        assertThat(jdbc.queryForList("SELECT * FROM card_benefit ORDER BY card_benefit_id")).isEqualTo(before);
        assertThat(count("card_product")).isEqualTo(4);
        assertThat(count("limit_group")).isEqualTo(31);
        assertThat(count("benefit_limit_link")).isEqualTo(42);
        assertThat(count("tier_limit")).isEqualTo(12);
        assertThat(count("merchant")).isEqualTo(30);
        assertThat(count("benefit_target")).isEqualTo(178);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_product WHERE issuer_code = 'OTHER'", Integer.class))
                .isEqualTo(1);

        // A conflicting existing industry must fail and roll back earlier catalog inserts.
        jdbc.update("DELETE FROM tier_limit WHERE tier_limit_id = (SELECT id FROM (SELECT MIN(tier_limit_id) AS id FROM tier_limit) removed)");
        jdbc.update("UPDATE merchant SET merchant_category_code = 'OTHER_INDUSTRY' WHERE brand_code = 'GS25'");
        assertThatThrownBy(this::applySeed).hasMessageContaining("seed97_guard");
        assertThat(count("tier_limit")).isEqualTo(11);
        assertThat(jdbc.queryForObject("SELECT merchant_category_code FROM merchant WHERE brand_code = 'GS25'", String.class))
                .isEqualTo("OTHER_INDUSTRY");
    }

    private java.util.List<Long> tierValues(long performance) {
        return jdbc.queryForList("""
                SELECT t.limit_value FROM tier_limit t JOIN limit_group g USING (limit_group_id)
                WHERE g.group_code IN ('TIME_MONTHLY', 'TODAY_MONTHLY')
                  AND t.min_performance_amount <= ?
                  AND (t.max_performance_amount IS NULL OR ? < t.max_performance_amount)
                ORDER BY g.group_code
                """, Long.class, performance, performance);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void applySeed() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            try {
                ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new FileSystemResource(Path.of("seed", "01-card-catalog.sql")), StandardCharsets.UTF_8));
            } catch (Exception failure) {
                // START TRANSACTION is in the SQL; do not commit partial data after failure.
                try (var statement = connection.createStatement()) {
                    statement.execute("ROLLBACK");
                }
                throw failure;
            }
        }
    }
}
