package com.yamu.be.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 실제 Redis가 필요한 테스트가 상속한다. Testcontainers가 로컬 개발 환경(be/docker-compose.yml)과 같은 Redis 7을
 * 잠깐 띄우고, 접속 정보는 @ServiceConnection이 자동으로 바꿔 넣는다.
 *
 * <p>Docker가 없는 환경에서는 이 클래스를 상속한 테스트만 건너뛴다(실패가 아니라 skip).
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
public abstract class RedisTestSupport {

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);
}
