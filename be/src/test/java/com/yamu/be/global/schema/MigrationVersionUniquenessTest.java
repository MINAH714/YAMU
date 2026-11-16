package com.yamu.be.global.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Catch cross-branch migration number collisions even when Docker is unavailable. */
class MigrationVersionUniquenessTest {

    @Test
    void productionMigrationVersionsAreUniqueUsingFlywayNormalization() throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*__*.sql");
        assertThat(resources).isNotEmpty();
        Map<MigrationVersion, List<String>> filesByVersion = new LinkedHashMap<>();
        for (Resource resource : resources) {
            String name = resource.getFilename();
            assertThat(name).isNotNull();
            int separator = name.indexOf("__");
            MigrationVersion version = MigrationVersion.fromVersion(name.substring(1, separator));
            filesByVersion.computeIfAbsent(version, ignored -> new ArrayList<>()).add(name);
        }
        Map<MigrationVersion, List<String>> duplicates = new LinkedHashMap<>();
        filesByVersion.forEach((version, files) -> {
            if (files.size() > 1) {
                duplicates.put(version, files);
            }
        });
        assertThat(duplicates).as("Duplicate Flyway migration versions").isEmpty();
    }
}
