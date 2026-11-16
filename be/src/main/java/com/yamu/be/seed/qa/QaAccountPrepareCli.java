package com.yamu.be.seed.qa;

import static com.yamu.be.seed.qa.QaAccountPreparationException.require;
import static com.yamu.be.seed.qa.QaAccountPreparationException.Code.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yamu.be.global.crypto.CryptoKeys;
import com.yamu.be.global.crypto.CryptoProperties;
import com.yamu.be.global.crypto.PersonalDataCipher;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Operator-only QA fixture preparation. No HTTP endpoint, Spring context, or financial client. */
public final class QaAccountPrepareCli {
    private QaAccountPrepareCli() {}

    public static void main(String[] args) {
        Map<String, String> pinInputs = new HashMap<>();
        try {
            require(args.length >= 3 && args.length <= 4, INVALID_INPUT);
            String mode = args[0];
            require(java.util.Set.of("plan", "prepare", "verify").contains(mode), INVALID_INPUT);
            require("false".equals(env("BILLING_JOBS_ENABLED")), APPROVAL_REQUIRED);
            ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            Path seedFolder = Path.of(args[1]);
            var manifest = mapper.readValue(readSmall(Path.of(args[2])), QaAccountDevices.Manifest.class);
            manifest.validate(Clock.systemUTC(), false);
            Path receipt = args.length == 4 ? Path.of(args[3]).toAbsolutePath() : null;
            if ("prepare".equals(mode)) {
                require("true".equals(env("QA_SEED_MAINTENANCE"))
                        && manifest.digest().equals(env("QA_ACCOUNT_PREPARE_APPROVAL")), APPROVAL_REQUIRED);
                require(receipt != null, INVALID_INPUT);
            }
            if (receipt != null) checkReceiptPath(receipt);
            Map<Integer, String> dataKeys = new HashMap<>();
            System.getenv().forEach((key, value) -> {
                if (key.matches("YAMU_CRYPTO_DATA_KEY_[0-9]+")) {
                    dataKeys.put(Integer.parseInt(key.substring("YAMU_CRYPTO_DATA_KEY_".length())), value);
                }
            });
            CryptoKeys keys = new CryptoKeys(new CryptoProperties(Integer.parseInt(env("YAMU_CRYPTO_DATA_KEY_ID")),
                    dataKeys, env("YAMU_CRYPTO_HMAC_KEY"), env("YAMU_CRYPTO_PIN_PEPPER")));
            var cipher = new PersonalDataCipher(keys, new SecureRandom());
            var encrypted = mapper.readTree(readSmall(Path.of(env("QA_SEED_ENCRYPTED_BINDINGS_FILE"))));
            var specs = new ArrayList<QaSeedSpec>();
            for (int i = 1; i <= 3; i++) {
                var source = mapper.readValue(readSmall(seedFolder.resolve("life-persona-" + i + ".example.json")), QaSeedSpec.class);
                specs.add(QaEncryptedBindings.bind(source, encrypted, cipher));
            }
            if ("prepare".equals(mode)) {
                if (System.console() != null) {
                    for (String dataset : QaAccountDevices.DATASETS.stream().sorted().toList()) {
                        char[] input = System.console().readPassword("PIN for %s: ", dataset);
                        require(input != null, PIN_REQUIRED);
                        try { pinInputs.put(dataset, new String(input)); }
                        finally { Arrays.fill(input, '\0'); }
                    }
                } else {
                    require("true".equals(System.getenv("QA_ACCOUNT_PINS_STDIN")), PIN_REQUIRED);
                    byte[] bytes = System.in.readNBytes(65537);
                    try {
                        require(bytes.length <= 65536, INVALID_INPUT);
                        pinInputs.putAll(mapper.readValue(bytes, new TypeReference<Map<String, String>>() {}));
                    } finally { Arrays.fill(bytes, (byte) 0); }
                }
            }
            try (var connection = DriverManager.getConnection(env("QA_SEED_JDBC_URL"),
                    env("QA_SEED_DB_USERNAME"), env("QA_SEED_DB_PASSWORD"))) {
                var result = new QaAccountProvisioner(connection, keys, Clock.systemUTC(), env("YAMU_CRYPTO_HMAC_KEY"))
                        .run(mode, specs, manifest, pinInputs);
                byte[] resultBytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result);
                if (receipt != null) writeReceipt(receipt, resultBytes);
                System.out.println(mapper.writeValueAsString(result));
            }
        } catch (QaAccountPreparationException failure) {
            System.err.println("QA account preparation failed: " + failure.code.name());
            System.exit(1);
        } catch (Exception failure) {
            // Parser/driver messages and stack traces can contain PINs or financial bindings.
            System.err.println("QA account preparation failed: " + failure.getClass().getSimpleName());
            System.exit(1);
        } finally {
            pinInputs.clear();
        }
    }

    private static byte[] readSmall(Path path) throws Exception {
        require(Files.isRegularFile(path) && Files.size(path) <= 262144, INVALID_INPUT);
        return Files.readAllBytes(path);
    }

    private static void checkReceiptPath(Path path) throws Exception {
        require(!Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)
                && path.getParent() != null && Files.isDirectory(path.getParent())
                && !Files.isSymbolicLink(path.getParent()), INVALID_INPUT);
        if (Files.getFileStore(path.getParent()).supportsFileAttributeView("posix")) {
            var permissions = Files.getPosixFilePermissions(path.getParent());
            require(permissions.stream().noneMatch(permission -> permission.name().startsWith("GROUP_")
                    || permission.name().startsWith("OTHERS_")), INVALID_INPUT);
        }
    }

    private static void writeReceipt(Path path, byte[] bytes) throws Exception {
        if (Files.getFileStore(path.getParent()).supportsFileAttributeView("posix")) {
            try (var output = Files.newByteChannel(path,
                    java.util.Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    PosixFilePermissions.asFileAttribute(java.util.Set.of(
                            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)))) {
                var buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
            }
        } else {
            // The receipt contains IDs and public-key fingerprints only; PIN/private keys never enter it.
            Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
    }

    private static String env(String name) {
        String value = System.getenv(name);
        require(value != null && !value.isBlank(), INVALID_INPUT);
        return value;
    }
}
