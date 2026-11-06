package com.yamu.be.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 실제 MySQL이 필요한 테스트가 상속한다. Testcontainers가 RDS와 같은 MySQL 8.4.11을 띄운다.
 *
 * <p>MySQL 컨테이너는 테스트 JVM에서 한 번만 띄워 모든 상속 클래스가 같이 쓴다(@Container를 쓰지 않는다).
 * 대신 스프링 테스트 컨텍스트를 새로 만들 때마다 빈 데이터베이스(test_1, test_2, ...)를 새로 만들어 연결하고,
 * Flyway가 그 데이터베이스에 처음부터 마이그레이션을 적용한다. 그래서 각 테스트 클래스는 예전처럼 자기만의 빈 DB에서
 * 시작하고(다른 클래스가 남긴 행을 보지 않는다), 같은 컨텍스트를 캐시로 같이 쓰는 클래스끼리만 DB를 같이 쓴다.
 * 컨텍스트가 닫히면 그 데이터베이스를 지운다(DropDatabaseOnClose). 컨테이너는 테스트가 끝나면 Testcontainers(Ryuk)가 지운다.
 *
 * <p>MySQL 데이터 폴더는 메모리 디스크(tmpfs)에 둔다. Windows Docker Desktop에서는 디스크에 쓸 때마다 확정(fsync)하는
 * 비용이 커서 Flyway가 클래스마다 10초 넘게 걸렸다. SQL 동작은 같고, 컨테이너가 꺼지면 데이터가 사라질 뿐이다.
 *
 * <p>기본 test 프로필은 DB 없이 돌도록 Flyway·스키마 검사를 꺼 두었으므로 여기서 다시 켠다.
 * Docker가 없는 환경에서는 이 클래스를 상속한 테스트만 건너뛴다(실패가 아니라 skip).
 *
 * <p>db/testmigration은 테스트 전용 테이블(예: test_audit_sample)이다. 실제 서버에는 들어가지 않는다.
 */
@SpringBootTest
@Import(MySqlTestSupport.DropDatabaseOnClose.class)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration,classpath:db/testmigration",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=true"
})
public abstract class MySqlTestSupport {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"));

    private static final AtomicInteger DATABASE_COUNT = new AtomicInteger();
    private static final String DATABASE_PROPERTY = "yamu.test.database";
    private static final int DROP_LOCK_WAIT_SECONDS = 10;

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            MYSQL.start();
        }
    }

    /** 스프링이 테스트 컨텍스트를 새로 만들 때마다 불린다. 빈 데이터베이스를 하나 만들고 접속 정보를 그쪽으로 바꾼다. */
    @DynamicPropertySource
    static void connectToNewDatabase(DynamicPropertyRegistry registry) {
        String database = "test_" + DATABASE_COUNT.incrementAndGet();
        createDatabase(database);
        String url = jdbcUrl(database);
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add(DATABASE_PROPERTY, () -> database);
    }

    /** 테스트 계정은 컨테이너가 처음 만든 데이터베이스에만 권한이 있어서, root로 만들고 권한을 준다. */
    private static void createDatabase(String database) {
        try (Connection connection = rootConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
            statement.execute("GRANT ALL ON " + database + ".* TO '" + MYSQL.getUsername() + "'@'%'");
        } catch (SQLException e) {
            throw new IllegalStateException("테스트 데이터베이스를 만들지 못했습니다: " + database, e);
        }
    }

    private static Connection rootConnection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
    }

    /**
     * 컨텍스트가 닫힐 때(@DirtiesContext 등) 그 컨텍스트의 데이터베이스를 지운다. 지우지 않으면 메모리 디스크에
     * 데이터베이스가 쌓여 MySQL 메모리가 DB 테스트 클래스 수에 비례해 늘어난다(전체 실행 끝에 약 875MB).
     */
    static class DropDatabaseOnClose implements DisposableBean {

        private final String database;

        DropDatabaseOnClose(@Value("${" + DATABASE_PROPERTY + "}") String database) {
            this.database = database;
        }

        @Override
        public void destroy() throws SQLException {
            try (Connection connection = rootConnection(); Statement statement = connection.createStatement()) {
                // 남은 연결이 테이블 잠금을 쥐고 있으면 기본값(1년) 동안 기다리므로, 짧게 기다리고 실패를 드러낸다.
                statement.execute("SET SESSION lock_wait_timeout = " + DROP_LOCK_WAIT_SECONDS);
                statement.execute("DROP DATABASE " + database);
            }
        }
    }

    /** 컨테이너의 접속 주소에서 데이터베이스 이름만 바꾼다. */
    private static String jdbcUrl(String database) {
        String prefix = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(MySQLContainer.MYSQL_PORT) + "/";
        String original = MYSQL.getJdbcUrl();
        if (!original.startsWith(prefix + MYSQL.getDatabaseName())) {
            throw new IllegalStateException("예상과 다른 MySQL 접속 주소 형식입니다: " + original);
        }
        return prefix + database + original.substring((prefix + MYSQL.getDatabaseName()).length());
    }
}
