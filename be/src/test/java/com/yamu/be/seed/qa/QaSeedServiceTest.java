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
    @Autowired CryptoProperties cryptoProperties;
    @Autowired DataSource dataSource;
    @Autowired BillConfirmer confirmer;
    @Autowired BillWithdrawer withdrawer;
    @Autowired RefundDepositor depositor;
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
            for(int a=0;a<2;a++) {
                var account=p.accounts().get(a);
                db.update("""
                        INSERT INTO linked_account(user_id,bank_code,account_no_enc,account_no_hash,account_last4,
                          status,is_primary,connected_at,created_at,updated_at)
                        VALUES (?,?,?,?,?,'CONNECTED',?,?,?,?)
                        """,user,account.bankCode(),cipher.encrypt(account.accountNo(),CipherField.LINKED_ACCOUNT_NO),
                        lookup.account(account.bankCode(),account.accountNo()),"0001",a==0,now,now,now);
            }
            db.update("""
                    UPDATE user_card c JOIN linked_account a ON a.user_id=c.user_id
                      AND a.bank_code=c.issuer_account_bank_code AND a.account_no_hash=c.issuer_account_hash
                    SET c.status='ACTIVE',c.registered_at=?,c.linked_account_id=a.linked_account_id WHERE c.user_id=?
                    """,now,user);
        }
    }
    private long count(String table) { return db.queryForObject("SELECT COUNT(*) FROM "+table,Long.class); }

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
        var spec=spec("all",QaSeedSpec.Scenario.values());
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
}
