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
 * <p>컨테이너는 테스트 JVM에서 한 번만 띄워 모든 상속 클래스가 같이 쓴다(@Container를 쓰지 않는다). 상속 클래스들은 설정이 같아
 * Spring 테스트 컨텍스트를 캐시로 같이 쓰는데, 클래스마다 컨테이너를 내리면 다음 클래스가 꺼진 Redis 주소를 그대로 들고 있어
 * 명령마다 시간 초과(1분)를 기다린다. 끝나면 Testcontainers(Ryuk)가 컨테이너를 지운다.
 * 클래스끼리 데이터가 섞이지 않게 각 테스트는 시작할 때 Redis를 비운다.
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
