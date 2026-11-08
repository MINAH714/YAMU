package com.yamu.be.support;

import java.time.Instant;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 연결 계좌 잔액 캐시(AccountBalanceService)를 테스트에서 직접 심고 확인한다. 돈이 오간 뒤 캐시를 지우는지 보는
 * 결제 승인·청구 출금·환급 입금 통합 테스트가 쓴다(S15P31E103-329).
 *
 * <p>Redis 키는 {@code account:balance:{linkedAccountId}}, 값은 {@code "잔액|조회 시각(epoch 초)"}로,
 * AccountBalanceService의 비공개 상수를 옮겨 적은 것이다. 접두사가 거기서 바뀌면 여기만 같이 바꾼다
 * (어긋나면 심은 키를 서비스가 지우지 못해 "지워진다" 검증이 먼저 깨진다).
 */
public final class AccountBalanceCache {

    private static final String KEY_PREFIX = "account:balance:";

    private AccountBalanceCache() {
    }

    /** 캐시에 잔액을 심는다. TTL은 두지 않아 테스트 도중 저절로 사라지지 않는다. */
    public static void put(StringRedisTemplate redis, long linkedAccountId, long balance, Instant fetchedAt) {
        redis.opsForValue().set(key(linkedAccountId), balance + "|" + fetchedAt.getEpochSecond());
    }

    public static boolean has(StringRedisTemplate redis, long linkedAccountId) {
        return Boolean.TRUE.equals(redis.hasKey(key(linkedAccountId)));
    }

    public static String key(long linkedAccountId) {
        return KEY_PREFIX + linkedAccountId;
    }
}
