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
        List<String> excludes,
        boolean gcEnabled,
        int gcPrunedThreshold,
        int gcMinIntervalHours,
        int gcMaxSeconds
) {
    public SnapshotConfig {
        snapshotsRoot = snapshotsRoot == null
                ? Path.of(System.getProperty("user.home"), ".devcli", "snapshots")
                : snapshotsRoot.toAbsolutePath().normalize();
        maxSnapshots = Math.max(1, maxSnapshots);
        excludes = excludes == null ? List.of() : List.copyOf(excludes);
        gcPrunedThreshold = Math.max(1, gcPrunedThreshold);
        gcMinIntervalHours = Math.max(1, gcMinIntervalHours);
        gcMaxSeconds = Math.max(1, gcMaxSeconds);
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
        boolean gcEnabled = ConfigResolver.booleanValue(
                "devcli.snapshot.gc.enabled", "DEVCLI_SNAPSHOT_GC_ENABLED", true);
        int gcThreshold = ConfigResolver.intValue(
                "devcli.snapshot.gc.pruned.threshold",
                "DEVCLI_SNAPSHOT_GC_PRUNED_THRESHOLD", 100, 1, Integer.MAX_VALUE);
        int gcIntervalHours = ConfigResolver.intValue(
                "devcli.snapshot.gc.min.interval.hours",
                "DEVCLI_SNAPSHOT_GC_MIN_INTERVAL_HOURS", 24, 1, Integer.MAX_VALUE);
        int gcMaxSeconds = ConfigResolver.intValue(
                "devcli.snapshot.gc.max.seconds",
                "DEVCLI_SNAPSHOT_GC_MAX_SECONDS", 30, 1, Integer.MAX_VALUE);
        return new SnapshotConfig(enabled, root, max, excludes,
                gcEnabled, gcThreshold, gcIntervalHours, gcMaxSeconds);
    }

    public SnapshotConfig(boolean enabled, Path snapshotsRoot, int maxSnapshots,
                          List<String> excludes) {
        this(enabled, snapshotsRoot, maxSnapshots, excludes,
                true, 100, 24, 30);
    }

    public SnapshotConfig withEnabled(boolean enabled) {
        return new SnapshotConfig(enabled, snapshotsRoot, maxSnapshots, excludes,
                gcEnabled, gcPrunedThreshold, gcMinIntervalHours, gcMaxSeconds);
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
