package com.devcli.context;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 将预算外输入保存为项目内可回读快照。
 */
public final class ContextInputSnapshotStore {
    private static final String OUTPUT_DIR = ".devcli/context-inputs";

    private final Path projectRoot;

    public ContextInputSnapshotStore(Path projectRoot) {
        this.projectRoot = normalize(projectRoot == null ? Path.of(".") : projectRoot);
    }

    public Snapshot store(String originalName, byte[] content) throws IOException {
        byte[] bytes = content == null ? new byte[0] : content.clone();
        String hash = sha256(bytes);
        Path outputDir = projectRoot.resolve(OUTPUT_DIR).normalize();
        if (!outputDir.startsWith(projectRoot)) {
            throw new IOException("附件快照目录超出项目根目录");
        }
        Files.createDirectories(outputDir);
        Path target = outputDir.resolve(
                hash.substring(0, 16) + "-" + sanitizeFileName(originalName)).normalize();
        if (!target.startsWith(outputDir)) {
            throw new IOException("附件快照路径非法");
        }
        Path temporary = Files.createTempFile(outputDir, ".attachment-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        String relativePath = projectRoot.relativize(target)
                .toString().replace('\\', '/');
        return new Snapshot(relativePath, hash, bytes.length);
    }

    private static Path normalize(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String sanitizeFileName(String value) {
        String sanitized = value == null ? "attachment.txt"
                : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.isBlank() ? "attachment.txt" : sanitized;
    }

    public record Snapshot(String storedPath, String sha256, long sizeBytes) {
    }
}
