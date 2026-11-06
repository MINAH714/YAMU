package com.yamu.be.merchant;

import static org.assertj.core.api.Assertions.*;

import com.yamu.be.support.MySqlTestSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = "spring.application.name=merchant-catalog-database")
class MerchantCatalogDatabaseTest extends MySqlTestSupport {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired MerchantCatalogRepository repository;
    @Autowired MerchantCatalogService service;

    @Test void actualSeedCodesBrandsAndQueriesAgreeWithoutChangingFinancialState() throws Exception {
        // 기본 로더의 01뿐 아니라 별도로 추가되는 카탈로그/혜택 SQL도 검사한다.
        List<Path> scripts;
        try (var files = Files.list(Path.of("seed"))) {
            scripts = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted().toList();
        }
        assertThat(scripts).isNotEmpty();
        for (var file : scripts) {
            try (var connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new FileSystemResource(file), StandardCharsets.UTF_8));
            }
        }
        var supported = service.categories().stream().map(MerchantCategories.Category::merchantCategoryCode).toList();
        assertThat(supported).containsAll(jdbc.queryForList("SELECT DISTINCT merchant_category_code FROM merchant", String.class));
        assertThat(supported).containsAll(jdbc.queryForList("SELECT DISTINCT target_value FROM benefit_target WHERE target_type='CATEGORY'", String.class));
        assertThat(service.merchants("OTHER")).extracting(MerchantCatalogService.MerchantItem::brandCode)
                .contains("APP_STORE", "GOOGLE_PLAY", "EVERLAND", "LOTTE_WORLD");
        assertThat(service.merchants("DENTAL_CLINIC")).isEmpty();
        assertThat(repository.findByCategory("COFFEE")).isEmpty();
        assertThat(repository.findByCategory("coffee_shop")).isEmpty();
        var coffee = service.merchants("COFFEE_SHOP");
        assertThat(coffee).isNotEmpty().allSatisfy(item -> {
            assertThat(item.merchantCategoryCode()).isEqualTo("COFFEE_SHOP");
            assertThat(service.resolveSelection(item.merchantId(), "COFFEE_SHOP").merchantId()).isEqualTo(item.merchantId());
        });
        jdbc.update("INSERT INTO merchant (brand_code, merchant_name, merchant_category_code, created_at, updated_at) VALUES ('TEST_SORT_B','정렬검증','PARKING',NOW(),NOW()),('TEST_SORT_A','정렬검증','PARKING',NOW(),NOW()),('TEST_SORT_C','가나다','PARKING',NOW(),NOW())");
        var sorted = service.merchants("PARKING");
        assertThat(sorted).extracting(MerchantCatalogService.MerchantItem::brandCode).containsExactly("TEST_SORT_C", "TEST_SORT_B", "TEST_SORT_A");
        for (var table : List.of("payment_transaction", "payment_limit_usage", "monthly_limit_usage", "card_monthly_performance")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)).isZero();
        }
    }
}
