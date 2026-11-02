package com.yamu.be.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.CryptoKeys;
import com.yamu.be.global.crypto.CryptoProperties;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.security.JwtProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** The development Compose secret filenames bind to the actual Boot 3.5 crypto properties, without a DB. */
class DevConfigTreeBindingTest {
    @TempDir Path folder;

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({CryptoProperties.class, JwtProperties.class})
    static class FileProperties {}

    @Test
    void fileSecretsOverrideEmptyYamlDefaultsAndUseTheSameKeysAsTheFixture() throws Exception {
        SecureRandom random = new SecureRandom();
        Map<String, String> files = new LinkedHashMap<>();
        for (String name : new String[]{"yamu.crypto.data-keys[1]", "yamu.crypto.hmac-key",
                "yamu.crypto.pin-pepper", "yamu.jwt.secret"}) {
            byte[] key = new byte[32];
            random.nextBytes(key);
            files.put(name, Base64.getEncoder().encodeToString(key));
        }
        files.put("yamu.crypto.data-key-id", "1");
        files.put("spring.datasource.password", "disposable-test-password");
        for (var file : files.entrySet()) {
            Files.writeString(folder.resolve(file.getKey()), file.getValue());
        }
        CryptoProperties fixture = new CryptoProperties(1, Map.of(1, files.get("yamu.crypto.data-keys[1]")),
                files.get("yamu.crypto.hmac-key"), files.get("yamu.crypto.pin-pepper"));
        byte[] encrypted = new PersonalDataCipher(new CryptoKeys(fixture), random)
                .encrypt("development-fixture", CipherField.APP_USER_SSAFY_USER_KEY);
        String importLocation = "configtree:" + folder.toAbsolutePath().toString().replace('\\', '/') + "/";
        try (var context = new SpringApplicationBuilder(FileProperties.class).web(WebApplicationType.NONE).run(
                "--spring.profiles.active=dev-configtree-test", "--spring.config.import=" + importLocation,
                "--spring.main.banner-mode=off", "--logging.level.root=ERROR")) {
            CryptoProperties actual = context.getBean(CryptoProperties.class);
            // Compare via booleans so failing assertions cannot print key material.
            assertThat(files.get("yamu.crypto.data-keys[1]").equals(actual.dataKeys().get(1))).isTrue();
            assertThat(files.get("yamu.crypto.hmac-key").equals(actual.hmacKey())).isTrue();
            assertThat(files.get("yamu.crypto.pin-pepper").equals(actual.pinPepper())).isTrue();
            assertThat(files.get("yamu.jwt.secret").equals(context.getBean(JwtProperties.class).secret())).isTrue();
            assertThat(files.get("spring.datasource.password")
                    .equals(context.getEnvironment().getProperty("spring.datasource.password"))).isTrue();
            String decrypted = new PersonalDataCipher(new CryptoKeys(actual), random)
                    .decrypt(encrypted, CipherField.APP_USER_SSAFY_USER_KEY);
            assertThat("development-fixture".equals(decrypted)).isTrue();
        }
    }
}
