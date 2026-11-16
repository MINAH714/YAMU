package com.yamu.be.accounthistory;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.payment.bank.*;
import com.yamu.be.seed.qa.*;
import com.yamu.be.global.exception.BusinessException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;

@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class AccountHistoryStoreTest extends MySqlTestSupport {
    @Autowired AccountHistoryStore store;
    @Autowired JdbcTemplate db;
    @Autowired DataSource dataSource;
    long user,account;
    static final LocalDate DAY=LocalDate.of(2026,11,12);
    static final Instant NOW=Instant.parse("2026-11-16T03:00:00Z");
    @BeforeEach void setup() {
        db.update("INSERT INTO app_user(created_at,updated_at) VALUES (UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        user=db.queryForObject("SELECT MAX(user_id) FROM app_user",Long.class);
        db.update("""
            INSERT INTO linked_account(user_id,bank_code,account_no_enc,account_no_hash,status,connected_at,created_at,updated_at)
            VALUES (?,'088',X'01',?,'CONNECTED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
            """,user,new byte[32]);
        account=db.queryForObject("SELECT MAX(linked_account_id) FROM linked_account",Long.class);
    }
    BankHistory snapshot(LocalDate from,LocalDate to,BankTransaction... rows) {
        return new BankHistory(from,to,null,(long)rows.length,List.of(rows),NOW);
    }
    BankTransaction deposit(String id,LocalDate day,long amount,long balance) {
        return new BankTransaction(id,day,LocalTime.NOON,BankDirection.DEPOSIT,amount,balance,"");
    }
    String sync(LocalDate from,LocalDate to,BankTransaction... rows) {
        return store.complete(store.begin(user,account,from,to),snapshot(from,to,rows));
    }
    @Test void materializedFundingConnectsWithoutAddingDepositTwiceAndFailsClosedOnConflict() throws Exception {
        sync(DAY,DAY,deposit("funding",DAY,900_000,900_000));
        String dataset="qa-funding-"+user;
        db.update("INSERT INTO qa_seed_dataset(dataset_key,seed_version,base_date,input_hash,run_id,created_at) VALUES (?,3,?,?,'00000000-0000-0000-0000-000000000001',UTC_TIMESTAMP(6))",dataset,DAY,new byte[32]);
        db.update("INSERT INTO qa_seed_member(dataset_key,persona_key,user_id,enriched) VALUES (?,'life',?,TRUE)",dataset,user);
        var spec=new QaAssetHistory.Spec(dataset,"life",account,DAY.minusDays(3),DAY.minusDays(1),0,0,
                List.of(new QaAssetHistoryPlan.Event("rent",DAY.minusDays(2),BankDirection.WITHDRAWAL,700_000)),null,"funding");
        try(var connection=dataSource.getConnection()) {
            connection.setAutoCommit(false);
            var qa=new QaAssetHistory(new JdbcTemplate(new SingleConnectionDataSource(connection,true)),Clock.fixed(NOW,ZoneOffset.UTC));
            var plan=qa.run("plan",spec);
            assertThat(plan.opening()).isEqualTo(1_600_000);
            assertThat(plan.closing()).isEqualTo(900_000);
            qa.run("apply",spec); connection.commit();
            qa.run("apply",spec); connection.commit();
            qa.run("verify",spec); connection.commit();
            var view=store.read(user,account,spec.from(),DAY);
            assertThat(view.reason()).isEqualTo("OK");
            assertThat(view.days().get(DAY.minusDays(1)).balance()).isEqualTo(900_000);
            assertThat(view.days().get(DAY).balance()).isEqualTo(900_000); // not 1,800,000
            assertThat(view.days().get(DAY).source()).isEqualTo(HistorySource.SSAFY);
            assertThat(sync(DAY,DAY,deposit("funding",DAY,800_000,800_000))).isEqualTo("SOURCE_CONFLICT");
            assertThat(store.read(user,account,spec.from(),spec.to()).reason()).isEqualTo("QA_BOUNDARY_UNVERIFIED");
            assertThatThrownBy(()->qa.run("verify",spec)).isInstanceOf(IllegalArgumentException.class);
            connection.rollback();
            qa.run("reset",spec);connection.commit();
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM account_transaction WHERE external_id='funding' AND stream_id IN (SELECT stream_id FROM account_history_stream WHERE linked_account_id=?)",Long.class,account)).isEqualTo(1);
    }
    @Test void storesOnceAndNeverInventsPreOpeningZero() {
        var row=deposit("first",DAY,300,300);
        assertThat(sync(DAY.withDayOfMonth(1),DAY,row)).isEqualTo("OK");
        assertThat(sync(DAY.withDayOfMonth(1),DAY,row)).isEqualTo("OK");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM account_transaction WHERE stream_id IN (SELECT stream_id FROM account_history_stream WHERE linked_account_id=?)",Long.class,account)).isEqualTo(1);
        var view=store.read(user,account,DAY.withDayOfMonth(1),DAY);
        assertThat(view.days()).containsOnlyKeys(DAY);
    }
    @Test void completeEmptyRangeRequiresVerifiedAdjacentCoverage() {
        assertThat(sync(DAY,DAY)).isEqualTo("NO_ANCHOR");
        assertThat(store.read(user,account,DAY,DAY).days()).isEmpty();
        sync(DAY,DAY,deposit("first",DAY,300,300));
        assertThat(sync(DAY.plusDays(1),DAY.plusDays(2))).isEqualTo("OK");
        assertThat(store.read(user,account,DAY.plusDays(1),DAY.plusDays(2)).days().values()).allSatisfy(d->assertThat(d.balance()).isEqualTo(300));
        assertThat(db.queryForObject("SELECT opening_evidence FROM account_history_coverage WHERE stream_id IN (SELECT stream_id FROM account_history_stream WHERE linked_account_id=?) ORDER BY coverage_id DESC LIMIT 1",String.class,account))
                .isEqualTo("PREVIOUS_COVERAGE");
        assertThat(sync(DAY.plusDays(3),DAY.plusDays(3))).isEqualTo("OK");
        assertThat(store.read(user,account,DAY.plusDays(3),DAY.plusDays(3)).days().get(DAY.plusDays(3)).balance()).isEqualTo(300);
    }
    @Test void missingRangeCannotCarryBalanceAcrossGap() {
        sync(DAY,DAY,deposit("first",DAY,300,300));
        assertThat(sync(DAY.plusDays(2),DAY.plusDays(2))).isEqualTo("NO_ANCHOR");
        assertThat(store.read(user,account,DAY.plusDays(2),DAY.plusDays(2)).days()).isEmpty();
    }
    @Test void schemaHasNoAnchorTableAndOpeningEvidenceIsRequired() {
        assertThat(db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='account_balance_anchor'",Long.class)).isZero();
        sync(DAY,DAY,deposit("first",DAY,300,300));
        assertThat(db.queryForObject("SELECT opening_evidence FROM account_history_coverage WHERE stream_id IN (SELECT stream_id FROM account_history_stream WHERE linked_account_id=?)",String.class,account))
                .isEqualTo("FIRST_TRANSACTION");
        assertThatThrownBy(()->db.update("UPDATE account_history_coverage SET opening_transaction_id=NULL WHERE stream_id IN (SELECT stream_id FROM account_history_stream WHERE linked_account_id=?)",account))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void supersededRunCannotOverwriteNewerRun() {
        var old=store.begin(user,account,DAY,DAY);
        var newer=store.begin(user,account,DAY,DAY);
        assertThat(store.complete(newer,snapshot(DAY,DAY,deposit("first",DAY,300,300)))).isEqualTo("OK");
        assertThat(store.complete(old,snapshot(DAY,DAY))).isEqualTo("SUPERSEDED");
        assertThat(store.read(user,account,DAY,DAY).days().get(DAY).balance()).isEqualTo(300);
    }
    @Test void conflictInvalidatesDependentBalancesButPreservesOriginal() {
        sync(DAY,DAY,deposit("first",DAY,300,300));
        sync(DAY.plusDays(1),DAY.plusDays(1));
        assertThat(sync(DAY,DAY,deposit("first",DAY,500,500))).isEqualTo("SOURCE_CONFLICT");
        assertThat(store.read(user,account,DAY,DAY.plusDays(1)).days()).isEmpty();
        assertThat(db.queryForObject("SELECT balance_after FROM account_transaction WHERE external_id='first' AND stream_id IN (SELECT stream_id FROM account_history_stream WHERE linked_account_id=?)",Long.class,account)).isEqualTo(300);
    }
    @Test void externalFailurePreservesVerifiedHistoryAndOwnershipIsEnforced() {
        sync(DAY,DAY,deposit("first",DAY,300,300));
        store.failed(store.begin(user,account,DAY,DAY),"UPSTREAM_TIMEOUT");
        assertThat(store.read(user,account,DAY,DAY).days()).containsKey(DAY);
        assertThatThrownBy(()->store.begin(user+10000,account,DAY,DAY)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->store.read(user+10000,account,DAY,DAY)).isInstanceOf(BusinessException.class);
    }
    @Test void incompleteAndAmbiguousResponsesAreNotConfirmed() {
        var ticket=store.begin(user,account,DAY,DAY);
        assertThat(store.complete(ticket,new BankHistory(DAY,DAY,null,2L,List.of(),NOW))).isEqualTo("HISTORY_INCOMPLETE");
        assertThat(sync(DAY,DAY,deposit("a",DAY,10,10),deposit("b",DAY,10,20))).isEqualTo("AMBIGUOUS_ORDER");
        assertThat(store.read(user,account,DAY,DAY).days()).isEmpty();
    }
    @Test void simultaneousStartsHaveDistinctGenerationsAndOnlyLatestCanCommit() throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->store.begin(user,account,DAY,DAY));
            var second=pool.submit(()->store.begin(user,account,DAY,DAY));
            var a=first.get();var b=second.get();
            assertThat(a.generation()).isNotEqualTo(b.generation());
            var older=a.generation()<b.generation()?a:b;
            var latest=a.generation()>b.generation()?a:b;
            assertThat(store.complete(older,snapshot(DAY,DAY))).isEqualTo("SUPERSEDED");
            assertThat(store.complete(latest,snapshot(DAY,DAY,deposit("first",DAY,300,300)))).isEqualTo("OK");
        } finally { pool.shutdownNow(); }
    }
    @Test void databaseRejectsCrossOwnerStream() {
        assertThatThrownBy(()->db.update("""
            INSERT INTO account_history_stream(linked_account_id,user_id,source,created_at,updated_at)
            VALUES (?,?,'SSAFY',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
            """,account,user+10000)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void qaOnlyResetPreservesAccountAndActualHistory() throws Exception {
        sync(DAY,DAY,deposit("first",DAY,300,300));
        String dataset="history-"+user;
        db.update("INSERT INTO qa_seed_dataset(dataset_key,seed_version,base_date,input_hash,run_id,created_at) VALUES (?,2,?,?,'00000000-0000-0000-0000-000000000001',UTC_TIMESTAMP(6))",dataset,DAY,new byte[32]);
        db.update("INSERT INTO qa_seed_member(dataset_key,persona_key,user_id,enriched) VALUES (?,'rich',?,TRUE)",dataset,user);
        var spec=new QaAssetHistory.Spec(dataset,"rich",account,DAY.minusDays(3),DAY.minusDays(1),0,100,List.of());
        try(var connection=dataSource.getConnection()) {
            connection.setAutoCommit(false);
            var jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            var qa=new QaAssetHistory(jdbc,Clock.fixed(NOW,ZoneOffset.UTC));
            assertThat(qa.run("plan",spec).closing()).isZero();
            qa.run("apply",spec);connection.commit();
            qa.run("verify",spec);connection.commit();
            assertThat(store.read(user,account,DAY.minusDays(3),DAY).days().get(DAY.minusDays(1)).source()).isEqualTo(HistorySource.QA_SYNTHETIC);
            qa.run("apply",spec);connection.commit();
            sync(DAY,DAY,deposit("first",DAY,400,400));
            assertThat(store.read(user,account,DAY.minusDays(3),DAY.minusDays(1)).reason()).isEqualTo("QA_BOUNDARY_UNVERIFIED");
            assertThat(store.read(user,account,DAY.minusDays(3),DAY.minusDays(1)).days()).isEmpty();
            sync(DAY,DAY,deposit("first",DAY,300,300));
            qa.run("verify",spec);connection.commit();
            qa.run("reset",spec);connection.commit();
            var empty=new QaAssetHistory.Spec(dataset,"rich",account,spec.from(),spec.to(),0,0,List.of());
            qa.run("apply",empty);connection.commit();
            qa.run("verify",empty);connection.commit();
            assertThat(store.read(user,account,spec.from(),spec.to()).days().values())
                    .hasSize(3).allSatisfy(d->assertThat(d.balance()).isZero());
            qa.run("reset",empty);connection.commit();
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM linked_account WHERE linked_account_id=?",Long.class,account)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM qa_seed_member WHERE user_id=?",Long.class,user)).isEqualTo(1);
        assertThat(store.read(user,account,DAY,DAY).days().get(DAY).balance()).isEqualTo(300);
        assertThat(store.read(user,account,DAY.minusDays(3),DAY.minusDays(1)).days()).isEmpty();
    }
}
