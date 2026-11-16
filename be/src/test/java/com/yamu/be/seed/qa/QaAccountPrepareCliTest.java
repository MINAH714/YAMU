package com.yamu.be.seed.qa;

import static com.yamu.be.seed.qa.QaAccountDeviceFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Executes the actual entry point in a child JVM; every scenario must fail before DB/crypto setup. */
class QaAccountPrepareCliTest {
    @TempDir Path folder;

    @Test void rejectsEnabledBillingBeforeReadingAnyInput() throws Exception {
        assertThat(run(List.of("plan", "missing", "missing"), Map.of("BILLING_JOBS_ENABLED", "true")))
                .isEqualTo("QA account preparation failed: APPROVAL_REQUIRED");
    }

    @Test void preparationRequiresMaintenanceAndExactReviewedManifest() throws Exception {
        var manifest = manifest();
        var args = List.of("prepare", folder.toString(), folder.resolve("manifest.json").toString(), folder.resolve("receipt.json").toString());
        assertThat(run(args, Map.of("BILLING_JOBS_ENABLED", "false", "QA_SEED_MAINTENANCE", "false",
                "QA_ACCOUNT_PREPARE_APPROVAL", manifest.digest()))).endsWith("APPROVAL_REQUIRED");
        assertThat(run(args, Map.of("BILLING_JOBS_ENABLED", "false", "QA_SEED_MAINTENANCE", "true",
                "QA_ACCOUNT_PREPARE_APPROVAL", "different-manifest"))).endsWith("APPROVAL_REQUIRED");
        assertThat(Files.exists(folder.resolve("receipt.json"))).isFalse();
    }

    @Test void neverOverwritesAnExistingReceipt() throws Exception {
        var manifest = manifest();
        Files.writeString(folder.resolve("receipt.json"), "existing-receipt");
        assertThat(run(List.of("prepare", folder.toString(), folder.resolve("manifest.json").toString(), folder.resolve("receipt.json").toString()),
                Map.of("BILLING_JOBS_ENABLED", "false", "QA_SEED_MAINTENANCE", "true", "QA_ACCOUNT_PREPARE_APPROVAL", manifest.digest())))
                .endsWith("INVALID_INPUT");
        assertThat(Files.readString(folder.resolve("receipt.json"))).isEqualTo("existing-receipt");
    }

    @Test void invalidInputIsNotEchoedInErrors() throws Exception {
        String marker = "untrusted-input-DO-NOT-LOG";
        var message = run(List.of(marker, marker, marker), Map.of("BILLING_JOBS_ENABLED", "false"));
        assertThat(message).isEqualTo("QA account preparation failed: INVALID_INPUT").doesNotContain(marker);
    }

    private QaAccountDevices.Manifest manifest() throws Exception {
        var devices = new ArrayList<QaAccountDevices.Device>();
        for (int i = 1; i <= 3; i++) devices.add(device(i, 10 + i, key(), NOW));
        var manifest = new QaAccountDevices.Manifest(1, devices);
        new ObjectMapper().writeValue(folder.resolve("manifest.json").toFile(), manifest);
        return manifest;
    }

    private String run(List<String> args, Map<String, String> environment) throws Exception {
        var classpath = new LinkedHashSet<String>();
        classpath.add(System.getProperty("java.class.path"));
        for (ClassLoader loader = getClass().getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) for (var url : urls.getURLs()) {
                if ("file".equals(url.getProtocol())) classpath.add(Path.of(url.toURI()).toString());
            }
        }
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", String.join(java.io.File.pathSeparator, classpath), QaAccountPrepareCli.class.getName()));
        command.addAll(args);
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().keySet().removeIf(name -> name.startsWith("QA_") || name.startsWith("YAMU_")
                || name.startsWith("SPRING_") || name.equals("BILLING_JOBS_ENABLED"));
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().putAll(environment);
        var process = builder.start();
        process.getOutputStream().close();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isEqualTo(1);
            return new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
