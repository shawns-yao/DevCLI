package com.devcli.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** Markdown-backed source of truth for long-term memory content and evidence. */
final class MemoryMarkdownRepository {
    private static final Logger log = LoggerFactory.getLogger(MemoryMarkdownRepository.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HEADER = "---\ndevcli-memory-format: 1\npayload: ";
    private static final String FOOTER = "\n---\n\n";

    private final Path root;

    MemoryMarkdownRepository(Path memoryDir) {
        this.root = memoryDir.toAbsolutePath().normalize().resolve("records");
    }

    PreparedWrite prepare(MemoryEntry entry) throws IOException {
        if (entry == null || entry.getId() == null || entry.getId().isBlank()) {
            throw new IOException("Memory id is required for Markdown persistence");
        }
        Path relativePath = relativePath(entry.getId());
        Path target = resolve(relativePath);
        byte[] previous = Files.exists(target) ? Files.readAllBytes(target) : null;
        String markdown = render(entry);
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        return new PreparedWrite(entry.getId(), relativePath, target, bytes,
                sha256(bytes), contentDigest(entry.getContent()), previous);
    }

    void apply(PreparedWrite write) throws IOException {
        Files.createDirectories(write.target().getParent());
        Path temporary = Files.createTempFile(write.target().getParent(), ".memory-", ".tmp");
        try {
            Files.write(temporary, write.bytes());
            moveAtomically(temporary, write.target());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    void rollback(PreparedWrite write) {
        try {
            if (write.previousBytes() == null) {
                Files.deleteIfExists(write.target());
                return;
            }
            Files.createDirectories(write.target().getParent());
            Path temporary = Files.createTempFile(write.target().getParent(), ".memory-rollback-", ".tmp");
            try {
                Files.write(temporary, write.previousBytes());
                moveAtomically(temporary, write.target());
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            log.error("Failed to restore Markdown memory {} after catalog rollback: {}",
                    write.memoryId(), e.getMessage());
        }
    }

    Optional<Document> read(String relativePath, String expectedHash) {
        if (relativePath == null || relativePath.isBlank()) return Optional.empty();
        try {
            Path path = resolve(Path.of(relativePath));
            byte[] bytes = Files.readAllBytes(path);
            String actualHash = sha256(bytes);
            if (expectedHash != null && !expectedHash.isBlank()
                    && !expectedHash.equalsIgnoreCase(actualHash)) {
                log.warn("Markdown memory hash mismatch for {}; expected {}, got {}",
                        relativePath, expectedHash, actualHash);
                return Optional.empty();
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            String payloadLine = text.lines()
                    .filter(line -> line.startsWith("payload: "))
                    .findFirst()
                    .orElseThrow(() -> new IOException("Missing payload front matter"));
            Payload payload = JSON.readValue(payloadLine.substring("payload: ".length()), Payload.class);
            return Optional.of(new Document(payload.id(), payload.content(), payload.confidence(),
                    payload.sourceQuote(), payload.reasoning(), payload.reviewState(),
                    payload.conflictsWith(), actualHash));
        } catch (Exception e) {
            log.warn("Failed to read Markdown memory {}: {}", relativePath, e.getMessage());
            return Optional.empty();
        }
    }

    void delete(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return;
        try {
            Files.deleteIfExists(resolve(Path.of(relativePath)));
        } catch (IOException e) {
            log.warn("Failed to delete orphaned Markdown memory {}: {}", relativePath, e.getMessage());
        }
    }

    void clear() {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("Failed to clear Markdown memory path {}: {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Failed to enumerate Markdown memory root {}: {}", root, e.getMessage());
        }
    }

    private Path resolve(Path relativePath) throws IOException {
        Path normalized = root.resolve(relativePath).normalize();
        if (!normalized.startsWith(root)) {
            throw new IOException("Markdown memory path escapes storage root");
        }
        return normalized;
    }

    private static Path relativePath(String id) {
        String digest = sha256(id.getBytes(StandardCharsets.UTF_8));
        return Path.of(digest.substring(0, 2), digest + ".md");
    }

    private static String render(MemoryEntry entry) throws JsonProcessingException {
        MemoryEvidence evidence = entry.getEvidence();
        Payload payload = new Payload(entry.getId(), entry.getContent(), evidence.confidence().name(),
                evidence.sourceQuote(), evidence.reasoning(), evidence.reviewState().name(),
                evidence.conflictsWith());
        String json = JSON.writeValueAsString(payload);
        return HEADER + json + FOOTER
                + "# " + escapeHeading(entry.getSubject().isBlank() ? entry.getId() : entry.getSubject()) + "\n\n"
                + "## Content\n\n" + entry.getContent() + "\n\n"
                + "## Evidence\n\n"
                + "- Confidence: `" + evidence.confidence().name() + "`\n"
                + "- Review state: `" + evidence.reviewState().name() + "`\n"
                + (evidence.sourceQuote().isBlank() ? "" : "- Source quote: " + evidence.sourceQuote() + "\n")
                + (evidence.reasoning().isBlank() ? "" : "- Reasoning: " + evidence.reasoning() + "\n");
    }

    private static String escapeHeading(String value) {
        return value == null ? "memory" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    static String contentDigest(String content) {
        return sha256((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    record PreparedWrite(String memoryId, Path relativePath, Path target, byte[] bytes,
                         String documentHash, String contentHash, byte[] previousBytes) {
        String relativePathString() {
            return relativePath.toString().replace('\\', '/');
        }
    }

    record Document(String id, String content, String confidence, String sourceQuote,
                    String reasoning, String reviewState, List<String> conflictsWith,
                    String documentHash) {
        Document {
            content = content == null ? "" : content;
            sourceQuote = sourceQuote == null ? "" : sourceQuote;
            reasoning = reasoning == null ? "" : reasoning;
            conflictsWith = conflictsWith == null ? List.of() : List.copyOf(conflictsWith);
        }
    }

    private record Payload(String id, String content, String confidence, String sourceQuote,
                           String reasoning, String reviewState, List<String> conflictsWith) {
        Payload {
            conflictsWith = conflictsWith == null ? new ArrayList<>() : List.copyOf(conflictsWith);
        }
    }
}
