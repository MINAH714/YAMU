package com.yamu.be.global.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Access Token(JWT HS256) 설정({@code yamu.jwt.*}).
 *
 * @param secret         HMAC 서명 키(Base64, 32바이트 이상). 운영은 환경변수, 로컬은 application-local.yml
 * @param accessTokenTtl Access Token 유효 시간(명세 제안값 30분)
 */
@ConfigurationProperties(prefix = "yamu.jwt")
public record JwtProperties(String secret, Duration accessTokenTtl) {
}
