package com.yamu.be.seed;

import com.yamu.be.global.crypto.CryptoKeys;
import com.yamu.be.global.crypto.CryptoProperties;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.crypto.LookupHasher;
import com.yamu.be.global.crypto.PinHasher;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import org.flywaydb.core.Flyway;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** 명시적으로 실행하는 로컬 Seed CLI. Spring 앱 기동 시 자동 실행하지 않는다. */
public final class LocalSeedLoader {
    private LocalSeedLoader() {}

    public static void main(String[] args) {
        try {
            run(args);
        } catch (SeedUserData.ResetRequiredException failure) {
            System.err.println("기존 Seed의 카드 구성이 다릅니다. 앱을 멈추고 seed/load.ps1 -Reset으로 테스트 사용자를 초기 상태로 복원하세요.");
            System.exit(1);
        } catch (Exception failure) {
            // JDBC/config exception messages may contain credentials. Do not print them or their stack traces.
            System.err.println("Seed 적재 실패 (" + failure.getClass().getSimpleName()
                    + "). Docker와 seed/.env.seed.local의 설정을 확인하세요. 기존 데이터를 임의 초기화하지 않습니다.");
            System.exit(1);
        }
    }

    static void run(String[] args) throws Exception {
        if (args.length == 0) { throw new IllegalArgumentException("Seed directory is required"); }
        Path directory = Path.of(args[0]).toAbsolutePath().normalize();
        // Lock before reading/generating keys. Two first runs must never save different keys for the same DB data.
        try (FileChannel channel = FileChannel.open(directory.resolve(".env.seed.lock.local"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) { throw new IllegalStateException("Another local seed loader is running"); }
            runLocked(args, directory);
        }
    }

    private static void runLocked(String[] args, Path directory) throws Exception {
        boolean reset = Arrays.asList(args).contains("--reset");
        boolean catalogOnly = Arrays.asList(args).contains("--catalog-only");
        if (reset && catalogOnly) { throw new IllegalArgumentException("Catalog-only mode cannot reset users"); }
        Path settingsPath = directory.resolve(".env.seed.local");
        Map<String, String> settings = readEnv(settingsPath);
        Map<String, String> database = readEnv(directory.getParent().resolve(".env"));
        PersonalDataCipher cipher = null;
        LookupHasher lookup = null;
        PinHasher pins = null;
        String member = null;
        String userKey = null;
        String bank = null;
        String account = null;
        String pin = null;
        if (!catalogOnly) {
            member = required(settings, "SEED_SSAFY_MEMBER_ID");
            userKey = required(settings, "SEED_SSAFY_USER_KEY");
            bank = configured(settings, "SEED_BANK_CODE", "088");
            account = required(settings, "SEED_ACCOUNT_NUMBER");
            if (!member.matches("[^\\s@]+@yamu\\.app") || !account.matches("[0-9]{4,}")) {
                throw new IllegalArgumentException("Invalid financial fixture settings");
            }
            ensureSecret(settings, "YAMU_CRYPTO_DATA_KEY");
            ensureSecret(settings, "YAMU_CRYPTO_HMAC_KEY");
            ensureSecret(settings, "YAMU_CRYPTO_PIN_PEPPER");
            pin = configured(settings, "SEED_PIN", "");
            if (pin.isEmpty()) {
                pin = String.format(java.util.Locale.ROOT, "%06d", new SecureRandom().nextInt(1000000));
            }
            if (!pin.matches("[0-9]{6}")) { throw new IllegalArgumentException("Invalid local fixture PIN"); }
            settings.put("SEED_SSAFY_MEMBER_ID", member);
            settings.put("SEED_SSAFY_USER_KEY", userKey);
            settings.put("SEED_BANK_CODE", bank);
            settings.put("SEED_ACCOUNT_NUMBER", account);
            settings.put("SEED_PIN", pin);
            settings.put("YAMU_CRYPTO_DATA_KEY_ID", "1");
            settings.put("YAMU_CRYPTO_DATA_KEY_1", settings.get("YAMU_CRYPTO_DATA_KEY"));
            // Reuse the current application's crypto module, including column AAD and PIN policy.
            CryptoKeys keys = new CryptoKeys(new CryptoProperties(1,
                    Map.of(1, settings.get("YAMU_CRYPTO_DATA_KEY_1")),
                    settings.get("YAMU_CRYPTO_HMAC_KEY"), settings.get("YAMU_CRYPTO_PIN_PEPPER")));
            cipher = new PersonalDataCipher(keys, new SecureRandom());
            lookup = new LookupHasher(keys);
            pins = new PinHasher(keys);
            ensureSecret(settings, "YAMU_JWT_SECRET");
            saveSettings(settingsPath, settings);
        }
        String dbName = configured(database, "DB_NAME", "yamu");
        int port = Integer.parseInt(configured(database, "DB_PORT", "3306"));
        if (!dbName.matches("[A-Za-z0-9_]+") || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid local database settings");
        }
        // Host is deliberately fixed to loopback. No remote/prod JDBC URL override is accepted.
        String url = "jdbc:mysql://127.0.0.1:" + port + "/" + dbName
                + "?serverTimezone=UTC&connectionTimeZone=UTC&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&useSSL=false";
        String dbUser = configured(database, "DB_USERNAME", "yamu");
        String dbPassword = configured(database, "DB_PASSWORD", "yamu_local");
        Flyway.configure().dataSource(url, dbUser, dbPassword).locations("classpath:db/migration")
                .cleanDisabled(true).load().migrate();
        try (Connection connection = DriverManager.getConnection(url, dbUser, dbPassword)) {
            try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT GET_LOCK('yamu_local_seed_97', 10)")) {
                if (!result.next() || result.getInt(1) != 1) {
                    throw new IllegalStateException("Another seed loader holds the local fixture lock");
                }
            }
            try {
                ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new FileSystemResource(directory.resolve("01-card-catalog.sql")), StandardCharsets.UTF_8));
                if (!catalogOnly) {
                    new SeedUserData(connection, cipher, lookup, pins, Clock.systemUTC())
                            .load(member, userKey, bank, account, pin, reset);
                }
            } catch (Exception failure) {
                try (var statement = connection.createStatement()) { statement.execute("ROLLBACK"); }
                throw failure;
            } finally {
                try (var statement = connection.createStatement()) { statement.execute("DO RELEASE_LOCK('yamu_local_seed_97')"); }
            }
        }
        System.out.println(catalogOnly ? "카드 상품·혜택·적용 대상·가맹점 Seed를 적재했습니다."
                : reset ? "테스트 사용자·계좌·카드·거래·실적을 초기 상태로 복원했습니다."
                : "카드 상품과 테스트 사용자 1명·계좌 1개·보유 카드 3장을 적재·확인했습니다.");
    }

    private static Map<String, String> readEnv(Path path) throws Exception {
        Map<String, String> values = new HashMap<>();
        if (!Files.exists(path)) { return values; }
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmed = line.strip().replace("\uFEFF", "");
            if (trimmed.isEmpty() || trimmed.startsWith("#")) { continue; }
            int separator = trimmed.indexOf('=');
            if (separator < 1) { throw new IllegalArgumentException("Invalid local env file line"); }
            String name = trimmed.substring(0, separator).strip();
            String value = trimmed.substring(separator + 1).strip();
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) { throw new IllegalArgumentException("Invalid local env setting name"); }
            if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            values.put(name, value);
        }
        return values;
    }

    private static void saveSettings(Path path, Map<String, String> values) throws Exception {
        StringBuilder contents = new StringBuilder("# LOCAL ONLY - never commit or share this file in Git.\n");
        for (var entry : new TreeMap<>(values).entrySet()) {
            if (entry.getValue().contains("\n") || entry.getValue().contains("\r")) {
                throw new IllegalArgumentException("Multiline local settings are unsupported");
            }
            contents.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        Path temporary = Files.createTempFile(path.getParent(), ".env.seed.", ".local");
        try {
            Files.writeString(temporary, contents.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unavailable) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void ensureSecret(Map<String, String> settings, String name) {
        String current = configured(settings, name, "");
        if (current.isEmpty()) {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            settings.put(name, Base64.getEncoder().encodeToString(key));
        } else {
            settings.put(name, current);
        }
    }

    private static String configured(Map<String, String> values, String key, String fallback) {
        String external = System.getenv(key);
        return external != null ? external : values.getOrDefault(key, fallback);
    }

    private static String required(Map<String, String> values, String key) {
        String value = configured(values, key, "");
        if (value.isBlank()) { throw new IllegalArgumentException("Required local fixture setting is missing"); }
        return value;
    }
}
