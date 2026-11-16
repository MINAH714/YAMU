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

@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="qa.localApp",matches="true")
@org.springframework.boot.test.context.SpringBootTest(webEnvironment=org.springframework.boot.test.context.SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties={"server.address=127.0.0.1","server.port=18553"})
@org.springframework.context.annotation.Import(QaLocalAppTest.LocalConfiguration.class)

@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class QaLocalAppTest extends MySqlTestSupport {
    @Autowired Bootstrap bootstrap;
    @Autowired CryptoProperties cryptoProperties;
    @Autowired DataSource dataSource;





    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired com.yamu.be.accounthistory.AccountHistoryStore accountHistory;
    @MockitoBean SsafyTransferApi transfer;

    @MockitoBean com.yamu.be.account.SsafyAccountApi localBank;
    @MockitoBean com.yamu.be.global.finapi.FinApiClient externalFinance;


    static final org.testcontainers.containers.GenericContainer<?> PREVIEW_REDIS =
        new org.testcontainers.containers.GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @org.springframework.test.context.DynamicPropertySource
    static void previewRedis(org.springframework.test.context.DynamicPropertyRegistry registry) {
        PREVIEW_REDIS.start();
        registry.add("spring.data.redis.host",PREVIEW_REDIS::getHost);
        registry.add("spring.data.redis.port",()->PREVIEW_REDIS.getMappedPort(6379));
    }
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


    @Test void serveLocalPreview() throws Exception {
        ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of("seed/05-ibk-narasarang.sql")));
        service=new QaSeedService(connection,keys,Clock.fixed(Instant.parse("2026-11-16T03:00:00Z"),ZoneOffset.UTC),cryptoProperties.hmacKey());
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        var input=mapper.readValue(Path.of("seed/qa/life-persona-1.example.json").toFile(),QaSeedSpec.class);
        var p=input.personas().get(0);
        var person=new QaSeedSpec.Persona("p0",p.scenario(),p.name(),p.birthDate(),p.phone(),p.ssafyMemberId(),"isolated-preview-only",p.accounts(),p.cards(),p.issuanceEligibility());
        var spec=new QaSeedSpec(input.datasetKey(),3,input.baseDate(),List.of(person));
        apply(spec);signedUp(spec);service.run("enrich",spec);service.run("verify",spec);
        long owner=user(spec,0);
        // Local-only bank fixtures: no external deposits or bank requests are made.

        var accountBalances=new java.util.HashMap<String,Long>();
        when(runtimeClock.instant()).thenReturn(Instant.parse("2026-11-17T04:00:00Z"));
        for(int index=0;index<p.accounts().size();index++) {
            var inputAccount=p.accounts().get(index);
            long amount=QaLifeScenario.startingBalance(index);
            accountBalances.put(inputAccount.accountNo(),amount);
            long accountId=db.queryForObject("SELECT linked_account_id FROM linked_account WHERE user_id=? AND bank_code=?",Long.class,owner,inputAccount.bankCode());
            String fundingId="LOCAL-FIXTURE-FUNDING-"+index;
            var fixture=new com.yamu.be.payment.bank.BankTransaction(fundingId,QaLifeScenario.BASE,java.time.LocalTime.NOON,
                com.yamu.be.payment.bank.BankDirection.DEPOSIT,amount,amount,"LOCAL TEST FIXTURE");
            var ticket=accountHistory.begin(owner,accountId,QaLifeScenario.BASE,QaLifeScenario.BASE);
            assertThat(accountHistory.complete(ticket,new com.yamu.be.payment.bank.BankHistory(QaLifeScenario.BASE,QaLifeScenario.BASE,null,1L,List.of(fixture),runtimeClock.instant()))).isEqualTo("OK");
            var assetSpec=new QaAssetHistory.Spec(spec.datasetKey(),"p0",accountId,QaLifeScenario.FROM,QaLifeScenario.BASE.minusDays(1),0,0,List.of(),"LIFE",fundingId);
            connection.setAutoCommit(false);
            try {
                var assets=new QaAssetHistory(db,Clock.fixed(runtimeClock.instant(),ZoneOffset.UTC));
                assets.run("apply",assetSpec);connection.commit();
                assets.run("verify",assetSpec);connection.commit();
            } finally { connection.rollback();connection.setAutoCommit(true); }
        }
        when(runtimeClock.instant()).thenAnswer(call -> Instant.now());
        when(localBank.balance(anyString(),anyString())).thenAnswer(call -> {
            Long balance=accountBalances.get(call.getArgument(1,String.class));
            if(balance==null) throw new IllegalArgumentException("Unknown local fixture account");
            return balance;
        });
        when(transfer.historySnapshot(anyString(),anyString(),any(),any(),any())).thenAnswer(call -> {
            LocalDate from=call.getArgument(2), to=call.getArgument(3);
            // Real stream begins on the fixture funding date; earlier history is QA_SYNTHETIC.
            return new com.yamu.be.payment.bank.BankHistory(from,to,null,0L,List.of(),runtimeClock.instant());
        });
        // These are the only differences at the financial boundary. Unsupported financial
        // operations fail locally rather than falling through to a network implementation.
        when(externalFinance.post(anyString(),any(),any(),any(Class.class)))
            .thenThrow(new com.yamu.be.global.exception.BusinessException(com.yamu.be.global.exception.ErrorCode.FIN_API_UNAVAILABLE));
        when(externalFinance.post(anyString(),any(),any(),any(),any(Class.class)))
            .thenThrow(new com.yamu.be.global.exception.BusinessException(com.yamu.be.global.exception.ErrorCode.FIN_API_UNAVAILABLE));
        when(externalFinance.postWithApiKey(anyString(),any(),any(Class.class)))
            .thenThrow(new com.yamu.be.global.exception.BusinessException(com.yamu.be.global.exception.ErrorCode.FIN_API_UNAVAILABLE));
        when(transfer.withdraw(anyString(),anyString(),anyLong(),any(),anyString()))
            .thenThrow(new com.yamu.be.global.exception.BusinessException(com.yamu.be.global.exception.ErrorCode.FIN_API_UNAVAILABLE));
        when(transfer.deposit(anyString(),anyString(),anyLong(),any(),anyString()))
            .thenThrow(new com.yamu.be.global.exception.BusinessException(com.yamu.be.global.exception.ErrorCode.FIN_API_UNAVAILABLE));
        bootstrap.owner=owner;
        bootstrap.name=p.name();
        java.nio.file.Files.createDirectories(Path.of("build/local-app"));
        java.nio.file.Files.writeString(Path.of("build/local-app/ready.txt"),"http://127.0.0.1:18553");
        new java.util.concurrent.CountDownLatch(1).await();
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class LocalConfiguration {
        @org.springframework.context.annotation.Bean Bootstrap bootstrap(
            com.yamu.be.auth.domain.UserDeviceRepository devices,
            com.yamu.be.user.domain.AppUserRepository users,
            org.springframework.transaction.PlatformTransactionManager transactions) {
            return new Bootstrap(devices,users,transactions);
        }
        @org.springframework.context.annotation.Bean
        @org.springframework.core.annotation.Order(0)
        org.springframework.security.web.SecurityFilterChain localBootstrapSecurity(
            org.springframework.security.config.annotation.web.builders.HttpSecurity http) throws Exception {
            return http.securityMatcher("/local/bootstrap-device").csrf(c->c.disable())
                .authorizeHttpRequests(a->a.anyRequest().permitAll()).build();
        }
    }

    /** Only compiled on test classpath; cannot be included in bootJar or production. */
    @org.springframework.web.bind.annotation.RestController
    static class Bootstrap {
        volatile long owner;
        volatile String name;
        final com.yamu.be.auth.domain.UserDeviceRepository devices;
        final com.yamu.be.user.domain.AppUserRepository users;
        final org.springframework.transaction.support.TransactionTemplate tx;
        Bootstrap(com.yamu.be.auth.domain.UserDeviceRepository devices,
            com.yamu.be.user.domain.AppUserRepository users,
            org.springframework.transaction.PlatformTransactionManager transactions) {
            this.devices=devices;this.users=users;this.tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        }
        record Request(String deviceId,String publicKey) {}
        @org.springframework.web.bind.annotation.PostMapping("/local/bootstrap-device")
        Map<String,Object> register(@org.springframework.web.bind.annotation.RequestBody Request request) throws Exception {
            if(owner==0) throw new IllegalStateException("Local seed is not ready");
            String uuid=java.util.UUID.fromString(request.deviceId()).toString();
            java.security.KeyFactory.getInstance("EC").generatePublic(new java.security.spec.X509EncodedKeySpec(java.util.Base64.getDecoder().decode(request.publicKey())));
            long id=tx.execute(status -> {
                var ownerUser=users.findById(owner).orElseThrow();
                var existing=devices.findByUserIdAndDeviceUuid(owner,uuid);
                for(var other:devices.findByUserIdAndStatusNotForUpdate(owner,com.yamu.be.auth.domain.DeviceStatus.REVOKED)) {
                    if(!other.getDeviceUuid().equals(uuid)) other.revoke();
                }
                var device=existing.orElseGet(()->com.yamu.be.auth.domain.UserDevice.register(ownerUser,uuid,"Android 에뮬레이터",
                    com.yamu.be.auth.domain.DevicePlatform.ANDROID,request.publicKey(),"EC_P256",null));
                if(existing.isPresent()) device.replaceKeys("Android 에뮬레이터",com.yamu.be.auth.domain.DevicePlatform.ANDROID,request.publicKey(),"EC_P256",null);
                return devices.saveAndFlush(device).getId();
            });
            return Map.of("userDeviceId",id,"userId",owner,"name",name);
        }
    }
}