package com.yamu.be.recommendation;

import static org.assertj.core.api.Assertions.*;
import static com.yamu.be.recommendation.CardSelectionSixMonthSupport.*;
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
import java.util.zip.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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

@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("experiment")
class CardSelectionSixMonthTest extends MySqlTestSupport {
    @Container @ServiceConnection(name="redis")
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @Autowired DataSource source; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json;
    @Autowired UserCardRepository cards; @Autowired CardProductRepository products;
    @Autowired RecommendationPolicyLoader loader; @Autowired RecommendationInputAssembler assembler;
    @Autowired RecommendationRecognitionResolver recognition; @Autowired PlatformTransactionManager transactions;
    static final Path ROOT=Path.of("../docs/experiments/card-selection/exp-006-six-months");
    final List<CardSelectionExperimentSupport.Card> selected=new ArrayList<>();
    RecommendationPolicyData data; JsonNode spec; List<Purchase> purchases; long user; Path out;
    final Map<String,Map<String,Object>> merchants=new HashMap<>();

    @Test void preflightSixMonthBoundaries() throws Exception { execute(false); }
    @Test @Tag("experiment-approved")
    @EnabledIfEnvironmentVariable(named="YAMU_EXP006_EXECUTE",matches="APPROVED_6_MONTHS_V1")
    void approvedSixMonthComparison() throws Exception { execute(true); }

