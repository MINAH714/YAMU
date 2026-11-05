package com.yamu.be.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis가 죽었을 때 한도별 동작(S15P31E103-238). 잔액 새로고침 한도만 건너뛰고, 보안용 한도는 지금처럼 실패한다.
 */
class LoginRateLimiterRedisDownTest {

    /** 어떤 명령이든 연결 실패를 던지는 Redis. */
    StringRedisTemplate down = mock(StringRedisTemplate.class, invocation -> {
        throw new RedisConnectionFailureException("down");
    });
    LoginRateLimiter limiter = new LoginRateLimiter(down, new LoginRateLimitProperties(Duration.ofMinutes(1), 3, 5));

    @Test
    void 잔액_새로고침_한도는_Redis가_죽으면_세지_않고_통과한다() {
        assertThatCode(() -> limiter.checkBalanceRefresh(1L, 5)).doesNotThrowAnyException();
    }

    @Test
    void 로그인_가입_카드_확인_한도는_Redis가_죽으면_지금처럼_실패한다() {
        assertThatThrownBy(() -> limiter.check(7L, "203.0.113.7")).isInstanceOf(RedisConnectionFailureException.class);
        assertThatThrownBy(() -> limiter.checkSignup("203.0.113.7", 5)).isInstanceOf(RedisConnectionFailureException.class);
        assertThatThrownBy(() -> limiter.checkCardLookup(1L, 5)).isInstanceOf(RedisConnectionFailureException.class);
    }
}
