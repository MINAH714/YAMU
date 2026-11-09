package com.yamu.be.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.CryptoKeys;
import com.yamu.be.global.crypto.CryptoProperties;
import com.yamu.be.global.crypto.PersonalDataCipher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class SeedLocalConfigTest {
    @TempDir Path directory;
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CryptoProperties.class)
    static class Config {}

    @Test
    void copiedLocalExampleUsesSeedEnvironmentAndDecryptsSeedCiphertext() throws Exception {
        Files.copy(Path.of("src/main/resources/application-local.yml.example"), directory.resolve("application-local.yml"));
        String data = newKey(), hmac = newKey(), pepper = newKey();
        CryptoKeys seedKeys = new CryptoKeys(new CryptoProperties(1, Map.of(1, data), hmac, pepper));
        byte[] ciphertext = new PersonalDataCipher(seedKeys, new SecureRandom())
                .encrypt("local-seed-test", CipherField.APP_USER_SSAFY_USER_KEY);
        runner(data, hmac, pepper).run(context -> {
            CryptoProperties properties = context.getBean(CryptoProperties.class);
            // Compare via boolean so test failure logs cannot expose key material.
            assertThat(data.equals(properties.dataKeys().get(1))).isTrue();
            var serverCipher = new PersonalDataCipher(new CryptoKeys(properties), new SecureRandom());
            assertThat(serverCipher.decrypt(ciphertext, CipherField.APP_USER_SSAFY_USER_KEY)).isEqualTo("local-seed-test");
        });
    }

    @Test
    void explicitExistingLocalKeyIsNotSilentlyOverwritten() throws Exception {
        String existing = newKey();
        Files.writeString(directory.resolve("application-local.yml"),
                "yamu:\n  crypto:\n    data-keys:\n      '[1]': " + existing + "\n");
        runner(newKey(), newKey(), newKey()).run(context ->
                assertThat(existing.equals(context.getBean(CryptoProperties.class).dataKeys().get(1))).isTrue());
    }

    private ApplicationContextRunner runner(String data, String hmac, String pepper) {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("seedEnvironment", Map.of(
                                "YAMU_CRYPTO_DATA_KEY_1", data, "YAMU_CRYPTO_DATA_KEY_ID", "1",
                                "YAMU_CRYPTO_HMAC_KEY", hmac, "YAMU_CRYPTO_PIN_PEPPER", pepper))))
                .withPropertyValues("spring.profiles.active=local", "spring.config.additional-location=" + directory.toUri())
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Config.class);
    }

    private static String newKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
