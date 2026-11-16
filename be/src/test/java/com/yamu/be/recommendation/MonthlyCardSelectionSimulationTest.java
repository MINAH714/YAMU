package com.yamu.be.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static com.yamu.be.recommendation.MonthlyCardSelectionSimulationSupport.*;

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
class MonthlyCardSelectionSimulationTest extends MySqlTestSupport {
    @Container @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static final Path OUT = Path.of("build/reports/monthly-card-selection");
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
        Files.createDirectories(OUT);
        Files.writeString(OUT.resolve("status.txt"), "STARTED; no successful result yet\n");
        try {
            executeExperiment();
            Files.writeString(OUT.resolve("status.txt"), "PASS: 48 initial card/transaction checks; 48 exhausted checks; manual checks; 104 main runs; deterministic replay\n");
        } catch (Throwable failure) {
            Files.writeString(OUT.resolve("status.txt"), "FAILED: " + failure + "\nExisting result files must not be treated as a successful run.\n");
            throw failure;
        }
    }

    private void executeExperiment() throws Exception {
        byte[] bytes;
        try (var in = new ClassPathResource("recommendation/monthly-card-selection.json").getInputStream()) { bytes = in.readAllBytes(); }
        var spec = json.readTree(bytes);
        Files.write(OUT.resolve("frozen-input.json"), bytes);
        var hashes = new TreeMap<String, String>();
        for (var root : List.of(Path.of("src/main/java/com/yamu/be/recommendation"), Path.of("src/main/resources/recommendation"),
                Path.of("src/test/java/com/yamu/be/recommendation"))) {
            try (var paths = Files.walk(root)) {
                for (var path : paths.filter(Files::isRegularFile).sorted().toList()) hashes.put(path.toString(), sha(Files.readAllBytes(path)));
            }
        }
        hashes.put("frozen-input.json", sha(bytes));
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
        }
        writeJson("coverage.json", coverage);
        manualChecks(prepared, user, defaultId, credit);
        var categoryRule = new HashMap<String, Integer>();
        spec.get("categoryRule").fields().forEachRemaining(e -> categoryRule.put(e.getKey(), e.getValue().asInt()));
        var runs = new ArrayList<Run>();
        for (var strategy : List.of("FIXED_FIRST", "FIXED_TOKTOK", "CATEGORY", "YAMU"))
            runs.add(run(strategy, -1, prepared, recognition, user, defaultId, credit, categoryRule));
        for (int seed = spec.at("/randomSeeds/first").asInt(); seed <= spec.at("/randomSeeds/last").asInt(); seed++)
            runs.add(run("RANDOM", seed, prepared, recognition, user, defaultId, credit, categoryRule));
        assertThat(runs).hasSize(104);
        var replay = run("YAMU", -1, prepared, recognition, user, defaultId, credit, categoryRule);
        assertThat(replay).isEqualTo(runs.get(3));
        writeResults(runs);
        // Read-only loading and each strategy's ledger must not mutate the seed snapshot.
        assertThat(jdbc.queryForObject("SELECT SUM(used_value) FROM monthly_limit_usage WHERE user_card_id IN (?,?)", Long.class,
                selected.get(0).row().getId(), selected.get(1).row().getId())).isZero();
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
        BigDecimal yamu = runs.get(3).total();
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
        var report = new StringBuilder("# 월간 카드 선택 최소 시뮬레이션\n\n선정한 카드 2종·합성 구매 24건·309,000원. 2026년 11월, 전월 실적 각 400,000원.\n\n");
        report.append("| 전략 | 총 원화 환산 혜택 | 전략−YAMU |\n|---|---:|---:|\n");
        for (var r : runs.subList(0, 4)) report.append("| ").append(r.strategy()).append(" | ").append(r.total()).append(" | ").append(r.total().subtract(yamu)).append(" |\n");
        report.append(String.format(Locale.ROOT, "| RANDOM 평균 | %.2f | %.2f |\n\n", mean, mean - yamu.doubleValue()));
        report.append("랜덤: java.util.Random, 고정 시드 0~99. 표준편차는 이 100회 결과의 모집단 표준편차. 백분위는 nearest rank.\n\n");
        report.append(stats).append("\n\n");
        report.append("초기 상태 48개 카드 평가 및 한도 소진 상태 48개 평가 통과. 본 실행 104×24×2=4,992개 카드 평가에서 계산 불가 0건. 대표 수식 검산 통과. YAMU 재실행 결과 동일.\n\n");
        report.append("할인과 포인트는 분리된 보상이며 처음의 등록 환산값 1P=1원으로만 합산한다. 승인·청구·현금 지급 검증은 아니다. 신규 유예 없음·일반 직접 구매의 합성 사실을 명시했다. 선택하지 않은 카드의 상태는 갱신하지 않는다.\n\n");
        report.append("카페는 톡톡, 그 외는 처음이라는 업종 규칙을 결과 전에 고정했다. 카페 월 한도 및 처음의 일·월 공유 한도 소진 여부는 limits.csv, 선택별 기회 차이는 transactions.csv에서 확인할 수 있다. 승리 여부는 테스트 조건이 아니다. 이 결과는 해당 카드·합성 소비에 한정하며 실제 사용자 평균이나 월간 최적 보장을 뜻하지 않는다.\n\n");
        report.append("재현: be에서 `./gradlew.bat test --tests com.yamu.be.recommendation.MonthlyCardSelectionSimulationTest` 실행. Docker 필요. 테스트 skipped는 성공 실험이 아니다. status.txt와 JUnit 실행 건수를 함께 확인한다. manifest.json은 미커밋 변경을 포함한 사용 코드·정책·입력의 SHA-256을 기록한다.\n");
        Files.writeString(OUT.resolve("report.md"), report);
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
