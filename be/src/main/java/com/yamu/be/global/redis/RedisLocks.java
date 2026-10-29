package com.yamu.be.global.redis;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 짧게 잡는 Redis 잠금. 여러 서버·요청 중 하나만 외부 호출(SSAFY 계정 생성·1원 송금 등)을 하게 할 때 쓴다.
 *
 * <pre>
 * try (RedisLocks.Lock lock = redisLocks.tryLock("signup:lock:" + key, Duration.ofSeconds(30))
 *         .orElseThrow(() -> new BusinessException(ErrorCode.SIGNUP_IN_PROGRESS))) {
 *     ... 한 번에 하나만 할 일 ...
 * }
 * </pre>
 *
 * <ul>
 *   <li>기다리지 않는다. 이미 잡혀 있으면 empty를 돌려주고, 받는 쪽이 409 등으로 바로 실패시킨다(요청 스레드를 붙잡지 않게).</li>
 *   <li>TTL이 지나면 저절로 풀린다. 서버가 죽어도 잠금이 영원히 남지 않게 하려는 것이라, TTL은 잠금 안의 일(외부 호출 제한 시간 포함)보다 길게 둔다.</li>
 *   <li>풀 때는 내가 잡은 잠금일 때만 지운다(Lua로 값 비교 후 삭제). TTL이 지나 다른 요청이 새로 잡은 잠금을 지우지 않게.</li>
 * </ul>
 *
 * DB 행 잠금(SELECT ... FOR UPDATE)과 달리 트랜잭션 밖의 외부 호출을 묶을 수 있지만, TTL 안에 끝난다는 가정이 깨지면 둘이 겹칠 수 있다.
 * 그래서 DB의 UNIQUE 같은 최종 안전장치는 그대로 둔다.
 */
@Component
@RequiredArgsConstructor
public class RedisLocks {

    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();

    /** 잠금을 잡으면 Lock(닫으면 풀림), 이미 잡혀 있으면 empty. */
    public Optional<Lock> tryLock(String key, Duration ttl) {
        String owner = newOwner();
        Boolean locked = redis.opsForValue().setIfAbsent(key, owner, ttl);
        return Boolean.TRUE.equals(locked) ? Optional.of(new Lock(key, owner)) : Optional.empty();
    }

    private String newOwner() {
        byte[] buf = new byte[16];
        random.nextBytes(buf);
        return URL.encodeToString(buf);
    }

    /** try-with-resources로 쓴다. 닫으면 내가 잡은 잠금일 때만 지운다. */
    public final class Lock implements AutoCloseable {

        private final String key;
        private final String owner;

        private Lock(String key, String owner) {
            this.key = key;
            this.owner = owner;
        }

        @Override
        public void close() {
            redis.execute(RELEASE, List.of(key), owner);
        }
    }
}
