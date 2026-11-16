package com.yamu.be.seed.qa;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yamu.be.global.crypto.CryptoKeys;
import com.yamu.be.global.crypto.CryptoProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;

/** Explicit offline CLI; no Spring context, automatic DDL, HTTP or financial API calls. */
public final class QaSeedCli {
    private QaSeedCli() {}
    public static void main(String[] args) {
        try {
            QaSeedSpec.require(args.length == 2, "Expected command and input file");
            String command = args[0];
            QaSeedSpec.require(Set.of("plan", "apply", "enrich", "verify", "inspect", "reset", "purge").contains(command),
                    "Unknown command");
            ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            QaSeedSpec spec = mapper.readValue(Files.readString(Path.of(args[1])), QaSeedSpec.class);
            spec.validate(LocalDate.now(ZoneId.of("Asia/Seoul")));
            // plan is offline and requires no database or secrets beyond the local input bindings.
            if ("plan".equals(command)) {
                for (var p : spec.personas()) {
                    if(p.scenario()==QaSeedSpec.Scenario.LIFE) for(int a=0;a<3;a++) QaLifeScenario.ledger(a);
                    var payments = QaHistoryPlan.build(p.scenario(), spec.baseDate());
                    System.out.printf("%s scenario=%s cards=%d payments=%d base=%s%n",
                            p.key(), p.scenario(), p.cards().size(), payments.size(), spec.baseDate());
                }
                return;
            }
            if (!Set.of("verify", "inspect").contains(command)) {
                QaSeedSpec.require("true".equals(env("QA_SEED_MAINTENANCE")),
                        "Stop QA traffic and workers; set QA_SEED_MAINTENANCE=true");
            }
            int keyId = Integer.parseInt(env("YAMU_CRYPTO_DATA_KEY_ID"));
            CryptoKeys keys = new CryptoKeys(new CryptoProperties(keyId,
                    Map.of(keyId, env("YAMU_CRYPTO_DATA_KEY_" + keyId)),
                    env("YAMU_CRYPTO_HMAC_KEY"), env("YAMU_CRYPTO_PIN_PEPPER")));
            String bindingsFile=System.getenv("QA_SEED_ENCRYPTED_BINDINGS_FILE");
            if(bindingsFile!=null&&!bindingsFile.isBlank()) spec=QaEncryptedBindings.bind(spec,
                    mapper.readTree(Files.readString(Path.of(bindingsFile))),
                    new com.yamu.be.global.crypto.PersonalDataCipher(keys,new java.security.SecureRandom()));
            try (var connection = DriverManager.getConnection(env("QA_SEED_JDBC_URL"),
                    env("QA_SEED_DB_USERNAME"), env("QA_SEED_DB_PASSWORD"))) {
                new QaSeedService(connection, keys, Clock.systemUTC(), env("YAMU_CRYPTO_HMAC_KEY")).run(command, spec);
            }
            System.out.println("QA seed " + command + " completed");
        } catch (Exception failure) {
            // Driver/Jackson errors can contain secrets, SQL bindings or source excerpts.
            System.err.println("QA seed failed (" + failure.getClass().getSimpleName()
                    + "). Check input, catalog, schema, ownership and maintenance prerequisites.");
            System.exit(1);
        }
    }
    private static String env(String key) {
        String value = System.getenv(key);
        QaSeedSpec.require(value != null && !value.isBlank(), "Missing required environment setting");
        return value;
    }
}
