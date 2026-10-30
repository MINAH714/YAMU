package com.yamu.be.auth;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 인증 없이 열린 로그인 준비·로그인 API의 호출 횟수를 기기 번호별·IP별로 제한한다. 가입 API도 같은 방식으로 IP별로 센다.
 *
 * <p>기기 번호는 순번이라 하나씩 넣어 보면 기기가 있는지·생체를 쓰는지·잠겼는지 알 수 있고, 엉터리 서명은 잠금 대신 인증 기록만 남기므로
 * 제한이 없으면 기록이 끝없이 쌓인다. 둘 다 호출 횟수를 묶어 막는다.
 *
 * <pre>
 * auth:rate:device:{userDeviceId} → 요청 수   TTL = 구간(1분)
 * auth:rate:ip:{ip}               → 요청 수   TTL = 구간
 * auth:rate:signup-ip:{ip}        → 요청 수   TTL = 구간   본인인증 요청·확인(인증 없이 열린 가입 API)
 * auth:rate:card-lookup:{userId}  → 요청 수   TTL = 구간   카드 확인(카드번호 대입 막기)
 * </pre>
 *
 * 고정 구간 방식이라 구간 경계에서 잠깐 두 배까지 허용될 수 있다. 목적이 대량 시도 차단이라 정확한 초당 제어는 하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class LoginRateLimiter {

    private static final String KEY_PREFIX = "auth:rate:";

    /** 1을 더하고, 처음 만든 키면 구간 TTL을 건다. {요청 수, 남은 ms}를 돌려준다. */
    private static final RedisScript<List> HIT = new DefaultRedisScript<>("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return {n, redis.call('PTTL', KEYS[1])}
            """, List.class);

    private final StringRedisTemplate redis;
    private final LoginRateLimitProperties properties;

    /** 한도를 넘으면 429 TOO_MANY_REQUESTS. details.retryAfterSeconds에 다시 시도할 수 있을 때까지 남은 초를 담는다. */
    public void check(Long userDeviceId, String ipAddress) {
        if (ipAddress != null) {
            hit("ip:" + ipAddress, properties.perIp());
        }
        if (userDeviceId != null) {
            hit("device:" + userDeviceId, properties.perDevice());
        }
    }

    /**
     * 휴대폰 본인인증 요청·확인을 합쳐 IP별로 센다. 로그인과 따로 세서 한쪽이 다른 쪽 한도를 깎지 않는다.
     * 막지 않으면 인증번호(demoCode)로 시드 사용자의 이름·생년월일·번호 조합을 무작위로 맞춰 볼 수 있다.
     */
    public void checkSignup(String ipAddress, int limit) {
        if (ipAddress != null) {
            hit("signup-ip:" + ipAddress, limit);
        }
    }

    /** 로그인한 사용자의 카드 확인 호출을 센다. 카드번호·유효기간을 바꿔 가며 남의 카드를 찾아보는 것을 막는다. */
    public void checkCardLookup(Long userId, int limit) {
        if (userId != null) {
            hit("card-lookup:" + userId, limit);
        }
    }

    private void hit(String key, int limit) {
        List<?> result = redis.execute(HIT, List.of(KEY_PREFIX + key), String.valueOf(properties.window().toMillis()));
        long count = ((Number) result.get(0)).longValue();
        if (count > limit) {
            long ttlMillis = Math.max(((Number) result.get(1)).longValue(), 0);
            long retryAfter = Math.max(1, (ttlMillis + 999) / 1000);
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, Map.of("retryAfterSeconds", retryAfter));
        }
    }
}
