package com.yamu.be.seed.qa;

import com.yamu.be.accounthistory.*;
import com.yamu.be.account.domain.LinkedAccount;
import com.yamu.be.assetflow.PersistedAssetFlowHistoryReader;
import com.yamu.be.global.crypto.*;
import com.yamu.be.support.MySqlTestSupport;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class QaAccountHistorySyncCliTest extends MySqlTestSupport {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-16T16:00:00Z"), ZoneOffset.UTC);
    static final LocalDate TODAY = LocalDate.of(2026,11,17);
    @Autowired Environment environment;
    JdbcTemplate db;
    EntityManager em;
    PersonalDataCipher cipher;
    PersistedAssetFlowHistoryReader history;
    AccountHistoryStore store;

    @BeforeEach void targets() {
        db=mock(JdbcTemplate.class);em=mock(EntityManager.class);cipher=mock(PersonalDataCipher.class);
        history=mock(PersistedAssetFlowHistoryReader.class);store=mock(AccountHistoryStore.class);
        for(int n=1;n<=3;n++) {
            long user=n+10;
            when(db.queryForList(contains("FROM qa_seed_member"),eq("qa-550-life-"+n),eq("qa-"+n)))
                    .thenReturn(List.of(Map.of("user_id",user)));
            when(db.queryForObject(contains("FROM user_card"),eq(Integer.class),eq(user))).thenReturn(4);
            var rows=new ArrayList<Map<String,Object>>();
            for(int bank=0;bank<3;bank++) {
                long id=user*10+bank;
                rows.add(Map.of("linked_account_id",id,"bank_code",List.of("004","088","003").get(bank),"is_primary",bank==0));
                var account=mock(LinkedAccount.class);
                when(account.getId()).thenReturn(id);when(account.getUserId()).thenReturn(user);when(account.isConnected()).thenReturn(true);
                when(em.find(LinkedAccount.class,id)).thenReturn(account);
                when(store.read(user,id,TODAY,TODAY)).thenReturn(new AccountHistoryStore.View(
                        Map.of(TODAY,new AccountHistoryStore.Day(300000L,HistorySource.SSAFY)),"OK"));
            }
            when(db.queryForList(contains("FROM linked_account"),eq(user))).thenReturn(rows);
            when(db.queryForObject(contains("ssafy_user_key FROM app_user"),eq(byte[].class),eq(user))).thenReturn(new byte[]{1});
        }
        when(cipher.decrypt(any(byte[].class),eq(CipherField.APP_USER_SSAFY_USER_KEY))).thenReturn("isolated-test-key");
        when(history.captureCurrentSnapshot(anyString(),any(),any(),anyLong())).thenReturn("INTRADAY_BOUNDARY");
    }
    QaAccountHistorySyncCli.Result run(String mode) { return QaAccountHistorySyncCli.run(mode,db,em,cipher,history,store,CLOCK); }

    @Test void planUsesActualKoreanTodayAndDoesNotReadFinanceOrWriteHistory() {
        var result=run("plan");assertThat(result.through()).isEqualTo(TODAY);
        assertThat(result.targets()).hasSize(9);assertThat(result.synchronizedAccounts()).isZero();
        verifyNoInteractions(history,store,em,cipher);
    }
    @Test void syncReadsThroughTodayUsingExistingReaderAndStoredSsafyEvidence() {
        var result=run("sync");assertThat(result.synchronizedAccounts()).isEqualTo(9);
        verify(history,times(9)).captureCurrentSnapshot(eq("isolated-test-key"),any(LinkedAccount.class),eq(CLOCK),anyLong());
        assertThat(result.targets()).extracting(QaAccountHistorySyncCli.Target::userId).containsOnly(11L,12L,13L);
    }
    @Test void invalidLastPersonaStopsBeforeAnyFinancialCallOrWrite() {
        when(db.queryForList(contains("FROM qa_seed_member"),eq("qa-550-life-3"),eq("qa-3"))).thenReturn(List.of());
        assertThatThrownBy(()->run("sync")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(history,store,em,cipher);
    }
    @Test void duplicateBankAndWrongPrimaryAreRejectedBeforeExternalCalls() {
        when(db.queryForList(contains("FROM linked_account"),eq(13L))).thenReturn(List.of(
                Map.of("linked_account_id",130L,"bank_code","004","is_primary",false),
                Map.of("linked_account_id",131L,"bank_code","088","is_primary",true),
                Map.of("linked_account_id",132L,"bank_code","088","is_primary",false)));
        assertThatThrownBy(()->run("sync")).isInstanceOf(IllegalStateException.class);verifyNoInteractions(history);
    }
    @Test void ownershipChangeStopsWithoutFinancialCall() {
        when(em.find(LinkedAccount.class,110L).getUserId()).thenReturn(999L);
        assertThatThrownBy(()->run("sync")).isInstanceOf(IllegalStateException.class);verifyNoInteractions(history);
    }
    @Test void externalFailureIsNotReportedAsSuccessfulOrRetried() {
        when(history.captureCurrentSnapshot(anyString(),any(),any(),anyLong())).thenThrow(new IllegalStateException("isolated timeout"));
        assertThatThrownBy(()->run("sync")).isInstanceOf(IllegalStateException.class);
        verify(history,times(1)).captureCurrentSnapshot(anyString(),any(),any(),anyLong());verifyNoInteractions(store);
    }
    @Test void syntheticOnlyOrMissingTodayEvidenceCannotPass() {
        when(history.captureCurrentSnapshot(anyString(),any(),any(),anyLong())).thenReturn("NO_ANCHOR");
        assertThatThrownBy(()->run("sync")).isInstanceOf(IllegalStateException.class);
        verify(history,times(1)).captureCurrentSnapshot(anyString(),any(),any(),anyLong());
    }
    @Test void gatewayAllowsOnlyExactOfficialReadEndpoint() {
        var good=URI.create("https://finopenapi.ssafy.io"+QaAccountHistorySyncCli.HISTORY_PATH);
        QaAccountHistorySyncCli.allowReadOnlyHistory(good,HttpMethod.POST);
        for(String value:List.of("http://finopenapi.ssafy.io"+QaAccountHistorySyncCli.HISTORY_PATH,
                "https://other.invalid"+QaAccountHistorySyncCli.HISTORY_PATH,
                "https://finopenapi.ssafy.io/ssafy/api/v1/edu/demandDeposit/updateDemandDepositAccountDeposit",
                "https://finopenapi.ssafy.io/ssafy/api/v1/edu/demandDeposit/updateDemandDepositAccountWithdrawal",
                "https://finopenapi.ssafy.io/ssafy/api/v1/edu/demandDeposit/openAccountAuth",
                good+"?redirect=1")) {
            assertThatThrownBy(()->QaAccountHistorySyncCli.allowReadOnlyHistory(URI.create(value),HttpMethod.POST)).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(()->QaAccountHistorySyncCli.allowReadOnlyHistory(good,HttpMethod.GET)).isInstanceOf(IllegalStateException.class);
    }
    @Test void operatorContextValidatesRealSchemaWithoutServerSchedulersOrFlyway() throws Exception {
        Map<String,Object> connection=new HashMap<>();
        for(String name:List.of("spring.datasource.url","spring.datasource.username","spring.datasource.password"))
            connection.put(name,environment.getRequiredProperty(name));
        try(var context=QaAccountHistorySyncCli.builder().profiles("test","qa-history-operator")
                .initializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated-db",connection))).run()) {
            assertThat(context.getBeansOfType(org.flywaydb.core.Flyway.class)).isEmpty();
            assertThat(context.containsBean("org.springframework.context.annotation.internalScheduledAnnotationProcessor")).isFalse();
            assertThat(context.getBeansOfType(org.springframework.boot.web.servlet.server.ServletWebServerFactory.class)).isEmpty();
            assertThat(context.getBeansOfType(com.yamu.be.accounthistory.AccountHistorySyncJob.class)).isEmpty();
            assertThat(context.getBeansOfType(com.yamu.be.payment.billing.withdraw.BillWithdrawJob.class)).isEmpty();
            assertThat(context.getBeansOfType(com.yamu.be.user.WithdrawnKeyPurgeJob.class)).isEmpty();
            var actualDb=context.getBean(JdbcTemplate.class);
            assertThat(actualDb.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1",Integer.class)).isGreaterThan(30);
            actualDb.update("INSERT INTO app_user(created_at,updated_at) VALUES (UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            long user=actualDb.queryForObject("SELECT MAX(user_id) FROM app_user",Long.class);
            actualDb.update("INSERT INTO linked_account(user_id,bank_code,account_no_enc,account_no_hash,status,connected_at,created_at,updated_at) VALUES (?,'003',X'01',?,'CONNECTED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",user,new byte[32]);
            long account=actualDb.queryForObject("SELECT MAX(linked_account_id) FROM linked_account",Long.class);
            var actualStore=context.getBean(AccountHistoryStore.class);
            var snapshot=new com.yamu.be.payment.bank.BankHistory(TODAY,TODAY,null,1L,List.of(
                    new com.yamu.be.payment.bank.BankTransaction("isolated-cli-history",TODAY,LocalTime.of(0,10),
                            com.yamu.be.payment.bank.BankDirection.DEPOSIT,300000,300000,"isolated fixture")),CLOCK.instant());
            for(int attempt=0;attempt<2;attempt++) {
                var ticket=actualStore.begin(user,account,TODAY,TODAY);
                assertThat(actualStore.completeCurrentSnapshot(ticket,snapshot)).isEqualTo("INTRADAY_BOUNDARY");
            }
            assertThat(actualStore.read(user,account,TODAY,TODAY).days()).isEmpty();
            assertThatThrownBy(()->new com.yamu.be.assetflow.DailyBalanceReconstructor().reconstruct(snapshot,null))
                    .isInstanceOf(IllegalArgumentException.class);
            String dataset="intraday-history-"+user;
            actualDb.update("INSERT INTO qa_seed_dataset(dataset_key,seed_version,base_date,input_hash,run_id,created_at) VALUES (?,2,?,?,'00000000-0000-0000-0000-000000000001',UTC_TIMESTAMP(6))",dataset,TODAY,new byte[32]);
            actualDb.update("INSERT INTO qa_seed_member(dataset_key,persona_key,user_id,enriched) VALUES (?,'rich',?,TRUE)",dataset,user);
            try(var connectionForQa=context.getBean(javax.sql.DataSource.class).getConnection()) {
                connectionForQa.setAutoCommit(false);
                var qa=new QaAssetHistory(new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connectionForQa,true)),CLOCK);
                var spec=new QaAssetHistory.Spec(dataset,"rich",account,TODAY.minusDays(3),TODAY.minusDays(1),0,0,List.of(),null,"isolated-cli-history");
                assertThat(qa.run("plan",spec).closing()).isEqualTo(300000);
                qa.run("apply",spec);connectionForQa.commit();
                qa.run("verify",spec);connectionForQa.commit();
            }
            assertThat(actualStore.read(user,account,TODAY.minusDays(3),TODAY.minusDays(1)).days().values())
                    .hasSize(3).allSatisfy(day->assertThat(day.source()).isEqualTo(HistorySource.QA_SYNTHETIC));
            assertThat(actualStore.read(user,account,TODAY,TODAY).days()).isEmpty();
            var completedDay=new com.yamu.be.payment.bank.BankHistory(TODAY,TODAY,null,1L,snapshot.transactions(),CLOCK.instant().plusSeconds(86400));
            assertThat(actualStore.complete(actualStore.begin(user,account,TODAY,TODAY),completedDay)).isEqualTo("OK");
            assertThat(actualStore.read(user,account,TODAY,TODAY).reason()).isEqualTo("OK");
            var futureRow=new com.yamu.be.payment.bank.BankHistory(TODAY,TODAY,null,1L,List.of(
                    new com.yamu.be.payment.bank.BankTransaction("isolated-future",TODAY,LocalTime.of(23,59),
                            com.yamu.be.payment.bank.BankDirection.DEPOSIT,300000,300000,"future fixture")),CLOCK.instant());
            assertThat(new com.yamu.be.assetflow.DailyBalanceReconstructor().validateCurrentSnapshot(futureRow,null).reason())
                    .isEqualTo(com.yamu.be.assetflow.DailyBalanceReconstructor.Reason.HISTORY_INCOMPLETE);
            var nextDay=TODAY.plusDays(1);
            var nextRows=new ArrayList<>(snapshot.transactions());
            nextRows.add(new com.yamu.be.payment.bank.BankTransaction("isolated-next-day",nextDay,LocalTime.of(0,20),
                    com.yamu.be.payment.bank.BankDirection.DEPOSIT,1,300001,"isolated fixture"));
            var nextSnapshot=new com.yamu.be.payment.bank.BankHistory(TODAY,nextDay,null,2L,nextRows,CLOCK.instant().plusSeconds(86400));
            assertThat(actualStore.completeCurrentSnapshot(actualStore.begin(user,account,TODAY,nextDay),nextSnapshot))
                    .isEqualTo("INTRADAY_BOUNDARY");
            assertThat(actualStore.read(user,account,TODAY,TODAY).reason()).isEqualTo("OK");
            assertThat(actualStore.read(user,account,nextDay,nextDay).days()).isEmpty();
            assertThat(actualDb.queryForObject("SELECT COUNT(*) FROM account_transaction WHERE external_id='isolated-cli-history'",Integer.class)).isEqualTo(1);
            assertThatThrownBy(()->QaAccountHistorySyncCli.run("plan",actualDb,org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(context.getBean(jakarta.persistence.EntityManagerFactory.class)),
                    context.getBean(PersonalDataCipher.class),context.getBean(PersistedAssetFlowHistoryReader.class),context.getBean(AccountHistoryStore.class),CLOCK))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
