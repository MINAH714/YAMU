package com.yamu.be.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 실제 MySQL이 필요한 테스트가 상속한다. Testcontainers가 RDS와 같은 MySQL 8.4.11을 잠깐 띄우고,
 * 접속 정보(datasource)는 @ServiceConnection이 자동으로 바꿔 넣는다.
 *
 * <p>기본 test 프로필은 DB 없이 돌도록 Flyway·스키마 검사를 꺼 두었으므로 여기서 다시 켠다.
 * Docker가 없는 환경에서는 이 클래스를 상속한 테스트만 건너뛴다(실패가 아니라 skip).
 *
 * <p>db/testmigration은 테스트 전용 테이블(예: test_audit_sample)이다. 실제 서버에는 들어가지 않는다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration,classpath:db/testmigration",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=true"
})
public abstract class MySqlTestSupport {

    @Container
    @ServiceConnection
    // V20261103_1 creates self-reference guard triggers. This flag is confined to the disposable test DB;
    // deployment requires the DBA to provision the corresponding trigger/binlog permissions separately.
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11")
            .withCommand("--log-bin-trust-function-creators=1");
}
