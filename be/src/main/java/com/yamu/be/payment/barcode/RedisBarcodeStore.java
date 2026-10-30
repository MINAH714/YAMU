package com.yamu.be.payment.barcode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 바코드를 Redis에 보관한다.
 *
 * <pre>
 * barcode:{토큰}       → 해시 { userId, status, issuedAt, expiresAt }   시각은 epoch 밀리초
 * barcode:user:{userId} → 그 사용자의 현재 바코드 토큰
 * </pre>
 *
 * 두 키 모두 보관 시간이 지나면 Redis가 지운다.
 */
@Component
class RedisBarcodeStore implements BarcodeStore {

    static final String KEY_PREFIX = "barcode:";
    static final String USER_KEY_PREFIX = "barcode:user:";

    /*
     * 새 바코드 저장과 이전 바코드 만료를 Redis 안에서 끊기지 않게 한 번에 실행한다.
     * KEYS[1] 새 바코드 키, KEYS[2] 사용자의 현재 바코드 키
     * ARGV[1] 새 토큰, ARGV[2] userId, ARGV[3] issuedAt, ARGV[4] expiresAt, ARGV[5] 보관 시간(밀리초), ARGV[6] 바코드 키 접두어
     * 이전 바코드가 SCANNED·USED면 결제가 진행 중이거나 끝난 것이므로 바꾸지 않는다.
     */
    private static final RedisScript<Long> ISSUE_SCRIPT = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
              return 0
            end
            local previous = redis.call('GET', KEYS[2])
            if previous then
              local previousKey = ARGV[6] .. previous
              if redis.call('HGET', previousKey, 'status') == 'ISSUED' then
                redis.call('HSET', previousKey, 'status', 'EXPIRED')
              end
            end
            redis.call('HSET', KEYS[1], 'userId', ARGV[2], 'status', 'ISSUED', 'issuedAt', ARGV[3], 'expiresAt', ARGV[4])
            redis.call('PEXPIRE', KEYS[1], ARGV[5])
            redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[5])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    RedisBarcodeStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean issue(Barcode barcode, Duration retention) {
        Long saved = redis.execute(ISSUE_SCRIPT,
                List.of(KEY_PREFIX + barcode.token(), USER_KEY_PREFIX + barcode.userId()),
                barcode.token(),
                barcode.userId().toString(),
                Long.toString(barcode.issuedAt().toEpochMilli()),
                Long.toString(barcode.expiresAt().toEpochMilli()),
                Long.toString(retention.toMillis()),
                KEY_PREFIX);
        return saved != null && saved == 1L;
    }

    @Override
    public Optional<Barcode> find(String token) {
        Map<Object, Object> fields = redis.opsForHash().entries(KEY_PREFIX + token);
        if (fields.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Barcode(
                token,
                Long.valueOf((String) fields.get("userId")),
                BarcodeStatus.valueOf((String) fields.get("status")),
                Instant.ofEpochMilli(Long.parseLong((String) fields.get("issuedAt"))),
                Instant.ofEpochMilli(Long.parseLong((String) fields.get("expiresAt")))));
    }
}
