package com.devcli.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreReviewVerifierTest {

    @Test
    void shouldSkipProjectWithoutJavaSources(@TempDir Path tempDir) {
        PreReviewVerifier.Result result = new PreReviewVerifier().verify(tempDir, "step-a");

        assertTrue(result.passed());
    }

    @Test
    void shouldCompileJavaSourcesWithoutMaven(@TempDir Path tempDir) throws Exception {
        Path javaRoot = tempDir.resolve("src/main/java");
        Files.createDirectories(javaRoot);
        Files.writeString(javaRoot.resolve("Hello.java"),
                "public class Hello { public static void main(String[] args) {} }",
                StandardCharsets.UTF_8);

        PreReviewVerifier.Result result = new PreReviewVerifier().verify(tempDir, "step-b");

        assertTrue(result.passed(), result.feedback());
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

        PreReviewVerifier.Result result = new PreReviewVerifier().verify(tempDir, "step-c");

        assertFalse(result.passed());
        assertTrue(result.feedback().contains("javac -encoding UTF-8"), result.feedback());
        assertTrue(result.feedback().contains("Broken.java"), result.feedback());
    }
}
