package com.yamu.be.global.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** 잠금을 Redis만 띄워 확인한다: 한 번에 하나, 닫으면 풀림, 남의 잠금은 지우지 않음. */
@Testcontainers(disabledWithoutDocker = true)
class RedisLocksTest {

    private static final String KEY = "test:lock:1";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    LettuceConnectionFactory connectionFactory;
    StringRedisTemplate redis;
    RedisLocks locks;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
        locks = new RedisLocks(redis);
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void 잡혀_있으면_기다리지_않고_empty이고_닫으면_다시_잡을_수_있다() {
        Optional<RedisLocks.Lock> first = locks.tryLock(KEY, Duration.ofSeconds(30));
        assertThat(first).isPresent();
        assertThat(locks.tryLock(KEY, Duration.ofSeconds(30))).isEmpty();

        first.get().close();

        assertThat(locks.tryLock(KEY, Duration.ofSeconds(30))).isPresent();
    }

    @Test
    void TTL이_지나면_저절로_풀린다() {
        assertThat(locks.tryLock(KEY, Duration.ofMillis(200))).isPresent();

        assertThat(redis.getExpire(KEY)).isLessThanOrEqualTo(1L);
        awaitGone();
        assertThat(locks.tryLock(KEY, Duration.ofSeconds(30))).isPresent();
    }

    @Test
    void TTL이_지나_다른_요청이_새로_잡은_잠금은_늦게_닫아도_지우지_않는다() {
        RedisLocks.Lock stale = locks.tryLock(KEY, Duration.ofMillis(200)).orElseThrow();
        awaitGone();
        RedisLocks.Lock current = locks.tryLock(KEY, Duration.ofSeconds(30)).orElseThrow();

        stale.close();

        assertThat(redis.hasKey(KEY)).isTrue();
        assertThat(locks.tryLock(KEY, Duration.ofSeconds(30))).isEmpty();
        current.close();
        assertThat(redis.hasKey(KEY)).isFalse();
    }

    private void awaitGone() {
        long deadline = System.currentTimeMillis() + 3000;
        while (Boolean.TRUE.equals(redis.hasKey(KEY)) && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
    }
}
