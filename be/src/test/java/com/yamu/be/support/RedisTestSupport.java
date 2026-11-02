package com.yamu.be.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 실제 Redis가 필요한 테스트가 상속한다. Testcontainers가 로컬 개발 환경(be/docker-compose.yml)과 같은 Redis 7을
 * 잠깐 띄우고, 접속 정보는 @ServiceConnection이 자동으로 바꿔 넣는다.
 *
 * <p>컨테이너는 테스트 JVM에서 한 번만 띄우고 끝날 때 Testcontainers가 지운다. {@code @Container}로 두면 상속한 테스트 클래스마다
 * 컨테이너를 멈췄다 다시 띄우는데, Spring은 같은 설정의 컨텍스트를 캐시해서 두 번째 클래스가 멈춘 컨테이너의 포트로 붙어
 * 모든 명령이 시간 초과로 실패한다(상속한 클래스가 둘이 되면서 드러남).
 *
 * <p>Docker가 없는 환경에서는 이 클래스를 상속한 테스트만 건너뛴다(실패가 아니라 skip).
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
public abstract class RedisTestSupport {

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            REDIS.start();
        }
    }
}
