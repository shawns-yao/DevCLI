package com.devcli.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.stream.Stream;

/** 保留旧源文件，将记忆卡与项目主题复制到当前作用域目录。 */
final class LegacyMemoryImporter {

    private static final Logger log = LoggerFactory.getLogger(LegacyMemoryImporter.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COMPLETION_MARKER = ".legacy-memory-import-v1.done";

    private LegacyMemoryImporter() {
    }

    record ProjectImportResult(int imported, int conflicts, int failed) {
        static final ProjectImportResult EMPTY = new ProjectImportResult(0, 0, 0);
    }

    /** 只复制缺少的旧项目主题；源文件保留，已存在的共享主题不覆盖。 */
    static ProjectImportResult importProjectTopics(Path sourceDir, Path targetDir) {
        if (sourceDir.equals(targetDir)
                || !Files.isDirectory(sourceDir, LinkOption.NOFOLLOW_LINKS)) {
            return ProjectImportResult.EMPTY;
        }
        Path marker = targetDir.resolve(".project-memory-import-"
                + shortDigest(sourceDir.toString()) + ".done");
        if (Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            try {
                int conflicts = Integer.parseInt(Files.readString(marker).trim());
                return new ProjectImportResult(0, Math.max(0, conflicts), 0);
            } catch (IOException | NumberFormatException e) {
                return new ProjectImportResult(0, 0, 1);
            }
        }
        int imported = 0;
        int conflicts = 0;
        int failed = 0;
        try (Stream<Path> files = Files.walk(sourceDir, MemoryScanner.MAX_DEPTH)) {
            Files.createDirectories(targetDir);
            for (Path source : files.filter(path -> Files.isRegularFile(path,
                            LinkOption.NOFOLLOW_LINKS))
                    .filter(MemoryScanner::isTopicFile)
                    .sorted().toList()) {
                Path target = targetDir.resolve(sourceDir.relativize(source));
                try {
                    if (TopicMemory.read(source) == null) {
                        failed++;
                    } else if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                                || Files.mismatch(source, target) != -1) conflicts++;
                    } else {
                        Files.createDirectories(target.getParent());
                        Path temp = Files.createTempFile(target.getParent(), ".project-memory-", ".tmp");
                        try {
                            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING,
                                    StandardCopyOption.COPY_ATTRIBUTES);
                            Files.move(temp, target);
                            imported++;
                        } finally {
                            Files.deleteIfExists(temp);
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    failed++;
                    log.warn("旧项目主题迁移失败 {}: {}", source, e.getMessage());
                }
            }
            if (failed == 0) writeAtomically(marker, Integer.toString(conflicts));
        } catch (IOException e) {
            failed++;
            log.warn("旧项目记忆迁移失败 {}: {}", sourceDir, e.getMessage());
        }
        return new ProjectImportResult(imported, conflicts, failed);
    }

    /**
     * 迁移是幂等且非破坏性的：目标文件名由旧 id 决定，已存在时跳过，旧文件永不删除。
     * 旧格式没有可靠的项目归属，因此统一迁入全局作用域，避免绑定到错误项目。
     */
    static ImportResult importRecords(Path memoryRoot, Path globalDir, Clock clock) {
        Path legacyRoot = memoryRoot.resolve("records");
        if (!Files.isDirectory(legacyRoot)) return ImportResult.EMPTY;
        Path completionMarker = memoryRoot.resolve(COMPLETION_MARKER);
        if (Files.isRegularFile(completionMarker)) return ImportResult.EMPTY;
        int discovered = 0;
        int imported = 0;
        int failed = 0;
        try (Stream<Path> files = Files.walk(legacyRoot)) {
            for (Path source : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(".md"))
                    .toList()) {
                discovered++;
                try {
                    LegacyRecord record = parse(source, clock.instant());
                    Path target = globalDir.resolve("legacy-" + shortDigest(record.id()) + ".md");
                    if (Files.isRegularFile(target)) continue;
                    Files.createDirectories(globalDir);
                    TopicMemory migrated = TopicMemory.of(target, record.name(),
                            "由旧版长期记忆迁移；旧记录 " + record.id(), record.type(),
                            record.content(), record.updatedAt(), record.expiresAt(),
                            record.revision(), record.createdAt());
                    writeAtomically(target, migrated.render());
                    imported++;
                } catch (IOException | RuntimeException e) {
                    failed++;
                    log.warn("旧版长期记忆迁移失败 {}: {}", source, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("扫描旧版长期记忆失败 {}: {}", legacyRoot, e.getMessage());
            return new ImportResult(discovered, imported, failed + 1);
        }
        if (imported > 0) {
            log.info("已迁移 {} 条旧版长期记忆到全局作用域；旧文件保留在 {}", imported, legacyRoot);
        }
        if (failed == 0) {
            try {
                writeAtomically(completionMarker, "legacy-memory-import-version: 1\n");
            } catch (IOException e) {
                failed++;
                log.warn("记录旧版长期记忆迁移完成状态失败 {}: {}", completionMarker, e.getMessage());
            }
        }
        return new ImportResult(discovered, imported, failed);
    }

    private static LegacyRecord parse(Path source, Instant now) throws IOException {
        String text = Files.readString(source, StandardCharsets.UTF_8);
        String payload = text.lines()
                .filter(line -> line.startsWith("payload: "))
                .map(line -> line.substring("payload: ".length()))
                .findFirst()
                .orElseThrow(() -> new IOException("缺少 payload frontmatter"));
        JsonNode node = JSON.readTree(payload);
        String id = text(node, "id");
        String content = text(node, "content");
        if (id.isBlank() || content.isBlank()) {
            throw new IOException("旧记忆缺少 id 或 content");
        }
        String subject = text(node, "subject");
        Instant createdAt = instant(node, "created", modifiedAt(source));
        Instant expiresAt = instant(node, "expiresAt", null);
        boolean active = node.path("active").asBoolean(true);
        if (!active && (expiresAt == null || expiresAt.isAfter(now))) expiresAt = now;
        return new LegacyRecord(id, subject.isBlank() ? id : subject, content,
                mapType(text(node, "type")), createdAt, createdAt, expiresAt,
                Math.max(1, node.path("revision").asInt(1)));
    }

    private static String mapType(String legacyType) {
        return switch (legacyType.toUpperCase(Locale.ROOT)) {
            case "PREFERENCE", "USER" -> "user";
            case "FEEDBACK" -> "feedback";
            case "PROJECT" -> "project";
            default -> "reference";
        };
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("").trim();
    }

    private static Instant instant(JsonNode node, String field, Instant fallback) {
        String value = text(node, field);
        if (value.isBlank()) return fallback;
        try {
            return Instant.parse(value);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static Instant modifiedAt(Path source) {
        try {
            return Files.getLastModifiedTime(source).toInstant();
        } catch (IOException ignored) {
            return Instant.EPOCH;
        }
    }

    private static String shortDigest(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), ".legacy-memory-", ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    record ImportResult(int discovered, int imported, int failed) {
        private static final ImportResult EMPTY = new ImportResult(0, 0, 0);
    }

    private record LegacyRecord(String id, String name, String content, String type,
                                Instant createdAt, Instant updatedAt, Instant expiresAt,
                                int revision) {
    }
}
