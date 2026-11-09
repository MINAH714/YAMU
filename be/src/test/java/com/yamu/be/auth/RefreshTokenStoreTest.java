package com.yamu.be.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 동시 재발급 유예(10초)를 Redis만 띄워 확인한다. 통합 테스트는 재사용 감지를 바로 보려고 유예를 꺼 두었다.
 * 상황: 앱이 같은 Refresh Token으로 재발급을 거의 동시에 두 번 보내 한쪽이 먼저 토큰을 바꿔 버린 경우.
 */
@Testcontainers(disabledWithoutDocker = true)
class RefreshTokenStoreTest {

    private static final long DEVICE_ID = 7L;

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    LettuceConnectionFactory connectionFactory;
    MutableClock clock;
    RefreshTokenStore store;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(connectionFactory);
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
        clock = new MutableClock();
        AuthProperties properties = new AuthProperties(Duration.ofSeconds(60), Duration.ofDays(14),
                Duration.ofSeconds(10), 5, Duration.ofMinutes(30), Duration.ofMinutes(5));
        store = new RefreshTokenStore(redis, properties, clock);
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void 유예_시간_안에_직전_토큰이_다시_오면_진_쪽도_새_토큰을_받는다() {
        String first = store.issue(DEVICE_ID);
        String winner = store.rotate(first);

        String loser = store.rotate(first);

        assertThat(loser).isNotNull().isNotEqualTo(winner);
    }

    @Test
    void 앱이_어느_응답을_저장해도_다음_재발급이_되고_남은_쪽은_버려진다() {
        String first = store.issue(DEVICE_ID);
        String winner = store.rotate(first);
        String loser = store.rotate(first);

        // 앱이 진 쪽 응답을 저장한 경우
        assertThat(store.rotate(loser)).isNotNull();
        assertThat(store.rotate(winner)).isNull();
    }

    @Test
    void 이긴_쪽_토큰을_저장해도_다음_재발급이_된다() {
        String first = store.issue(DEVICE_ID);
        String winner = store.rotate(first);
        String loser = store.rotate(first);

        assertThat(store.rotate(winner)).isNotNull();
        assertThat(store.rotate(loser)).isNull();
    }

    @Test
    void 유예_시간이_지나_직전_토큰이_오면_탈취로_보고_세션을_지운다() {
        String first = store.issue(DEVICE_ID);
        String second = store.rotate(first);

        clock.advance(Duration.ofSeconds(11));

        assertThat(store.rotate(first)).isNull();
        assertThat(store.rotate(second)).isNull();
    }
}
