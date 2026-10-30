package com.yamu.be.account;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 1원 인증의 재요청 간격·시도 횟수·하루 한도. SSAFY는 틀린 코드를 몇 번 넣어도 막지 않아(10/27 실측 24회) YAMU가 센다.
 *
 * <pre>
 * onewon:resend:{userId}:{계좌 해시}             SET NX, 1분       같은 계좌로 1분 안에 다시 보내면 429
 * onewon:try:{userId}:{계좌 해시}                INCR, 10분        코드 하나에 3회. 새로 보내면 0부터
 * onewon:sent:{userId}:{계좌 해시}:{한국 날짜}    INCR, 다음 자정    하루 5회 발송
 * onewon:fail:{userId}:{계좌 해시}:{한국 날짜}    INCR, 다음 자정    하루 10회 틀리면 그날 잠금
 * </pre>
 *
 * 하루 한도가 없으면 1분마다 다시 보내 시도 횟수를 0으로 돌리면서 4자리 코드를 하루 4,320번 넣을 수 있다(맞힐 확률 약 35%).
 * 기기 재등록에서는 1원 인증이 사실상 유일한 본인 확인이라 계정 탈취로 이어진다. 두 한도로 하루 최대 10번(0.1%)으로 줄인다.
 *
 * <p>키에 사용자를 넣는 이유: 같은 계좌라도 다른 사람의 시도가 내 횟수를 깎지 않게. 1원은 이 사용자 userKey의 계좌 목록에서
 * 고른 계좌로만 보내므로 남의 계좌로는 보낼 수 없다.
 */
@Component
@RequiredArgsConstructor
public class OneWonAttempts {

