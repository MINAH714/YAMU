package com.yamu.be.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.support.OwnershipFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 실제 시드와 일반 모의 구매 기본값으로 765개 조합을 검증하고 TSV를 생성한다. */
class RecommendationZeroAuditTest extends SimulationSeedRecommendationDatabaseTest {
    @Autowired RecommendationService service;

    @Test void reportsCatalogCoverageAcrossOrdinaryPurchases() throws Exception {
        var lines = new ArrayList<String>();
        lines.add("product\tprevious\tbrand\tamount\tstatus\tbenefit\tproblems\tmissingFacts\tbenefitDetails");
        com.fasterxml.jackson.databind.JsonNode catalog;
        try (var input = new org.springframework.core.io.ClassPathResource(
                "recommendation/simulation-catalog.json").getInputStream()) {
            catalog = json.readTree(input);
        }
        int count = 0;
        int expectedCount = 0;
        int positiveCount = 0;
        int zeroCount = 0;
        var uncalculated = new ArrayList<String>();
        assertThat(catalog.size()).as("승인된 모의 카드 수").isEqualTo(17);
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
                                + "\t" + (calculation == null ? evaluated.reason() : calculation.problems()) + "\t" + missing + "\t" + (calculation == null ? "" : calculation.benefits().stream().map(b -> b.name() + ":" + b.krwValue() + ":" + b.reason()).toList()));
                        String name = names.get(evaluated.userCardId());
                        if (evaluated.status() != RecommendationSelector.CardStatus.CALCULATED) {
                            uncalculated.add(name + "/" + previous + "/" + brand + "/" + amount);
                        } else if (calculation.netBenefit().signum() > 0) {
                            positiveCount++;
                        } else if (calculation.netBenefit().signum() == 0) {
                            zeroCount++;
                        }
                        Long expected = null;
                        if (name.equals("LOCA LIKIT 2.0")) expected = amount * 12 / 1000;
                        if (name.equals("카카오뱅크 프렌즈 체크")) expected = amount * 2 / 1000;
                        if (name.equals("하나카드 트래블로그 체크카드")) expected = amount * 3 / 1000;
                        if (name.equals("현대카드 ZERO Edition3 (할인형)")) expected = amount * 8 / 1000;
                        if (name.equals("현대카드ZERO Edition3(포인트형)")) expected = amount * 12 / 1000;
                        if (name.equals("신한카드 처음"))
                            expected = previous < 300000 || brand.equals("EMART") ? 0L : Math.min(amount * 5 / 100, 500L);
                        if (name.equals("신한카드 Mr.Life") && brand.equals("GS25"))
                            expected = previous < 300000 ? 0L : Math.min(amount / 10, 1000L);
                        if (brand.equals("STARBUCKS") && amount == 10000) {
                            if (name.equals("KB국민 노리 체크")) expected = previous < 200000 ? 0L : 2000L;
                            if (name.equals("KB국민 노리2 체크카드(KB Pay)")) expected = 1000L;
                            if (name.equals("KB국민 청춘대로 톡톡카드")) expected = previous < 300000 ? 0L : 5000L;
                            if (name.equals("LOCA LIKIT Eat")) expected = previous < 400000 ? 0L : 6000L;
                        }
                        if (expected != null) {
                            expectedCount++;
                            assertThat(evaluated.status()).as(name + "/" + previous + "/" + brand + "/" + amount)
                                    .isEqualTo(RecommendationSelector.CardStatus.CALCULATED);
                            assertThat(calculation.netBenefit()).as(name + "/" + previous + "/" + brand + "/" + amount)
                                    .isEqualByComparingTo(expected.toString());
                        }
                        count++;
                    }
                }
            }
        }
        var report = Path.of("build", "reports", "recommendation-zero-coupang-audit.tsv");
        Files.createDirectories(report.getParent());
        Files.write(report, lines);
        assertThat(count).isEqualTo(765);
        assertThat(uncalculated).as("계산 불가 조합").isEmpty();
        assertThat(positiveCount).isEqualTo(293);
        assertThat(zeroCount).isEqualTo(472);
        assertThat(expectedCount).isEqualTo(291);
    }
}
