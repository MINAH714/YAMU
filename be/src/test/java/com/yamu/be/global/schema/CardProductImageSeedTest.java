package com.yamu.be.global.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.support.MySqlTestSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/** Image data contract tests against the real Flyway schema, including MR !75's image column. */
@TestPropertySource(properties = "spring.application.name=yamu-card-product-image-seed-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CardProductImageSeedTest extends MySqlTestSupport {

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    private List<JsonNode> cards;
    private long nextId;

    @BeforeEach
    void prepareIsolatedCatalog() throws Exception {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='card_product' AND column_name='image_key'
                """, Integer.class)).isEqualTo(1);
        jdbc.update("DELETE FROM card_product");
        cards = new ArrayList<>();
        new ObjectMapper().readTree(Path.of("seed/card-images/card-images.json").toFile())
                .path("cards").forEach(cards::add);
        nextId = 1000; // Deliberately unrelated to collection IDs or real product IDs.
    }

    @Test
    void mapsSeventeenExistingProductsAndPreservesOtherColumns() throws Exception {
        cards.stream().filter(this::applies).forEach(this::insert);
        var before = withoutImages();

        apply();

        assertThat(withoutImages()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_product WHERE image_key IS NOT NULL", Integer.class))
                .isEqualTo(17);
        for (JsonNode card : cards.stream().filter(this::applies).toList()) {
            assertThat(jdbc.queryForObject("""
                    SELECT image_key FROM card_product WHERE issuer_code=? AND product_name=? AND card_type=?
                    """, String.class, text(card, "issuer_code"), text(card, "product_name"), text(card, "card_type")))
                    .isEqualTo(text(card, "image_key"));
        }
        String samsung = jdbc.queryForObject("SELECT image_key FROM card_product WHERE product_name='삼성 iD SIMPLE'", String.class);
        assertThat(samsung).contains("/static-front/").endsWith(".png");
    }

    @Test
    void threeCardCatalogDoesNotCreateOtherProductsOrChangeHeldProducts() throws Exception {
        cards.stream().filter(card -> "01".equals(text(card, "catalog")) || !applies(card)).forEach(this::insert);
        apply();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_product", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_product WHERE image_key IS NOT NULL", Integer.class))
                .isEqualTo(3);
        for (JsonNode card : cards.stream().filter(card -> !applies(card)).toList()) {
            assertThat(jdbc.queryForObject("SELECT image_key FROM card_product WHERE product_name=?", String.class,
                    text(card, "product_name"))).isNull();
        }
    }

    @Test
    void rerunDoesNotChangeRowsAndConflictingOrEmptyKeysArePreserved() throws Exception {
        var applyCards = cards.stream().filter(this::applies).toList();
        applyCards.forEach(this::insert);
        jdbc.update("UPDATE card_product SET image_key='cards/manual/existing.png' WHERE product_name=?",
                text(applyCards.get(0), "product_name"));
        jdbc.update("UPDATE card_product SET image_key='' WHERE product_name=?", text(applyCards.get(1), "product_name"));
        apply();
        var afterFirst = jdbc.queryForList("SELECT * FROM card_product ORDER BY card_product_id");
        assertThat(jdbc.queryForObject("SELECT image_key FROM card_product WHERE product_name=?", String.class,
                text(applyCards.get(0), "product_name"))).isEqualTo("cards/manual/existing.png");
        assertThat(jdbc.queryForObject("SELECT image_key FROM card_product WHERE product_name=?", String.class,
                text(applyCards.get(1), "product_name"))).isEmpty();
        apply();
        assertThat(jdbc.queryForList("SELECT * FROM card_product ORDER BY card_product_id")).isEqualTo(afterFirst);
    }

    @Test
    void sameNameWithDifferentIssuerOrCardTypeDoesNotMatch() throws Exception {
        JsonNode card = cards.stream().filter(this::applies).findFirst().orElseThrow();
        insert(card);
        jdbc.update("UPDATE card_product SET issuer_code='OTHER' WHERE product_name=?", text(card, "product_name"));
        insert(card);
        jdbc.update("UPDATE card_product SET card_type=? WHERE issuer_code=?",
                "CREDIT".equals(text(card, "card_type")) ? "CHECK" : "CREDIT", text(card, "issuer_code"));
        apply();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_product WHERE image_key IS NOT NULL", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_product", Integer.class)).isEqualTo(2);
    }

    private boolean applies(JsonNode card) {
        return "APPLY".equals(text(card, "state"));
    }

    private static String text(JsonNode card, String field) {
        return card.path(field).asText();
    }

    private void insert(JsonNode card) {
        jdbc.update("""
                INSERT INTO card_product (card_product_id, issuer_code, product_name, card_type, terms_url,
                    created_at, updated_at) VALUES (?, ?, ?, ?, 'https://example.com/terms',
                    '2026-10-01 00:00:00', '2026-10-01 00:00:00')
                """, nextId++, text(card, "issuer_code"), text(card, "product_name"), text(card, "card_type"));
    }

    private List<Map<String, Object>> withoutImages() {
        return jdbc.queryForList("SELECT * FROM card_product ORDER BY card_product_id").stream().map(row -> {
            Map<String, Object> values = new LinkedHashMap<>(row);
            values.remove("image_key");
            return values;
        }).toList();
    }

    private void apply() throws Exception {
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new FileSystemResource(Path.of("seed/03-card-product-images.sql")), StandardCharsets.UTF_8));
        }
    }
}
