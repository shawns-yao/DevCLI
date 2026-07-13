package com.devcli.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 隔离工作区产生的结构化文件变更集。
 */
public final class PatchSet {
    static final String MISSING_HASH = "<missing>";

    public static boolean isMissingHash(String hash) {
        return MISSING_HASH.equals(hash);
    }

    public enum ChangeType {
        ADD,
        MODIFY,
        DELETE
    }

    public record FileChange(String relativePath, ChangeType type,
                             String beforeHash, String afterHash, byte[] content) {
        public FileChange {
            if (relativePath == null || relativePath.isBlank()) {
                throw new IllegalArgumentException("relativePath is required");
            }
            type = type == null ? ChangeType.MODIFY : type;
            beforeHash = beforeHash == null ? MISSING_HASH : beforeHash;
            afterHash = afterHash == null ? MISSING_HASH : afterHash;
            content = content == null ? new byte[0] : content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    public record ApplyResult(boolean applied, List<String> conflicts,
                              List<String> modifiedResources, String error,
                              List<String> rollbackFailures) {
        public ApplyResult {
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
            modifiedResources = modifiedResources == null ? List.of() : List.copyOf(modifiedResources);
            error = error == null ? "" : error;
            rollbackFailures = rollbackFailures == null ? List.of() : List.copyOf(rollbackFailures);
        }

        public boolean rollbackComplete() {
            return rollbackFailures.isEmpty();
        }

        public String failureDescription() {
            if (!conflicts.isEmpty()) {
                return "PatchSet 冲突: " + String.join(", ", conflicts);
            }
            String base = "PatchSet 应用失败: " + error;
            if (rollbackFailures.isEmpty()) {
                return base;
            }
            return base + "; 回滚不完整: " + String.join(" | ", rollbackFailures);
        }

        static ApplyResult success(List<String> modifiedResources) {
            return new ApplyResult(true, List.of(), modifiedResources, "", List.of());
        }

        static ApplyResult conflict(List<String> conflicts) {
            return new ApplyResult(false, conflicts, List.of(), "patch conflict", List.of());
        }

        static ApplyResult failure(String error) {
            return failure(error, List.of());
        }

        static ApplyResult failure(String error, List<String> rollbackFailures) {
            return new ApplyResult(false, List.of(), List.of(), error, rollbackFailures);
        }
    }

    private final List<FileChange> changes;

    public PatchSet(List<FileChange> changes) {
        this.changes = changes == null ? List.of() : changes.stream()
                .sorted(Comparator.comparing(FileChange::relativePath))
                .toList();
    }

    public List<FileChange> changes() {
        return changes;
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    public ApplyResult apply(Path projectRoot) {
        Path root = normalizeRoot(projectRoot);
        List<String> conflicts = new ArrayList<>();
        Map<Path, byte[]> originals = new LinkedHashMap<>();
        Map<Path, Boolean> existed = new LinkedHashMap<>();

        try {
            for (FileChange change : changes) {
                Path target = resolveSafe(root, change.relativePath());
                if (hasUnsafePathEntry(root, target)) {
                    conflicts.add(change.relativePath());
                    continue;
                }
                boolean present = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
                boolean regularFile = Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS);
                if (present && !regularFile) {
                    conflicts.add(change.relativePath());
                    continue;
                }
                String currentHash = regularFile ? hash(Files.readAllBytes(target)) : MISSING_HASH;
                if (!currentHash.equals(change.beforeHash())) {
                    conflicts.add(change.relativePath());
                    continue;
                }
                existed.put(target, regularFile);
                originals.put(target, regularFile ? Files.readAllBytes(target) : new byte[0]);
            }
            if (!conflicts.isEmpty()) {
                conflicts.sort(String::compareTo);
                return ApplyResult.conflict(conflicts);
            }

            List<String> applied = new ArrayList<>();
            try {
                for (FileChange change : changes) {
                    Path target = resolveSafe(root, change.relativePath());
                    if (hasUnsafePathEntry(root, target)) {
                        throw new IOException("unsafe patch path: " + change.relativePath());
                    }
                    if (change.type() == ChangeType.DELETE) {
                        Files.deleteIfExists(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        if (hasUnsafePathEntry(root, target)) {
                            throw new IOException("unsafe patch path: " + change.relativePath());
                        }
                        Path temporary = Files.createTempFile(
                                target.getParent(), ".devcli-patch-", ".tmp");
                        try {
                            Files.write(temporary, change.content());
                            try {
                                Files.move(temporary, target,
                                        StandardCopyOption.ATOMIC_MOVE,
                                        StandardCopyOption.REPLACE_EXISTING);
                            } catch (IOException atomicFailure) {
                                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                            }
                        } finally {
                            Files.deleteIfExists(temporary);
                        }
                    }
                    applied.add(change.relativePath());
                }
                return ApplyResult.success(applied);
            } catch (Exception applyFailure) {
                List<String> rollbackFailures = rollback(originals, existed);
                String error = applyFailure.getMessage() == null
                        ? applyFailure.getClass().getSimpleName()
                        : applyFailure.getMessage();
                return ApplyResult.failure(error, rollbackFailures);
            }
        } catch (Exception e) {
            return ApplyResult.failure(e.getMessage());
        }
    }

    private static List<String> rollback(Map<Path, byte[]> originals, Map<Path, Boolean> existed) {
        List<String> failures = new ArrayList<>();
        List<Path> paths = new ArrayList<>(originals.keySet());
        paths.sort(Comparator.comparingInt(Path::getNameCount).reversed());
        for (Path path : paths) {
            try {
                if (Boolean.TRUE.equals(existed.get(path))) {
                    Files.createDirectories(path.getParent());
                    Files.write(path, originals.get(path));
                } else {
                    Files.deleteIfExists(path);
                }
            } catch (Exception rollbackFailure) {
                String message = rollbackFailure.getMessage() == null
                        ? rollbackFailure.getClass().getSimpleName()
                        : rollbackFailure.getMessage();
                failures.add(path + ": " + message);
            }
        }
        return failures;
    }

    public static String hash(byte[] bytes) {
        MessageDigest digest = newSha256();
        return formatHash(digest.digest(bytes));
    }

    public static String hash(Path path) throws IOException {
        MessageDigest digest = newSha256();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return formatHash(digest.digest());
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String formatHash(byte[] digest) {
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    private static Path normalizeRoot(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("projectRoot is required");
        }
        Path normalized = root.toAbsolutePath().normalize();
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return normalized;
        }
        try {
            return normalized.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot resolve project root: " + normalized, e);
        }
    }

    private static Path resolveSafe(Path root, String relativePath) {
        Path relative = Path.of(relativePath);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("patch path must be relative: " + relativePath);
        }
        Path resolved = root.resolve(relative).normalize();
        if (resolved.equals(root) || !resolved.startsWith(root)) {
            throw new IllegalArgumentException("patch path escapes project root: " + relativePath);
        }
        return resolved;
    }

    private static boolean hasUnsafePathEntry(Path root, Path target) {
        Path relative = root.relativize(target);
        Path current = root;
        for (int i = 0; i < relative.getNameCount(); i++) {
            current = current.resolve(relative.getName(i));
            if (Files.isSymbolicLink(current)) {
                return true;
            }
            boolean present = Files.exists(current, LinkOption.NOFOLLOW_LINKS);
            if (present) {
                try {
                    if (!current.toRealPath().startsWith(root)) {
                        return true;
                    }
                } catch (IOException e) {
                    return true;
                }
            }
            boolean last = i == relative.getNameCount() - 1;
            if (!last && present && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                return true;
            }
        }
        return false;
    }
}
