package com.devcli.snapshot;

import com.devcli.config.ConfigResolver;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record SnapshotConfig(
        boolean enabled,
        Path snapshotsRoot,
        int maxSnapshots,
        List<String> excludes
) {
    public SnapshotConfig {
        snapshotsRoot = snapshotsRoot == null
                ? Path.of(System.getProperty("user.home"), ".devcli", "snapshots")
                : snapshotsRoot.toAbsolutePath().normalize();
        maxSnapshots = Math.max(1, maxSnapshots);
        excludes = excludes == null ? List.of() : List.copyOf(excludes);
    }

    private static final List<String> DEFAULT_EXCLUDES = List.of(
            ".git",
            ".devcli/snapshots",
            "target",
            "node_modules",
            "dist",
            ".idea",
            "*.class",
            "*.jar"
    );

    public static SnapshotConfig fromEnvironment() {
        boolean enabled = ConfigResolver.booleanValue(
                "devcli.snapshot.enabled", "DEVCLI_SNAPSHOT_ENABLED", true);
        Path root = Path.of(ConfigResolver.stringValue(
                "devcli.snapshot.dir", "DEVCLI_SNAPSHOT_DIR",
                Path.of(System.getProperty("user.home"), ".devcli", "snapshots").toString()));
        int max = ConfigResolver.intValue(
                "devcli.snapshot.max", "DEVCLI_SNAPSHOT_MAX", 50, 1, Integer.MAX_VALUE);
        List<String> excludes = mergeExcludes(ConfigResolver.stringValue(
                "devcli.snapshot.excludes", "DEVCLI_SNAPSHOT_EXCLUDES", ""));
        return new SnapshotConfig(enabled, root, max, excludes);
    }

    public SnapshotConfig withEnabled(boolean enabled) {
        return new SnapshotConfig(enabled, snapshotsRoot, maxSnapshots, excludes);
    }

    private static List<String> mergeExcludes(String configured) {
        Set<String> merged = new LinkedHashSet<>(DEFAULT_EXCLUDES);
        if (configured != null && !configured.isBlank()) {
            for (String item : configured.split(",")) {
                String trimmed = item.trim();
                if (!trimmed.isEmpty()) {
                    merged.add(trimmed);
                }
            }
        }
        return new ArrayList<>(merged);
    }
}
