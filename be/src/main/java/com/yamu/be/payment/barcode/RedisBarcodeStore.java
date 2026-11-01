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
 * barcode:{토큰}       → 해시 { userId, status, issuedAt, expiresAt, paymentId? }   시각은 epoch 밀리초
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

    /*
     * ISSUED이고 만료 전인 바코드만 SCANNED로 바꾼다. 상태 확인과 변경이 한 스크립트라 동시 스캔은 한 건만 성공한다.
     * KEYS[1] 바코드 키, KEYS[2] 사용자의 현재 바코드 키
     * ARGV[1] paymentId, ARGV[2] 지금(밀리초), ARGV[3] 새 보관 시간(밀리초), ARGV[4] 토큰
     * 반환: 1 SCANNED, 0 이미 사용, -1 없음, -2 만료
     */
    private static final RedisScript<Long> SCAN_SCRIPT = RedisScript.of("""
            local status = redis.call('HGET', KEYS[1], 'status')
            if not status then
              return -1
            end
            if status == 'EXPIRED' then
              return -2
            end
            if status ~= 'ISSUED' then
              return 0
            end
            local expiresAt = tonumber(redis.call('HGET', KEYS[1], 'expiresAt'))
            if expiresAt <= tonumber(ARGV[2]) then
              redis.call('HSET', KEYS[1], 'status', 'EXPIRED')
              return -2
            end
            redis.call('HSET', KEYS[1], 'status', 'SCANNED', 'paymentId', ARGV[1])
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            if redis.call('GET', KEYS[2]) == ARGV[4] then
              redis.call('PEXPIRE', KEYS[2], ARGV[3])
            end
            return 1
            """, Long.class);

    /* 그 결제 번호로 SCANNED일 때만 ISSUED로 되돌린다. KEYS[1] 바코드 키, ARGV[1] paymentId */
    private static final RedisScript<Long> REVERT_SCAN_SCRIPT = RedisScript.of("""
            if redis.call('HGET', KEYS[1], 'status') ~= 'SCANNED' then
              return 0
            end
            if redis.call('HGET', KEYS[1], 'paymentId') ~= ARGV[1] then
              return 0
            end
            redis.call('HSET', KEYS[1], 'status', 'ISSUED')
            redis.call('HDEL', KEYS[1], 'paymentId')
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
        if (!BarcodeTokenGenerator.isWellFormed(token)) {
            return Optional.empty();
        }
        Map<Object, Object> fields = redis.opsForHash().entries(KEY_PREFIX + token);
        if (fields.isEmpty()) {
            return Optional.empty();
        }
        String paymentId = (String) fields.get("paymentId");
        return Optional.of(new Barcode(
                token,
                Long.valueOf((String) fields.get("userId")),
                BarcodeStatus.valueOf((String) fields.get("status")),
                Instant.ofEpochMilli(Long.parseLong((String) fields.get("issuedAt"))),
                Instant.ofEpochMilli(Long.parseLong((String) fields.get("expiresAt"))),
                paymentId == null ? null : Long.valueOf(paymentId)));
    }

    @Override
    public ScanResult scan(String token, Long paymentId, Duration retention) {
        if (!BarcodeTokenGenerator.isWellFormed(token)) {
            return ScanResult.NOT_FOUND;
        }
        Barcode owner = find(token).orElse(null);
        if (owner == null) {
            return ScanResult.NOT_FOUND;
        }
        Long result = redis.execute(SCAN_SCRIPT,
                List.of(KEY_PREFIX + token, USER_KEY_PREFIX + owner.userId()),
                paymentId.toString(),
                Long.toString(Instant.now().toEpochMilli()),
                Long.toString(retention.toMillis()),
                token);
        if (result == null) {
            return ScanResult.NOT_FOUND;
        }
        return switch (result.intValue()) {
            case 1 -> ScanResult.SCANNED;
            case 0 -> ScanResult.ALREADY_USED;
            case -2 -> ScanResult.EXPIRED;
            default -> ScanResult.NOT_FOUND;
        };
    }

    @Override
    public boolean revertScan(String token, Long paymentId) {
        if (!BarcodeTokenGenerator.isWellFormed(token)) {
            return false;
        }
        Long result = redis.execute(REVERT_SCAN_SCRIPT, List.of(KEY_PREFIX + token), paymentId.toString());
        return result != null && result == 1L;
    }
}
