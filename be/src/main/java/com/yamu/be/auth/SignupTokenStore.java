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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 가입 진행 토큰(signupToken)을 Redis에 30분 보관한다. 원문 대신 SHA-256 해시를 키로 쓴다.
 *
 * <pre>
 * 토큰: "sgt_{32바이트 랜덤 Base64URL}"
 * signup:{sha256(토큰)} → Hash { mode, verifiedAt, name, birthDate, phoneEnc, phoneHash, userId(시드 사용자),
 *                               pendingPinHash(기기 재등록 중 새 PIN) }   TTL 30분
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

    private static final String PENDING_PIN_HASH = "pendingPinHash";

    /** 아직 있는 토큰에만 칸을 붙인다. 만료된 키에 HSET하면 TTL 없는 키가 새로 생긴다. */
    private static final RedisScript<Long> ATTACH = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            return 1
            """, Long.class);

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

    /**
     * 기기 재등록 중 새로 정한 PIN 해시를 맡아 둔다. 1원 인증을 마친 기기 등록 때 사용자에 넣는다.
     * 휴대폰 본인인증(연출)만으로 가입한 사람의 PIN을 바로 바꾸면, 번호·생년월일만 아는 사람이 남의 PIN을 바꿀 수 있다.
     *
     * @return 토큰이 이미 만료됐으면 false
     */
    public boolean stagePin(String token, String pinHash) {
        return Long.valueOf(1).equals(redis.execute(ATTACH, List.of(key(token)), PENDING_PIN_HASH, pinHash));
    }

    /** {@link #stagePin}으로 맡긴 PIN 해시. 없으면 empty. */
    public Optional<String> stagedPinHash(String token) {
        if (token == null || !token.startsWith(TOKEN_PREFIX)) {
            return Optional.empty();
        }
        return Optional.ofNullable((String) redis.opsForHash().get(key(token), PENDING_PIN_HASH));
    }

    /** 가입 완료·기기 재등록이 끝나면 지운다(명세: 기기 등록 성공 시 폐기). */
    public void delete(String token) {
        redis.delete(key(token));
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
