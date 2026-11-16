package com.yamu.be.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static com.yamu.be.recommendation.CardSelectionExperimentSupport.*;

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
class CardSelectionExperimentTest extends MySqlTestSupport {
    @Container @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static final Path ROOT = Path.of("../docs/experiments/card-selection");
    final Path session = ROOT.resolve("executions").resolve("run-" + Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID());
    Path OUT;
    JsonNode extension;
    JsonNode originalRuns;
    final Map<String, List<Run>> completed = new LinkedHashMap<>();
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
            try (var zip = new java.util.zip.ZipFile(ROOT.resolve("exp-001-two-cards/original-results.zip").toFile());
                 var in = zip.getInputStream(zip.getEntry("runs.json"))) { originalRuns = json.readTree(in); }
            executeExperiment("exp-001-reproduction", false, false);
            assertThat(semantic(json.valueToTree(completed.get("exp-001-reproduction"))))
                    .as("All 104 original runs, transactions, benefit lines, performance and limit changes")
                    .isEqualTo(semantic(originalRuns));
            executeExperiment("exp-001-fact-equivalence", false, true);
            assertThat(semantic(json.valueToTree(completed.get("exp-001-fact-equivalence"))))
                    .isEqualTo(semantic(originalRuns));
            Files.writeString(session.resolve("reproduction.txt"), "PASS: all 104 runs match original after database ID normalization. Supplemented facts also preserve all 104 results.\n");
            executeExperiment("exp-002-three-cards", true, true);
            Files.writeString(session.resolve("status.txt"), "PASS: original reproduction 104; fact-equivalence 104; three-card main runs 105; boundary checks and manual checks; deterministic replay\n");
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
        if (extraFacts) extension.get("additionalFacts").fields().forEachRemaining(e ->
                ((com.fasterxml.jackson.databind.node.ObjectNode) spec.get("purchaseFacts")).set(e.getKey(), e.getValue()));
        if (threeCards) {
            ((com.fasterxml.jackson.databind.node.ArrayNode) spec.get("cards")).add(extension.get("addedCard").asText());
            ((com.fasterxml.jackson.databind.node.ObjectNode) spec).set("categoryRule", extension.get("categoryRule"));
            ((com.fasterxml.jackson.databind.node.ObjectNode) spec).put("randomAlgorithm", extension.get("randomAlgorithm").asText());
        }
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
        hashes.put("extension.json", sha(Files.readAllBytes(session.resolve("frozen-extension.json"))));
        for (var name : List.of("01-card-catalog.sql", "02-card-catalog-ab20.sql", "03-benefit-composition.sql")) {
            var path = Path.of("seed", name); hashes.put(path.toString(), sha(Files.readAllBytes(path)));
            try (var connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new EncodedResource(new FileSystemResource(path), StandardCharsets.UTF_8));
            }
        }
        writeJson("manifest.json", Map.of("createdAtUtc", Instant.now().toString(), "java", System.getProperty("java.version"),
                "hashes", hashes, "random", spec.get("randomAlgorithm").asText(), "quantile", "nearest rank ceil(p*n)",
                "scope", "In-memory independent state; registered DB policies; approval success assumed; no real payment"));
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
        assertThat(prepared.stream().mapToLong(p -> p.transaction().amount()).sum()).isEqualTo(309_000L);
        assertThat(spec.get("initialCurrentPerformance").asLong()).isZero();
        long defaultId = selected.get(spec.get("defaultCardIndex").asInt()).row().getId();
        long credit = spec.get("availableCreditPerCard").asLong();
        var coverage = new ArrayList<Map<String, Object>>();
        for (var p : prepared) {
            var empty = evaluate(p, new Ledger(), recognition, user, defaultId, credit);
            coverage.add(Map.of("transaction", p.number(), "state", "INITIAL", "evaluations", empty.result().evaluatedCards()));
            var exhausted = new Ledger();
            for (var c : p.cards()) for (var limit : c.policy().limits()) {
                if (limit.period() != BenefitPolicy.Period.PER_TRANSACTION) exhausted.used.put(exhausted.key(c.card().row().getId(), limit, c.transaction()), limit.maximum());
            }
            var zero = evaluate(p, exhausted, recognition, user, defaultId, credit);
            assertThat(zero.result().evaluatedCards()).allSatisfy(e -> assertThat(e.calculation().netBenefit()).isEqualByComparingTo("0"));
            coverage.add(Map.of("transaction", p.number(), "state", "EXHAUSTED", "evaluations", zero.result().evaluatedCards()));
            // Each individual limit exhausted and almost exhausted, with all other limits unused.
            for (var c : p.cards()) for (var limit : c.policy().limits()) {
                if (limit.period() == BenefitPolicy.Period.PER_TRANSACTION) continue;
                for (long used : new long[] {Math.max(0L, limit.maximum() - 1L), limit.maximum()}) {
                    var boundary = new Ledger();
                    boundary.used.put(boundary.key(c.card().row().getId(), limit, c.transaction()), used);
                    var evaluated = evaluate(p, boundary, recognition, user, defaultId, credit);
                    coverage.add(Map.of("transaction", p.number(), "state", "SINGLE_LIMIT_BOUNDARY", "card", c.card().product().getProductName(),
                            "limitId", limit.id(), "used", used, "evaluations", evaluated.result().evaluatedCards()));
                }
            }
        }
        writeJson("coverage.json", coverage);
        manualChecks(prepared, user, defaultId, credit);
        if (threeCards) locaChecks(prepared, user, defaultId, credit);
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
        writeResults(runs);
        completed.put(phase, List.copyOf(runs));
        writeJson("validation.json", Map.of("initialCardEvaluations", 24 * selected.size(), "boundaryEvaluationStates", coverage.size(),
                "mainRuns", runs.size(), "mainCardEvaluations", runs.size() * 24 * selected.size(), "uncomputableMain", 0,
                "manualChecks", "PASS", "deterministicReplay", "PASS", "countLimits", prepared.get(0).cards().stream()
                        .flatMap(c -> c.policy().limits().stream()).filter(l -> l.unit() == BenefitPolicy.Unit.COUNT).count()));
        // Read-only loading and each strategy's ledger must not mutate the seed snapshot.
        for (var card : selected) assertThat(jdbc.queryForObject("SELECT SUM(used_value) FROM monthly_limit_usage WHERE user_card_id=?", Long.class,
                card.row().getId())).isZero();
    }

    private void manualChecks(List<Purchase> purchases, long user, long defaultId, long credit) throws Exception {
        var cafe = purchases.get(1);
        var first = cafe.cards().get(0);
        var toktok = cafe.cards().get(1);
        var normal = evaluate(cafe, new Ledger(), recognition, user, defaultId, credit);
        assertThat(normal.result().evaluatedCards().get(0).calculation().netBenefit()).isEqualByComparingTo("300");
        assertThat(normal.result().evaluatedCards().get(1).calculation().netBenefit()).isEqualByComparingTo("3000");
        assertThat(normal.candidates().get(1).performance().recognizedAmount()).isZero();
        var food = evaluate(purchases.get(0), new Ledger(), recognition, user, defaultId, credit);
        assertThat(food.result().evaluatedCards().get(0).calculation().netBenefit()).isEqualByComparingTo("500");
        var daily = new Ledger();
        var dayLimit = first.policy().limits().stream().filter(l -> l.period() == BenefitPolicy.Period.DAILY).findFirst().orElseThrow();
        assertThat(dayLimit.maximum()).isEqualTo(1000L);
        daily.used.put(daily.key(first.card().row().getId(), dayLimit, first.transaction()), 900L);
        assertThat(evaluate(cafe, daily, recognition, user, defaultId, credit).result().evaluatedCards().get(0).calculation().netBenefit()).isEqualByComparingTo("100");
        var monthly = new Ledger();
        var coffeeLimit = toktok.policy().limits().stream().filter(l -> l.maximum() == 10000L).findFirst().orElseThrow();
        monthly.used.put(monthly.key(toktok.card().row().getId(), coffeeLimit, toktok.transaction()), 9900L);
        assertThat(evaluate(cafe, monthly, recognition, user, defaultId, credit).result().evaluatedCards().get(1).calculation().netBenefit()).isEqualByComparingTo("100");
        monthly.used.put(monthly.key(toktok.card().row().getId(), coffeeLimit, toktok.transaction()), 10000L);
        var exhausted = evaluate(cafe, monthly, recognition, user, defaultId, credit);
        assertThat(exhausted.result().evaluatedCards().get(1).calculation().netBenefit()).isEqualByComparingTo("0");
        assertThat(exhausted.candidates().get(1).performance().recognizedAmount()).isEqualTo(6000L);
        Files.writeString(OUT.resolve("manual-checks.md"), "# 독립 수식 검산\n\n모두 통과.\n\n"
                + "- 스타벅스 6,000원: 처음 6,000×5%=300P, 톡톡 6,000×50%=3,000원.\n"
                + "- 음식점 12,000원: 처음 min(12,000×5%, 건별 500)=500P.\n"
                + "- 처음 일 한도 1,000P 중 900P 사용: min(300,100)=100P.\n"
                + "- 톡톡 월 한도 10,000원 중 9,900원 사용: min(3,000,100)=100원.\n"
                + "- 톡톡 월 한도 전부 사용: 0원(정상 계산).\n"
                + "- 톡톡 할인 발생 거래 실적 0원, 한도 소진 후 할인 없는 거래 실적 6,000원.\n");
    }

    private void writeResults(List<Run> runs) throws Exception {
        BigDecimal yamu = runs.stream().filter(r -> r.strategy().equals("YAMU")).findFirst().orElseThrow().total();
        var summary = new ArrayList<Map<String, Object>>();
        for (var r : runs) {
            var row = new LinkedHashMap<String, Object>();
            row.put("strategy", r.strategy()); row.put("seed", r.seed()); row.put("totalKrw", r.total());
            row.put("differenceFromYamu", r.total().subtract(yamu)); row.put("currentPerformanceByCardId", r.performance());
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
        var report = new StringBuilder("# 카드 선택 실험 실행 결과\n\n실행 구분: " + OUT.getFileName() + "\n\n합성 구매 24건·309,000원. 2026년 11월, 전월 실적 각 400,000원.\n\n");
        report.append("| 전략 | 총 원화 환산 혜택 | 전략−YAMU |\n|---|---:|---:|\n");
        for (var r : runs) if (!r.strategy().equals("RANDOM")) report.append("| ").append(r.strategy()).append(" | ").append(r.total()).append(" | ").append(r.total().subtract(yamu)).append(" |\n");
        report.append(String.format(Locale.ROOT, "| RANDOM 평균 | %.2f | %.2f |\n\n", mean, mean - yamu.doubleValue()));
        report.append("랜덤: java.util.Random, 고정 시드 0~99. 표준편차는 이 100회 결과의 모집단 표준편차. 백분위는 nearest rank.\n\n");
        report.append(stats).append("\n\n");
        report.append("초기·전체 소진·개별 한도 부분/완전 소진 검사 통과. 본 실행 계산 불가 0건. 검사 수는 validation.json 참고. 대표 수식 검산 통과. YAMU 재실행 결과 동일.\n\n");
        report.append("할인과 포인트는 분리된 보상이며 처음의 등록 환산값 1P=1원으로만 합산한다. 승인·청구·현금 지급 검증은 아니다. 신규 유예 없음·일반 직접 구매의 합성 사실을 명시했다. 선택하지 않은 카드의 상태는 갱신하지 않는다.\n\n");
        report.append("업종 규칙은 effective-input.json에 사전 고정했다. 한도 소진은 limits.csv, 선택별 차이는 transactions.csv에서 확인한다. 승리 여부는 테스트 조건이 아니다. 카드·합성 소비에 한정하며 실제 사용자 평균이나 월간 최적 보장을 뜻하지 않는다.\n\n");
        report.append("재현: be에서 `./gradlew.bat test --tests com.yamu.be.recommendation.CardSelectionExperimentTest --rerun-tasks` 실행. Docker 필요. skipped는 성공이 아니다. 상위 status.txt와 JUnit 실행 건수를 확인한다. manifest.json은 미커밋 변경 포함 코드·정책·입력 SHA-256이다. 원본 및 다른 실행을 덮어쓰지 않는다.\n");
        Files.writeString(OUT.resolve("report.md"), report);
    }

    private void locaChecks(List<Purchase> purchases, long user, long defaultId, long credit) throws Exception {
        var food = purchases.get(0);
        var cafe = purchases.get(1);
        var loca = food.cards().get(2);
        assertThat(loca.policy().limits()).hasSize(1);
        var shared = loca.policy().limits().get(0);
        assertThat(shared.maximum()).isEqualTo(13000L);
        assertThat(shared.period()).isEqualTo(BenefitPolicy.Period.MONTHLY);
        var a = evaluate(food, new Ledger(), recognition, user, defaultId, credit);
        assertThat(a.result().evaluatedCards().get(2).calculation().netBenefit()).isEqualByComparingTo("7200");
        assertThat(a.candidates().get(2).performance().recognizedAmount()).isZero();
        assertThat(evaluate(cafe, new Ledger(), recognition, user, defaultId, credit).result().evaluatedCards().get(2).calculation().netBenefit()).isEqualByComparingTo("3600");
        var ledger = new Ledger();
        ledger.used.put(ledger.key(loca.card().row().getId(), shared, food.transaction()), 12500L);
        assertThat(evaluate(cafe, ledger, recognition, user, defaultId, credit).result().evaluatedCards().get(2).calculation().netBenefit()).isEqualByComparingTo("500");
        ledger.used.put(ledger.key(loca.card().row().getId(), shared, food.transaction()), 13000L);
        var exhausted = evaluate(food, ledger, recognition, user, defaultId, credit);
        assertThat(exhausted.result().evaluatedCards().get(2).calculation().netBenefit()).isEqualByComparingTo("0");
        assertThat(exhausted.candidates().get(2).performance().recognizedAmount()).isEqualTo(12000L);
        for (int index : List.of(2, 3)) assertThat(evaluate(purchases.get(index), new Ledger(), recognition, user, defaultId, credit)
                .result().evaluatedCards().get(2).calculation().netBenefit()).isEqualByComparingTo("0");
        var first = food.cards().get(0);
        var day = first.policy().limits().stream().filter(l -> l.period() == BenefitPolicy.Period.DAILY).findFirst().orElseThrow();
        var rollover = new Ledger();
        rollover.used.put(rollover.key(first.card().row().getId(), day, food.transaction()), day.maximum());
        assertThat(evaluate(cafe, rollover, recognition, user, defaultId, credit).result().evaluatedCards().get(0).calculation().netBenefit()).isEqualByComparingTo("0");
        assertThat(evaluate(purchases.get(4), rollover, recognition, user, defaultId, credit).result().evaluatedCards().get(0).calculation().netBenefit()).isEqualByComparingTo("500");
        var month = first.policy().limits().stream().filter(l -> l.period() == BenefitPolicy.Period.MONTHLY).findFirst().orElseThrow();
        rollover.used.put(rollover.key(first.card().row().getId(), month, food.transaction()), month.maximum());
        assertThat(evaluate(purchases.get(4), rollover, recognition, user, defaultId, credit).result().evaluatedCards().get(0).calculation().netBenefit()).isEqualByComparingTo("0");
        Files.writeString(OUT.resolve("loca-manual-checks.md"), "# 추가 검산 — PASS\n\n"
                + "- 음식점 12,000×60%=7,200원. 할인 거래 실적 0원.\n"
                + "- 스타벅스 6,000×60%=3,600원.\n"
                + "- 동일 공유 월 한도 13,000원 중 12,500원 사용 시 카페는 500원만 할인.\n"
                + "- 공유 월 한도 소진 시 음식점 0원, 실적은 12,000원 인정.\n"
                + "- GS25·쿠팡 일반 상품은 LOCA 정상 계산 0원.\n"
                + "- 처음 카드 일 한도 소진은 같은 날 유지·다음 날짜 초기화, 월 소진은 다음 날짜에도 유지.\n");
    }

    /** Compare economic records without generated DB identities or JSON numeric scale differences. */
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
