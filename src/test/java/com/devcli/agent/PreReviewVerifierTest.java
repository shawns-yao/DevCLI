package com.devcli.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.devcli.tool.command.CommandExecutionService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreReviewVerifierTest {

    @Test
    void shouldSkipProjectWithoutJavaSources(@TempDir Path tempDir) {
        PreReviewVerifier.Result result = verifierWithHostBackend().verify(tempDir, "step-a");

        assertTrue(result.passed());
        assertFalse(result.hardCheckExecuted());
    }

    @Test
    void shouldCompileJavaSourcesWithoutMaven(@TempDir Path tempDir) throws Exception {
        Path javaRoot = tempDir.resolve("src/main/java");
        Files.createDirectories(javaRoot);
        Files.writeString(javaRoot.resolve("Hello.java"),
                "public class Hello { public static void main(String[] args) {} }",
                StandardCharsets.UTF_8);

        PreReviewVerifier.Result result = verifierWithHostBackend().verify(tempDir, "step-b");

        assertTrue(result.passed(), result.feedback());
        assertTrue(result.hardCheckExecuted());
        assertTrue(Files.isRegularFile(tempDir.resolve(
                "target/devcli-pre-review-classes/step-b/Hello.class")));
    }

    @Test
    void shouldReportCompilerFailureWithoutMaven(@TempDir Path tempDir) throws Exception {
        Path javaRoot = tempDir.resolve("src/main/java");
        Files.createDirectories(javaRoot);
        Files.writeString(javaRoot.resolve("Broken.java"),
                "public class Broken { public void run() { missing } }",
                StandardCharsets.UTF_8);

        PreReviewVerifier.Result result = verifierWithHostBackend().verify(tempDir, "step-c");

        assertFalse(result.passed());
        assertTrue(result.hardCheckExecuted());
        assertTrue(result.feedback().contains("javac -encoding UTF-8"), result.feedback());
        assertTrue(result.feedback().contains("Broken.java"), result.feedback());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void shouldCompileLargeJavaSourceSetWithoutExceedingWindowsCommandLine(@TempDir Path tempDir)
            throws Exception {
        Path javaRoot = tempDir.resolve("src/main/java");
        Files.createDirectories(javaRoot);
        String suffix = "LongSourceName".repeat(6);
        String lastClassName = null;
        for (int index = 0; index < 400; index++) {
            lastClassName = "Generated" + index + suffix;
            Files.writeString(javaRoot.resolve(lastClassName + ".java"),
                    "public class " + lastClassName + " {}",
                    StandardCharsets.UTF_8);
        }

        PreReviewVerifier.Result result = verifierWithHostBackend().verify(tempDir, "step-large");

        assertTrue(result.passed(), result.feedback());
        assertTrue(result.hardCheckExecuted());
        assertTrue(Files.isRegularFile(tempDir.resolve(
                "target/devcli-pre-review-classes/step-large/" + lastClassName + ".class")));
    }

    @Test
    void shouldRequireSandboxForMavenPreReview(@TempDir Path tempDir) throws Exception {
        Path javaRoot = tempDir.resolve("src/main/java");
        Files.createDirectories(javaRoot);
        Files.writeString(javaRoot.resolve("Hello.java"), "public class Hello {}",
                StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        AtomicReference<CommandExecutionService.Request> captured = new AtomicReference<>();
        PreReviewVerifier verifier = new PreReviewVerifier(30, request -> {
            captured.set(request);
            return CommandExecutionService.Result.completed(0, "");
        });

        PreReviewVerifier.Result result = verifier.verify(tempDir, "step-maven");

        assertTrue(result.passed(), result.feedback());
        assertTrue(result.hardCheckExecuted());
        assertTrue(captured.get().sandboxRequired());
        assertEquals("mvn -q -DskipTests test-compile", captured.get().command());
    }

    @Test
    void shouldRunMavenAtMultiModuleRootWithoutRootJavaSources(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        AtomicReference<CommandExecutionService.Request> captured = new AtomicReference<>();
        PreReviewVerifier verifier = new PreReviewVerifier(30, request -> {
            captured.set(request);
            return CommandExecutionService.Result.completed(0, "");
        });

        PreReviewVerifier.Result result = verifier.verify(tempDir, "step-multi-module");

        assertTrue(result.passed(), result.feedback());
        assertTrue(result.hardCheckExecuted());
        assertEquals("mvn -q -DskipTests test-compile", captured.get().command());
    }

    @Test
    void shouldPreferMavenWrapperWhenAvailable(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("mvnw"), "#!/bin/sh", StandardCharsets.UTF_8);
        AtomicReference<CommandExecutionService.Request> captured = new AtomicReference<>();
        PreReviewVerifier verifier = new PreReviewVerifier(30, request -> {
            captured.set(request);
            return CommandExecutionService.Result.completed(0, "");
        });

        PreReviewVerifier.Result result = verifier.verify(tempDir, "step-wrapper");

        assertTrue(result.passed(), result.feedback());
        assertEquals("./mvnw -q -DskipTests test-compile", captured.get().command());
    }

    @Test
    void shouldClassifyCompilerFailureAsCodeFailure(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        PreReviewVerifier verifier = new PreReviewVerifier(30, request ->
                CommandExecutionService.Result.completed(1,
                        "[ERROR] /src/Foo.java:[3,9] cannot find symbol"));

        PreReviewVerifier.Result result = verifier.verify(tempDir, "step-code-failure");

        assertFalse(result.passed());
        assertEquals(PreReviewVerifier.FailureKind.CODE, result.failureKind());
    }

    @Test
    void shouldClassifyDependencyResolutionFailureAsInfrastructure(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        PreReviewVerifier verifier = new PreReviewVerifier(30, request ->
                CommandExecutionService.Result.completed(1,
                        "Plugin org.apache.maven.plugins:maven-compiler-plugin could not be resolved in offline mode"));

        PreReviewVerifier.Result result = verifier.verify(tempDir, "step-missing-plugin");

        assertFalse(result.passed());
        assertEquals(PreReviewVerifier.FailureKind.INFRASTRUCTURE, result.failureKind());
    }

    @Test
    void shouldNotTreatGenericCannotAccessCompilerErrorAsInfrastructure(
            @TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        PreReviewVerifier verifier = new PreReviewVerifier(30, request ->
                CommandExecutionService.Result.completed(1,
                        "[ERROR] /src/App.java:[7,12] cannot access BrokenType\n"
                                + "bad source file: BrokenType.java does not contain class BrokenType"));

        PreReviewVerifier.Result result = verifier.verify(tempDir, "step-source-error");

        assertFalse(result.passed());
        assertEquals(PreReviewVerifier.FailureKind.CODE, result.failureKind());
    }

    @Test
    void shouldClassifyReadonlyMavenRepositoryFailureAsInfrastructure(
            @TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        PreReviewVerifier verifier = new PreReviewVerifier(30, request ->
                CommandExecutionService.Result.completed(1,
                        "Could not create tracking file /maven-repository/example.lastUpdated: "
                                + "Read-only file system"));

        PreReviewVerifier.Result result = verifier.verify(tempDir, "step-readonly-repository");

        assertFalse(result.passed());
        assertEquals(PreReviewVerifier.FailureKind.INFRASTRUCTURE, result.failureKind());
    }

    @Test
    void shouldClassifyTimeoutCancellationAndSandboxFailureAsInfrastructure(@TempDir Path tempDir)
            throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);

        PreReviewVerifier.Result timedOut = new PreReviewVerifier(30,
                request -> CommandExecutionService.Result.timedOut("timeout"))
                .verify(tempDir, "step-timeout");
        PreReviewVerifier.Result cancelled = new PreReviewVerifier(30,
                request -> CommandExecutionService.Result.cancelled("cancelled"))
                .verify(tempDir, "step-cancelled");
        PreReviewVerifier.Result sandboxFailure = new PreReviewVerifier(30, request -> {
            throw new IllegalStateException("Docker daemon unavailable");
        }).verify(tempDir, "step-sandbox");

        assertEquals(PreReviewVerifier.FailureKind.INFRASTRUCTURE, timedOut.failureKind());
        assertEquals(PreReviewVerifier.FailureKind.INFRASTRUCTURE, cancelled.failureKind());
        assertEquals(PreReviewVerifier.FailureKind.INFRASTRUCTURE, sandboxFailure.failureKind());
    }

    @Test
    void shouldPreserveHostWarnNoticeFromSuccessfulHardCheck(@TempDir Path tempDir) throws Exception {
        Path javaRoot = tempDir.resolve("src/main/java");
        Files.createDirectories(javaRoot);
        Files.writeString(javaRoot.resolve("Hello.java"), "public class Hello {}",
                StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        PreReviewVerifier verifier = new PreReviewVerifier(30, request ->
                CommandExecutionService.Result.completed(0,
                        "⚠️ 沙箱模式 HOST_WARN：隔离命令在主机上执行，风险由用户承担。"));

        PreReviewVerifier.Result result = verifier.verify(tempDir, "step-host-warn");

        assertTrue(result.passed(), result.feedback());
        assertTrue(result.hardCheckExecuted());
        assertTrue(result.feedback().contains("HOST_WARN"), result.feedback());
    }

    private PreReviewVerifier verifierWithHostBackend() {
        return new PreReviewVerifier(60, PreReviewVerifierTest::executeOnHost);
    }

    private static CommandExecutionService.Result executeOnHost(CommandExecutionService.Request request) {
        Process process = null;
        try {
            List<String> command;
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                command = List.of("powershell.exe", "-NoProfile", "-NonInteractive",
                        "-Command", request.command());
            } else {
                command = List.of("sh", "-lc", request.command());
            }
            process = new ProcessBuilder(command)
                    .directory(request.projectRoot().toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(request.timeoutSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return CommandExecutionService.Result.timedOut("timeout");
            }
            return CommandExecutionService.Result.completed(process.exitValue(), output);
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return CommandExecutionService.Result.completed(-1, e.getMessage());
        }
    }
}
