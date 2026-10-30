package com.yamu.be.auth;

import com.yamu.be.auth.domain.SignupMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 가입 진행 토큰(signupToken)을 Redis에 30분 보관한다. 원문 대신 SHA-256 해시를 키로 쓴다.
 *
 * <pre>
 * 토큰: "sgt_{32바이트 랜덤 Base64URL}"
 * signup:{sha256(토큰)} → Hash { mode, verifiedAt, name, birthDate, phoneEnc, phoneHash, userId(시드 사용자) }   TTL 30분
 * </pre>
 *
 * <p>가입 단계 API(계좌 연결 → PIN 설정 → 기기 등록)가 같은 토큰을 여러 번 쓰므로 꺼낼 때 지우지 않는다.
 * 진행 단계는 저장하지 않고 DB(사용자·계좌·PIN)에서 계산한다. 같은 사실을 두 곳에 두면 어긋날 수 있다.
 */
@Component
@RequiredArgsConstructor
public class SignupTokenStore {

    private static final String KEY_PREFIX = "signup:";
    private static final String TOKEN_PREFIX = "sgt_";
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private final StringRedisTemplate redis;
    private final SignupProperties properties;
    private final SecureRandom random = new SecureRandom();

    public IssuedSignupToken issue(SignupSession session) {
        byte[] buf = new byte[32];
        random.nextBytes(buf);
        String token = TOKEN_PREFIX + URL.encodeToString(buf);
        Map<String, String> fields = new HashMap<>(session.identity().toFields());
        fields.put("mode", session.mode().name());
        fields.put("verifiedAt", String.valueOf(session.verifiedAt().toInstant(ZoneOffset.UTC).toEpochMilli()));
        fields.put("userId", String.valueOf(session.userId()));
        PhoneVerificationStore.putWithTtl(redis, key(token), fields, properties.tokenTtl().toMillis());
        return new IssuedSignupToken(token, properties.tokenTtl().toSeconds());
    }

    /** 없거나(만료) 형식이 틀리면 empty. 꺼내도 지우지 않는다. */
    public Optional<SignupSession> find(String token) {
        if (token == null || !token.startsWith(TOKEN_PREFIX)) {
            return Optional.empty();
        }
        Map<Object, Object> fields = redis.opsForHash().entries(key(token));
        if (fields.isEmpty()) {
            return Optional.empty();
        }
        try {
            LocalDateTime verifiedAt = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(Long.parseLong((String) fields.get("verifiedAt"))), ZoneOffset.UTC);
            return Optional.of(new SignupSession(SignupMode.valueOf((String) fields.get("mode")),
                    VerifiedIdentity.fromFields(fields), verifiedAt, Long.valueOf((String) fields.get("userId"))));
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

    /**
     * @param verifiedAt 본인인증을 마친 시각(UTC)
     * @param userId     본인인증으로 찾은 시드 사용자(가입 중 또는 가입 완료). 시드에 없는 사람은 토큰을 받지 못하므로 항상 있다
     */
    public record SignupSession(SignupMode mode, VerifiedIdentity identity, LocalDateTime verifiedAt, Long userId) {
    }

    public record IssuedSignupToken(String token, long expiresIn) {
    }
}
