package com.yamu.be.global.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yamu.be.support.MySqlTestSupport;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * 번호가 작은 마이그레이션이 더 늦게 develop에 머지된 경우(S15P31E103-345)를 운영 DB처럼 재현한다.
 *
 * <p>11/8에 {@code V20261107_1}(-309)·{@code V20261108_1}(-310)이 먼저 머지·배포되고 {@code V20261106_3}(-315)이 나중에
 * 머지됐다. Flyway 기본값은 마지막 적용 번호보다 작은 새 파일을 만나면 검증에서 멈추므로, 앱 설정({@code spring.flyway.out-of-order})이
 * 이 파일을 그대로 적용하는지 확인한다.
 */
@TestPropertySource(properties = {
        "spring.application.name=yamu-late-merged-migration-test",
        "spring.flyway.target=20261106.2",
        "spring.jpa.hibernate.ddl-auto=none"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LateMergedMigrationTest extends MySqlTestSupport {

    private static final String LATE_FILE_PREFIX = "V20261106_3__";

    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;

    @TempDir Path withoutLateFile;

    @Test
    void 앞_번호가_이미_적용된_DB에도_늦게_머지된_마이그레이션을_적용한다() throws IOException {
        // 운영 DB처럼 V20261106_3만 빠진 채 V20261107_1·V20261108_1까지 적용한다.
        copyMigrationsExceptLateFile();
        Flyway.configure().configuration(flyway.getConfiguration())
                .locations("filesystem:" + withoutLateFile, "classpath:db/testmigration")
                .target(MigrationVersion.LATEST).load().migrate();
        assertThat(appliedVersions()).contains("20261107.1", "20261108.1").doesNotContain("20261106.3");

        // 수정 전 설정(Flyway 기본값)으로 최신 코드를 띄우면 멈춘다.
        Flyway strict = Flyway.configure().configuration(flyway.getConfiguration())
                .target(MigrationVersion.LATEST).outOfOrder(false).load();
        assertThatThrownBy(strict::migrate).isInstanceOf(FlywayException.class).hasMessageContaining("20261106.3");

        // 앱 설정이면 늦게 들어온 파일만 적용하고, 다시 띄워도 더 할 일이 없다.
        Flyway app = Flyway.configure().configuration(flyway.getConfiguration()).target(MigrationVersion.LATEST).load();
        assertThat(app.getConfiguration().isOutOfOrder()).isTrue();
        assertThat(app.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(appliedVersions()).contains("20261106.3");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'app_user' AND column_name = 'payment_auto_select'
                """, Integer.class)).isEqualTo(1);
        assertThat(app.migrate().migrationsExecuted).isZero();
    }

    private void copyMigrationsExceptLateFile() throws IOException {
        Resource[] migrations = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*.sql");
        assertThat(migrations).extracting(Resource::getFilename).anyMatch(name -> name.startsWith(LATE_FILE_PREFIX));
        for (Resource migration : migrations) {
            String name = migration.getFilename();
            if (name == null || name.startsWith(LATE_FILE_PREFIX)) {
                continue;
            }
            try (InputStream in = migration.getInputStream()) {
                Files.copy(in, withoutLateFile.resolve(name));
            }
        }
    }

    private java.util.List<String> appliedVersions() {
        return jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL",
                String.class);
    }
}
