package com.yamu.be.global.finapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yamu.be.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** 기관거래고유번호 채번을 Redis만 띄워 확인한다. 시계는 한국 2026-10-28 09:30:15에 고정한다. */
@Testcontainers(disabledWithoutDocker = true)
class FinTransactionNoIssuerTest {

    /** 2026-10-28 00:30:15 UTC = 한국 09:30:15 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-28T00:30:15Z"), ZoneOffset.UTC);
    private static final String KEY = "fin:txno:20261028093015";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    LettuceConnectionFactory connectionFactory;
    StringRedisTemplate redis;
    FinTransactionNoIssuer issuer;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
        issuer = new FinTransactionNoIssuer(redis, CLOCK);
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void 한국_날짜_시각_뒤에_그_초의_일련번호_6자리를_붙인다() {
        assertThat(issuer.issue()).isEqualTo("20261028093015000001");
        assertThat(issuer.issue()).isEqualTo("20261028093015000002");
    }

    @Test
    void 카운터에는_TTL이_걸려_남지_않는다() {
        issuer.issue();

        assertThat(redis.getExpire(KEY)).isBetween(1L, 60L);
    }

    @Test
    void 동시에_받아도_번호가_겹치지_않는다() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> calls = IntStream.range(0, 200).<Callable<String>>mapToObj(i -> issuer::issue).toList();
            Set<String> numbers = new HashSet<>();
            for (Future<String> f : pool.invokeAll(calls)) {
                numbers.add(f.get());
            }
            assertThat(numbers).hasSize(200).allMatch(n -> n.matches("20261028093015\\d{6}"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 한_초에_999999개를_넘으면_번호를_재사용하지_않고_NOT_SENT() {
        redis.opsForValue().set(KEY, "999999");

        assertNotSent(() -> issuer.issue());
    }

    @Test
    void Redis에_닿지_않으면_NOT_SENT() {
        LettuceConnectionFactory closed = new LettuceConnectionFactory("127.0.0.1", 1);
        closed.afterPropertiesSet();
        try {
            FinTransactionNoIssuer down = new FinTransactionNoIssuer(new StringRedisTemplate(closed), CLOCK);

            assertNotSent(down::issue);
        } finally {
            closed.destroy();
        }
    }

    private static void assertNotSent(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(FinApiException.class)
                .satisfies(e -> {
                    FinApiException error = (FinApiException) e;
                    assertThat(error.getOutcome()).isEqualTo(FinApiOutcome.NOT_SENT);
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FIN_API_UNAVAILABLE);
                });
    }
}
