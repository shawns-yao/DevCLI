package com.devcli.cli;

import com.devcli.config.ConfigResolver;
import com.devcli.llm.LlmClient;
import com.devcli.policy.SensitiveDataRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 可选的脱敏诊断导出；RunStore 才是会话恢复事实来源。 */
final class CliSessionArchive {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(CliSessionArchive.class);
    private final boolean enabled;
    private final Path directory;
    private final int retentionDays;
    private final String sessionId = Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID();

    static CliSessionArchive fromEnvironment() {
        boolean enabled = ConfigResolver.booleanValue(
                "devcli.session.archive.enabled", "DEVCLI_SESSION_ARCHIVE_ENABLED", false);
        int retention = ConfigResolver.intValue(
                "devcli.session.archive.retention.days",
                "DEVCLI_SESSION_ARCHIVE_RETENTION_DAYS", 30, 1, 3650);
        String configured = ConfigResolver.optional(
                "devcli.session.archive.dir", "DEVCLI_SESSION_ARCHIVE_DIR");
        Path directory = configured == null
                ? Path.of(System.getProperty("user.home"), ".devcli", "history", "sessions")
                : Path.of(configured).toAbsolutePath().normalize();
        return new CliSessionArchive(enabled, directory, retention);
    }

    CliSessionArchive(boolean enabled, Path directory, int retentionDays) {
        this.enabled = enabled;
        this.directory = directory.toAbsolutePath().normalize();
        this.retentionDays = Math.max(1, retentionDays);
        cleanupExpired();
    }

    synchronized void recordTurn(String threadId, String branchId, String mode,
                                 String submittedInput, String expandedInput,
                                 String response, List<LlmClient.Message> modelMessages) {
        if (!enabled) return;
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("timestamp", Instant.now().toString());
        record.put("sessionId", sessionId);
        record.put("canonicalThreadId", threadId == null ? "" : threadId);
        record.put("canonicalBranchId", branchId == null ? "" : branchId);
        record.put("source", "derived_diagnostic_export");
        record.put("mode", mode);
        record.put("submittedInput", SensitiveDataRedactor.redact(submittedInput));
        record.put("expandedInput", SensitiveDataRedactor.redact(expandedInput));
        record.put("response", SensitiveDataRedactor.redact(response));
        record.put("modelMessages", sanitizeMessages(modelMessages));
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("session-" + sessionId + ".jsonl"),
                    MAPPER.writeValueAsString(record) + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.warn("CLI session archive write failed: {}", e.getMessage());
        }
    }

    synchronized void clearAll() {
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("session-"))
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception ignored) {
        }
    }

    private void cleanupExpired() {
        if (!enabled || !Files.isDirectory(directory)) return;
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile).forEach(path -> {
                try {
                    if (Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(path);
                    }
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static List<Map<String, Object>> sanitizeMessages(List<LlmClient.Message> messages) {
        if (messages == null || messages.isEmpty()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (LlmClient.Message message : messages) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", message.role());
            item.put("content", SensitiveDataRedactor.redact(message.content()));
            item.put("toolCallId", message.toolCallId());
            item.put("imageCount", message.imagePartCount());
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                item.put("toolCalls", message.toolCalls().stream().map(call -> Map.of(
                        "id", call.id(),
                        "name", call.function() == null ? "" : call.function().name(),
                        "arguments", call.function() == null ? "" : SensitiveDataRedactor.redact(call.function().arguments())
                )).toList());
            }
            result.add(item);
        }
        return result;
    }

}
