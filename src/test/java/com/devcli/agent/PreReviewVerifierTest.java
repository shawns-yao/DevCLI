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
