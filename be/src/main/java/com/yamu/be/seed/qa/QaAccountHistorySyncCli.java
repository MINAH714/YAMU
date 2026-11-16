package com.yamu.be.seed.qa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.account.domain.LinkedAccount;
import com.yamu.be.accounthistory.AccountHistoryStore;
import com.yamu.be.assetflow.PersistedAssetFlowHistoryReader;
import com.yamu.be.global.crypto.*;
import com.yamu.be.global.finapi.*;
import com.yamu.be.payment.bank.SsafyTransferApi;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.Validator;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.web.client.RestClient;

/** Explicit operator entry point. No web server, scheduler, transfer client path or schema migration. */
public final class QaAccountHistorySyncCli {
    private QaAccountHistorySyncCli() {}
    static final String HISTORY_PATH = "/ssafy/api/v1/edu/demandDeposit/inquireTransactionHistoryList";
    record Target(long userId, long accountId, String bankCode) {}
    record Result(String mode, LocalDate through, List<Target> targets, int synchronizedAccounts) {}

    public static void main(String[] args) {
        try {
            require(args.length == 1 && Set.of("plan", "sync").contains(args[0]));
            require("false".equals(System.getenv("BILLING_JOBS_ENABLED")));
            if (args[0].equals("sync")) require("true".equals(System.getenv("QA_SEED_MAINTENANCE")));
            try (var context = builder().run()) {
                try (var connection = context.getBean(DataSource.class).getConnection()) {
                    String database;
                    try (var s = connection.createStatement(); var r = s.executeQuery("SELECT DATABASE()")) {
                        require(r.next()); database = r.getString(1); require(database != null);
                    }
                    String lock = "qa442:" + UUID.nameUUIDFromBytes(database.getBytes(StandardCharsets.UTF_8));
                    try (var statement = connection.prepareStatement("SELECT GET_LOCK(?,0)")) {
                        statement.setString(1, lock);
                        try (var r = statement.executeQuery()) { require(r.next() && r.getInt(1) == 1); }
                    }
                    try {
                        var result = run(args[0], context.getBean(JdbcTemplate.class),
                                SharedEntityManagerCreator.createSharedEntityManager(context.getBean(EntityManagerFactory.class)),
                                context.getBean(PersonalDataCipher.class), context.getBean(PersistedAssetFlowHistoryReader.class),
                                context.getBean(AccountHistoryStore.class), context.getBean(Clock.class));
                        System.out.println(context.getBean(ObjectMapper.class).writeValueAsString(result));
                    } finally {
                        try (var statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                            statement.setString(1, lock); statement.execute();
                        }
                    }
                }
            }
        } catch (Exception failure) {
            System.err.println("QA history sync stopped: " + failure.getClass().getSimpleName());
            System.exit(1);
        }
    }

