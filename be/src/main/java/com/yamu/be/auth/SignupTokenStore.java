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
import java.util.Set;
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
 *                               oneWonVerified(이 토큰으로 1원 인증을 마침), pendingPinHash(기기 재등록 중 새 PIN) }   TTL 30분
 * signup:user:{userId}   → Set { sha256(토큰) … }   TTL 30분(마지막 발급 기준)
 * </pre>
 *
 * <p>사용자별 목록은 가입 완료·기기 재등록이 끝날 때 그 사용자의 다른 가입 진행 토큰까지 지우려고 둔다.
 * 남은 토큰은 30분 동안 살아 있어, 그 사이 다른 단계 API를 다시 부를 수 있기 때문이다.
 *
 * <p>가입 단계 API(계좌 연결 → PIN 설정 → 기기 등록)가 같은 토큰을 여러 번 쓰므로 꺼낼 때 지우지 않는다.
 * 진행 단계는 저장하지 않고 DB(사용자·계좌·PIN)에서 계산한다. 같은 사실을 두 곳에 두면 어긋날 수 있다.
 */
@Component
@RequiredArgsConstructor
public class SignupTokenStore {

    private static final String KEY_PREFIX = "signup:";
    private static final String USER_INDEX_PREFIX = "signup:user:";
    private static final String TOKEN_PREFIX = "sgt_";
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private static final String PENDING_PIN_HASH = "pendingPinHash";
    private static final String ONE_WON_VERIFIED = "oneWonVerified";

    /** 사용자별 토큰 목록에 넣고 만료를 다시 건다. SADD 뒤 PEXPIRE를 따로 부르다 끊기면 만료 없는 키가 남는다. */
    private static final RedisScript<Long> INDEX = new DefaultRedisScript<>("""
            redis.call('SADD', KEYS[1], ARGV[1])
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            return 1
            """, Long.class);

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
        redis.execute(INDEX, List.of(USER_INDEX_PREFIX + session.userId()), key(token),
                String.valueOf(properties.tokenTtl().toMillis()));
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
                    VerifiedIdentity.fromFields(fields), verifiedAt, Long.valueOf((String) fields.get("userId")),
                    "1".equals(fields.get(ONE_WON_VERIFIED))));
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

    /**
     * 이 토큰으로 가입 중 1원 인증을 마쳤다고 적는다. PIN 설정·기기 등록은 1원 인증을 한 그 가입 세션(토큰)에서만 받는다.
     * 계좌를 연결해 둔 가입 중 사용자를 다른 사람이 본인인증(연출)만으로 이어받아 PIN을 바꾸고 기기를 등록하지 못하게 하려고.
     *
     * @return 토큰이 이미 만료됐으면 false
     */
    public boolean markOneWonVerified(String token) {
        return Long.valueOf(1).equals(redis.execute(ATTACH, List.of(key(token)), ONE_WON_VERIFIED, "1"));
    }

    /** 가입 완료·기기 재등록이 끝나면 그 사용자의 가입 진행 토큰을 모두 지운다(명세: 기기 등록 성공 시 폐기). */
    public void deleteAllForUser(Long userId) {
        String index = USER_INDEX_PREFIX + userId;
        Set<String> keys = redis.opsForSet().members(index);
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        redis.delete(index);
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
     * @param oneWonVerified 이 토큰으로 가입 중 1원 인증을 마쳤는지({@link #markOneWonVerified})
     */
    public record SignupSession(SignupMode mode, VerifiedIdentity identity, LocalDateTime verifiedAt, Long userId,
            boolean oneWonVerified) {

        /** 막 발급하는 토큰. 아직 1원 인증 전이다. */
        public SignupSession(SignupMode mode, VerifiedIdentity identity, LocalDateTime verifiedAt, Long userId) {
            this(mode, identity, verifiedAt, userId, false);
        }
    }

    public record IssuedSignupToken(String token, long expiresIn) {
    }
}