    private static final String RESEND_PREFIX = "onewon:resend:";
    private static final String TRY_PREFIX = "onewon:try:";
    private static final String SENT_PREFIX = "onewon:sent:";
    private static final String FAIL_PREFIX = "onewon:fail:";
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    /** 처음 센 때만 만료를 건다. INCR 뒤 EXPIRE를 따로 부르다 끊기면 만료 없는 키가 남는다. */
    private static final RedisScript<Long> COUNT = new DefaultRedisScript<>("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return n
            """, Long.class);

    /** 한도 안이면 하나 올리고 그 값을, 이미 한도면 올리지 않고 -1을 돌려준다(확인과 증가를 한 번에). */
    private static final RedisScript<Long> CLAIM = new DefaultRedisScript<>("""
            local n = tonumber(redis.call('GET', KEYS[1]) or '0')
            if n >= tonumber(ARGV[1]) then return -1 end
            n = redis.call('INCR', KEYS[1])
            if n == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
            return n
            """, Long.class);

    /** 0보다 클 때만 하나 내린다. */
    private static final RedisScript<Long> REFUND = new DefaultRedisScript<>("""
            local n = tonumber(redis.call('GET', KEYS[1]) or '0')
            if n > 0 then return redis.call('DECR', KEYS[1]) end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final AccountLinkProperties properties;
    private final Clock clock;

    /** 1원을 보내기 전에 부른다. 1분 안에 같은 계좌로 다시 요청했으면 429 AUTH_REQUEST_TOO_FREQUENT(details.retryAfterSeconds). */
    public void claimResendSlot(String accountKey) {
        String key = RESEND_PREFIX + accountKey;
        Boolean claimed = redis.opsForValue().setIfAbsent(key, "1", properties.resendInterval());
        if (!Boolean.TRUE.equals(claimed)) {
            Long ttlMillis = redis.getExpire(key, TimeUnit.MILLISECONDS);
            throw new BusinessException(ErrorCode.AUTH_REQUEST_TOO_FREQUENT,
                    Map.of("retryAfterSeconds", seconds(ttlMillis == null ? 0 : ttlMillis)));
        }
    }

    /** 1원을 보내지 못한 게 확실하면 재요청 자리를 돌려준다. 금융망 실패로 1분을 기다리게 하지 않으려고. */
    public void releaseResendSlot(String accountKey) {
        redis.delete(RESEND_PREFIX + accountKey);
    }

    /** 오늘 이 계좌로 보낸 횟수를 하나 올린다. 이미 하루 한도면 429 ONE_WON_DAILY_LIMIT_EXCEEDED(details.retryAfterSeconds: 자정까지). */
    public void claimDailySend(String accountKey) {
        Long sent = redis.execute(CLAIM, List.of(dailyKey(SENT_PREFIX, accountKey)),
                String.valueOf(properties.dailySendLimit()), String.valueOf(untilMidnight().toMillis()));
        if (sent == null || sent < 0) {
            throw new BusinessException(ErrorCode.ONE_WON_DAILY_LIMIT_EXCEEDED,
                    Map.of("retryAfterSeconds", seconds(untilMidnight().toMillis())));
        }
    }

    /** 1원이 나가지 않은 게 확실하면(요청 안 감·거절) 하루 횟수를 돌려준다. 결과 불명이면 돌려주지 않는다. */
    public void refundDailySend(String accountKey) {
        redis.execute(REFUND, List.of(dailyKey(SENT_PREFIX, accountKey)));
    }

    /** 오늘 이 계좌로 인증코드를 하루 한도만큼 틀렸으면 429 AUTH_CODE_LOCKED. 1원 발송과 코드 확인 앞에서 부른다. */
    public void requireNotLocked(String accountKey) {
        String failed = redis.opsForValue().get(dailyKey(FAIL_PREFIX, accountKey));
        if (failed != null && Long.parseLong(failed) >= properties.dailyFailLimit()) {
            throw locked();
        }
    }

    /** SSAFY가 틀렸다고 한 코드를 하루 횟수에 더한다. 이번에 한도에 닿았으면 그 자리에서 429 AUTH_CODE_LOCKED. */
    public void recordFailure(String accountKey) {
        Long failed = redis.execute(COUNT, List.of(dailyKey(FAIL_PREFIX, accountKey)),
                String.valueOf(untilMidnight().toMillis()));
        if (failed != null && failed >= properties.dailyFailLimit()) {
            throw locked();
        }
    }

    /** 새 인증코드를 보냈으니 시도 횟수를 처음부터 센다. 하루 틀린 횟수는 그대로 둔다. */
    public void resetTries(String accountKey) {
        redis.delete(TRY_PREFIX + accountKey);
    }

    /**
     * 1원 인증을 마쳤으면 재요청 간격·시도 횟수를 지운다. 끝난 인증 때문에 다음 인증(기기 재등록 등)이 막히지 않게.
     * 하루 발송·실패 횟수는 지우지 않는다(지우면 연결·해제를 반복해 한도를 우회할 수 있다).
     */
    public void clear(String accountKey) {
        redis.delete(List.of(RESEND_PREFIX + accountKey, TRY_PREFIX + accountKey));
    }

    /**
     * 이번 시도를 센다. 이미 다 썼으면 SSAFY를 부르기 전에 400 AUTH_CODE_EXPIRED(1원 인증부터 다시).
     *
     * <p>횟수는 verificationToken과 같은 10분 동안 기억한다. 5분(안내한 코드 유효시간)으로 두면 SSAFY 코드가 실제로는
     * 5~10분 사이에도 살아 있어(10/27 실측) 키가 먼저 사라진 뒤 재발송 없이 3번을 더 넣을 수 있었다.
     *
     * @return 이번 시도 뒤 남은 횟수
     */
    public int consumeTry(String accountKey) {
        Long used = redis.execute(COUNT, List.of(TRY_PREFIX + accountKey),
                String.valueOf(properties.verificationTtl().toMillis()));
        long tries = used == null ? Long.MAX_VALUE : used;
        if (tries > properties.maxCodeAttempts()) {
            throw new BusinessException(ErrorCode.AUTH_CODE_EXPIRED);
        }
        return (int) (properties.maxCodeAttempts() - tries);
    }

    private BusinessException locked() {
        return new BusinessException(ErrorCode.AUTH_CODE_LOCKED,
                Map.of("retryAfterSeconds", seconds(untilMidnight().toMillis())));
    }

    /** 한국 날짜가 바뀌면 새 키가 되어 저절로 초기화된다. */
    private String dailyKey(String prefix, String accountKey) {
        return prefix + accountKey + ":" + LocalDate.now(clock.withZone(KOREA)).format(DAY);
    }

    private Duration untilMidnight() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(KOREA));
        return Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(KOREA));
    }

    private static long seconds(long millis) {
        return Math.max(1, (Math.max(millis, 0) + 999) / 1000);
    }
}
