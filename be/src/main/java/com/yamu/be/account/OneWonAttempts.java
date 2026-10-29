package com.yamu.be.account;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 1원 인증의 재요청 간격과 인증코드 시도 횟수. SSAFY는 틀린 코드를 몇 번 넣어도 막지 않아(10/27 실측 24회) YAMU가 센다.
 *
 * <pre>
 * onewon:resend:{userId}:{계좌 해시}  SET NX, 1분     같은 계좌로 1분 안에 다시 보내면 429
 * onewon:try:{userId}:{계좌 해시}     INCR, 5분       3회를 넘으면 1원 인증부터 다시. 새로 보내면 0부터
 * </pre>
 *
 * 키에 사용자를 넣는 이유: 같은 계좌라도 다른 사람의 시도가 내 횟수를 깎지 않게.
 */
@Component
@RequiredArgsConstructor
public class OneWonAttempts {

    private static final String RESEND_PREFIX = "onewon:resend:";
    private static final String TRY_PREFIX = "onewon:try:";

    /** 처음 센 때만 만료를 건다. INCR 뒤 EXPIRE를 따로 부르다 끊기면 만료 없는 키가 남는다. */
    private static final RedisScript<Long> COUNT = new DefaultRedisScript<>("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return n
            """, Long.class);

    private final StringRedisTemplate redis;
    private final AccountLinkProperties properties;

    /** 1원을 보내기 전에 부른다. 1분 안에 같은 계좌로 다시 요청했으면 429 AUTH_REQUEST_TOO_FREQUENT. */
    public void claimResendSlot(String accountKey) {
        Boolean claimed = redis.opsForValue().setIfAbsent(RESEND_PREFIX + accountKey, "1", properties.resendInterval());
        if (!Boolean.TRUE.equals(claimed)) {
            throw new BusinessException(ErrorCode.AUTH_REQUEST_TOO_FREQUENT);
        }
    }

    /** 1원을 보내지 못했으면 재요청 자리를 돌려준다. 금융망 실패로 1분을 기다리게 하지 않으려고. */
    public void releaseResendSlot(String accountKey) {
        redis.delete(RESEND_PREFIX + accountKey);
    }

    /** 새 인증코드를 보냈으니 시도 횟수를 처음부터 센다. */
    public void resetTries(String accountKey) {
        redis.delete(TRY_PREFIX + accountKey);
    }

    /** 1원 인증을 마쳤으면 재요청 간격·시도 횟수를 모두 지운다. 끝난 인증 때문에 다음 인증(기기 재등록 등)이 막히지 않게. */
    public void clear(String accountKey) {
        redis.delete(List.of(RESEND_PREFIX + accountKey, TRY_PREFIX + accountKey));
    }

    /**
     * 이번 시도를 센다. 이미 다 썼으면 SSAFY를 부르기 전에 400 AUTH_CODE_EXPIRED(1원 인증부터 다시).
     *
     * @return 이번 시도 뒤 남은 횟수
     */
    public int consumeTry(String accountKey) {
        Long used = redis.execute(COUNT, List.of(TRY_PREFIX + accountKey),
                String.valueOf(properties.authCodeValidity().toMillis()));
        long tries = used == null ? Long.MAX_VALUE : used;
        if (tries > properties.maxCodeAttempts()) {
            throw new BusinessException(ErrorCode.AUTH_CODE_EXPIRED);
        }
        return (int) (properties.maxCodeAttempts() - tries);
    }
}
