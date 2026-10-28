package com.yamu.be.auth;

import com.yamu.be.auth.domain.AuthMethod;
import com.yamu.be.auth.domain.StepUpPurpose;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 본인 인증 토큰(stepUpToken)을 Redis에 5분 보관한다. 원문 대신 SHA-256 해시를 키로 쓴다.
 *
 * <pre>
 * 토큰: "sut_{32바이트 랜덤 Base64URL}"
 * auth:step-up:{sha256(토큰)} → "{userId}:{userDeviceId}:{purpose}:{method}:{auditLogId}"   TTL 5분
 * </pre>
 *
 * <p>꺼낼 때 GETDEL로 지워서 한 번만 쓸 수 있다. 용도가 틀린 요청에 써도 지워진다(다시 본인 인증).
 */
@Component
@RequiredArgsConstructor
public class StepUpTokenStore {

    private static final String KEY_PREFIX = "auth:step-up:";
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private final StringRedisTemplate redis;
    private final AuthProperties properties;
    private final SecureRandom random = new SecureRandom();

    public String issue(StepUpGrant grant) {
        byte[] buf = new byte[32];
        random.nextBytes(buf);
        String token = "sut_" + URL.encodeToString(buf);
        String value = grant.userId() + ":" + grant.userDeviceId() + ":" + grant.purpose().name() + ":"
                + grant.method().name() + ":" + grant.auditLogId();
        redis.opsForValue().set(key(token), value, properties.stepUpTokenTtl());
        return token;
    }

    /** 한 번만 꺼낼 수 있다. 없거나(만료·이미 사용) 형식이 틀리면 empty. */
    public Optional<StepUpGrant> consume(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String value = redis.opsForValue().getAndDelete(key(token));
        if (value == null) {
            return Optional.empty();
        }
        String[] parts = value.split(":");
        try {
            return Optional.of(new StepUpGrant(Long.valueOf(parts[0]), Long.valueOf(parts[1]),
                    StepUpPurpose.valueOf(parts[2]), AuthMethod.valueOf(parts[3]), Long.valueOf(parts[4])));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String key(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 토큰이 증명하는 것: 누가, 어느 기기에서, 무엇을 하려고, 어떤 방식으로 인증했고, 그 기록은 몇 번인지. */
    public record StepUpGrant(Long userId, Long userDeviceId, StepUpPurpose purpose, AuthMethod method, Long auditLogId) {
    }
}
