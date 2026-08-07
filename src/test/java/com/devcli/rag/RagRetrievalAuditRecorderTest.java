package com.devcli.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RagRetrievalAuditRecorderTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsStageScoresWithoutCodeContent() throws Exception {
        CodeRetriever.AuditResult result = new CodeRetriever.AuditResult(
                "src/main/UserService.java", "class", "UserService", 0.42, "v1", "epoch-1");
        CodeRetriever.RetrievalAudit audit = new CodeRetriever.RetrievalAudit(
                Instant.parse("2026-08-03T00:00:00Z"), "用户登录入口", "GENERAL", 5,
                Map.of("semantic", List.of(result)), List.of(result), List.of(result), List.of(result),
                false, false, "disabled");

        new RagRetrievalAuditRecorder(tempDir, true).record(audit);

        Path file = Files.list(tempDir).findFirst().orElseThrow();
        String content = Files.readString(file);
        assertTrue(content.contains("用户登录入口"));
        assertTrue(content.contains("UserService"));
        assertTrue(content.contains("0.42"));
        assertTrue(!content.contains("public class"));
    }
}
