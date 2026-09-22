package com.devcli.tool;

import com.devcli.config.ConfigResolver;
import com.devcli.concurrent.CancellationContext;
import com.devcli.concurrent.RunContext;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.Map;

/** 受控运行时工具结果存储。 */
public final class ToolResultArtifactStore {
    public static final String ROOT_PROPERTY = "devcli.tool.results.root";
    public static final String ROOT_ENV = "DEVCLI_TOOL_RESULTS_ROOT";
    /** 保持恢复页连同元数据低于 5K 内联阈值，避免 result_ref 再次生成 result_ref。 */
    public static final int DEFAULT_PAGE_CHARS = 4_000;
    public static final int MAX_PAGE_CHARS = 4_000;

    private static final String FALLBACK_RUN_ID =
            "run_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    public static final long MAX_ARTIFACT_CHARS = 4L * 1024 * 1024;
    private static final long MAX_ARTIFACT_BYTES = MAX_ARTIFACT_CHARS * 4;
    private static final long MAX_RUN_BYTES = 128L * 1024 * 1024;
    private static final Map<Path, Long> RESERVED = new java.util.HashMap<>();

    private ToolResultArtifactStore() {
    }

    public static StoredArtifact store(String toolCallId, String content) throws IOException {
        String normalized = content == null ? "" : content;
        if (normalized.length() > MAX_ARTIFACT_CHARS) throw new IOException("工具结果超过单文件存储配额");
        try (ArtifactWriter writer = openWriter(toolCallId)) {
            for (int offset = 0; offset < normalized.length(); offset += 4096) {
                writer.append(normalized.substring(offset, Math.min(normalized.length(), offset + 4096)));
            }
            return writer.finish();
        }
    }

    public static ArtifactWriter openWriter(String toolCallId) throws IOException {
        Path root = rootDirectory();
        String runId = currentRunId();
        Path runDir = controlledResolve(root, sanitize(runId));
        Files.createDirectories(runDir);
        Path realRoot = root.toRealPath();
        Path realRunDir = runDir.toRealPath();
        if (!realRunDir.startsWith(realRoot)) {
            throw new IOException("工具结果目录超出受控根目录");
        }

        String fileName = sanitize(toolCallId == null ? "anon" : toolCallId)
                + "-" + UUID.randomUUID().toString().replace("-", "") + ".txt";
        synchronized (RESERVED) {
            long existing = 0;
            try (var files = Files.newDirectoryStream(realRunDir)) {
                for (Path file : files) {
                    if (Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        existing += Files.size(file);
                    }
                }
            }
            long reserved = RESERVED.getOrDefault(realRunDir, 0L);
            if (existing + reserved + MAX_ARTIFACT_BYTES + 1024 > MAX_RUN_BYTES) {
                throw new IOException("工具结果超过本次运行存储配额");
            }
            RESERVED.put(realRunDir, reserved + MAX_ARTIFACT_BYTES + 1024);
        }
        try {
            return new ArtifactWriter(realRoot, realRunDir, controlledResolve(realRunDir, fileName));
        } catch (IOException e) {
            releaseReservation(realRunDir);
            throw e;
        }
    }

    private static void releaseReservation(Path directory) {
        synchronized (RESERVED) {
            long remaining = RESERVED.getOrDefault(directory, 0L) - MAX_ARTIFACT_BYTES - 1024;
            if (remaining <= 0) RESERVED.remove(directory);
            else RESERVED.put(directory, remaining);
        }
    }

    public static final class ArtifactWriter implements AutoCloseable {
        private final Path root;
        private final Path directory;
        private final Path target;
        private final Path temp;
        private final MessageDigest digest;
        private final java.io.Writer writer;
        private long chars;
        private boolean closed;
        private boolean finished;

        private ArtifactWriter(Path root, Path directory, Path target) throws IOException {
            this.root = root;
            this.directory = directory;
            this.target = target;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IOException(e);
            }
            temp = Files.createTempFile(directory, ".tool-result-", ".tmp");
            try {
                writer = new java.io.OutputStreamWriter(new java.security.DigestOutputStream(
                        Files.newOutputStream(temp), digest), StandardCharsets.UTF_8);
            } catch (IOException e) {
                Files.deleteIfExists(temp);
                throw e;
            }
        }

        public boolean append(String chunk) throws IOException {
            if (closed || finished) throw new IOException("工具结果写入已结束");
            if (chunk.length() > MAX_ARTIFACT_CHARS - chars) return false;
            writer.write(chunk);
            chars += chunk.length();
            return true;
        }

