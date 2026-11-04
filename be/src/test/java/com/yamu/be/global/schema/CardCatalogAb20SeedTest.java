package com.yamu.be.global.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yamu.be.support.MySqlTestSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * 카드 20종 v2 가공 시드(02)를 실제 MySQL에서 확인한다. 97 시드(01)와 순서 상관없이 함께 돌고,
 * 다시 돌려도 행이 늘지 않으며, 대표 혜택이 원본 값을 DB 단위로 바꿔 들어가는지 본다.
 * 컨테이너를 다른 테스트와 같이 쓰므로 카드 카탈로그 표를 앞뒤로 비운다.
 */
@TestPropertySource(properties = "spring.application.name=yamu-card-catalog-ab20-seed-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CardCatalogAb20SeedTest extends MySqlTestSupport {
    private static final List<String> CATALOG_TABLES = List.of(
            "benefit_application_member", "benefit_application_group", "benefit_target_group_member", "benefit_target_group",
            "benefit_limit_link", "tier_limit", "benefit_target", "limit_group", "card_benefit", "merchant", "card_product");
    private static final String TOKTOK = "KB국민 청춘대로 톡톡카드";

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void clearCatalog() throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            for (String table : CATALOG_TABLES) statement.execute("DELETE FROM " + table);
            statement.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    @Test
    void addsCardsBesideSeed97AndRerunsWithoutDuplicates() throws Exception {
        apply("01-card-catalog.sql");
        apply("02-card-catalog-ab20.sql");
        assertCatalogCounts();

        var before = jdbc.queryForList("SELECT * FROM card_benefit ORDER BY card_benefit_id");
        apply("02-card-catalog-ab20.sql");
        assertThat(jdbc.queryForList("SELECT * FROM card_benefit ORDER BY card_benefit_id")).isEqualTo(before);
        assertCatalogCounts();

        // 97의 3장은 02가 건드리지 않는다.
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM card_benefit b JOIN card_product p USING (card_product_id)
                WHERE p.product_name IN ('신한카드 Mr.Life', '현대카드 ZERO Edition3 (할인형)', '신한카드 처음')
                """, Integer.class)).isEqualTo(13);
    }

    @Test
    void runsBeforeSeed97Too() throws Exception {
        apply("02-card-catalog-ab20.sql");
        apply("01-card-catalog.sql");
        assertCatalogCounts();
    }

    @Test
    void representativeBenefitsKeepSourceValuesInDbUnits() throws Exception {
        apply("02-card-catalog-ab20.sql");

        // 톡톡 스타벅스 50%: 정률 0.5, 전월 30만원부터, 월 1만원 한도(실적 30만원 구간), 스타벅스 포함 + 카드 전체 제외 대상
        assertThat(jdbc.queryForMap("""
                SELECT b.benefit_type, b.calculation_type, b.rate, b.value_unit, b.point_krw_rate, b.min_performance_amount, b.application_timing
                FROM card_benefit b JOIN card_product p USING (card_product_id)
                WHERE p.product_name = ? AND b.benefit_code = 'STARBUCKS_50'
                """, TOKTOK)).containsEntry("benefit_type", "DISCOUNT").containsEntry("calculation_type", "RATE")
                .containsEntry("value_unit", "KRW").containsEntry("point_krw_rate", null)
                .containsEntry("min_performance_amount", 300000L).containsEntry("application_timing", "AT_BILLING");
        assertThat(jdbc.queryForObject("""
                SELECT b.rate FROM card_benefit b JOIN card_product p USING (card_product_id)
                WHERE p.product_name = ? AND b.benefit_code = 'STARBUCKS_50'
                """, java.math.BigDecimal.class, TOKTOK)).isEqualByComparingTo("0.5");
        assertThat(jdbc.queryForMap("""
                SELECT g.period_type, g.limit_unit, g.base_limit_value, t.min_performance_amount, t.max_performance_amount, t.limit_value
                FROM benefit_limit_link l
                JOIN card_benefit b USING (card_benefit_id)
                JOIN limit_group g ON g.limit_group_id = l.limit_group_id
                JOIN tier_limit t ON t.limit_group_id = g.limit_group_id
                JOIN card_product p ON p.card_product_id = b.card_product_id
                WHERE p.product_name = ? AND b.benefit_code = 'STARBUCKS_50'
                """, TOKTOK)).containsEntry("period_type", "MONTHLY").containsEntry("limit_unit", "KRW")
                .containsEntry("base_limit_value", null).containsEntry("min_performance_amount", 300000L)
                .containsEntry("max_performance_amount", null).containsEntry("limit_value", 10000L);
        assertThat(jdbc.queryForList("""
                SELECT CONCAT(t.target_type, ':', COALESCE(g.is_excluded,t.is_excluded), ':', COALESCE(m.brand_code, t.target_value, '*'))
                FROM benefit_target t
                JOIN card_benefit b USING (card_benefit_id)
                JOIN card_product p ON p.card_product_id = b.card_product_id
                LEFT JOIN merchant m ON m.merchant_id = t.merchant_id
                LEFT JOIN benefit_target_group_member gm ON gm.benefit_target_id=t.benefit_target_id
                LEFT JOIN benefit_target_group g ON g.benefit_target_group_id=gm.benefit_target_group_id
                WHERE p.product_name = ? AND b.benefit_code = 'STARBUCKS_50'
                """, String.class, TOKTOK)).hasSize(11)
                .contains("BRAND:0:STARBUCKS", "CONDITION:1:TRANSACTION_KIND:GIFT_CERTIFICATE_PURCHASE",
                        "CONDITION:1:INSTALLMENT:INTEREST_FREE", "CATEGORY:1:UNIVERSITY");

        // 종료일은 원본(그날 포함) + 1일, 요일 조건은 conditions_json, 정률은 퍼센트 ÷ 100
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM card_benefit b JOIN card_product p USING (card_product_id)
                WHERE p.product_name = '카카오뱅크 프렌즈 체크' AND b.benefit_code LIKE 'PROMO\\_%' AND b.valid_until = '2027-02-01'
                """, Integer.class)).isEqualTo(4);
        // 가족행사월 대체 한도를 표현하기 전까지 카드 전체가 보류된다.
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM card_product WHERE product_name = '신한카드 Point Plan'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT b.rate FROM card_benefit b JOIN card_product p USING (card_product_id)
                WHERE p.product_name = 'LOCA LIKIT 2.0' AND b.benefit_code = 'DOMESTIC_12'
                """, java.math.BigDecimal.class)).isEqualByComparingTo("0.012");
        // 정해지지 않은 포인트 가치는 비워 둔다.
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM card_benefit WHERE value_unit = 'POINT' AND point_krw_rate IS NOT NULL
                """, Integer.class)).isZero();
    }

    @Test
    void conflictingBrandIndustryStopsAndRollsBack() throws Exception {
        apply("01-card-catalog.sql");
        jdbc.update("UPDATE merchant SET merchant_category_code = 'OTHER_INDUSTRY' WHERE brand_code = 'STARBUCKS'");

        assertThatThrownBy(() -> apply("02-card-catalog-ab20.sql")).hasMessageContaining("ab20_guard");
        assertThat(count("card_product")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT merchant_category_code FROM merchant WHERE brand_code = 'STARBUCKS'", String.class))
                .isEqualTo("OTHER_INDUSTRY");
    }

    /** 01(3 · 13 · 177 · 31 · 12 · 42 · 27) + 02(14 · 43 · 636 · 33 · 41 · 55 · 새 브랜드 32) */
    private void assertCatalogCounts() {
        assertThat(count("card_product")).isEqualTo(17);
        assertThat(count("card_benefit")).isEqualTo(56);
        assertThat(count("benefit_target")).isEqualTo(813);
        assertThat(count("limit_group")).isEqualTo(64);
        assertThat(count("tier_limit")).isEqualTo(53);
        assertThat(count("benefit_limit_link")).isEqualTo(97);
        assertThat(count("merchant")).isEqualTo(59);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void apply(String file) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            try {
                ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new FileSystemResource(Path.of("seed", file)), StandardCharsets.UTF_8));
            } catch (Exception failure) {
                // SQL 안에 START TRANSACTION이 있다 — 실패하면 일부만 남지 않게 되돌린다.
                try (var statement = connection.createStatement()) {
                    statement.execute("ROLLBACK");
                }
                throw failure;
            }
        }
    }
}
