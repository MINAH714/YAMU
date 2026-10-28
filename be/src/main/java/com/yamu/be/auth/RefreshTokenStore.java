package com.yamu.be.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 기기별 Refresh Token을 Redis에 보관한다. 토큰은 JWT가 아니라 랜덤 문자열이고, Redis에는 원문 대신 SHA-256 해시를 둔다.
 *
 * <pre>
 * 토큰: "{userDeviceId}.{32바이트 랜덤 Base64URL}"      예) 7.Kq8vN2xR5tYb9WmC3dLp0sHfJ6gAeZ1uOiTn4rVwXy
 * auth:refresh:{userDeviceId} → { cur: 지금 토큰 해시, prev: 직전 토큰 해시, rotatedAt: 교체 시각(ms), alt: 동시 재발급 때만 }   TTL 14일
 * </pre>
 *
 * <p>재발급할 때마다 토큰을 바꾼다(rotation). 이미 쓴 직전 토큰이 다시 오면 탈취로 보고 그 기기 세션을 지운다.
 * 아무 문자열이나 보낸 요청(cur도 prev도 아님)은 거절만 하고 세션은 그대로 둔다. 그렇지 않으면 기기 번호만 아는 사람이
 * 엉터리 토큰을 보내 남을 로그아웃시킬 수 있다. 비교·교체는 Lua 스크립트 하나로 해서 동시 요청에도 한쪽만 바뀐다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenStore {

    private static final String KEY_PREFIX = "auth:refresh:";
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    /**
     * 1 교체됨, 2 재사용 감지(세션 삭제), 0 모름.
     * 직전 토큰이 유예 시간 안에 다시 오면(같은 앱이 재발급을 동시에 두 번 보냄) 진 쪽에도 새 토큰을 주고 alt에 둔다.
     * 앱이 둘 중 어느 응답을 저장해도 다음 재발급이 되도록 cur와 alt를 모두 받고, 한쪽이 쓰이면 다른 쪽은 버린다.
     */
    private static final RedisScript<Long> ROTATE = new DefaultRedisScript<>("""
            local cur = redis.call('HGET', KEYS[1], 'cur')
            if not cur then return 0 end
            local alt = redis.call('HGET', KEYS[1], 'alt')
            if cur == ARGV[1] or (alt and alt == ARGV[1]) then
              redis.call('HSET', KEYS[1], 'prev', ARGV[1], 'cur', ARGV[2], 'rotatedAt', ARGV[3])
              redis.call('HDEL', KEYS[1], 'alt')
              redis.call('PEXPIRE', KEYS[1], ARGV[4])
              return 1
            end
            local prev = redis.call('HGET', KEYS[1], 'prev')
            if prev and prev == ARGV[1] then
              local rotatedAt = tonumber(redis.call('HGET', KEYS[1], 'rotatedAt') or '0')
              if tonumber(ARGV[3]) - rotatedAt < tonumber(ARGV[5]) then
                redis.call('HSET', KEYS[1], 'alt', ARGV[2])
                return 1
              end
              redis.call('DEL', KEYS[1])
              return 2
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final AuthProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /** 새 세션을 만든다. 저장과 TTL을 한 번에 걸어 TTL 없는 키가 남지 않게 한다. */
    private static final RedisScript<Long> ISSUE = new DefaultRedisScript<>("""
            redis.call('DEL', KEYS[1])
            redis.call('HSET', KEYS[1], 'cur', ARGV[1], 'rotatedAt', '0')
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            return 1
            """, Long.class);

    /** 로그인 성공 시. 같은 기기의 이전 세션은 덮어써져 끝난다. */
    public String issue(Long userDeviceId) {
        String token = newToken(userDeviceId);
        redis.execute(ISSUE, List.of(key(userDeviceId)), sha256(token),
                String.valueOf(properties.refreshTokenTtl().toMillis()));
        return token;
    }

    /** 토큰 앞부분의 기기 번호. 형식이 틀리면 empty. */
    public static OptionalLong deviceIdOf(String token) {
        if (token == null) {
            return OptionalLong.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(token.substring(0, dot)));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    /** 맞는 토큰이면 새 토큰으로 바꿔 돌려준다. 아니면 null(재사용이면 세션까지 삭제됨). */
    public String rotate(String token) {
        OptionalLong deviceId = deviceIdOf(token);
        if (deviceId.isEmpty()) {
            return null;
        }
        String next = newToken(deviceId.getAsLong());
        Long result = redis.execute(ROTATE, List.of(key(deviceId.getAsLong())),
                sha256(token), sha256(next), String.valueOf(clock.millis()),
                String.valueOf(properties.refreshTokenTtl().toMillis()),
                String.valueOf(properties.refreshReuseGrace().toMillis()));
        if (result != null && result == 2L) {
            log.warn("Refresh Token 재사용 감지: 기기 {} 세션을 폐기함", deviceId.getAsLong());
        }
        return result != null && result == 1L ? next : null;
    }

    public void revoke(Long userDeviceId) {
        redis.delete(key(userDeviceId));
    }

    private String newToken(long userDeviceId) {
        byte[] buf = new byte[32];
        random.nextBytes(buf);
        return userDeviceId + "." + URL.encodeToString(buf);
    }

    private static String key(long userDeviceId) {
        return KEY_PREFIX + userDeviceId;
    }

    private static String sha256(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
