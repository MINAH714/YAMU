package com.yamu.be.global.security;

import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Access Token(JWT HS256)을 발급한다. 내용은 누구나 Base64로 읽을 수 있으므로 번호만 넣고 개인정보는 넣지 않는다.
 *
 * <pre>{ "sub": "1", "did": 7, "iat": ..., "exp": ... }</pre>
 */
@Component
@RequiredArgsConstructor
public class AccessTokenIssuer {

    static final String DEVICE_CLAIM = "did";

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;
    private final Clock clock;

    public IssuedAccessToken issue(Long userId, Long userDeviceId) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(String.valueOf(userId))
                .claim(DEVICE_CLAIM, userDeviceId)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new IssuedAccessToken(token, properties.accessTokenTtl().toSeconds());
    }

    /** @param expiresIn 유효 시간(초). 응답의 expiresIn */
    public record IssuedAccessToken(String token, long expiresIn) {
    }
}