    void execute(boolean main) throws Exception {
        out=ROOT.resolve("runs").resolve((main?"main-":"preflight-")+Instant.now().toString().replace(':','-')+"-"+UUID.randomUUID());
        Files.createDirectories(out);Files.writeString(out.resolve("status.txt"),"STARTED");
        try {
            setup();
            var frozen=ROOT.resolve("frozen-transactions.json");
            if(main) {
                assertThat(Files.exists(frozen)).isTrue();
                assertThat(Files.readAllBytes(frozen)).isEqualTo(json.writerWithDefaultPrettyPrinter().writeValueAsBytes(purchases));
                assertThat(json.readTree(Files.readAllBytes(ROOT.resolve("frozen-spec.json")))).isEqualTo(spec);
            }
            preflight();
            if(main) compare();
            else if(!Files.exists(frozen)) {
                Files.write(frozen,json.writerWithDefaultPrettyPrinter().writeValueAsBytes(purchases),StandardOpenOption.CREATE_NEW);
                Files.write(ROOT.resolve("frozen-spec.json"),json.writerWithDefaultPrettyPrinter().writeValueAsBytes(spec),StandardOpenOption.CREATE_NEW);
            }
            Files.writeString(out.resolve("status.txt"),"SUCCESS");
        } catch(Throwable e) { Files.writeString(out.resolve("status.txt"),"FAILED: "+e);throw e; }
    }
    void setup() throws Exception {
        byte[] bytes=new ClassPathResource("recommendation/card-selection-exp-006.json").getInputStream().readAllBytes();
        spec=json.readTree(bytes);purchases=generate(spec);assertThat(generate(spec)).isEqualTo(purchases);
        assertThat(purchases).isNotEmpty();write("frozen-spec.json",spec);write("transactions.json",purchases);
        var hashes=new TreeMap<String,String>();
        try(var zip=new ZipOutputStream(Files.newOutputStream(out.resolve("source-snapshot.zip")))) {
            var sources=new ArrayList<Path>();
            for(var dir:List.of("src/main/java/com/yamu/be/recommendation","src/main/resources/recommendation"))
                try(var walk=Files.walk(Path.of(dir))) { sources.addAll(walk.filter(Files::isRegularFile).sorted().toList()); }
            sources.add(Path.of("src/test/java/com/yamu/be/recommendation/CardSelectionExperimentSupport.java"));
            sources.add(Path.of("src/test/java/com/yamu/be/recommendation/CardSelectionSixMonthSupport.java"));
            sources.add(Path.of("src/test/java/com/yamu/be/recommendation/CardSelectionSixMonthTest.java"));
            sources.add(Path.of("src/test/resources/recommendation/card-selection-exp-006.json"));
            for(var name:List.of("01-card-catalog.sql","02-card-catalog-ab20.sql","03-benefit-composition.sql"))sources.add(Path.of("seed",name));
            for(var p:sources) {byte[] b=Files.readAllBytes(p);String name=p.toString().replace('\\','/');hashes.put(name,sha(b));zip.putNextEntry(new ZipEntry(name));zip.write(b);zip.closeEntry();}
        }
        write("manifest.json",Map.of("experiment","EXP-006","inputSha256",sha(json.writeValueAsBytes(purchases)),"files",hashes,"java",System.getProperty("java.version"),"policySnapshotDate",spec.get("policySnapshotDate").asText()));
        for(var name:List.of("01-card-catalog.sql","02-card-catalog-ab20.sql","03-benefit-composition.sql"))try(var connection=source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new EncodedResource(new FileSystemResource(Path.of("seed",name)),StandardCharsets.UTF_8));
        }
        // Only disposable test DB fixtures; no product targets or production merchant catalog changes.
        for(var merchant:spec.get("syntheticMerchants")) jdbc.update("INSERT INTO merchant (merchant_name,brand_code,merchant_category_code,created_at,updated_at) VALUES (?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",merchant.get("name").asText(),merchant.get("brand").asText(),merchant.get("category").asText());
        user=new OwnershipFixtures(jdbc).user();
        for(var name:spec.get("cards")) {
            long product=jdbc.queryForObject("SELECT card_product_id FROM card_product WHERE product_name=?",Long.class,name.asText());
            long id=new OwnershipFixtures(jdbc).card(user,product,null,"ACTIVE");
            jdbc.update("INSERT INTO monthly_limit_usage(user_card_id,card_product_id,limit_group_id,usage_month,used_value,created_at,updated_at) SELECT ?,card_product_id,limit_group_id,'2026-11-01',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM limit_group WHERE card_product_id=? AND period_type='MONTHLY'",id,product);
            selected.add(new CardSelectionExperimentSupport.Card(cards.findById(id).orElseThrow(),products.findById(product).orElseThrow()));
        }
        data=new TransactionTemplate(transactions).execute(s->loader.load(user,selected.stream().map(CardSelectionExperimentSupport.Card::row).toList(),LocalDate.of(2026,11,15)));
        for(var brand:purchases.stream().map(Purchase::brand).distinct().toList())merchants.put(brand,jdbc.queryForMap("SELECT merchant_id,merchant_category_code FROM merchant WHERE brand_code=?",brand));
        for(var c:selected)loader.validateFallbacks(c.row(),data);
    }
    Prepared prepare(Purchase p,int index,long previous) {
        var merchant=merchants.get(p.brand());assertThat(merchant.get("merchant_category_code")).isEqualTo(p.category());
        var facts=new HashMap<String,Boolean>();spec.get("facts").fields().forEachRemaining(e->facts.put(e.getKey(),e.getValue().asBoolean()));
        facts.put("CHANNEL:ONLINE",p.channel().equals("ONLINE"));facts.put("CHANNEL:OFFLINE",p.channel().equals("OFFLINE"));
        var overrides=spec.path("groupFacts").path(p.purpose());
        overrides.fields().forEachRemaining(e->facts.put(e.getKey(),e.getValue().asBoolean()));
        var raw=new Transaction(p.amount(),RecommendationInput.Plan.LUMP_SUM,1,p.at(),((Number)merchant.get("merchant_id")).longValue(),p.category(),facts);
        var tx=BasicPurchaseSimulation.apply(raw,p.channel(),"GENERAL");var card=selected.get(index);var issues=new ArrayList<RecommendationDataIssue>();
        var policy=extend(assembler.policy(card.row(),data,LocalDate.of(2026,11,15),previous,issues,tx,card.product()));
        // Preserve missing below-threshold limits as null, not zero. They are safe only if no linked benefit can apply.
        for(var l:policy.limits()) if(l.maximum()==null) {
            assertThat(policy.benefits().stream().filter(b->b.limitIds().contains(l.id())).toList()).isNotEmpty().allSatisfy(b->assertThat(b.minimumPerformance()).isGreaterThan(previous));
        }
        assertThat(issues).allSatisfy(i->assertThat(i.code()).isEqualTo("LIMIT_MAXIMUM_UNKNOWN"));
        return new Prepared(policy,CheoeumPurchaseSimulation.apply(tx,card.row().getId(),card.product(),policy,p.brand()),List.copyOf(issues));
    }
    void preflight() throws Exception {
        var calculator=new CardBenefitCalculator();long checked=0,zero=0;var diagnostics=new LinkedHashSet<String>();
        for(var p:purchases) for(int card=0;card<2;card++) for(long previous:new long[]{0,299999,300000,400000,500000,1000000}) {
            var prepared=prepare(p,card,previous);prepared.assemblyIssues().forEach(i->diagnostics.add(i.code()));
            for(var state:List.of("EMPTY","PARTIAL","EXHAUSTED")) {
                var ledger=new Ledger();ledger.enter(YearMonth.from(p.at().atZone(SEOUL)));ledger.previous[card]=previous;
                for(var l:prepared.policy().limits())if(l.maximum()!=null&&l.period()!=BenefitPolicy.Period.PER_TRANSACTION)
                    ledger.used.put(ledger.key(card,l,p.at()),state.equals("EMPTY")?0L:state.equals("PARTIAL")?Math.max(0,l.maximum()-1):l.maximum());
                var policy=ledger.overlay(card,prepared);var result=calculator.calculate(prepared.transaction(),policy);
                assertThat(result.complete()).as("preflight purchase=%s card=%s previous=%s state=%s problems=%s",p.number(),card,previous,state,result.problems()).isTrue();
                assertThat(result.problems()).isEmpty();if(result.netBenefit().signum()==0)zero++;checked++;
                var issues=new ArrayList<RecommendationDataIssue>();var progress=recognition.resolve(selected.get(card).row().getId(),selected.get(card).product(),prepared.transaction(),policy,issues);
                assertThat(issues).isEmpty();assertThat(progress.recognizedAmount()).isNotNull();assertThat(progress.period()).isEqualTo(ledger.month);
            }
        }
        var l=new Ledger();l.enter(YearMonth.of(2026,11));l.performance[0]=500000;l.performance[1]=299999;l.spent[0]=900000;
        l.enter(YearMonth.of(2026,12));assertThat(l.previous).containsExactly(500000,299999);assertThat(l.performance).containsOnly(0);assertThat(l.spent).containsOnly(0);
        l.performance[1]=300000;l.enter(YearMonth.of(2027,1));assertThat(l.previous).containsExactly(0,300000);
        var p=purchases.stream().filter(x->x.brand().equals("STARBUCKS")).findFirst().orElseThrow();
        var first=prepare(p,0,400000);var toktok=prepare(p,1,400000);
        var a=calculator.calculate(first.transaction(),first.policy());var b=calculator.calculate(toktok.transaction(),toktok.policy());
        assertThat(a.netBenefit()).isEqualByComparingTo(BigDecimal.valueOf(Math.min(500,p.amount()*5/100)));
        assertThat(b.netBenefit()).isEqualByComparingTo(BigDecimal.valueOf(p.amount()/2));
        var issues=new ArrayList<RecommendationDataIssue>();
        assertThat(recognition.resolve(selected.get(1).row().getId(),selected.get(1).product(),toktok.transaction(),toktok.policy(),issues).recognizedAmount()).isZero();
        var below=prepare(p,1,0);assertThat(recognition.resolve(selected.get(1).row().getId(),selected.get(1).product(),below.transaction(),below.policy(),issues).recognizedAmount()).isEqualTo(p.amount());
        var cap=first.policy().limits().stream().filter(x->x.period()==BenefitPolicy.Period.MONTHLY).findFirst().orElseThrow();
        assertThat(l.key(0,cap,p.at())).isNotEqualTo(l.key(0,cap,p.at().atZone(SEOUL).plusMonths(1).toInstant()));
        var day=first.policy().limits().stream().filter(x->x.period()==BenefitPolicy.Period.DAILY).findFirst().orElseThrow();
        assertThat(l.key(0,day,p.at())).isNotEqualTo(l.key(0,day,p.at().plusSeconds(86400)));
        write("preflight.json",Map.of("evaluations",checked,"normalZero",zero,"incalculable",0,"assemblyDiagnostics",diagnostics,"note","Below-minimum unused tier limit stays null; eligibility-zero is confirmed by existing calculator","manualCheck",Map.of("amount",p.amount(),"first",a.netBenefit(),"toktok",b.netBenefit()),"monthRollover","PASS","recognitionExclusion","PASS","inputDeterministic",true));
    }
    void compare() throws Exception {
        var summaries=new ArrayList<Map<String,Object>>();var monthly=new ArrayList<Map<String,Object>>();
        String yamuHash=null;
        try(var zip=new ZipOutputStream(Files.newOutputStream(out.resolve("trades.zip")))) {
            for(int n=0;n<104;n++) {
                String strategy=n<4?List.of("FIXED_FIRST","FIXED_TOKTOK","CATEGORY","YAMU").get(n):"RANDOM";int seed=n<4?-1:n-4;
                var r=run(strategy,seed,purchases,this::prepare,selected,recognition,user);
                summaries.add(Map.of("strategy",strategy,"seed",seed,"totalKrw",r.total()));
                for(var month:r.months()) {var row=new LinkedHashMap<String,Object>(month);row.put("strategy",strategy);row.put("seed",seed);monthly.add(row);}
                byte[] bytes=json.writeValueAsBytes(r);
                if(strategy.equals("YAMU")) yamuHash=sha(bytes);
                zip.putNextEntry(new ZipEntry(strategy+(seed>=0?"-"+seed:"")+".json"));zip.write(bytes);zip.closeEntry();
            }
        }
        assertThat(sha(json.writeValueAsBytes(run("YAMU",-1,purchases,this::prepare,selected,recognition,user)))).isEqualTo(yamuHash);
        write("summary.json",summaries);write("monthly.json",monthly);
        assertThat(jdbc.queryForObject("SELECT SUM(used_value) FROM monthly_limit_usage WHERE user_card_id IN (?,?)",Long.class,selected.get(0).row().getId(),selected.get(1).row().getId())).isZero();
        write("validation.json",Map.of("strategies",104,"transactionsPerStrategy",purchases.size(),"candidateEvaluations",104L*purchases.size()*2,"incalculable",0,"seedDatabaseUsageUnchanged",true,"yamuReplaySha256",yamuHash));
    }
    void write(String name,Object value) throws Exception {Files.write(out.resolve(name),json.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));}
    static String sha(byte[] value) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
}