        public StoredArtifact finish() throws IOException {
            if (closed || finished) throw new IOException("工具结果写入已结束");
            writer.close();
            String hash = HexFormat.of().formatHex(digest.digest());
            moveAtomically(temp, target);
            try {
                writeHashSidecar(target, hash);
            } catch (IOException e) {
                Files.deleteIfExists(target);
                throw e;
            }
            finished = true;
            return new StoredArtifact(root.relativize(target).toString().replace('\\', '/'),
                    hash, chars, Files.size(target));
        }

        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            try {
                writer.close();
            } finally {
                try {
                    Files.deleteIfExists(temp);
                } finally {
                    releaseReservation(directory);
                }
            }
        }
    }

    public static ArtifactPage read(String artifactRef, long offset, int requestedLimit)
            throws IOException {
        if (offset < 0) {
            throw new IllegalArgumentException("offset 不能小于 0");
        }
        int limit = Math.max(1, Math.min(requestedLimit, MAX_PAGE_CHARS));
        Path root = rootDirectory();
        Path file = controlledResolve(root, artifactRef);
        if (!Files.isRegularFile(file)) {
            throw new IOException("result_ref 不存在: " + artifactRef);
        }
        Path realRoot = root.toRealPath();
        file = file.toRealPath();
        if (!file.startsWith(realRoot)) {
            throw new IOException("result_ref 超出受控结果目录");
        }
        verifyHash(file);

        StringBuilder content = new StringBuilder(limit);
        boolean hasMore;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            long skipped = skipFully(reader, offset);
            if (skipped < offset) {
                return new ArtifactPage("", offset, "", false);
            }
            char[] buffer = new char[Math.min(4_096, limit + 1)];
            while (content.length() <= limit) {
                int remaining = limit + 1 - content.length();
                int read = reader.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read < 0) break;
                content.append(buffer, 0, read);
            }
            hasMore = content.length() > limit;
        }
        if (hasMore) content.setLength(limit);
        String nextCursor = hasMore ? Long.toString(offset + content.length()) : "";
        return new ArtifactPage(content.toString(), offset, nextCursor, hasMore);
    }

    public static Path rootDirectory() {
        String configured = ConfigResolver.optional(ROOT_PROPERTY, ROOT_ENV);
        Path root = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".devcli", "runtime", "tool-results")
                : Path.of(configured.trim());
        return root.toAbsolutePath().normalize();
    }

    /** 子运行只能恢复自己生成的结果；父运行仍可恢复历史上下文中的引用。 */
    static boolean belongsToCurrentRun(String artifactRef) {
        if (CancellationContext.currentRun() == null) return false;
        try {
            Path root = rootDirectory();
            Path runDir = controlledResolve(root, sanitize(currentRunId())).toRealPath();
            Path file = controlledResolve(root, artifactRef).toRealPath();
            return runDir.startsWith(root.toRealPath()) && file.getParent().equals(runDir);
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    private static void writeHashSidecar(Path target, String sha256) throws IOException {
        Path sidecar = target.resolveSibling(target.getFileName() + ".sha256");
        Path temp = Files.createTempFile(target.getParent(), ".tool-result-hash-", ".tmp");
        try {
            Files.writeString(temp, sha256, StandardCharsets.UTF_8);
            moveAtomically(temp, sidecar);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void verifyHash(Path file) throws IOException {
        Path sidecar = file.resolveSibling(file.getFileName() + ".sha256");
        if (!Files.isRegularFile(sidecar)) {
            throw new IOException("工具结果缺少 SHA-256 校验文件");
        }
        String expected = Files.readString(sidecar, StandardCharsets.UTF_8).trim();
        String actual = sha256(file);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII))) {
            throw new IOException("工具结果 SHA-256 校验失败");
        }
    }

    private static Path controlledResolve(Path root, String relative) {
        if (relative == null || relative.isBlank()) {
            throw new IllegalArgumentException("result_ref 不能为空");
        }
        Path resolved = root.resolve(relative).toAbsolutePath().normalize();
        if (!resolved.startsWith(root.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("result_ref 超出受控结果目录");
        }
        return resolved;
    }

    private static long skipFully(Reader reader, long offset) throws IOException {
        long skipped = 0;
        while (skipped < offset) {
            long current = reader.skip(offset - skipped);
            if (current > 0) {
                skipped += current;
                continue;
            }
            if (reader.read() < 0) break;
            skipped++;
        }
        return skipped;
    }

    private static String currentRunId() {
        RunContext run = CancellationContext.currentRun();
        return run == null ? FALLBACK_RUN_ID : run.runId();
    }

    private static String sanitize(String raw) {
        StringBuilder safe = new StringBuilder();
        String value = raw == null ? "" : raw;
        for (int i = 0; i < value.length() && safe.length() < 128; i++) {
            char c = value.charAt(i);
            safe.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' ? c : '_');
        }
        return safe.isEmpty() ? "anon" : safe.toString();
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("运行环境不支持 SHA-256", e);
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8_192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("运行环境不支持 SHA-256", e);
        }
    }

    public record StoredArtifact(String ref, String sha256, long chars, long bytes) {
    }

    public record ArtifactPage(String content, long offset, String nextCursor, boolean hasMore) {
    }
}
