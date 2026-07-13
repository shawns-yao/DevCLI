package com.devcli.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;

final class PatchJournalPolicy {
    static final String TTL_PROPERTY = "devcli.patch.journal.ttl.hours";
    static final String TTL_ENV = "DEVCLI_PATCH_JOURNAL_TTL_HOURS";
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");
    private static final Logger log = LoggerFactory.getLogger(PatchJournalPolicy.class);

    private PatchJournalPolicy() {
    }

    static void maintain(Path checkpointDir) {
        try {
            pruneOrphans(checkpointDir, resolveTtl(System.getProperties().entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            entry -> String.valueOf(entry.getKey()),
                            entry -> String.valueOf(entry.getValue()))), System.getenv()), Instant.now());
        } catch (IOException e) {
            log.warn("清理孤儿 PatchSet 写前日志失败: {}", e.getMessage());
        }
    }

    static void pruneOrphans(Path checkpointDir, Duration ttl, Instant now) throws IOException {
        if (checkpointDir == null || !Files.isDirectory(checkpointDir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Instant cutoff = now.minus(ttl == null || ttl.isNegative() ? DEFAULT_TTL : ttl);
        try (var entries = Files.list(checkpointDir)) {
            for (Path candidate : entries.toList()) {
                String name = candidate.getFileName().toString();
                if (!name.endsWith(".patch-journal")
                        || !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(candidate)
                        || Files.getLastModifiedTime(candidate, LinkOption.NOFOLLOW_LINKS)
                        .toInstant().isAfter(cutoff)) {
                    continue;
                }
                String checkpointName = name.substring(0,
                        name.length() - ".patch-journal".length()) + ".json";
                if (Files.exists(checkpointDir.resolve(checkpointName), LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                deleteTree(candidate);
            }
        }
    }

    static Duration resolveTtl(Map<String, String> properties, Map<String, String> environment) {
        String value = properties == null ? null : properties.get(TTL_PROPERTY);
        if (value == null || value.isBlank()) {
            value = environment == null ? null : environment.get(TTL_ENV);
        }
        if (value == null || value.isBlank()) {
            return DEFAULT_TTL;
        }
        try {
            long hours = Long.parseLong(value.trim());
            return hours < 0 ? DEFAULT_TTL : Duration.ofHours(hours);
        } catch (NumberFormatException | ArithmeticException ignored) {
            return DEFAULT_TTL;
        }
    }

    static void secureDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        setPermissions(directory, DIRECTORY_PERMISSIONS, true);
    }

    static void secureFile(Path file) throws IOException {
        setPermissions(file, FILE_PERMISSIONS, false);
    }

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions,
                                       boolean directory) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, permissions);
            return;
        }
        AclFileAttributeView aclView = Files.getFileAttributeView(
                path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (aclView == null) {
            throw new IOException("文件系统不支持 PatchSet 写前日志权限控制: " + path);
        }
        java.util.EnumSet<AclEntryPermission> allowed =
                java.util.EnumSet.allOf(AclEntryPermission.class);
        AclEntry ownerOnly = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS))
                .setPermissions(allowed)
                .build();
        aclView.setAcl(java.util.List.of(ownerOnly));
    }

    private static void deleteTree(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
