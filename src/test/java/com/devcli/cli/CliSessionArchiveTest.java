package com.devcli.cli;

import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliSessionArchiveTest {
    @TempDir
    Path tempDir;

    @Test
    void archivesRedactedTurnAndSupportsClear() throws Exception {
        CliSessionArchive archive = new CliSessionArchive(true, tempDir, 30);
        archive.recordTurn("thread-test", "branch-test", "react",
                "token=secret-value", "读取配置", "完成",
                List.of(LlmClient.Message.user("password: hidden"), LlmClient.Message.assistant("完成")));

        Path file = Files.list(tempDir).findFirst().orElseThrow();
        String content = Files.readString(file);
        assertTrue(content.contains("token=***"));
        assertTrue(content.contains("password: ***"));
        assertFalse(content.contains("secret-value"));
        assertTrue(content.contains("\"canonicalThreadId\":\"thread-test\""));
        assertTrue(content.contains("\"canonicalBranchId\":\"branch-test\""));
        assertTrue(content.contains("\"source\":\"derived_diagnostic_export\""));

        archive.clearAll();
        assertTrue(Files.list(tempDir).findAny().isEmpty());
    }

    @Test
    void removesFilesOlderThanRetentionOnStartup() throws Exception {
        Path old = tempDir.resolve("session-old.jsonl");
        Files.writeString(old, "old");
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minusSeconds(3 * 86_400L)));

        new CliSessionArchive(true, tempDir, 1);

        assertFalse(Files.exists(old));
    }
}
