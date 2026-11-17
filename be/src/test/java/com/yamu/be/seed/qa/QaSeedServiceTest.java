package com.yamu.be.seed.qa;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.yamu.be.payment.bank.SsafyTransferApi;
import com.yamu.be.payment.bank.TransferResult;
import com.yamu.be.payment.billing.confirm.BillConfirmer;
import com.yamu.be.payment.billing.withdraw.BillWithdrawer;
import com.yamu.be.payment.billing.refund.RefundDepositor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.CryptoKeys;
import com.yamu.be.global.crypto.CryptoProperties;
import com.yamu.be.global.crypto.LookupHasher;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.crypto.PinHasher;
import com.yamu.be.support.MySqlTestSupport;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class QaSeedServiceTest extends MySqlTestSupport {
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired CryptoProperties cryptoProperties;
    @Autowired DataSource dataSource;
    @Autowired BillConfirmer confirmer;
    @Autowired BillWithdrawer withdrawer;
    @Autowired RefundDepositor depositor;
    @Autowired com.yamu.be.forecast.CardBillReader billReader;
    @Autowired com.yamu.be.payment.billing.CreditBillingWriter billingWriter;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired com.yamu.be.accounthistory.AccountHistoryStore accountHistory;
    @MockitoBean SsafyTransferApi transfer;
    @MockitoBean com.yamu.be.account.AccountBalanceService balances;
    @MockitoBean Clock runtimeClock;
    private Connection connection;
    private JdbcTemplate db;
    private QaSeedService service;
    private CryptoKeys keys;
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-12T03:00:00Z"), ZoneOffset.UTC);
    private final List<QaSeedSpec> created = new ArrayList<>();

    @BeforeEach void setup() throws Exception {
        when(runtimeClock.instant()).thenReturn(Instant.parse("2026-11-16T03:00:00Z"));
        when(runtimeClock.getZone()).thenReturn(ZoneOffset.UTC);
        when(runtimeClock.withZone(any())).thenAnswer(call -> Clock.fixed(runtimeClock.instant(),call.getArgument(0)));
        when(transfer.newTransactionNo()).thenReturn("20261116120000000001", "20261116120000000002");
        connection = dataSource.getConnection();
        db = new JdbcTemplate(new SingleConnectionDataSource(connection,true));
        String hmac = cryptoProperties.hmacKey();
        keys = new CryptoKeys(cryptoProperties);
        service = new QaSeedService(connection,keys,clock,hmac);
        ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of("seed/01-card-catalog.sql")));
        ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of("seed/02-card-catalog-ab20.sql")));
    }
    @AfterEach void cleanup() throws Exception {
        try {
            for (var spec : created) service.run("purge",spec);
        } finally { connection.close(); }
    }
    private static String key(int n) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes,(byte)n);
        return Base64.getEncoder().encodeToString(bytes);
    }
    private QaSeedSpec spec(String name, QaSeedSpec.Scenario... scenarios) {
        List<QaSeedSpec.Persona> persons = new ArrayList<>();
        for (int i=0;i<scenarios.length;i++) {
            String suffix = String.format("%04d",i+1);
            var accounts = List.of(new QaSeedSpec.Account("088","80000000"+suffix),
                    new QaSeedSpec.Account("088","90000000"+suffix));
            var cards = List.of(
                    new QaSeedSpec.Card("KB","KB국민 노리 체크","CHECK","90000000"+suffix+"0001",0,null),
                    new QaSeedSpec.Card("SHINHAN","신한카드 처음","CREDIT","90000000"+suffix+"0002",0,15),
                    new QaSeedSpec.Card("SHINHAN","신한카드 Mr.Life","CREDIT","90000000"+suffix+"0003",1,15));
            persons.add(new QaSeedSpec.Persona("p"+i,scenarios[i],"큐에이",LocalDate.of(1995,1,1),
                    "0100000"+suffix,"qa"+suffix+"@example.invalid","test-financial-binding",accounts,cards));
        }
        return new QaSeedSpec("qa-"+name,2,LocalDate.of(2026,11,12),persons);
    }
    private void apply(QaSeedSpec spec) throws Exception { service.run("apply",spec); created.add(spec); }
    private long user(QaSeedSpec spec,int index) {
        return db.queryForObject("SELECT user_id FROM qa_seed_member WHERE dataset_key=? AND persona_key=?",
                Long.class,spec.datasetKey(),"p"+index);
    }
    /** Simulates completed prerequisites in the isolated DB; never bypasses auth in production code. */
    private void signedUp(QaSeedSpec spec) {
        LookupHasher lookup = new LookupHasher(keys);
        PersonalDataCipher cipher = new PersonalDataCipher(keys,new SecureRandom());
        Timestamp now = Timestamp.from(clock.instant());
        for(int i=0;i<spec.personas().size();i++) {
            var p=spec.personas().get(i);
            long user=user(spec,i);
            db.update("""
                    UPDATE app_user SET status='ACTIVE',pin_hash=?,pin_updated_at=?,identity_verified_at=? WHERE user_id=?
                    """,new PinHasher(keys).hash("493827"),now,now,user);
            for(int a=0;a<p.accounts().size();a++) {
                var account=p.accounts().get(a);
                db.update("""
                        INSERT INTO linked_account(user_id,bank_code,account_no_enc,account_no_hash,account_last4,
                          status,is_primary,connected_at,created_at,updated_at)
                        VALUES (?,?,?,?,?,'CONNECTED',?,?,?,?)
                        """,user,account.bankCode(),cipher.encrypt(account.accountNo(),CipherField.LINKED_ACCOUNT_NO),
                        lookup.account(account.bankCode(),account.accountNo()),account.accountNo().substring(account.accountNo().length()-4),a==0,now,now,now);
            }
            db.update("""
                    UPDATE user_card c JOIN linked_account a ON a.user_id=c.user_id
                      AND a.bank_code=c.issuer_account_bank_code AND a.account_no_hash=c.issuer_account_hash
                    SET c.status='ACTIVE',c.registered_at=?,c.linked_account_id=a.linked_account_id WHERE c.user_id=?
                    """,now,user);
        }
    }
    private long count(String table) { return db.queryForObject("SELECT COUNT(*) FROM "+table,Long.class); }

    @Test void compileCurrentPolicyLifeBenefits() throws Exception {
        ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of("seed/05-ibk-narasarang.sql")));
        service=new QaSeedService(connection,keys,Clock.fixed(Instant.parse("2026-11-16T03:00:00Z"),ZoneOffset.UTC),cryptoProperties.hmacKey());
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var input=mapper.readValue(Path.of("seed/qa/life-persona-1.example.json").toFile(),QaSeedSpec.class);
        var p=input.personas().get(0);
        var person=new QaSeedSpec.Persona("p0",p.scenario(),p.name(),p.birthDate(),p.phone(),p.ssafyMemberId(),"local-replay",p.accounts(),p.cards(),p.issuanceEligibility());
        var spec=new QaSeedSpec(input.datasetKey(),3,input.baseDate(),List.of(person));
        apply(spec); signedUp(spec);
        var compiled=com.yamu.be.recommendation.QaLifePolicyReplay.generate(context,user(spec,0));
        java.nio.file.Files.createDirectories(Path.of("build/reports"));
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of("build/reports/life-benefits.json").toFile(),compiled);
        assertThat(mapper.readTree(mapper.writeValueAsBytes(compiled))).isEqualTo(mapper.readTree(Path.of("src/main/resources/qa/life-benefits.json").toFile()));
    }
    @Test void extendedHistoryIsAvailableForAllThreeUsersAndNineAccounts() throws Exception {
        ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of("seed/05-ibk-narasarang.sql")));
        service=new QaSeedService(connection,keys,Clock.fixed(Instant.parse("2026-11-16T03:00:00Z"),ZoneOffset.UTC),cryptoProperties.hmacKey());
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        var actualStreams=new ArrayList<Long>();
        var assetSpecs=new ArrayList<QaAssetHistory.Spec>();
        var assets=new QaAssetHistory(db,Clock.fixed(Instant.parse("2026-11-17T04:00:00Z"),ZoneOffset.UTC));
        var responseSnapshots=mapper.createArrayNode();
        when(transfer.historySnapshot(anyString(),anyString(),any(),any(),any())).thenAnswer(call ->
            new com.yamu.be.payment.bank.BankHistory(call.getArgument(2),call.getArgument(3),null,0L,List.of(),runtimeClock.instant()));
        try {
            for(int n=1;n<=3;n++) {
                var input=mapper.readValue(Path.of("seed/qa/life-persona-"+n+".example.json").toFile(),QaSeedSpec.class);
                var p=input.personas().get(0);
                var person=new QaSeedSpec.Persona("p0",p.scenario(),p.name(),p.birthDate(),p.phone(),p.ssafyMemberId(),
                    "isolated-extension-"+n,p.accounts(),p.cards(),p.issuanceEligibility());
                var spec=new QaSeedSpec(input.datasetKey(),3,input.baseDate(),List.of(person));
                apply(spec);signedUp(spec);service.run("enrich",spec);service.run("verify",spec);
                long owner=user(spec,0);
                var accountIds=new ArrayList<Long>();
                var bankBalances=new ArrayList<com.yamu.be.account.AccountBalance>();
                when(runtimeClock.instant()).thenReturn(Instant.parse("2026-11-17T04:00:00Z"));
                for(int a=0;a<3;a++) {
                    long account=db.queryForObject("SELECT linked_account_id FROM linked_account WHERE user_id=? AND bank_code=?",Long.class,owner,p.accounts().get(a).bankCode());
                    accountIds.add(account);
                    long opening=QaLifeScenario.startingBalance(a);
                    bankBalances.add(new com.yamu.be.account.AccountBalance(account,opening,java.time.LocalDateTime.of(2026,11,16,3,0)));
                    String funding="extension-local-funding-"+n+"-"+a;
                    var ticket=accountHistory.begin(owner,account,QaLifeScenario.BASE,QaLifeScenario.BASE);
                    actualStreams.add(ticket.streamId());
                    var row=new com.yamu.be.payment.bank.BankTransaction(funding,QaLifeScenario.BASE,java.time.LocalTime.NOON,
                        com.yamu.be.payment.bank.BankDirection.DEPOSIT,opening,opening,"");
                    assertThat(accountHistory.complete(ticket,new com.yamu.be.payment.bank.BankHistory(QaLifeScenario.BASE,QaLifeScenario.BASE,null,1L,List.of(row),runtimeClock.instant()))).isEqualTo("OK");
                    var assetSpec=new QaAssetHistory.Spec(spec.datasetKey(),"p0",account,QaLifeScenario.FROM,QaLifeScenario.BASE.minusDays(1),0,0,List.of(),"LIFE",funding);
                    assetSpecs.add(assetSpec);
                    connection.setAutoCommit(false);
                    try { assets.run("apply",assetSpec);connection.commit();assets.run("verify",assetSpec);connection.commit(); }
                    finally {connection.rollback();connection.setAutoCommit(true);}
                    assertThat(accountHistory.read(owner,account,QaLifeScenario.FROM,LocalDate.of(2026,3,31)).reason()).isEqualTo("OK");
                }
                when(runtimeClock.instant()).thenReturn(Instant.parse("2026-11-16T03:00:00Z"));
                when(balances.getBalances(eq(owner),eq(accountIds),eq(false))).thenReturn(bankBalances);
                for(var ym=java.time.YearMonth.of(2025,12);ym.isBefore(java.time.YearMonth.of(2026,4));ym=ym.plusMonths(1)) {
                    var data=mapper.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/asset-flows")
                        .param("yearMonth",ym.toString()).param("accountIds",accountIds.stream().map(String::valueOf).toArray(String[]::new))
                        .with(com.yamu.be.support.AuthUsers.asUser(owner)))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                        .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
                    assertThat(data.path("partialErrors").size()).isZero();
                    assertThat(data.path("currentTotalBalance").asLong()).isEqualTo(2_250_000);
                    assertThat(data.path("series").size()).isEqualTo(ym.lengthOfMonth());
                    for(var day:data.path("series")) {
                        assertThat(day.path("kind").asText()).isEqualTo("SIMULATED");
                        assertThat(day.path("dataStatus").asText()).isEqualTo("AVAILABLE");
                    }
                    var snapshot=responseSnapshots.addObject();snapshot.put("name",p.name());snapshot.set("assetFlow",data);
                }
            }
            verify(transfer,never()).withdraw(anyString(),anyString(),anyLong(),any(),anyString());
            verify(transfer,never()).deposit(anyString(),anyString(),anyLong(),any(),anyString());
            java.nio.file.Files.createDirectories(Path.of("build/reports"));
            mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of("build/reports/qa-life-extension-assets.json").toFile(),responseSnapshots);
        } finally {
            // Cleanup only in MySqlTestSupport's disposable database; never touch financial originals.
            for(var assetSpec:assetSpecs) assets.run("reset",assetSpec);
            for(long stream:actualStreams) {
                for(String table:List.of("account_history_coverage","account_history_sync_run","account_transaction"))
                    db.update("DELETE FROM "+table+" WHERE stream_id=?",stream);
                db.update("DELETE FROM account_history_stream WHERE stream_id=?",stream);
            }
        }
    }
    @Test void lifeConsumptionReportSnapshot() throws Exception {
        ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of("seed/05-ibk-narasarang.sql")));
        service=new QaSeedService(connection,keys,Clock.fixed(Instant.parse("2026-11-16T03:00:00Z"),ZoneOffset.UTC),cryptoProperties.hmacKey());
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        var result=mapper.createObjectNode();
        result.put("referenceDate","2026-11-16");
        result.put("execution","MockMvc controllers + isolated MySQL; signup prerequisites prepared in test; no financial calls");
        var people=result.putArray("personas");
        com.fasterxml.jackson.databind.JsonNode baseline=null;
        for(int n=1;n<=3;n++) {
            var input=mapper.readValue(Path.of("seed/qa/life-persona-"+n+".example.json").toFile(),QaSeedSpec.class);
            var p=input.personas().get(0);
            var person=new QaSeedSpec.Persona("p0",p.scenario(),p.name(),p.birthDate(),p.phone(),p.ssafyMemberId(),"isolated-report-binding-"+n,p.accounts(),p.cards(),p.issuanceEligibility());
            var spec=new QaSeedSpec(input.datasetKey(),3,input.baseDate(),List.of(person));
            apply(spec);signedUp(spec);service.run("enrich",spec);service.run("verify",spec);
            long owner=user(spec,0);
            assertThat(db.queryForObject("SELECT created_at FROM app_user WHERE user_id=?",Timestamp.class,owner).toInstant()).isEqualTo(Instant.parse("2026-03-31T15:00:00Z"));
            var entry=people.addObject();entry.put("name",p.name());var months=entry.putArray("months");
            for(var ym=java.time.YearMonth.of(2025,12);!ym.isAfter(java.time.YearMonth.of(2026,11));ym=ym.plusMonths(1)) {
                String month=ym.toString();
                var report=mapper.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/reports/monthly")
                        .param("yearMonth",month).with(com.yamu.be.support.AuthUsers.asUser(owner)))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
                var payments=mapper.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/payments")
                        .param("yearMonth",month).param("page","1").param("size","1").with(com.yamu.be.support.AuthUsers.asUser(owner)))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
                var benefits=mapper.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/reports/monthly/benefits")
                        .param("yearMonth",month).param("source","YAMU").with(com.yamu.be.support.AuthUsers.asUser(owner)))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
                final var benefitMonth=ym;
                long expectedBenefit=QaLifeScenario.payments(QaLifeScenario.BASE).stream()
                    .filter(t->java.time.YearMonth.from(t.date()).equals(benefitMonth))
                    .map(QaLifeBenefits::row).filter(java.util.Objects::nonNull)
                    .flatMap(r->java.util.stream.StreamSupport.stream(r.path("benefits").spliterator(),false))
                    .mapToLong(b->b.path("krw").asLong()).sum();
                assertThat(benefits.path("summary").path("expectedBenefit").asLong()).isEqualTo(expectedBenefit);
                if(!ym.isBefore(java.time.YearMonth.of(2026,4)) && ym.isBefore(java.time.YearMonth.of(2026,11))) {
                    assertThat(benefits.path("summary").path("unconfirmedBenefit").asLong()).isZero();
                    assertThat(benefits.path("summary").path("confirmedBenefit").asLong()).isEqualTo(expectedBenefit);
                }
                if(ym.equals(java.time.YearMonth.of(2026,11))) {
                    assertThat(benefits.path("summary").path("confirmedBenefit").asLong()).isEqualTo(2200);
                    assertThat(benefits.path("summary").path("unconfirmedBenefit").asLong()).isEqualTo(1000);
                }
                if(!ym.isBefore(java.time.YearMonth.of(2026,4))) assertThat(expectedBenefit).isPositive();
                else assertThat(expectedBenefit).isZero();
                assertThat(benefits.path("summary").path("yamuPickExtra").asLong()).isZero();
                var row=months.addObject();row.set("monthly",report);row.set("paymentsSummary",payments.path("summary"));row.set("yamuBenefits",benefits);
                final var snapshotMonth=ym;
                long total=QaLifeScenario.payments(QaLifeScenario.BASE).stream().filter(t->java.time.YearMonth.from(t.date()).equals(snapshotMonth)).mapToLong(QaHistoryPlan.Payment::amount).sum();
                assertThat(report.path("totalSpent").asLong()).isEqualTo(total);
                assertThat(report.path("livingSpent").asLong()).isEqualTo(total-(snapshotMonth.equals(java.time.YearMonth.of(2026,7))?900000:0));
            }
            var comparable=months.deepCopy();
            for(var m:comparable) for(var card:m.path("yamuBenefits").path("cards")) ((com.fasterxml.jackson.databind.node.ObjectNode)card).remove(List.of("userCardId","cardProductId"));
            if(baseline==null) baseline=comparable;else assertThat(comparable).isEqualTo(baseline);
        }
        verify(transfer,never()).withdraw(anyString(),anyString(),anyLong(),any(),anyString());
        verify(transfer,never()).deposit(anyString(),anyString(),anyLong(),any(),anyString());
        var output=Path.of("build/reports/qa-life-consumption.json");java.nio.file.Files.createDirectories(output.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),result);
    }

    @Test void lifeUsersHaveIndependentMatchingBillsPlansAndReset() throws Exception {
        ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of("seed/05-ibk-narasarang.sql")));
        service=new QaSeedService(connection,keys,Clock.fixed(Instant.parse("2026-11-16T03:00:00Z"),ZoneOffset.UTC),cryptoProperties.hmacKey());
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        var life=new ArrayList<QaSeedSpec>();
        for(int n=1;n<=3;n++) {
            var input=mapper.readValue(Path.of("seed/qa/life-persona-"+n+".example.json").toFile(),QaSeedSpec.class);
            var p=input.personas().get(0);
            var person=new QaSeedSpec.Persona("p0",p.scenario(),p.name(),p.birthDate(),p.phone(),p.ssafyMemberId(),"isolated-test-binding-"+n,p.accounts(),p.cards(),p.issuanceEligibility());
            var spec=new QaSeedSpec(input.datasetKey(),3,input.baseDate(),List.of(person));
            apply(spec); signedUp(spec);service.run("enrich",spec);service.run("enrich",spec);service.run("verify",spec);
            life.add(spec);
            long owner=user(spec,0);
            assertThat(db.queryForObject("""
                SELECT COUNT(*) FROM card_bill b WHERE b.user_id=? AND b.status IN ('CONFIRMED','PAID')
                  AND b.confirmed_amount<>(SELECT SUM(i.billed_amount) FROM bill_item i WHERE i.card_bill_id=b.card_bill_id)
                    -COALESCE((SELECT SUM(d.amount) FROM bill_item_discount d WHERE d.card_bill_id=b.card_bill_id),0)
                """,Long.class,owner)).isZero();
            assertThat(db.queryForObject("""
                SELECT COUNT(*) FROM payment_benefit pb JOIN payment_transaction t USING(payment_transaction_id)
                  WHERE t.user_id=? AND t.approved_at<'2026-03-31 15:00:00'
                """,Long.class,owner)).isZero();
            assertThat(db.queryForObject("""
                SELECT COUNT(*) FROM payment_transaction WHERE user_id=? AND source='YAMU'
                """,Long.class,owner)).isEqualTo(234);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id=? AND target_id IS NOT NULL",Long.class,owner)).isGreaterThan(24);
            assertThat(db.queryForObject("SELECT COUNT(DISTINCT notification_type) FROM notification WHERE user_id=?",Long.class,owner)).isEqualTo(3);
            assertThat(db.queryForObject("SELECT SUM(confirmed_amount) FROM card_bill WHERE user_id=? AND status='CONFIRMED'",Long.class,owner)).isEqualTo(QaLifeScenario.bills().stream().filter(b->b.month().equals(java.time.YearMonth.of(2026,11))).mapToLong(QaLifeScenario.Bill::amount).sum());
            assertThat(db.queryForObject("SELECT SUM(i.billed_amount) FROM bill_item i JOIN card_bill b USING(card_bill_id) WHERE b.user_id=? AND b.billing_month='2026-12-01'",Long.class,owner)).isEqualTo(420000);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM cashflow_item WHERE user_id=? AND title='정기 구독' AND linked_account_id IS NULL AND user_card_id IS NOT NULL",Long.class,owner)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT COALESCE(SUM(used_value),0) FROM monthly_limit_usage m JOIN user_card c USING(user_card_id) JOIN card_product p ON p.card_product_id=c.card_product_id WHERE c.user_id=? AND p.issuer_code='IBK'",Long.class,owner)).isZero();
            var accounts=db.queryForList("SELECT linked_account_id FROM linked_account WHERE user_id=?",Long.class,owner);
            var outstanding=billReader.outstanding(owner,accounts,LocalDate.of(2026,12,15));
            assertThat(outstanding).hasSize(4);
            assertThat(outstanding.stream().mapToLong(com.yamu.be.forecast.CardBillReader.OutstandingBill::amount).sum()).isEqualTo(QaLifeScenario.bills().stream().filter(b->!b.due().isBefore(QaLifeScenario.BASE)).mapToLong(QaLifeScenario.Bill::amount).sum());
            long card=db.queryForObject("SELECT c.user_card_id FROM user_card c JOIN card_product p USING(card_product_id) WHERE c.user_id=? AND p.product_name='신한카드 처음'",Long.class,owner);
            var purchase=com.yamu.be.payment.billing.InstallmentPlan.of(25000,1,new com.yamu.be.payment.billing.BillingCycle(15,14),QaLifeScenario.BASE,QaLifeScenario.calendar());
            var ensured=billingWriter.ensureBills(owner,card,purchase);
            assertThat(ensured.get(java.time.YearMonth.of(2026,12)).getId()).isEqualTo(db.queryForObject("SELECT card_bill_id FROM card_bill WHERE user_card_id=? AND billing_month='2026-12-01'",Long.class,card));
            if(n==1) {
                long payment=service.insert("""
                    INSERT INTO payment_transaction(user_id,user_card_id,card_product_id,merchant_id,payment_channel,facility_type,
                      request_key,request_fingerprint,source,status,requested_amount,approved_amount,installment_months,
                      recognized_performance_amount,performance_month,requested_at,approved_at,created_at,updated_at)
                    SELECT user_id,user_card_id,card_product_id,merchant_id,'OFFLINE','GENERAL',UUID(),UNHEX(REPEAT('01',32)),
                      'YAMU','APPROVED',25000,25000,1,25000,'2026-11-01','2026-11-16 03:00:00','2026-11-16 03:00:00',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
                    FROM payment_transaction WHERE user_card_id=? LIMIT 1
                    """,card);
                var approved=mock(com.yamu.be.payment.domain.PaymentTransaction.class);
                when(approved.getId()).thenReturn(payment);when(approved.getUserId()).thenReturn(owner);when(approved.getUserCardId()).thenReturn(card);
                new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(tx->billingWriter.writeItems(approved,purchase,ensured));
                assertThat(billReader.outstanding(owner,accounts,LocalDate.of(2026,12,15)).stream()
                        .filter(b->b.billingMonth().equals(LocalDate.of(2026,12,1))).mapToLong(com.yamu.be.forecast.CardBillReader.OutstandingBill::amount).sum()).isEqualTo(445000);
                service.run("apply",spec); // reapplying must preserve QA purchases
                assertThat(db.queryForObject("SELECT COUNT(*) FROM payment_transaction WHERE payment_transaction_id=?",Long.class,payment)).isEqualTo(1);
            }
        }
        long other=user(life.get(1),0);
        long firstOwner=user(life.get(0),0);
        long account=db.queryForObject("SELECT linked_account_id FROM linked_account WHERE user_id=? AND bank_code='088'",Long.class,firstOwner);
        var actual=new com.yamu.be.payment.bank.BankTransaction("isolated-funding",QaLifeScenario.BASE,java.time.LocalTime.NOON,
                com.yamu.be.payment.bank.BankDirection.DEPOSIT,900000,900000,"");
        when(runtimeClock.instant()).thenReturn(Instant.parse("2026-11-17T04:00:00Z"));
        var ticket=accountHistory.begin(firstOwner,account,QaLifeScenario.BASE,QaLifeScenario.BASE);
        assertThat(accountHistory.complete(ticket,new com.yamu.be.payment.bank.BankHistory(QaLifeScenario.BASE,QaLifeScenario.BASE,null,1L,List.of(actual),Instant.parse("2026-11-17T04:00:00Z")))).isEqualTo("OK");
        var assetSpec=new QaAssetHistory.Spec(life.get(0).datasetKey(),"p0",account,QaLifeScenario.FROM,QaLifeScenario.BASE.minusDays(1),0,0,List.of(),"LIFE","isolated-funding");
        connection.setAutoCommit(false);
        try {
            var assets=new QaAssetHistory(db,Clock.fixed(Instant.parse("2026-11-17T04:00:00Z"),ZoneOffset.UTC));
            assertThat(assets.run("plan",assetSpec).closing()).isEqualTo(900000);
            assets.run("apply",assetSpec);connection.commit();
            assets.run("verify",assetSpec);connection.commit();
            assertThat(accountHistory.read(firstOwner,account,LocalDate.of(2026,10,1),LocalDate.of(2026,10,31)).reason()).isEqualTo("OK");
            assets.run("reset",assetSpec);connection.commit();
        } finally {connection.rollback();connection.setAutoCommit(true);}
        assertThatThrownBy(()->service.run("reset",life.get(0))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Actual bank history is preserved");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM account_transaction WHERE stream_id=?",Long.class,ticket.streamId())).isEqualTo(1);
        // Isolated test fixture cleanup only; production reset intentionally cannot do this.
        for(String table:List.of("account_history_coverage","account_history_sync_run","account_transaction"))
            db.update("DELETE FROM "+table+" WHERE stream_id=?",ticket.streamId());
        db.update("DELETE FROM account_history_stream WHERE stream_id=?",ticket.streamId());
        service.run("reset",life.get(0));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM card_bill WHERE user_id=?",Long.class,other)).isEqualTo(26);
        assertThat(db.queryForObject("SELECT status FROM app_user WHERE user_id=?",String.class,other)).isEqualTo("ACTIVE");
        verify(transfer,never()).withdraw(anyString(),anyString(),anyLong(),any(),anyString());
        verify(transfer,never()).deposit(anyString(),anyString(),anyLong(),any(),anyString());
    }


    @Test void versionThreeLoadsFourCardsAndEnforcesIbkBankBinding() throws Exception {
        ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of("seed/05-ibk-narasarang.sql")));
        var original=spec("ibk551",QaSeedSpec.Scenario.RICH).personas().get(0);
        var accounts=List.of(new QaSeedSpec.Account("004","800000000001"),
                new QaSeedSpec.Account("088","900000000001"),new QaSeedSpec.Account("003","700000000001"));
        var cards=new ArrayList<>(original.cards());
        var first=cards.get(1);
        cards.set(1,new QaSeedSpec.Card(first.issuerCode(),first.productName(),first.cardType(),first.cardNo(),1,15));
        cards.add(new QaSeedSpec.Card("IBK","IBK나라사랑카드","CHECK","9000000000010004",2,null,LocalDate.of(2026,1,1),false,false));
        var person=new QaSeedSpec.Persona(original.key(),original.scenario(),"최인국",original.birthDate(),original.phone(),
                original.ssafyMemberId(),original.ssafyUserKey(),accounts,cards,"MILITARY_EXAM_COMPLETED");
        var spec=new QaSeedSpec("qa-ibk551",3,LocalDate.of(2026,11,12),List.of(person));
        spec.validate(LocalDate.of(2026,11,16));
        apply(spec); signedUp(spec); service.run("enrich",spec); service.run("verify",spec);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM user_card WHERE user_id=?",Long.class,user(spec,0))).isEqualTo(4);
        assertThat(db.queryForObject("SELECT a.bank_code FROM user_card c JOIN linked_account a ON a.linked_account_id=c.linked_account_id JOIN card_product p USING(card_product_id) WHERE c.user_id=? AND p.issuer_code='IBK'",String.class,user(spec,0))).isEqualTo("003");
        service.run("apply",spec);
        service.run("reset",spec);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM user_card WHERE user_id=?",Long.class,user(spec,0))).isEqualTo(4);
    }

    @Test void seededBillConfirmsWithdrawsAndDoesNotWithdrawTwice() throws Exception {
        var spec=spec("billing",QaSeedSpec.Scenario.RICH);
        apply(spec); signedUp(spec); service.run("enrich",spec);
        long bill=db.queryForObject("SELECT MIN(card_bill_id) FROM card_bill",Long.class);
        assertThat(confirmer.confirm(bill,java.time.LocalDateTime.of(2026,11,16,3,0)).kind())
                .isEqualTo(BillConfirmer.Kind.CONFIRMED);
        assertThat(db.queryForObject("SELECT confirmed_amount FROM card_bill WHERE card_bill_id=?",Long.class,bill))
                .isEqualTo(13500);
        when(transfer.withdraw(anyString(),anyString(),anyLong(),any(),anyString()))
                .thenReturn(new TransferResult(TransferResult.Status.SUCCESS,"qa-withdraw",LocalDate.of(2026,11,16),null,null));
        assertThat(withdrawer.withdraw(bill,LocalDate.of(2026,11,16),false).kind()).isEqualTo(BillWithdrawer.Kind.PAID);
        assertThat(withdrawer.withdraw(bill,LocalDate.of(2026,11,16),false).kind()).isEqualTo(BillWithdrawer.Kind.SKIPPED);
        verify(transfer,times(1)).withdraw(anyString(),eq(spec.personas().get(0).accounts().get(1).accountNo()),eq(13500L),any(),anyString());
        service.run("reset",spec);
        assertThat(count("card_bill")).isZero();
    }

    @Test void canceledSeedWaitsForWithdrawalThenRefundsExactlyOnce() throws Exception {
        var spec=spec("refund",QaSeedSpec.Scenario.STATES);
        apply(spec); signedUp(spec); service.run("enrich",spec);
        long refund=db.queryForObject("SELECT refund_id FROM refund",Long.class);
        long bill=db.queryForObject("SELECT card_bill_id FROM card_bill WHERE status='CONFIRMED'",Long.class);
        assertThat(depositor.deposit(refund).outcome()).isEqualTo(RefundDepositor.Outcome.SKIPPED);
        verify(transfer,never()).deposit(anyString(),anyString(),anyLong(),any(),anyString());
        when(transfer.withdraw(anyString(),anyString(),anyLong(),any(),anyString()))
                .thenReturn(new TransferResult(TransferResult.Status.SUCCESS,"qa-withdraw",LocalDate.of(2026,11,16),null,null));
        when(transfer.deposit(anyString(),anyString(),anyLong(),any(),anyString()))
                .thenReturn(new TransferResult(TransferResult.Status.SUCCESS,"qa-refund",LocalDate.of(2026,11,16),null,null));
        assertThat(withdrawer.withdraw(bill,LocalDate.of(2026,11,16),false).kind()).isEqualTo(BillWithdrawer.Kind.PAID);
        assertThat(db.queryForObject("SELECT due_date FROM refund",LocalDate.class)).isEqualTo(LocalDate.of(2026,11,19));
        assertThat(depositor.deposit(refund).outcome()).isEqualTo(RefundDepositor.Outcome.COMPLETED);
        assertThat(depositor.deposit(refund).outcome()).isEqualTo(RefundDepositor.Outcome.SKIPPED);
        verify(transfer,times(1)).withdraw(anyString(),anyString(),eq(12000L),any(),anyString());
        verify(transfer,times(1)).deposit(anyString(),eq(spec.personas().get(0).accounts().get(1).accountNo()),eq(12000L),any(),anyString());
        assertThat(db.queryForObject("SELECT status FROM bill_item WHERE bill_item_id=(SELECT bill_item_id FROM refund)",String.class))
                .isEqualTo("REFUNDED");
        service.run("inspect",spec);
        service.run("reset",spec);
        assertThat(count("refund")).isZero();
    }

    @Test void rejectedWithdrawalCanResetButUnknownWithdrawalCannot() throws Exception {
        var spec=spec("bill-failure",QaSeedSpec.Scenario.RICH);
        apply(spec); signedUp(spec); service.run("enrich",spec);
        long bill=db.queryForObject("SELECT MIN(card_bill_id) FROM card_bill",Long.class);
        confirmer.confirm(bill,java.time.LocalDateTime.of(2026,11,16,3,0));
        when(transfer.withdraw(anyString(),anyString(),anyLong(),any(),anyString()))
                .thenReturn(new TransferResult(TransferResult.Status.UNKNOWN,null,null,null,null));
        assertThat(withdrawer.withdraw(bill,LocalDate.of(2026,11,16),false).kind()).isEqualTo(BillWithdrawer.Kind.UNKNOWN);
        try {
            assertThatThrownBy(()->service.run("reset",spec)).isInstanceOf(IllegalArgumentException.class);
            assertThat(count("card_bill")).isEqualTo(1);
        } finally {
            // Isolated test only: model the bank proving that no transfer occurred before the next case.
            db.update("UPDATE card_bill SET fin_request_no=NULL WHERE card_bill_id=?",bill);
        }
        when(transfer.withdraw(anyString(),anyString(),anyLong(),any(),anyString()))
                .thenReturn(new TransferResult(TransferResult.Status.REJECTED,null,null,TransferResult.RejectReason.INSUFFICIENT_BALANCE,"A1014"));
        assertThat(withdrawer.withdraw(bill,LocalDate.of(2026,11,16),false).kind()).isEqualTo(BillWithdrawer.Kind.UNPAID);
        service.run("reset",spec);
        assertThat(count("card_bill")).isZero();
    }

    @Test void unknownRefundBlocksResetAndDefinitiveAccountFailureAllowsReset() throws Exception {
        var spec=spec("refund-failure",QaSeedSpec.Scenario.STATES);
        apply(spec); signedUp(spec); service.run("enrich",spec);
        long refund=db.queryForObject("SELECT refund_id FROM refund",Long.class);
        long bill=db.queryForObject("SELECT card_bill_id FROM card_bill WHERE status='CONFIRMED'",Long.class);
        when(transfer.withdraw(anyString(),anyString(),anyLong(),any(),anyString()))
                .thenReturn(new TransferResult(TransferResult.Status.SUCCESS,"qa-withdraw",LocalDate.of(2026,11,16),null,null));
        withdrawer.withdraw(bill,LocalDate.of(2026,11,16),false);
        when(transfer.deposit(anyString(),anyString(),anyLong(),any(),anyString()))
                .thenReturn(new TransferResult(TransferResult.Status.UNKNOWN,null,null,null,null));
        assertThat(depositor.deposit(refund).outcome()).isEqualTo(RefundDepositor.Outcome.UNKNOWN);
        try {
            assertThatThrownBy(()->service.run("reset",spec)).isInstanceOf(IllegalArgumentException.class);
            assertThat(count("refund")).isEqualTo(1);
        } finally {
            // Only after simulated bank reconciliation in this isolated test.
            db.update("UPDATE refund SET fin_request_no=NULL WHERE refund_id=?",refund);
        }
        when(transfer.deposit(anyString(),anyString(),anyLong(),any(),anyString()))
                .thenReturn(new TransferResult(TransferResult.Status.REJECTED,null,null,TransferResult.RejectReason.ACCOUNT_NOT_FOUND,"A1003"));
        assertThat(depositor.deposit(refund).outcome()).isEqualTo(RefundDepositor.Outcome.FAILED);
        service.run("reset",spec);
        assertThat(count("refund")).isZero();
    }

    @Test void versionOneCannotCreateDisplayOnlyFixturesButCanPurgeWithOriginalInput() throws Exception {
        var current=spec("legacy",QaSeedSpec.Scenario.EMPTY);
        var legacy=new QaSeedSpec(current.datasetKey(),1,current.baseDate(),current.personas());
        for(String command:List.of("apply","enrich","reset")) {
            assertThatThrownBy(()->service.run(command,legacy)).isInstanceOf(IllegalArgumentException.class);
        }
        service.run("purge",legacy);
        assertThat(count("qa_seed_dataset")).isZero();
    }

    @Test void allFivePersonasRemainConsistentAndRepeatedApplyDoesNotDuplicate() throws Exception {
        var spec=spec("all",QaSeedSpec.Scenario.RICH,QaSeedSpec.Scenario.EMPTY,QaSeedSpec.Scenario.BOUNDARY,
                QaSeedSpec.Scenario.SHORTFALL,QaSeedSpec.Scenario.STATES);
        apply(spec);
        long payments=count("payment_transaction");
        assertThat(payments).isGreaterThan(2000);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM payment_transaction WHERE user_id=?",
                Long.class,user(spec,1))).isZero();
        service.run("apply",spec);
        assertThat(count("payment_transaction")).isEqualTo(payments);
        service.run("verify",spec);
        signedUp(spec);
        service.run("enrich",spec);
        long bills=count("card_bill");
        assertThat(bills).isPositive();
        assertThat(count("notification")).isEqualTo(96);
        assertThat(count("cashflow_item")).isEqualTo(20);
        service.run("enrich",spec);
        assertThat(count("card_bill")).isEqualTo(bills);
        assertThat(count("notification")).isEqualTo(96);
        service.run("verify",spec);
        assertThat(db.queryForObject("""
                SELECT COUNT(*) FROM payment_transaction t WHERE installment_months=3 AND (
                    SELECT SUM(principal_amount) FROM bill_item i
                    WHERE i.payment_transaction_id=t.payment_transaction_id)=t.approved_amount
                """,Long.class)).isEqualTo(1);
        assertThat(db.queryForObject("""
                SELECT recognized_amount FROM card_monthly_performance m JOIN user_card c USING(user_card_id)
                JOIN card_product p USING(card_product_id)
                WHERE c.user_id=? AND p.product_name='신한카드 처음' AND m.performance_month='2026-10-01'
                """,Long.class,user(spec,2))).isEqualTo(299999);
    }
    @Test void changedInputCannotResetOrDeleteAnExistingDataset() throws Exception {
        var spec=spec("changed",QaSeedSpec.Scenario.RICH); apply(spec);
        var changed=new QaSeedSpec(spec.datasetKey(),2,spec.baseDate().minusDays(1),spec.personas());
        long before=count("payment_transaction");
        assertThatThrownBy(()->service.run("reset",changed)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.run("purge",changed)).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("payment_transaction")).isEqualTo(before);
    }
    @Test void normalUserCollisionDoesNotClaimOrDeleteTheUser() throws Exception {
        var spec=spec("collision",QaSeedSpec.Scenario.EMPTY);
        var p=spec.personas().get(0);
        db.update("INSERT INTO app_user(ssafy_member_id,status,created_at,updated_at) VALUES (?,'PENDING',?,?)",
                p.ssafyMemberId(),Timestamp.from(clock.instant()),Timestamp.from(clock.instant()));
        try {
            assertThatThrownBy(()->service.run("apply",spec)).isInstanceOf(IllegalArgumentException.class);
            assertThat(count("qa_seed_member")).isZero();
            assertThat(count("app_user")).isEqualTo(1);
        } finally { db.update("DELETE FROM app_user WHERE ssafy_member_id=?",p.ssafyMemberId()); }
    }
    @Test void failedEnrichmentRollsBackEarlierPersonas() throws Exception {
        var spec=spec("rollback",QaSeedSpec.Scenario.RICH,QaSeedSpec.Scenario.RICH);
        apply(spec); signedUp(spec);
        db.update("UPDATE app_user SET status='PENDING' WHERE user_id=?",user(spec,1));
        assertThatThrownBy(()->service.run("enrich",spec)).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("cashflow_item")).isZero();
        assertThat(count("notification")).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM qa_seed_member WHERE enriched=TRUE",Long.class)).isZero();
    }
    @Test void purgeIncludesRowsCreatedDuringQaAndPreservesSharedCatalogAndOtherUsers() throws Exception {
        var spec=spec("purge",QaSeedSpec.Scenario.RICH); apply(spec); signedUp(spec); service.run("enrich",spec);
        long products=count("card_product"),merchants=count("merchant");
        db.update("INSERT INTO app_user(name,status,created_at,updated_at) VALUES ('보존','PENDING',?,?)",
                Timestamp.from(clock.instant()),Timestamp.from(clock.instant()));
        long outsider=db.queryForObject("SELECT user_id FROM app_user WHERE name='보존'",Long.class);
        db.update("""
                INSERT INTO cashflow_create_request(user_id,request_key,request_fingerprint,cashflow_item_id,created_at)
                VALUES (?,UUID(),UNHEX(REPEAT('00',32)),999999,?)
                """,user(spec,0),Timestamp.from(clock.instant()));
        try {
            service.run("purge",spec); service.run("purge",spec);
            assertThat(count("qa_seed_member")).isZero();
            assertThat(count("payment_transaction")).isZero();
            assertThat(count("cashflow_create_request")).isZero();
            assertThat(count("card_bill")).isZero();
            assertThat(count("app_user")).isEqualTo(1);
            assertThat(count("card_product")).isEqualTo(products);
            assertThat(count("merchant")).isEqualTo(merchants);
        } finally {db.update("DELETE FROM app_user WHERE user_id=?",outsider);}
    }
    @Test void resetReturnsToPendingAndDoesNotReuseIds() throws Exception {
        var spec=spec("reset",QaSeedSpec.Scenario.RICH); apply(spec); signedUp(spec); service.run("enrich",spec);
        long old=user(spec,0),payments=count("payment_transaction");
        service.run("reset",spec);
        assertThat(user(spec,0)).isNotEqualTo(old);
        assertThat(count("payment_transaction")).isEqualTo(payments);
        assertThat(count("cashflow_item")).isZero();
        assertThat(db.queryForObject("SELECT status FROM app_user WHERE user_id=?",String.class,user(spec,0))).isEqualTo("PENDING");
        service.run("verify",spec);
    }
    @Test void unresolvedFinancialOutcomeBlocksPurgeWithoutPartialDeletion() throws Exception {
        var spec=spec("unknown",QaSeedSpec.Scenario.RICH); apply(spec);
        long payment=db.queryForObject("SELECT MIN(payment_transaction_id) FROM payment_transaction",Long.class);
        db.update("UPDATE payment_transaction SET fin_request_no='20261112000000000001' WHERE payment_transaction_id=?",payment);
        try {
            long before=count("payment_transaction");
            assertThatThrownBy(()->service.run("purge",spec)).isInstanceOf(IllegalArgumentException.class);
            assertThat(count("payment_transaction")).isEqualTo(before);
            assertThat(count("qa_seed_member")).isEqualTo(1);
        } finally { db.update("UPDATE payment_transaction SET fin_request_no=NULL WHERE payment_transaction_id=?",payment); }
    }

    @Test void queriesRenderSeededListsAndRejectAnotherUsersDetail() throws Exception {
        var spec=spec("api",QaSeedSpec.Scenario.RICH,QaSeedSpec.Scenario.EMPTY);
        apply(spec); signedUp(spec); service.run("enrich",spec);
        long rich=user(spec,0),empty=user(spec,1);
        var asRich=com.yamu.be.support.AuthUsers.asUser(rich);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/cards").with(asRich))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.cards.length()").value(3));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/payments")
                        .param("yearMonth","2026-11").param("size","20").with(asRich))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.hasNext").value(true));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/notifications").with(asRich))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.unreadCount").value(12));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/billings").with(asRich))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/cashflow-items")
                        .param("from","2026-11-12").param("to","2026-11-30").with(asRich))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        long payment=db.queryForObject("SELECT MIN(payment_transaction_id) FROM payment_transaction WHERE user_id=?",Long.class,rich);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/payments/"+payment).with(asRich))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/payments/"+payment)
                        .with(com.yamu.be.support.AuthUsers.asUser(empty)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        service.run("inspect",spec);
    }
    @Test void advisoryLockRejectsConcurrentCli() throws Exception {
        var spec=spec("locked",QaSeedSpec.Scenario.EMPTY);
        String database=db.queryForObject("SELECT DATABASE()",String.class);
        String lock="qa442:"+java.util.UUID.nameUUIDFromBytes(database.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try(var other=dataSource.getConnection()) {
            var otherDb=new JdbcTemplate(new SingleConnectionDataSource(other,true));
            otherDb.queryForObject("SELECT GET_LOCK(?,0)",Integer.class,lock);
            try {
                assertThatThrownBy(()->service.run("apply",spec)).isInstanceOf(IllegalArgumentException.class);
                assertThat(count("qa_seed_member")).isZero();
            } finally {otherDb.queryForObject("SELECT RELEASE_LOCK(?)",Integer.class,lock);}
        }
    }
    @Test void missingHistoryCannotPassVerification() throws Exception {
        var spec=spec("missing",QaSeedSpec.Scenario.RICH); apply(spec);
        db.update("DELETE FROM card_monthly_performance");
        assertThatThrownBy(()->service.run("verify",spec)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void crossOwnerNotificationBlocksCleanupAndKeepsBothDatasets() throws Exception {
        var spec=spec("cross",QaSeedSpec.Scenario.RICH,QaSeedSpec.Scenario.EMPTY);
        apply(spec);
        long rich=user(spec,0),other=user(spec,1);
        long payment=db.queryForObject("SELECT MIN(payment_transaction_id) FROM payment_transaction WHERE user_id=?",
                Long.class,rich);
        db.update("""
                INSERT INTO notification(user_id,notification_type,deduplication_key,title,body,target_type,
                  target_id,created_at,updated_at) VALUES (?,'PAYMENT_RESULT','qa-cross','test','test',
                  'PAYMENT_TRANSACTION',?,?,?)
                """,other,payment,Timestamp.from(clock.instant()),Timestamp.from(clock.instant()));
        try {
            assertThatThrownBy(()->service.run("purge",spec)).isInstanceOf(IllegalArgumentException.class);
            assertThat(count("qa_seed_member")).isEqualTo(2);
        } finally {db.update("DELETE FROM notification WHERE deduplication_key='qa-cross'");}
    }

    @Test void brokenAggregateIsDetected() throws Exception {
        var spec=spec("verify",QaSeedSpec.Scenario.RICH); apply(spec);
        db.update("UPDATE card_monthly_performance SET recognized_amount=recognized_amount+1");
        assertThatThrownBy(()->service.run("verify",spec)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void benefitRevisionPreservesPendingUsersAndRejectsChangedInput() throws Exception {
        ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of("seed/05-ibk-narasarang.sql")));
        service=new QaSeedService(connection,keys,Clock.fixed(Instant.parse("2026-11-16T03:00:00Z"),ZoneOffset.UTC),cryptoProperties.hmacKey());
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        for(int n=1;n<=3;n++) {
            var input=mapper.readValue(Path.of("seed/qa/life-persona-"+n+".example.json").toFile(),QaSeedSpec.class);
            var p=input.personas().get(0);
            var person=new QaSeedSpec.Persona("p0",p.scenario(),p.name(),p.birthDate(),p.phone(),p.ssafyMemberId(),"revision-test-only",p.accounts(),p.cards(),p.issuanceEligibility());
            var spec=new QaSeedSpec("qa-benefit-revision-"+n,3,input.baseDate(),List.of(person));
            apply(spec);
            long owner=user(spec,0);
            long target=db.queryForObject("SELECT t.payment_transaction_id FROM payment_transaction t JOIN card_product c USING(card_product_id) WHERE t.user_id=? AND c.issuer_code='IBK' AND t.approved_at='2026-11-07 15:00:00'",Long.class,owner);
            // Emulate the previous revision without deleting the user's purchases or authentication state.
            db.update("DELETE FROM payment_limit_usage WHERE payment_transaction_id=?",target);
            db.update("DELETE FROM payment_benefit WHERE payment_transaction_id=?",target);
            db.update("UPDATE payment_benefit b JOIN payment_transaction t USING(payment_transaction_id) SET b.confirmed_at=NULL,b.confirmation_source=NULL WHERE t.user_id=?",owner);
            db.update("UPDATE payment_transaction SET approved_amount=22000,merchant_id=(SELECT merchant_id FROM merchant WHERE brand_code='YAMU_TEST_RESTAURANT') WHERE payment_transaction_id=?",target);
            String hash=db.queryForObject("SELECT HEX(input_hash) FROM qa_seed_dataset WHERE dataset_key=?",String.class,spec.datasetKey());
            assertThatThrownBy(()->service.reviseBenefits(spec,"00".repeat(32))).isInstanceOf(IllegalArgumentException.class);
            assertThat(db.queryForObject("SELECT approved_amount FROM payment_transaction WHERE payment_transaction_id=?",Long.class,target)).isEqualTo(22000);
            long later=db.queryForObject("SELECT payment_transaction_id FROM payment_transaction WHERE user_id=? ORDER BY approved_at DESC LIMIT 1",Long.class,owner);
            db.update("UPDATE payment_transaction SET approved_amount=approved_amount+1 WHERE payment_transaction_id=?",later);
            assertThatThrownBy(()->service.reviseBenefits(spec,hash)).isInstanceOf(IllegalArgumentException.class);
            assertThat(db.queryForObject("SELECT approved_amount FROM payment_transaction WHERE payment_transaction_id=?",Long.class,target)).isEqualTo(22000);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM payment_benefit WHERE payment_transaction_id=?",Long.class,target)).isZero();
            db.update("UPDATE payment_transaction SET approved_amount=approved_amount-1 WHERE payment_transaction_id=?",later);
            service.reviseBenefits(spec,hash);
            String updated=db.queryForObject("SELECT HEX(input_hash) FROM qa_seed_dataset WHERE dataset_key=?",String.class,spec.datasetKey());
            service.reviseBenefits(spec,updated);
            assertThat(db.queryForObject("SELECT approved_amount FROM payment_transaction WHERE payment_transaction_id=?",Long.class,target)).isEqualTo(19800);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM payment_benefit WHERE payment_transaction_id=?",Long.class,target)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT status FROM app_user WHERE user_id=?",String.class,owner)).isEqualTo("PENDING");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM linked_account WHERE user_id=?",Long.class,owner)).isZero();
            assertThat(db.queryForObject("SELECT COUNT(*) FROM payment_transaction WHERE user_id=?",Long.class,owner)).isEqualTo(395);
            // Explicit QA evidence must not turn a normal payment into a confirmed point award.
            long point=db.queryForObject("SELECT b.payment_transaction_id FROM payment_benefit b JOIN payment_transaction t USING(payment_transaction_id) WHERE t.user_id=? AND b.value_unit='POINT' AND b.confirmed_at IS NOT NULL LIMIT 1",Long.class,owner);
            String query="SELECT "+com.yamu.be.payment.history.PaymentSqlFragments.CONFIRMED_LINE+" FROM payment_benefit b JOIN card_benefit cb USING(card_benefit_id) WHERE b.payment_transaction_id=?";
            assertThat(db.queryForObject(query,Boolean.class,point)).isTrue();
            assertThatThrownBy(()->db.update("UPDATE payment_benefit SET confirmation_source=NULL WHERE payment_transaction_id=?",point))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("ck_benefit_confirmation_pair");
            db.update("UPDATE payment_transaction SET merchant_context_snapshot=JSON_SET(merchant_context_snapshot,'$.source','NORMAL') WHERE payment_transaction_id=?",point);
            assertThat(db.queryForObject(query,Boolean.class,point)).isFalse();
        }
        verifyNoInteractions(transfer);
    }
    @Test void lifeNotificationsUseProductionMessagesAndEventTimes() throws Exception {
        ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of("seed/05-ibk-narasarang.sql")));
        service=new QaSeedService(connection,keys,Clock.fixed(Instant.parse("2026-11-16T03:00:00Z"),ZoneOffset.UTC),cryptoProperties.hmacKey());
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        var input=mapper.readValue(Path.of("seed/qa/life-persona-1.example.json").toFile(),QaSeedSpec.class);
        var p=input.personas().get(0);
        var person=new QaSeedSpec.Persona("p0",p.scenario(),p.name(),p.birthDate(),p.phone(),p.ssafyMemberId(),"notification-test-only",p.accounts(),p.cards(),p.issuanceEligibility());
        var spec=new QaSeedSpec("qa-notification-production",3,input.baseDate(),List.of(person));
        apply(spec);signedUp(spec);service.run("enrich",spec);
        long owner=user(spec,0);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id=? AND read_at IS NOT NULL",Long.class,owner)).isEqualTo(250);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id=? AND read_at IS NULL",Long.class,owner)).isEqualTo(14);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id=? AND read_at IS NULL AND created_at < ?",Long.class,owner,Timestamp.from(Instant.parse("2026-10-31T15:00:00Z")))).isZero();
        assertThat(db.queryForObject("""
            SELECT COUNT(*) FROM notification n JOIN payment_transaction p ON n.target_id=p.payment_transaction_id AND n.user_id=p.user_id
            JOIN merchant m ON m.merchant_id=p.merchant_id
            WHERE n.user_id=? AND n.notification_type='PAYMENT_RESULT'
              AND (n.title<>'결제 승인' OR n.created_at<>p.approved_at OR n.body NOT LIKE CONCAT('%',COALESCE(NULLIF(p.merchant_context_snapshot->>'$.merchantName','null'),m.merchant_name),'%'))
            """,Long.class,owner)).isZero();
        long total=db.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id=?",Long.class,owner);
        assertThat(total).isGreaterThan(24);
        var since=QaLifeBenefits.SIGNUP.atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toInstant();
        assertThat(com.yamu.be.notification.SeedNotificationReplay.replay(db,spec.datasetKey(),owner,since,runtimeClock.instant())).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id=?",Long.class,owner)).isEqualTo(total);
        db.update("DELETE FROM notification WHERE user_id=?",owner);
        db.update("UPDATE app_user SET notification_settings=JSON_OBJECT('transactionEnabled',false) WHERE user_id=?",owner);
        assertThat(com.yamu.be.notification.SeedNotificationReplay.replay(db,spec.datasetKey(),owner,since,runtimeClock.instant())).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id=?",Long.class,owner)).isZero();
        verifyNoInteractions(transfer);
    }
    private QaSeedSpec displaySpec(String dataset) throws Exception {
        ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of("seed/05-ibk-narasarang.sql")));
        service=new QaSeedService(connection,keys,Clock.fixed(Instant.parse("2026-11-17T03:00:00Z"),ZoneOffset.UTC),cryptoProperties.hmacKey());
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        var input=mapper.readValue(Path.of("seed/qa/life-persona-1.example.json").toFile(),QaSeedSpec.class);
        var p=input.personas().get(0);
        var person=new QaSeedSpec.Persona("p0",p.scenario(),p.name(),p.birthDate(),p.phone(),p.ssafyMemberId(),"isolated-display-test",p.accounts(),p.cards(),p.issuanceEligibility());
        var spec=new QaSeedSpec(dataset,3,input.baseDate(),List.of(person));
        apply(spec);return spec;
    }

    private void legacyDisplay(long owner) {
        db.update("UPDATE payment_transaction p JOIN merchant m USING(merchant_id) SET p.merchant_context_snapshot=JSON_REMOVE(p.merchant_context_snapshot,'$.merchantName','$.displayNameSource') WHERE p.user_id=? AND m.brand_code='YAMU_TEST_RESTAURANT'",owner);
    }
    @Test void displayRevisionDryRunCommitRetryPreservesMoneyAndLivePurchases() throws Exception {
        var spec=displaySpec("qa-550-life-1");signedUp(spec);service.run("enrich",spec);long owner=user(spec,0);
        legacyDisplay(owner);
        var ids=db.queryForList("SELECT p.payment_transaction_id FROM payment_transaction p JOIN merchant m USING(merchant_id) WHERE p.user_id=? AND m.brand_code='YAMU_TEST_RESTAURANT' ORDER BY p.payment_transaction_id",Long.class,owner);
        for(long id:ids) com.yamu.be.notification.SeedNotificationReplay.refreshPayment(db,owner,id);
        db.update("""
            INSERT INTO payment_transaction(user_id,user_card_id,card_product_id,merchant_id,payment_channel,facility_type,
              merchant_context_snapshot,request_key,request_fingerprint,source,status,requested_amount,approved_amount,
              installment_months,recognized_performance_amount,performance_month,requested_at,approved_at,created_at,updated_at)
            SELECT user_id,user_card_id,card_product_id,merchant_id,payment_channel,facility_type,
              JSON_OBJECT('merchantName','사용자가 직접 만든 식사','source','LIVE_APP'),UUID(),UNHEX(SHA2(UUID(),256)),
              'YAMU','APPROVED',requested_amount,approved_amount,installment_months,recognized_performance_amount,
              performance_month,requested_at,approved_at,created_at,updated_at
            FROM payment_transaction WHERE payment_transaction_id=?
            """,ids.get(0));
        var protectedBefore=db.queryForList("SELECT payment_transaction_id,requested_amount,approved_amount,recognized_performance_amount,source,status,merchant_id,request_fingerprint FROM payment_transaction WHERE user_id=? ORDER BY payment_transaction_id",owner);
        var notificationsBefore=db.queryForList("SELECT notification_id,created_at,read_at,target_id FROM notification WHERE user_id=? ORDER BY notification_id",owner);
        var benefitsBefore=db.queryForList("SELECT b.* FROM payment_benefit b JOIN payment_transaction p USING(payment_transaction_id) WHERE p.user_id=? ORDER BY payment_benefit_id",owner);
        var legacyBefore=db.queryForList("SELECT * FROM payment_transaction WHERE user_id=? ORDER BY payment_transaction_id",owner);
        service.run("plan-display-names",spec);
        assertThat(db.queryForList("SELECT * FROM payment_transaction WHERE user_id=? ORDER BY payment_transaction_id",owner)).usingRecursiveComparison().isEqualTo(legacyBefore);
        service.run("revise-display-names",spec);
        assertThat(db.queryForObject("SELECT COUNT(DISTINCT p.merchant_context_snapshot->>'$.merchantName') FROM payment_transaction p JOIN merchant m USING(merchant_id) WHERE p.user_id=? AND m.brand_code='YAMU_TEST_RESTAURANT' AND p.merchant_context_snapshot->>'$.displayNameSource'='QA_DISPLAY_550_V1'",Long.class,owner)).isEqualTo(4);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id=? AND body REGEXP '테스트|느티나무'",Long.class,owner)).isZero();
        assertThat(db.queryForList("SELECT payment_transaction_id,requested_amount,approved_amount,recognized_performance_amount,source,status,merchant_id,request_fingerprint FROM payment_transaction WHERE user_id=? ORDER BY payment_transaction_id",owner)).usingRecursiveComparison().isEqualTo(protectedBefore);
        assertThat(db.queryForList("SELECT notification_id,created_at,read_at,target_id FROM notification WHERE user_id=? ORDER BY notification_id",owner)).usingRecursiveComparison().isEqualTo(notificationsBefore);
        assertThat(db.queryForList("SELECT b.* FROM payment_benefit b JOIN payment_transaction p USING(payment_transaction_id) WHERE p.user_id=? ORDER BY payment_benefit_id",owner)).usingRecursiveComparison().isEqualTo(benefitsBefore);
        var after=db.queryForList("SELECT * FROM payment_transaction WHERE user_id=? ORDER BY payment_transaction_id",owner);
        service.run("revise-display-names",spec);
        assertThat(db.queryForList("SELECT * FROM payment_transaction WHERE user_id=? ORDER BY payment_transaction_id",owner)).usingRecursiveComparison().isEqualTo(after);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM payment_transaction WHERE user_id=? AND merchant_context_snapshot->>'$.source'='LIVE_APP' AND merchant_context_snapshot->>'$.merchantName'='사용자가 직접 만든 식사'",Long.class,owner)).isEqualTo(1);
        verifyNoInteractions(transfer);
    }

    @Test void displayRevisionFailureRollsBackEarlierRows() throws Exception {
        var spec=displaySpec("qa-550-life-1");long owner=user(spec,0);legacyDisplay(owner);
        long last=db.queryForObject("SELECT MAX(p.payment_transaction_id) FROM payment_transaction p JOIN merchant m USING(merchant_id) WHERE p.user_id=? AND m.brand_code='YAMU_TEST_RESTAURANT'",Long.class,owner);
        db.update("UPDATE payment_transaction SET merchant_context_snapshot=JSON_SET(merchant_context_snapshot,'$.merchantName','직접 수정한 이름') WHERE payment_transaction_id=?",last);
        var before=db.queryForList("SELECT * FROM payment_transaction WHERE user_id=? ORDER BY payment_transaction_id",owner);
        assertThatThrownBy(()->service.run("revise-display-names",spec)).hasMessageContaining("Edited display name");
        assertThat(db.queryForList("SELECT * FROM payment_transaction WHERE user_id=? ORDER BY payment_transaction_id",owner)).usingRecursiveComparison().isEqualTo(before);verifyNoInteractions(transfer);
    }

    @Test void displayRevisionRejectsUnregisteredDataset() throws Exception {
        var spec=displaySpec("qa-not-display-target");long owner=user(spec,0);legacyDisplay(owner);
        var before=db.queryForList("SELECT * FROM payment_transaction WHERE user_id=? ORDER BY payment_transaction_id",owner);
        assertThatThrownBy(()->service.run("revise-display-names",spec)).hasMessageContaining("Only the three fixed LIFE");
        assertThat(db.queryForList("SELECT * FROM payment_transaction WHERE user_id=? ORDER BY payment_transaction_id",owner)).usingRecursiveComparison().isEqualTo(before);
    }
    @Test void displayRevisionPreservesEditedCandidatesAndOverriddenOccurrences() throws Exception {
        var spec=displaySpec("qa-550-life-1");long owner=user(spec,0);legacyDisplay(owner);
        long card=db.queryForObject("SELECT user_card_id FROM user_card WHERE user_id=? ORDER BY user_card_id DESC LIMIT 1",Long.class,owner);
        long merchant=db.queryForObject("SELECT merchant_id FROM merchant WHERE brand_code='YAMU_TEST_RESTAURANT'",Long.class);
        for(long amount:List.of(22000L,88000L)) {
            db.update("""
                INSERT INTO cashflow_item(user_id,source,direction,item_kind,title,status,amount,scheduled_date,
                  recurrence_type,recurrence_day_of_month,user_card_id,detected_user_card_id,detected_merchant_id,detected_amount,created_at,updated_at)
                VALUES (?,'DETECTED','EXPENSE','SCHEDULED',?,'CANDIDATE',?,'2026-12-01','MONTHLY',1,?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """,owner,amount==22000L?"야무 테스트 식당":"직접 수정한 식당",amount,card,card,merchant,amount);
            long id=db.queryForObject("SELECT cashflow_item_id FROM cashflow_item WHERE user_id=? AND detected_amount=?",Long.class,owner,amount);
            db.update("""
                INSERT INTO cashflow_occurrence(cashflow_item_id,user_id,occurrence_date,title,direction,amount,status,scheduled_date,is_overridden,created_at,updated_at)
                VALUES (?,?,'2026-12-01',?,'EXPENSE',?,'CONFIRMED','2026-12-01',TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """,id,owner,"개별 회차 사용자 메모",amount);
        }
        var occurrenceBefore=db.queryForList("SELECT * FROM cashflow_occurrence WHERE user_id=? ORDER BY cashflow_occurrence_id",owner);
        service.run("revise-display-names",spec);
        assertThat(db.queryForObject("SELECT title FROM cashflow_item WHERE user_id=? AND detected_amount=22000",String.class,owner)).doesNotContain("테스트");
        assertThat(db.queryForObject("SELECT title FROM cashflow_item WHERE user_id=? AND detected_amount=88000",String.class,owner)).isEqualTo("직접 수정한 식당");
        assertThat(db.queryForList("SELECT * FROM cashflow_occurrence WHERE user_id=? ORDER BY cashflow_occurrence_id",owner)).usingRecursiveComparison().isEqualTo(occurrenceBefore);
    }

    @Test void displayRevisionRefusesSharedCafeWithAnyExistingPurchaseAndRollsBack() throws Exception {
        var spec=displaySpec("qa-550-life-1");long owner=user(spec,0);legacyDisplay(owner);
        long cafe=db.queryForObject("SELECT merchant_id FROM merchant WHERE brand_code='YAMU_TEST_CAFE'",Long.class);
        db.update("UPDATE merchant SET merchant_name='오월커피' WHERE merchant_id=?",cafe);
        db.update("""
            INSERT INTO payment_transaction(user_id,user_card_id,card_product_id,merchant_id,payment_channel,facility_type,
              merchant_context_snapshot,request_key,request_fingerprint,source,status,requested_amount,approved_amount,
              installment_months,recognized_performance_amount,performance_month,requested_at,approved_at,created_at,updated_at)
            SELECT user_id,user_card_id,card_product_id,?,payment_channel,facility_type,
              JSON_OBJECT('merchantName','사용자 카페 구매','source','LIVE_APP'),UUID(),UNHEX(SHA2(UUID(),256)),
              'YAMU','APPROVED',requested_amount,approved_amount,installment_months,recognized_performance_amount,
              performance_month,requested_at,approved_at,created_at,updated_at
            FROM payment_transaction WHERE user_id=? ORDER BY payment_transaction_id LIMIT 1
            """,cafe,owner);
        var before=db.queryForList("SELECT * FROM payment_transaction WHERE user_id=? ORDER BY payment_transaction_id",owner);
        try {
            assertThatThrownBy(()->service.run("revise-display-names",spec)).hasMessageContaining("Cafe has existing purchases");
            assertThat(db.queryForList("SELECT * FROM payment_transaction WHERE user_id=? ORDER BY payment_transaction_id",owner)).usingRecursiveComparison().isEqualTo(before);
            assertThat(db.queryForObject("SELECT merchant_name FROM merchant WHERE merchant_id=?",String.class,cafe)).isEqualTo("오월커피");
        } finally { db.update("UPDATE merchant SET merchant_name='메가MGC커피' WHERE merchant_id=?",cafe); }
        verifyNoInteractions(transfer);
    }

}
