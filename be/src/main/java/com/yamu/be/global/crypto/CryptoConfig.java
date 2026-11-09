package com.yamu.be.global.crypto;

import java.security.SecureRandom;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 개인정보 암호화 모듈 등록. 키 검증({@link CryptoKeys})이 실패하면 서버가 뜨지 않는다.
 *
 * <p>사용하는 쪽은 {@link PersonalDataCipher}, {@link LookupHasher}, {@link PinHasher}만 주입받는다.
 * 사용법은 docs/be/personal-data-crypto.md.
 */
@Configuration
@EnableConfigurationProperties(CryptoProperties.class)
public class CryptoConfig {

    @Bean
    CryptoKeys cryptoKeys(CryptoProperties properties) {
        return new CryptoKeys(properties);
    }

    @Bean
    PersonalDataCipher personalDataCipher(CryptoKeys keys) {
        return new PersonalDataCipher(keys, new SecureRandom());
    }

    @Bean
    LookupHasher lookupHasher(CryptoKeys keys) {
        return new LookupHasher(keys);
    }

    @Bean
    PinHasher pinHasher(CryptoKeys keys) {
        return new PinHasher(keys);
    }
}
