package com.yamu.be.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.support.MutableClock;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 직전 토큰 재사용 유예를 Redis만 띄워 확인한다. 통합 테스트는 재사용 감지를 바로 보려고 유예를 꺼 두었다.
 * 상황: 앱이 같은 Refresh Token으로 재발급을 거의 동시에 두 번 보내 한쪽이 먼저 토큰을 바꿔 버린 경우,
 * 그리고 재발급 응답이 끊겨 앱이 직전 토큰으로 다시 부르는 경우(S15P31E103-352).
 * 유예 시간은 실제 설정({@code application.yml})에서 읽는다.
 */
@Testcontainers(disabledWithoutDocker = true)
class RefreshTokenStoreTest {

    private static final long DEVICE_ID = 7L;
    /** 앱의 응답 대기 시간(OkHttp readTimeout, fe/android NetworkModule). 유예가 이보다 짧으면 재시도가 탈취로 보인다. */
    private static final Duration APP_READ_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration GRACE = configuredGrace();

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
                GRACE, 5, Duration.ofMinutes(30), Duration.ofMinutes(5));
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
    void 재발급_응답이_끊겨_앱이_응답_대기_시간이_지난_뒤_직전_토큰으로_다시_불러도_새_토큰을_받는다() {
        assertThat(GRACE).isGreaterThan(APP_READ_TIMEOUT);
        String first = store.issue(DEVICE_ID);
        String lost = store.rotate(first);   // 서버는 바꿨지만 앱은 응답을 받지 못함

        clock.advance(APP_READ_TIMEOUT.plusSeconds(1));
        String retried = store.rotate(first);

        assertThat(retried).isNotNull().isNotEqualTo(lost);
        // 앱은 다시 받은 토큰을 저장하고, 이걸로 다음 재발급이 된다
        assertThat(store.rotate(retried)).isNotNull();
    }

    @Test
    void 유예_시간이_지나_직전_토큰이_오면_탈취로_보고_세션을_지운다() {
        String first = store.issue(DEVICE_ID);
        String second = store.rotate(first);

        clock.advance(GRACE.plusSeconds(1));

        assertThat(store.rotate(first)).isNull();
        assertThat(store.rotate(second)).isNull();
    }

    private static Duration configuredGrace() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();
        return DurationStyle.detectAndParse(properties.getProperty("yamu.auth.refresh-reuse-grace"));
    }
}