    static SpringApplicationBuilder builder() {
        return new SpringApplicationBuilder(OperatorConfiguration.class).web(WebApplicationType.NONE)
                .profiles("qa-history-operator").initializers(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("qa-history-safety", Map.of(
                                "spring.flyway.enabled", "false", "spring.jpa.hibernate.ddl-auto", "validate",
                                "spring.main.banner-mode", "off", "spring.jmx.enabled", "false",
                                "spring.data.redis.repositories.enabled", "false", "logging.level.root", "WARN"))));
    }

    static Result run(String mode, JdbcTemplate db, EntityManager em, PersonalDataCipher cipher,
            PersistedAssetFlowHistoryReader history, AccountHistoryStore store, Clock clock) {
        require(Set.of("plan", "sync").contains(mode));
        var targets = new ArrayList<Target>();
        var owners = new HashSet<Long>();
        // Validate every target before the first financial request or history write.
        for (int n = 1; n <= 3; n++) {
            var members = db.queryForList("""
                    SELECT m.user_id FROM qa_seed_member m JOIN app_user u ON u.user_id=m.user_id
                    WHERE m.dataset_key=? AND m.persona_key=? AND m.enriched=TRUE
                      AND u.status='ACTIVE' AND u.pin_hash IS NOT NULL AND u.ssafy_user_key IS NOT NULL
                    """, "qa-550-life-" + n, "qa-" + n);
            require(members.size() == 1);
            long user = ((Number) members.get(0).get("user_id")).longValue();
            require(owners.add(user));
            require(Integer.valueOf(4).equals(db.queryForObject(
                    "SELECT COUNT(*) FROM user_card WHERE user_id=? AND status='ACTIVE'", Integer.class, user)));
            var accounts = db.queryForList("""
                    SELECT linked_account_id,bank_code,is_primary FROM linked_account
                    WHERE user_id=? AND status='CONNECTED' ORDER BY bank_code,linked_account_id
                    """, user);
            require(accounts.size() == 3);
            var banks = new HashSet<String>();
            int primary = 0;
            for (var account : accounts) {
                String bank = (String) account.get("bank_code");
                require(Set.of("004", "088", "003").contains(bank) && banks.add(bank));
                if (Boolean.TRUE.equals(account.get("is_primary"))) { require(bank.equals("004")); primary++; }
                targets.add(new Target(user, ((Number) account.get("linked_account_id")).longValue(), bank));
            }
            require(primary == 1);
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        if (mode.equals("plan")) return new Result(mode, today, List.copyOf(targets), 0);
        int processed = 0;
        for (var target : targets) {
            var account = em.find(LinkedAccount.class, target.accountId());
            require(account != null && account.isConnected() && target.userId() == account.getUserId());
            byte[] encrypted = db.queryForObject("SELECT ssafy_user_key FROM app_user WHERE user_id=? AND status='ACTIVE'",
                    byte[].class, target.userId());
            String key = cipher.decrypt(encrypted, CipherField.APP_USER_SSAFY_USER_KEY);
            // Actual current Korean date, never a future job asOf or a modified transaction date.
            String status=history.captureCurrentSnapshot(key,account,clock,System.nanoTime()+Duration.ofSeconds(30).toNanos());
            require("INTRADAY_BOUNDARY".equals(status));
            processed++;
        }
        return new Result(mode, today, List.copyOf(targets), processed);
    }

    static void allowReadOnlyHistory(URI uri, HttpMethod method) {
        require(method == HttpMethod.POST && uri.getScheme().equals("https") && uri.getHost().equals("finopenapi.ssafy.io")
                && uri.getPort() == -1 && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                && uri.getRawPath().equals(HISTORY_PATH));
    }

    static void require(boolean condition) { if (!condition) throw new IllegalStateException("QA history prerequisite failed"); }

    @Configuration(proxyBeanMethods = false)
    @Profile("qa-history-operator")
    @EnableAutoConfiguration(exclude = {FlywayAutoConfiguration.class, JpaRepositoriesAutoConfiguration.class})
    @EnableJpaAuditing
    @EntityScan(basePackages = {"com.yamu.be.account.domain", "com.yamu.be.accounthistory"})
    @Import({CryptoConfig.class, FinTransactionNoIssuer.class})
    @EnableConfigurationProperties(FinApiProperties.class)
    @ComponentScan(basePackages = {"com.yamu.be.assetflow", "com.yamu.be.accounthistory"}, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = "com[.]yamu[.]be[.](assetflow[.](AssetFlowHistoryReader|PersistedAssetFlowHistoryReader)|accounthistory[.]AccountHistoryStore)"))
    static class OperatorConfiguration {
        @Bean Clock operatorClock() { return Clock.systemUTC(); }
        @Bean SsafyTransferApi readOnlyHistoryBank(RestClient.Builder builder, FinApiProperties properties,
                ObjectMapper mapper, Clock clock, FinTransactionNoIssuer issuer, Validator validator) {
            require(properties.connectTimeout().compareTo(properties.readTimeout()) < 0);
            var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                    .connectTimeout(properties.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build());
            factory.setReadTimeout(properties.readTimeout());
            var client = builder.baseUrl(properties.baseUrl()).requestFactory(factory)
                    .requestInterceptor((request, body, execution) -> {
                        allowReadOnlyHistory(request.getURI(), request.getMethod());
                        return execution.execute(request, body);
                    }).build();
            return new SsafyTransferApi(new FinApiClient(client, properties, mapper, clock, issuer, validator), clock);
        }
    }
}
