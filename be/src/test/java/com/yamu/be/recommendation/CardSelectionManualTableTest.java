package com.yamu.be.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static com.yamu.be.recommendation.CardSelectionManualTableSupport.*;

import com.fasterxml.jackson.databind.*;
import com.yamu.be.card.domain.*;
import com.yamu.be.recommendation.RecommendationInput.Transaction;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.*;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;

/** Isolated registered-policy experiment, not a payment approval integration test. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("experiment")
class CardSelectionManualTableTest extends MySqlTestSupport {
    @Container @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static final Path ROOT = Path.of("../docs/experiments/card-selection");
    final Path session = ROOT.resolve("executions").resolve("exp004-" + Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID());
    Path OUT;
    JsonNode extension;
    JsonNode scaleSpec;
    int percentage;
    long spending;
    final List<Map<String, Object>> comparison = new ArrayList<>();
    final Map<Integer, List<List<Integer>>> randomMappings = new HashMap<>();
    @Autowired DataSource source;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired UserCardRepository cards;
    @Autowired CardProductRepository products;
    @Autowired RecommendationPolicyLoader loader;
    @Autowired RecommendationInputAssembler assembler;
    @Autowired RecommendationRecognitionResolver recognition;
    @Autowired PlatformTransactionManager transactions;

    @Test void compareFrozenMonthlyPurchases() throws Exception {
        Files.createDirectories(session.getParent());
        Files.createDirectory(session); // Never overwrite an existing execution.
        Files.writeString(session.resolve("status.txt"), "STARTED\n");
        try {
            try (var in = new ClassPathResource("recommendation/card-selection-exp-002.json").getInputStream()) { extension = json.readTree(in); }
            Files.copy(Path.of("src/test/resources/recommendation/card-selection-exp-002.json"), session.resolve("frozen-extension.json"));
            Files.copy(Path.of("src/test/resources/recommendation/card-selection-exp-003.json"), session.resolve("frozen-scale-plan.json"));
            scaleSpec = json.readTree(session.resolve("frozen-scale-plan.json").toFile());
            assertThat(sha(Files.readAllBytes(session.resolve("frozen-extension.json")))).isEqualTo(scaleSpec.get("extensionInputSha256").asText());
            assertThat(scaleSpec.get("percentages").toString()).isEqualTo("[10,15,25,50,75]");
            assertThat(scaleSpec.get("cardCounts").toString()).isEqualTo("[2,3]");
            Files.copy(Path.of("src/test/resources/recommendation/card-selection-exp-004.json"), session.resolve("frozen-manual-table.json"));
            table = json.readTree(session.resolve("frozen-manual-table.json").toFile());
            for (int i = 0; i < table.get("percentages").size(); i++) {
                percentage = table.get("percentages").get(i).asInt();
                spending = 309000L * percentage / 100;
                executeExperiment("scale-" + percentage + "-cards-2", false, false);
                executeExperiment("scale-" + percentage + "-cards-3", true, true);
            }
            OUT = session;
            csv("comparison.csv", comparison);
            writeJson("comparison.json", comparison);
            Files.writeString(session.resolve("status.txt"), "PASS: 12 conditions; 1266 main runs; 76032 main card evaluations; 1254 archived runs reproduced; no uncomputable evaluations; independent manual table and boundary checks\n");
        } catch (Throwable failure) {
            Files.writeString(session.resolve("status.txt"), "FAILED: " + failure + "\nLater phases were not executed.\n");
            throw failure;
        }
    }

    private void executeExperiment(String phase, boolean threeCards, boolean extraFacts) throws Exception {
        OUT = session.resolve(phase);
        Files.createDirectory(OUT);
        byte[] bytes;
        try (var in = new ClassPathResource("recommendation/monthly-card-selection.json").getInputStream()) { bytes = in.readAllBytes(); }
        var spec = json.readTree(bytes);
        assertThat(sha(bytes)).isEqualTo(extension.get("baseInputSha256").asText());
        assertThat(sha(bytes)).isEqualTo(scaleSpec.get("baseInputSha256").asText());
        for (var day : spec.get("days")) {
            var amounts = (com.fasterxml.jackson.databind.node.ArrayNode) day.get("amounts");
            for (int i = 0; i < amounts.size(); i++) {
                long numerator = Math.multiplyExact(amounts.get(i).asLong(), percentage);
                assertThat(numerator % 100).as("No synthetic rounding").isZero();
                amounts.set(i, json.getNodeFactory().numberNode(numerator / 100));
            }
        }
        if (extraFacts) extension.get("additionalFacts").fields().forEachRemaining(e ->
                ((com.fasterxml.jackson.databind.node.ObjectNode) spec.get("purchaseFacts")).set(e.getKey(), e.getValue()));
        if (threeCards) {
            ((com.fasterxml.jackson.databind.node.ArrayNode) spec.get("cards")).add(extension.get("addedCard").asText());
            ((com.fasterxml.jackson.databind.node.ObjectNode) spec).set("categoryRule", extension.get("categoryRule"));
            ((com.fasterxml.jackson.databind.node.ObjectNode) spec).put("randomAlgorithm", extension.get("randomAlgorithm").asText());
        }
        assertThat(spec.get("categoryRule")).isEqualTo(scaleSpec.get(threeCards ? "threeCardCategoryRule" : "twoCardCategoryRule"));
        assertThat(spec.at("/randomSeeds/first").asInt()).isEqualTo(scaleSpec.at("/random/firstSeed").asInt());
        assertThat(spec.at("/randomSeeds/last").asInt()).isEqualTo(scaleSpec.at("/random/lastSeed").asInt());
        Files.write(OUT.resolve("frozen-input.json"), bytes);
        writeJson("effective-input.json", spec);
        var hashes = new TreeMap<String, String>();
        for (var root : List.of(Path.of("src/main/java/com/yamu/be/recommendation"), Path.of("src/main/resources/recommendation"),
                Path.of("src/test/java/com/yamu/be/recommendation"))) {
            try (var paths = Files.walk(root)) {
                for (var path : paths.filter(Files::isRegularFile).sorted().toList()) hashes.put(path.toString(), sha(Files.readAllBytes(path)));
            }
        }
        hashes.put("frozen-input.json", sha(bytes));
        hashes.put("effective-input.json", sha(Files.readAllBytes(OUT.resolve("effective-input.json"))));
        hashes.put("manual-table.json", sha(Files.readAllBytes(session.resolve("frozen-manual-table.json"))));
        hashes.put("extension.json", sha(Files.readAllBytes(session.resolve("frozen-extension.json"))));
        hashes.put("scale-plan.json", sha(Files.readAllBytes(session.resolve("frozen-scale-plan.json"))));
        for (var name : List.of("01-card-catalog.sql", "02-card-catalog-ab20.sql", "03-benefit-composition.sql")) {
            var path = Path.of("seed", name); hashes.put(path.toString(), sha(Files.readAllBytes(path)));
            try (var connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new EncodedResource(new FileSystemResource(path), StandardCharsets.UTF_8));
            }
        }
        writeJson("manifest.json", Map.of("createdAtUtc", Instant.now().toString(), "java", System.getProperty("java.version"),
                "hashes", hashes, "random", spec.get("randomAlgorithm").asText(), "quantile", "nearest rank ceil(p*n)",
                "scope", "In-memory independent state; registered DB policies; approval success assumed; no real payment"));
        limitCodes = new HashMap<>();
        jdbc.query("SELECT limit_group_id,group_code FROM limit_group", rs -> { limitCodes.put(rs.getLong(1), rs.getString(2)); });
        long user = new OwnershipFixtures(jdbc).user();
        var selected = new ArrayList<Card>();
        for (var name : spec.get("cards")) {
            long product = jdbc.queryForObject("SELECT card_product_id FROM card_product WHERE product_name=?", Long.class, name.asText());
            long id = new OwnershipFixtures(jdbc).card(user, product, null, "ACTIVE");
            jdbc.update("INSERT INTO monthly_limit_usage(user_card_id,card_product_id,limit_group_id,usage_month,used_value,created_at,updated_at) SELECT ?,card_product_id,limit_group_id,'2026-11-01',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM limit_group WHERE card_product_id=? AND period_type='MONTHLY'", id, product);
            selected.add(new Card(cards.findById(id).orElseThrow(), products.findById(product).orElseThrow()));
        }
        var prepared = new ArrayList<Purchase>();
        var assemblyIssues = new ArrayList<RecommendationDataIssue>();
        var facts = new HashMap<String, Boolean>();
        spec.get("purchaseFacts").fields().forEachRemaining(e -> facts.put(e.getKey(), e.getValue().asBoolean()));
        for (var day : spec.get("days")) {
            for (int slot = 0; slot < spec.get("slots").size(); slot++) {
                var settings = spec.get("slots").get(slot);
                String brand = settings.get("brand").asText();
                var merchant = jdbc.queryForMap("SELECT merchant_id,merchant_category_code FROM merchant WHERE brand_code=?", brand);
                var when = LocalDate.parse(day.get("date").asText()).atTime(LocalTime.parse(settings.get("time").asText())).atZone(SEOUL).toInstant();
                var raw = new Transaction(day.get("amounts").get(slot).asLong(), RecommendationInput.Plan.LUMP_SUM, 1, when,
                        ((Number) merchant.get("merchant_id")).longValue(), (String) merchant.get("merchant_category_code"), facts);
                var tx = BasicPurchaseSimulation.apply(raw, settings.get("channel").asText(), "GENERAL");
                var entries = new TransactionTemplate(transactions).execute(status -> {
                    var data = loader.load(user, selected.stream().map(Card::row).toList(), when.atZone(SEOUL).toLocalDate());
                    var result = new ArrayList<Prepared>();
                    for (var card : selected) {
                        loader.validateFallbacks(card.row(), data);
                        var policy = assembler.policy(card.row(), data, when.atZone(SEOUL).toLocalDate(),
                                spec.get("previousPerformance").asLong(), assemblyIssues, tx, card.product());
                        var cardTx = CheoeumPurchaseSimulation.apply(tx, card.row().getId(), card.product(), policy, brand);
                        result.add(new Prepared(card, policy, cardTx));
                    }
                    return List.copyOf(result);
                });
                prepared.add(new Purchase(prepared.size() + 1, brand, tx, entries));
            }
        }
        writeJson("prepared-policies.json", prepared.stream().map(p -> Map.of("transaction", p.number(), "brand", p.brand(),
                "cards", p.cards().stream().map(c -> Map.of("name", c.card().product().getProductName(), "policy", c.policy(), "transaction", c.transaction())).toList())).toList());
        writeJson("assembly-issues.json", assemblyIssues);
        assertThat(assemblyIssues).isEmpty();
        assertThat(prepared).hasSize(24);
        assertThat(prepared.stream().mapToLong(p -> p.transaction().amount()).sum()).isEqualTo(spending);
        assertThat(spec.get("initialCurrentPerformance").asLong()).isZero();
        long defaultId = selected.get(spec.get("defaultCardIndex").asInt()).row().getId();
        long credit = spec.get("availableCreditPerCard").asLong();
        validateTable(prepared);
        var coverage = new ArrayList<Map<String, Object>>();
        for (var p : prepared) {
            var empty = evaluate(p, new Ledger(), recognition, user, defaultId, credit);
            verifyEstimates(p, new Ledger(), empty);
            coverage.add(Map.of("transaction", p.number(), "state", "INITIAL", "evaluations", empty.result().evaluatedCards()));
            var exhausted = new Ledger();
            for (var c : p.cards()) for (var limit : c.policy().limits()) {
                if (limit.period() != BenefitPolicy.Period.PER_TRANSACTION) exhausted.used.put(exhausted.key(c.card().row().getId(), limit, c.transaction()), limit.maximum());
            }
            var zero = evaluate(p, exhausted, recognition, user, defaultId, credit);
            verifyEstimates(p, exhausted, zero);
            assertThat(chooseManual(manualEstimates(p, exhausted), 0)).isZero();
            assertThat(zero.result().evaluatedCards()).allSatisfy(e -> assertThat(e.calculation().netBenefit()).isEqualByComparingTo("0"));
            coverage.add(Map.of("transaction", p.number(), "state", "EXHAUSTED", "evaluations", zero.result().evaluatedCards()));
            // Each individual limit exhausted and almost exhausted, with all other limits unused.
            for (var c : p.cards()) for (var limit : c.policy().limits()) {
                if (limit.period() == BenefitPolicy.Period.PER_TRANSACTION) continue;
                for (long used : new long[] {Math.max(0L, limit.maximum() - 1L), limit.maximum()}) {
                    var boundary = new Ledger();
                    boundary.used.put(boundary.key(c.card().row().getId(), limit, c.transaction()), used);
                    var evaluated = evaluate(p, boundary, recognition, user, defaultId, credit);
                    verifyEstimates(p, boundary, evaluated);
                    coverage.add(Map.of("transaction", p.number(), "state", "SINGLE_LIMIT_BOUNDARY", "card", c.card().product().getProductName(),
                            "limitId", limit.id(), "used", used, "evaluations", evaluated.result().evaluatedCards()));
                }
            }
        }
        writeJson("coverage.json", coverage);
        scaledManualChecks(prepared, user, defaultId, credit);
        var categoryRule = new HashMap<String, Integer>();
        spec.get("categoryRule").fields().forEachRemaining(e -> categoryRule.put(e.getKey(), e.getValue().asInt()));
        var runs = new ArrayList<Run>();
        for (var strategy : threeCards ? List.of("FIXED_FIRST", "FIXED_TOKTOK", "FIXED_LOCA", "CATEGORY", "YAMU") : List.of("FIXED_FIRST", "FIXED_TOKTOK", "CATEGORY", "YAMU"))
            runs.add(run(strategy, -1, prepared, recognition, user, defaultId, credit, categoryRule));
        for (int seed = spec.at("/randomSeeds/first").asInt(); seed <= spec.at("/randomSeeds/last").asInt(); seed++)
            runs.add(run("RANDOM", seed, prepared, recognition, user, defaultId, credit, categoryRule));
        assertThat(runs).hasSize(threeCards ? 105 : 104);
        var replay = run("YAMU", -1, prepared, recognition, user, defaultId, credit, categoryRule);
        assertThat(replay).isEqualTo(runs.stream().filter(r -> r.strategy().equals("YAMU")).findFirst().orElseThrow());
        compareArchive(runs, threeCards);
        runs.add(run("MANUAL_TABLE", -1, prepared, recognition, user, defaultId, credit, categoryRule));
        writeManualAudit(runs);
        writeResults(runs);
        var mappings = runs.stream().filter(r -> r.strategy().equals("RANDOM")).map(r -> r.trades().stream()
                .map(t -> ((Number) t.get("selectedIndex")).intValue()).toList()).toList();
        if (randomMappings.containsKey(selected.size())) assertThat(mappings).isEqualTo(randomMappings.get(selected.size()));
        else randomMappings.put(selected.size(), mappings);
        writeLimitUtilization(runs, prepared);
        writeJson("validation.json", Map.of("initialCardEvaluations", 24 * selected.size(), "boundaryEvaluationStates", coverage.size(),
                "mainRuns", runs.size(), "mainCardEvaluations", runs.size() * 24 * selected.size(), "uncomputableMain", 0,
                "manualChecks", "PASS", "deterministicReplay", "PASS", "countLimits", prepared.get(0).cards().stream()
                        .flatMap(c -> c.policy().limits().stream()).filter(l -> l.unit() == BenefitPolicy.Unit.COUNT).count()));
        // Read-only loading and each strategy's ledger must not mutate the seed snapshot.
        for (var card : selected) assertThat(jdbc.queryForObject("SELECT SUM(used_value) FROM monthly_limit_usage WHERE user_card_id=?", Long.class,
                card.row().getId())).isZero();
    }

    private void verifyEstimates(Purchase p, Ledger ledger, Evaluation evaluation) {
        var expected = manualEstimates(p, ledger);
        for (int i = 0; i < expected.size(); i++)
            assertThat(expected.get(i).amount()).isEqualByComparingTo(evaluation.result().evaluatedCards().get(i).calculation().netBenefit());
    }

    private void validateTable(List<Purchase> purchases) throws Exception {
        for (var p : purchases) for (int i = 0; i < p.cards().size(); i++) {
            var policy = p.cards().get(i).policy();
            var row = table.get("cards").get(i);
            assertThat(policy.complete()).isTrue();
            assertThat(policy.fallbacks()).isEmpty();
            assertThat(policy.additionalCost()).isEqualByComparingTo("0");
            for (var configured : row.get("limits")) {
                var limit = policy.limits().stream().filter(l -> configured.get("code").asText().equals(limitCodes.get(l.id()))).findFirst().orElseThrow();
                assertThat(limit.maximum()).isEqualTo(configured.get("maximum").asLong());
                assertThat(limit.period().name()).isEqualTo(configured.get("period").asText());
                assertThat(limit.unit().name()).isEqualTo(row.get("unit").asText());
            }
            for (var benefit : policy.benefits()) {
                assertThat(benefit.calculationType()).isEqualTo(BenefitPolicy.CalculationType.RATE);
                assertThat(benefit.minimumPayment()).isZero();
                assertThat(benefit.minimumPerformance()).isEqualTo(row.get("minimumPerformance").asLong());
                assertThat(benefit.rounding().mode()).isEqualTo(java.math.RoundingMode.DOWN);
                assertThat(benefit.rounding().unit()).isEqualByComparingTo("1");
                assertThat(benefit.rounding().stage()).isEqualTo(BenefitPolicy.RoundingStage.PER_TRANSACTION);
                assertThat(benefit.calculationOrder()).isEqualTo(BenefitPolicy.CalculationOrder.LIMIT_THEN_ROUND);
            }
        }
        var firstUsage = Map.of("TODAY_DAILY", 999L, "TODAY_MONTHLY", 0L);
        var one = tableEstimate(0, "STARBUCKS", 2000, 400000L, firstUsage);
        var thousand = tableEstimate(1, "STARBUCKS", 2000, 400000L, Map.of("STARBUCKS", 0L));
        assertThat(chooseManual(List.of(one, thousand), 0)).isEqualTo(1);
        assertThat(chooseManual(List.of(thousand, thousand), 0)).isZero();
        assertThat(tableEstimate(0, "STARBUCKS", 2001, 400000L, Map.of("TODAY_DAILY", 0L, "TODAY_MONTHLY", 0L)).amount()).isEqualByComparingTo("100");
        assertThat(tableEstimate(0, "STARBUCKS", 2000, 299999L, firstUsage).amount()).isEqualByComparingTo("0");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tableEstimate(0, "UNKNOWN", 2000, 400000L, firstUsage)).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tableEstimate(0, "STARBUCKS", 2000, null, firstUsage)).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tableEstimate(0, "STARBUCKS", 2000, 400000L, Map.of())).isInstanceOf(IllegalArgumentException.class);
        writeJson("table-validation.json", Map.of("status", "PASS", "partial1Versus1000", "second card", "tie", "default", "missingInformation", "throws", "minimumPayment", "registered zero; positive threshold not applicable", "scope", "frozen 24 purchases and existing facts; initial and cap boundary values checked separately"));
    }

    private void compareArchive(List<Run> runs, boolean threeCards) throws Exception {
        Path archive = percentage == 100
                ? threeCards ? ROOT.resolve("executions/run-2026-11-12T15-34-58.098810900Z-9db10134-5b83-4b4c-9881-6ff7b6a1b619/exp-002-three-cards/raw-records.zip")
                    : ROOT.resolve("exp-001-two-cards/original-results.zip")
                : ROOT.resolve("executions/exp003-2026-11-12T15-53-49.629195800Z-c0f56cb2-e64a-4426-b76d-67b3fe9becb0/scale-" + percentage + "-cards-" + (threeCards ? 3 : 2) + "/raw-records.zip");
        try (var zip = new java.util.zip.ZipFile(archive.toFile())) {
            var entry = zip.stream().filter(e -> e.getName().endsWith("runs.json")).findFirst().orElseThrow();
            try (var in = zip.getInputStream(entry)) { assertThat(semantic(json.valueToTree(runs))).isEqualTo(semantic(json.readTree(in))); }
        }
        writeJson("archive-reproduction.json", Map.of("status", "PASS", "runs", runs.size(), "archive", archive.toString(), "sha256", sha(Files.readAllBytes(archive)), "comparison", "all economic records; generated identities and numeric scales normalized"));
    }

    private void writeManualAudit(List<Run> runs) throws Exception {
        var manual = runs.stream().filter(r -> r.strategy().equals("MANUAL_TABLE")).findFirst().orElseThrow();
        var yamu = runs.stream().filter(r -> r.strategy().equals("YAMU")).findFirst().orElseThrow();
        var candidates = new ArrayList<Map<String, Object>>();
        var differences = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < manual.trades().size(); i++) {
            var m = manual.trades().get(i); var y = yamu.trades().get(i);
            for (var candidate : json.valueToTree(m.get("manualCandidateAudit"))) {
                Map<String, Object> row = json.convertValue(candidate, new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>() {});
                row.put("transaction", i + 1); row.put("selectedCard", m.get("selectedCard")); row.put("selectionReason", m.get("selectionReason")); candidates.add(row);
            }
            var row = new LinkedHashMap<String,Object>(); row.put("transaction", i + 1);
            row.put("manualCard", m.get("selectedCard")); row.put("yamuCard", y.get("selectedCard"));
            row.put("manualActual", m.get("benefitKrw")); row.put("yamuActual", y.get("benefitKrw"));
            row.put("yamuMinusManual", new BigDecimal(y.get("benefitKrw").toString()).subtract(new BigDecimal(m.get("benefitKrw").toString())));
            row.put("manualDetail", m); row.put("yamuDetail", y); differences.add(row);
        }
        csv("manual-estimates.csv", candidates); csv("manual-vs-yamu.csv", differences);
        writeJson("manual-audit.json", Map.of("candidateCount", candidates.size(), "nonzeroEstimateErrors", candidates.stream().filter(r -> new BigDecimal(r.get("differenceActualMinusExpected").toString()).signum() != 0).count(),
                "differentChoices", differences.stream().filter(r -> !r.get("manualCard").equals(r.get("yamuCard"))).count(), "yamuMinusManual", yamu.total().subtract(manual.total())));
    }

    private void writeResults(List<Run> runs) throws Exception {
        BigDecimal yamu = runs.stream().filter(r -> r.strategy().equals("YAMU")).findFirst().orElseThrow().total();
        var summary = new ArrayList<Map<String, Object>>();
        for (var r : runs) {
            var row = new LinkedHashMap<String, Object>();
            row.put("strategy", r.strategy()); row.put("seed", r.seed()); row.put("totalKrw", r.total());
            row.put("differenceFromYamu", r.total().subtract(yamu)); row.put("currentPerformanceByCardId", r.performance());
            row.put("benefitPerSpending", r.total().divide(BigDecimal.valueOf(spending), 8, java.math.RoundingMode.HALF_UP));
            summary.add(row);
        }
        csv("summary.csv", summary);
        csv("transactions.csv", runs.stream().flatMap(r -> r.trades().stream()).toList());
        csv("limits.csv", runs.stream().flatMap(r -> r.limits().stream()).toList());
        writeJson("runs.json", runs);
        double[] samples = runs.stream().filter(r -> r.strategy().equals("RANDOM")).mapToDouble(r -> r.total().doubleValue()).sorted().toArray();
        double mean = Arrays.stream(samples).average().orElseThrow();
        double sd = Math.sqrt(Arrays.stream(samples).map(x -> (x - mean) * (x - mean)).sum() / samples.length);
        var distribution = new TreeMap<Double, Long>();
        for (double value : samples) distribution.merge(value, 1L, Long::sum);
        csv("random-distribution.csv", distribution.entrySet().stream().map(e -> {
            var row = new LinkedHashMap<String, Object>(); row.put("totalKrw", e.getKey()); row.put("count", e.getValue()); return (Map<String, Object>) row;
        }).toList());
        var stats = new LinkedHashMap<String, Object>();
        stats.put("mean", mean); stats.put("populationStdDev", sd); stats.put("min", samples[0]); stats.put("p05", samples[4]);
        stats.put("median", (samples[49] + samples[50]) / 2); stats.put("p95", samples[94]); stats.put("max", samples[99]);
        stats.put("lessThanYamu", Arrays.stream(samples).filter(x -> x < yamu.doubleValue()).count());
        stats.put("equalToYamu", Arrays.stream(samples).filter(x -> x == yamu.doubleValue()).count());
        stats.put("greaterThanYamu", Arrays.stream(samples).filter(x -> x > yamu.doubleValue()).count());
        writeJson("random-statistics.json", stats);
        var c = new LinkedHashMap<String, Object>();
        c.put("percentage", percentage); c.put("cardCount", runs.size() == 106 ? 3 : 2); c.put("spending", spending);
        for (String strategy : List.of("FIXED_FIRST", "FIXED_TOKTOK", "FIXED_LOCA", "CATEGORY", "YAMU", "MANUAL_TABLE"))
            c.put(strategy, runs.stream().filter(r -> r.strategy().equals(strategy)).map(Run::total).findFirst().orElse(null));
        c.put("randomMean", mean); c.put("randomStdDev", sd); c.put("randomP05", samples[4]); c.put("randomP95", samples[94]);
        c.put("yamuMinusCategory", yamu.subtract(runs.stream().filter(r -> r.strategy().equals("CATEGORY")).findFirst().orElseThrow().total()));
        c.put("yamuMinusRandomMean", yamu.doubleValue() - mean); comparison.add(c);
        var report = new StringBuilder("# EXP-004 혜택표 수동 선택 비교\n\n실행 구분: " + OUT.getFileName() + "\n\n합성 구매 24건·" + spending + "원. 2026년 11월, 전월 실적 각 400,000원.\n\n");
        report.append("| 전략 | 총 원화 환산 혜택 | 전략−YAMU |\n|---|---:|---:|\n");
        for (var r : runs) if (!r.strategy().equals("RANDOM")) report.append("| ").append(r.strategy()).append(" | ").append(r.total()).append(" | ").append(r.total().subtract(yamu)).append(" |\n");
        report.append(String.format(Locale.ROOT, "| RANDOM 평균 | %.2f | %.2f |\n\n", mean, mean - yamu.doubleValue()));
        report.append("랜덤: java.util.Random, 고정 시드 0~99. 표준편차는 이 100회 결과의 모집단 표준편차. 백분위는 nearest rank.\n\n");
        report.append(stats).append("\n\n");
        report.append("초기·전체 소진·개별 한도 부분/완전 소진 검사 통과. 본 실행 계산 불가 0건. 검사 수는 validation.json 참고. 대표 수식 검산 통과. YAMU 재실행 결과 동일.\n\n");
        report.append("할인과 포인트는 분리된 보상이며 처음의 등록 환산값 1P=1원으로만 합산한다. 승인·청구·현금 지급 검증은 아니다. 신규 유예 없음·일반 직접 구매의 합성 사실을 명시했다. 선택하지 않은 카드의 상태는 갱신하지 않는다.\n\n");
        report.append("업종 규칙은 effective-input.json에 사전 고정했다. 한도 소진은 limits.csv, 선택별 차이는 transactions.csv에서 확인한다. 승리 여부는 테스트 조건이 아니다. 카드·합성 소비에 한정하며 실제 사용자 평균이나 월간 최적 보장을 뜻하지 않는다.\n\n");
        report.append("재현: be에서 `./gradlew.bat test --tests com.yamu.be.recommendation.CardSelectionManualTableTest --rerun-tasks` 실행. Docker 필요. skipped는 성공이 아니다. 상위 status.txt와 JUnit 실행 건수를 확인한다. manifest.json은 미커밋 변경 포함 코드·정책·입력 SHA-256이다. 원본 및 다른 실행을 덮어쓰지 않는다.\n");
        Files.writeString(OUT.resolve("report.md"), report);
    }

    private void scaledManualChecks(List<Purchase> purchases, long user, long defaultId, long credit) throws Exception {
        var checks = new ArrayList<Map<String, Object>>();
        for (int transaction : List.of(0, 1)) {
            var p = purchases.get(transaction);
            var evaluated = evaluate(p, new Ledger(), recognition, user, defaultId, credit);
            for (int card = 0; card < p.cards().size(); card++) {
                // Independent arithmetic for the first ordinary restaurant/Starbucks purchases.
                long expected = card == 0 ? Math.min(500L, p.transaction().amount() * 5 / 100)
                        : card == 1 ? (transaction == 1 ? p.transaction().amount() / 2 : 0L)
                        : p.transaction().amount() * 60 / 100;
                var actual = evaluated.result().evaluatedCards().get(card).calculation().netBenefit();
                assertThat(actual).isEqualByComparingTo(BigDecimal.valueOf(expected));
                long recognized = (card == 1 || card == 2) && expected > 0 ? 0 : p.transaction().amount();
                assertThat(evaluated.candidates().get(card).performance().recognizedAmount()).isEqualTo(recognized);
                checks.add(Map.of("transaction", p.number(), "card", p.cards().get(card).card().product().getProductName(),
                        "amount", p.transaction().amount(), "expected", expected, "actual", actual, "recognized", recognized));
            }
        }
        var food = purchases.get(0);
        var first = food.cards().get(0);
        var day = first.policy().limits().stream().filter(l -> l.period() == BenefitPolicy.Period.DAILY).findFirst().orElseThrow();
        var ledger = new Ledger();
        ledger.used.put(ledger.key(first.card().row().getId(), day, food.transaction()), day.maximum() - 1);
        assertThat(evaluate(purchases.get(1), ledger, recognition, user, defaultId, credit).result().evaluatedCards().get(0).calculation().netBenefit()).isEqualByComparingTo("1");
        var next = purchases.get(4);
        assertThat(evaluate(next, ledger, recognition, user, defaultId, credit).result().evaluatedCards().get(0).calculation().netBenefit())
                .isEqualByComparingTo(BigDecimal.valueOf(Math.min(500L, next.transaction().amount() * 5 / 100)));
        writeJson("manual-checks.json", checks);
        Files.writeString(OUT.resolve("manual-checks.md"), "# 대표 거래 검산 — PASS\n\n"
                + "첫 음식점·스타벅스의 모든 카드: 처음 floor(min(금액×5%,500)), 톡톡 스타벅스 금액×50%/음식점 0, LOCA 금액×60%. 구체적인 입력·기대·실제값은 manual-checks.json.\n\n"
                + "할인 거래 실적 제외·처음 적립 거래 실적 인정 검증. 처음 일 잔여 한도 1이면 1P, 다음 결제일에는 일 한도 초기화 검증.\n");
    }

    private void writeLimitUtilization(List<Run> runs, List<Purchase> purchases) throws Exception {
        var rows = new ArrayList<Map<String, Object>>();
        for (var run : runs) for (var c : purchases.get(0).cards()) for (var limit : c.policy().limits()) {
            if (limit.period() == BenefitPolicy.Period.PER_TRANSACTION) continue;
            var periods = new TreeSet<String>();
            for (var p : purchases) periods.add(new Ledger().key(c.card().row().getId(), limit, p.transaction()).period());
            for (var period : periods) {
                var matching = run.limits().stream().filter(r -> r.get("card").equals(c.card().product().getProductName())
                        && ((Number) r.get("limitId")).longValue() == limit.id() && r.get("period").equals(period)).toList();
                long used = matching.stream().mapToLong(r -> ((Number) r.get("usedAfter")).longValue()).max().orElse(0L);
                long consumed = matching.stream().map(r -> new BigDecimal(r.get("consumed").toString())).reduce(BigDecimal.ZERO, BigDecimal::add).longValueExact();
                assertThat(used).as("ledger conservation").isEqualTo(consumed);
                boolean exhausted = used == limit.maximum();
                if (percentage <= 15 && limit.period() == BenefitPolicy.Period.MONTHLY)
                    assertThat(exhausted).as("All strategies must remain below monthly caps at %s%%", percentage).isFalse();
                var row = new LinkedHashMap<String, Object>();
                row.put("percentage", percentage); row.put("cardCount", purchases.get(0).cards().size());
                row.put("strategy", run.strategy()); row.put("seed", run.seed()); row.put("card", c.card().product().getProductName());
                row.put("limitCode", jdbc.queryForObject("SELECT group_code FROM limit_group WHERE limit_group_id=?", String.class, limit.id()));
                row.put("unit", limit.unit()); row.put("periodType", limit.period()); row.put("period", period);
                row.put("maximum", limit.maximum()); row.put("used", used);
                row.put("usageRate", BigDecimal.valueOf(used).divide(BigDecimal.valueOf(limit.maximum()), 8, java.math.RoundingMode.HALF_UP));
                row.put("exhausted", exhausted);
                row.put("firstExhaustedTransaction", matching.stream().filter(r -> ((Number) r.get("usedAfter")).longValue() == limit.maximum())
                        .map(r -> r.get("transaction")).findFirst().orElse(""));
                rows.add(row);
            }
        }
        csv("limit-utilization.csv", rows);
    }

    private JsonNode semantic(JsonNode node) {
        if (node.isNumber()) return json.getNodeFactory().textNode(node.decimalValue().stripTrailingZeros().toPlainString());
        if (node.isArray()) {
            var result = json.createArrayNode(); node.forEach(n -> result.add(semantic(n))); return result;
        }
        if (node.isObject()) {
            var result = json.createObjectNode();
            node.fields().forEachRemaining(e -> {
                if (Set.of("userCardId", "benefitId", "limitId", "selectedIndex").contains(e.getKey())) return;
                if (Set.of("performance", "spent").contains(e.getKey())) {
                    var keys = new ArrayList<String>(); e.getValue().fieldNames().forEachRemaining(keys::add);
                    keys.sort(Comparator.comparingLong(Long::parseLong));
                    var values = json.createArrayNode(); keys.forEach(k -> values.add(semantic(e.getValue().get(k))));
                    result.set(e.getKey(), values);
                } else result.set(e.getKey(), semantic(e.getValue()));
            });
            return result;
        }
        return node;
    }

    private void writeJson(String name, Object value) throws Exception { json.writerWithDefaultPrettyPrinter().writeValue(OUT.resolve(name).toFile(), value); }
    private void csv(String name, List<Map<String, Object>> rows) throws Exception {
        var lines = new ArrayList<String>(); var columns = new ArrayList<>(rows.get(0).keySet());
        lines.add(String.join(",", columns));
        for (var row : rows) {
            var cells = new ArrayList<String>();
            for (var column : columns) {
                Object v = row.get(column);
                String s = v instanceof Collection<?> || v instanceof Map<?, ?> ? json.writeValueAsString(v) : String.valueOf(v);
                cells.add("\"" + s.replace("\"", "\"\"") + "\"");
            }
            lines.add(String.join(",", cells));
        }
        Files.write(OUT.resolve(name), lines);
    }
    private static String sha(byte[] value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
}
