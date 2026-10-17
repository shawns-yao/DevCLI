package com.devcli.memory;

import com.devcli.tool.ToolResultArtifactStore;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** 压缩触发记录与恢复边界的持久化路径。 */
final class CompactionPaths {
    private CompactionPaths() {}
    static Path defaultCompactionStatePath() {
        return ToolResultArtifactStore.rootDirectory().resolve("compaction-trigger-"
                + UUID.randomUUID().toString().replace("-", "") + ".properties");
    }

    static Path defaultBoundarySnapshotPath() {
        return Path.of(System.getProperty("user.home"), ".devcli", "compaction-boundaries",
                "boundary-default.json");
    }

    static Path defaultCompactionStatePath(Path projectPath) {
        String fingerprint = sha256(projectPath.toAbsolutePath().normalize().toString());
        return ToolResultArtifactStore.rootDirectory().resolve("compaction-trigger-" + fingerprint + ".properties");
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
