package com.yamu.be.global.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Clock;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * JWT 발급·검증에 쓰는 키와 Nimbus 인코더·디코더. 검증 필터는 Spring Resource Server가 이 디코더로 붙인다.
 * 키가 비었거나 32바이트보다 짧으면 서버가 뜨지 않는다.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    private static final int MIN_SECRET_BYTES = 32;

    @Bean
    SecretKey jwtSecretKey(JwtProperties properties) {
        String secret = properties.secret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("yamu.jwt.secret이 비어 있습니다. 운영은 YAMU_JWT_SECRET 환경변수, 로컬은 application-local.yml을 확인하세요.");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(secret.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("yamu.jwt.secret이 Base64 형식이 아닙니다.", e);
        }
        if (raw.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("yamu.jwt.secret은 32바이트 이상이어야 합니다. 현재 " + raw.length + "바이트");
        }
        return new SecretKeySpec(raw, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    /** 서명(HS256)과 만료(exp, 기본 허용 오차 60초)를 검사한다. 만료는 발급과 같은 {@link Clock}으로 본다(테스트에서 시간을 돌릴 수 있게). */
    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSecretKey, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator();
        timestamps.setClock(clock);
        decoder.setJwtValidator(timestamps);
        return decoder;
    }
}
