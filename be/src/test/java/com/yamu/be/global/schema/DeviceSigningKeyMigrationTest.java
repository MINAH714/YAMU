package com.yamu.be.global.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.support.MySqlTestSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/** 기기 키 필수화가 기존 PIN·생체 기기의 키와 설정을 보존하는지 확인한다. */
@TestPropertySource(properties = {
        "spring.application.name=yamu-device-key-migration-test",
        "spring.flyway.target=20261027.2"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeviceSigningKeyMigrationTest extends MySqlTestSupport {

    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;

    @Test
    void requiringKeysPreservesExistingPinAndBiometricDevices() {
        jdbc.update("INSERT INTO app_user (user_id, created_at, updated_at) VALUES (1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
        for (int biometric = 0; biometric <= 1; biometric++) {
            jdbc.update("""
                    INSERT INTO user_device (user_id, device_uuid, platform, public_key, key_algorithm,
                        biometric_enabled, created_at, updated_at)
                    VALUES (1, ?, 'ANDROID', ?, 'TEST', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """, "00000000-0000-0000-0000-00000000000" + biometric,
                    "test-public-key-" + biometric, biometric);
        }
        var before = jdbc.queryForList("SELECT * FROM user_device ORDER BY user_device_id");
        Integer checksum = jdbc.queryForObject(
                "SELECT checksum FROM flyway_schema_history WHERE version = '20261026.1'", Integer.class);
        Flyway upgrade = Flyway.configure()
                .dataSource(flyway.getConfiguration().getDataSource())
                .locations(flyway.getConfiguration().getLocations())
                .target(MigrationVersion.fromVersion("20261027.3"))
                .load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM user_device ORDER BY user_device_id")).isEqualTo(before);
        assertThat(jdbc.queryForList("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'user_device'
                    AND column_name IN ('public_key', 'key_algorithm')
                """, String.class)).containsExactly("NO", "NO");
        assertThat(jdbc.queryForObject(
                "SELECT checksum FROM flyway_schema_history WHERE version = '20261026.1'", Integer.class))
                .isEqualTo(checksum);
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
    }
}
