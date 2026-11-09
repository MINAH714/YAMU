package com.yamu.be.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
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

/** 기기별·IP별 호출 횟수 제한을 Redis만 띄워 확인한다. 한도는 기기 3번, IP 5번으로 줄여서 본다. */
@Testcontainers(disabledWithoutDocker = true)
class LoginRateLimiterTest {

    private static final String IP = "203.0.113.7";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    LettuceConnectionFactory connectionFactory;
    StringRedisTemplate redis;
    LoginRateLimiter limiter;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
        limiter = new LoginRateLimiter(redis, new LoginRateLimitProperties(Duration.ofMinutes(1), 3, 5));
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void 같은_기기는_한도까지_되고_넘으면_429와_다시_시도할_시간을_준다() {
        for (int i = 0; i < 3; i++) {
            limiter.check(7L, IP);
        }

        assertThatThrownBy(() -> limiter.check(7L, IP))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException error = (BusinessException) e;
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
                    assertThat((Long) error.getDetails().get("retryAfterSeconds")).isBetween(1L, 60L);
                });
    }

    @Test
    void 다른_기기는_따로_센다() {
        for (int i = 0; i < 3; i++) {
            limiter.check(7L, null);
        }

        assertThatCode(() -> limiter.check(8L, null)).doesNotThrowAnyException();
    }

    @Test
    void 기기_번호를_바꿔_가며_넣어도_같은_IP면_막힌다() {
        for (long device = 1; device <= 5; device++) {
            limiter.check(device, IP);
        }

        assertThatThrownBy(() -> limiter.check(6L, IP))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
        assertThatCode(() -> limiter.check(6L, "198.51.100.1")).doesNotThrowAnyException();
    }

    @Test
    void 세는_키에는_구간만큼_TTL이_걸린다() {
        limiter.check(7L, IP);

        assertThat(redis.getExpire("auth:rate:device:7")).isBetween(1L, 60L);
        assertThat(redis.getExpire("auth:rate:ip:" + IP)).isBetween(1L, 60L);
    }
}
