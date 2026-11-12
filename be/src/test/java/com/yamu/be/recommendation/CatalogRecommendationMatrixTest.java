package com.yamu.be.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.support.OwnershipFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Actual catalog diagnostic: no invented transaction facts or completed policy fixtures. */
class CatalogRecommendationMatrixTest extends SimulationSeedRecommendationDatabaseTest {
    @Autowired RecommendationService service;

    @Test void reportsCatalogCoverageAcrossOrdinaryPurchases() throws Exception {
        var lines = new ArrayList<String>();
        lines.add("product\tprevious\tbrand\tamount\tstatus\tbenefit\tproblems\tmissingFacts");
        com.fasterxml.jackson.databind.JsonNode catalog;
        try (var input = new org.springframework.core.io.ClassPathResource(
                "recommendation/simulation-catalog.json").getInputStream()) {
            catalog = json.readTree(input);
        }
        int count = 0;
        for (long previous : List.of(0L, 299_999L, 1_000_000L)) {
            long user = new OwnershipFixtures(jdbc).user();
            var names = new LinkedHashMap<Long, String>();
            for (var entry : catalog) {
                String name = entry.get("product").asText();
                names.put(card(user, product(name), previous, 0), name);
            }
            for (String brand : List.of("GS25", "STARBUCKS", "YAMU_TEST_RESTAURANT", "COUPANG", "EMART")) {
                var merchant = jdbc.queryForMap("SELECT merchant_id,merchant_category_code FROM merchant WHERE brand_code=?", brand);
                for (long amount : List.of(1_000L, 10_000L, 100_000L)) {
                    var tx = new RecommendationInput.Transaction(amount, RecommendationInput.Plan.LUMP_SUM, 1,
                            Instant.parse("2026-11-12T03:00:00Z"), ((Number)merchant.get("merchant_id")).longValue(),
                            (String)merchant.get("merchant_category_code"), Map.of());
                    tx = BasicPurchaseSimulation.apply(tx, brand.equals("COUPANG") ? "ONLINE" : "OFFLINE", "GENERAL");
                    var result = service.recommend(new RecommendationCommand(100L, user, tx, Map.of()));
                    assertThat(result.recommendation().evaluatedCards()).hasSize(names.size());
                    for (var evaluated : result.recommendation().evaluatedCards()) {
                        var calculation = evaluated.calculation();
                        assertThat(evaluated.status()).as(names.get(evaluated.userCardId()) + "/" + brand + "/" + previous + "/" + amount)
                                .isEqualTo(RecommendationSelector.CardStatus.CALCULATED);
                        String productName = names.get(evaluated.userCardId());
                        if (productName.equals("현대카드 ZERO Edition3 (할인형)"))
                            assertThat(calculation.netBenefit()).isEqualByComparingTo(Long.toString(amount * 8 / 1000));
                        if (productName.equals("현대카드ZERO Edition3(포인트형)"))
                            assertThat(calculation.netBenefit()).isEqualByComparingTo(Long.toString(amount * 12 / 1000));
                        if (productName.equals("신한카드 처음") && brand.equals("COUPANG"))
                            assertThat(calculation.netBenefit()).isEqualByComparingTo(Long.toString(
                                    previous < 300000 ? 0 : Math.min(amount * 5 / 100, 500)));
                        var missing = new TreeSet<String>();
                        if (calculation != null) {
                            for (var problem : calculation.problems()) {
                                if (problem.benefitId() == null) continue;
                                for (String fact : jdbc.queryForList("SELECT target_value FROM benefit_target WHERE card_benefit_id=? AND target_type='CONDITION'",
                                        String.class, problem.benefitId())) {
                                    if (!fact.startsWith("INSTALLMENT:") && !tx.facts().containsKey(fact)) missing.add(fact);
                                }
                            }
                        }
                        lines.add(names.get(evaluated.userCardId()) + "\t" + previous + "\t" + brand + "\t" + amount
                                + "\t" + evaluated.status() + "\t" + (calculation == null ? "" : calculation.netBenefit())
                                + "\t" + (calculation == null ? evaluated.reason() : calculation.problems()) + "\t" + missing);
                        count++;
                    }
                }
            }
        }
        var report = Path.of("build", "reports", "recommendation-matrix.tsv");
        Files.createDirectories(report.getParent());
        Files.write(report, lines);
        assertThat(count).isEqualTo(catalog.size() * 45);
    }
}
