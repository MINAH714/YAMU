package com.yamu.be.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalSeedLoaderTest {
    @TempDir Path directory;

    @Test
    void concurrentFirstRunsCannotGenerateTwoSetsOfKeys() throws Exception {
        try (var channel = FileChannel.open(directory.resolve(".env.seed.lock.local"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            assertThatThrownBy(() -> LocalSeedLoader.run(new String[]{directory.toString()}))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(Files.exists(directory.resolve(".env.seed.local"))).isFalse();
        }
    }

    @Test
    void conflictingModesAreRejectedBeforeAnyDatabaseAccess() {
        assertThatThrownBy(() -> LocalSeedLoader.run(new String[]{directory.toString(), "--reset", "--catalog-only"}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(directory.resolve(".env.seed.local"))).isFalse();
    }
}
