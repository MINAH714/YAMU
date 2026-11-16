package com.yamu.be.seed.qa;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Reuses an explicitly selected loopback fixture DB; never creates or drops a database. */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="qa.localResume",matches="true")
@org.springframework.boot.test.context.SpringBootTest(webEnvironment=org.springframework.boot.test.context.SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties={"server.address=127.0.0.1","server.port=18559","spring.flyway.enabled=true",
      "spring.flyway.locations=classpath:db/migration,classpath:db/testmigration",
      "spring.jpa.hibernate.ddl-auto=validate","spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=true"})
@org.springframework.context.annotation.Import(QaLocalAppTest.LocalConfiguration.class)
class QaLocalResumeTest {
    @Autowired DataSource source;
    @Autowired com.yamu.be.global.crypto.CryptoProperties properties;
    @Autowired QaLocalAppTest.Bootstrap bootstrap;
    @MockitoBean com.yamu.be.payment.bank.SsafyTransferApi transfer;
    @MockitoBean com.yamu.be.account.SsafyAccountApi bank;
    @MockitoBean com.yamu.be.global.finapi.FinApiClient finance;
    @MockitoBean Clock runtimeClock;

    @org.springframework.test.context.DynamicPropertySource
    static void local(org.springframework.test.context.DynamicPropertyRegistry r) {
        String url=System.getenv("QA_LOCAL_JDBC_URL");
        QaSeedSpec.require(url!=null && url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/test_[0-9]+\\?.*"),"Only isolated loopback DB allowed");
        r.add("spring.datasource.url",()->url);
        r.add("spring.datasource.username",()->"root");
        r.add("spring.datasource.password",()->System.getenv("QA_LOCAL_DB_PASSWORD"));
        r.add("spring.data.redis.host",()->"127.0.0.1");
        r.add("spring.data.redis.port",()->Integer.parseInt(System.getenv("QA_LOCAL_REDIS_PORT")));
    }

    @Test void resumeLocalPreview() throws Exception {
        when(runtimeClock.instant()).thenAnswer(call->Instant.now());
        when(runtimeClock.getZone()).thenReturn(ZoneOffset.UTC);
        when(runtimeClock.withZone(any())).thenAnswer(call->Clock.system(call.getArgument(0)));
        var unavailable=new com.yamu.be.global.exception.BusinessException(com.yamu.be.global.exception.ErrorCode.FIN_API_UNAVAILABLE);
        when(finance.post(anyString(),any(),any(),any(Class.class))).thenThrow(unavailable);
        when(finance.post(anyString(),any(),any(),any(),any(Class.class))).thenThrow(unavailable);
        when(finance.postWithApiKey(anyString(),any(),any(Class.class))).thenThrow(unavailable);
        when(transfer.withdraw(anyString(),anyString(),anyLong(),any(),anyString())).thenThrow(unavailable);
        when(transfer.deposit(anyString(),anyString(),anyLong(),any(),anyString())).thenThrow(unavailable);
        when(transfer.historySnapshot(anyString(),anyString(),any(),any(),any())).thenAnswer(call->
            new com.yamu.be.payment.bank.BankHistory(call.getArgument(2),call.getArgument(3),null,0L,List.of(),runtimeClock.instant()));
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        var input=mapper.readValue(Path.of("seed/qa/life-persona-1.example.json").toFile(),QaSeedSpec.class);
        var p=input.personas().get(0);
        var person=new QaSeedSpec.Persona("p0",p.scenario(),p.name(),p.birthDate(),p.phone(),p.ssafyMemberId(),"isolated-preview-only",p.accounts(),p.cards(),p.issuanceEligibility());
        var spec=new QaSeedSpec(input.datasetKey(),3,input.baseDate(),List.of(person));
        var balances=new HashMap<String,Long>();
        for(int i=0;i<p.accounts().size();i++) balances.put(p.accounts().get(i).accountNo(),QaLifeScenario.startingBalance(i));
        when(bank.balance(anyString(),anyString())).thenAnswer(call->{
            Long amount=balances.get(call.getArgument(1,String.class));
            if(amount==null) throw new IllegalArgumentException("Unknown local account");
            return amount;
        });
        try(var c=source.getConnection()) {
            var db=new JdbcTemplate(new SingleConnectionDataSource(c,true));
            var service=new QaSeedService(c,new com.yamu.be.global.crypto.CryptoKeys(properties),Clock.systemUTC(),properties.hmacKey());
            var hash=db.queryForObject("SELECT HEX(input_hash) FROM qa_seed_dataset WHERE dataset_key=?",String.class,spec.datasetKey());
            service.reviseBenefits(spec,hash);
            service.run("verify",spec);
            bootstrap.owner=db.queryForObject("SELECT user_id FROM qa_seed_member WHERE dataset_key=? AND persona_key='p0'",Long.class,spec.datasetKey());
            bootstrap.name=p.name();
        }
        Files.createDirectories(Path.of("build/local-resume"));
        Files.writeString(Path.of("build/local-resume/ready.txt"),"http://127.0.0.1:18559");
        new java.util.concurrent.CountDownLatch(1).await();
    }
}
