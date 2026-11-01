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
 * barcode:code:{토큰}         → 해시 { userId, status, issuedAt, expiresAt, paymentId? }   시각은 epoch 밀리초
 * barcode:user:{userId}       → 그 사용자의 현재 바코드 토큰
 * barcode:payment:{paymentId} → 그 결제 요청이 스캔한 바코드 토큰(스캔 때 생김). 승인·만료가 토큰 없이 바코드를 찾는 역색인
 * </pre>
 *
 * 세 키 모두 보관 시간이 지나면 Redis가 지운다.
 * 바코드 키와 사용자 키는 접두어를 나눈다. 같은 접두어면 토큰 자리에 "user:5" 같은 값이 왔을 때 사용자 키(문자열)를
 * 해시로 읽어 WRONGTYPE 오류(500)가 나고, 그 사용자가 바코드를 발급했는지가 404와의 차이로 드러난다.
 */
@Component
class RedisBarcodeStore implements BarcodeStore {

    static final String KEY_PREFIX = "barcode:code:";
    static final String USER_KEY_PREFIX = "barcode:user:";
    static final String PAYMENT_KEY_PREFIX = "barcode:payment:";

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
     * KEYS[1] 바코드 키, KEYS[2] 사용자의 현재 바코드 키, KEYS[3] 결제 번호 역색인 키
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
            redis.call('SET', KEYS[3], ARGV[4], 'PX', ARGV[3])
            return 1
            """, Long.class);

    /* 그 결제 번호로 SCANNED일 때만 ISSUED로 되돌린다. KEYS[1] 바코드 키, KEYS[2] 결제 번호 역색인 키, ARGV[1] paymentId */
    private static final RedisScript<Long> REVERT_SCAN_SCRIPT = RedisScript.of("""
            if redis.call('HGET', KEYS[1], 'status') ~= 'SCANNED' then
              return 0
            end
            if redis.call('HGET', KEYS[1], 'paymentId') ~= ARGV[1] then
              return 0
            end
            redis.call('HSET', KEYS[1], 'status', 'ISSUED')
            redis.call('HDEL', KEYS[1], 'paymentId')
            redis.call('DEL', KEYS[2])
            return 1
            """, Long.class);

    /*
     * 결제 결과를 바코드에 반영한다(USED·EXPIRED). 역색인으로 토큰을 찾고, 그 결제 번호로 SCANNED일 때만 바꾼다.
     * KEYS[1] 결제 번호 역색인 키, ARGV[1] paymentId, ARGV[2] 새 상태, ARGV[3] 바코드 키 접두어
     * 반환: 1 바꿈, 0 다른 상태·다른 결제, -1 역색인 없음(보관 종료)
     */
    private static final RedisScript<Long> SETTLE_SCRIPT = RedisScript.of("""
            local token = redis.call('GET', KEYS[1])
            if not token then
              return -1
            end
            local key = ARGV[3] .. token
            if redis.call('HGET', key, 'status') ~= 'SCANNED' then
              return 0
            end
            if redis.call('HGET', key, 'paymentId') ~= ARGV[1] then
              return 0
            end
            redis.call('HSET', key, 'status', ARGV[2])
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
                List.of(KEY_PREFIX + token, USER_KEY_PREFIX + owner.userId(), PAYMENT_KEY_PREFIX + paymentId),
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
        Long result = redis.execute(REVERT_SCAN_SCRIPT, List.of(KEY_PREFIX + token, PAYMENT_KEY_PREFIX + paymentId),
                paymentId.toString());
        return result != null && result == 1L;
    }

    @Override
    public boolean markUsed(Long paymentId) {
        return settle(paymentId, BarcodeStatus.USED);
    }

    @Override
    public boolean expireByPayment(Long paymentId) {
        return settle(paymentId, BarcodeStatus.EXPIRED);
    }

    private boolean settle(Long paymentId, BarcodeStatus status) {
        Long result = redis.execute(SETTLE_SCRIPT, List.of(PAYMENT_KEY_PREFIX + paymentId), paymentId.toString(),
                status.name(), KEY_PREFIX);
        return result != null && result == 1L;
    }
}
