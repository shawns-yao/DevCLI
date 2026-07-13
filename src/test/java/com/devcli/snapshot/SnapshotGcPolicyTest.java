package com.devcli.snapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotGcPolicyTest {

    @Test
    void triggersOnlyAfterPrunedThresholdAndPersistsState(@TempDir Path tempDir)
            throws Exception {
        SnapshotConfig config = config(tempDir, true, 3, 24);
        Instant now = Instant.parse("2026-07-13T00:00:00Z");
        SnapshotGcPolicy first = new SnapshotGcPolicy(tempDir.resolve(".git"), config);

        assertFalse(first.recordPrunedAndShouldRun(1, now));
        assertFalse(first.recordPrunedAndShouldRun(1, now.plusSeconds(1)));

        SnapshotGcPolicy reloaded = new SnapshotGcPolicy(tempDir.resolve(".git"), config);
        assertTrue(reloaded.recordPrunedAndShouldRun(1, now.plusSeconds(2)));
        assertEquals(3, reloaded.state(now).prunedSinceGc());

        reloaded.markCompleted(now.plusSeconds(3));
        assertEquals(0, reloaded.state(now).prunedSinceGc());
    }

    @Test
    void elapsedIntervalTriggersGcWithoutReachingThreshold(@TempDir Path tempDir)
            throws Exception {
        SnapshotConfig config = config(tempDir, true, 100, 24);
        Instant now = Instant.parse("2026-07-13T00:00:00Z");
        SnapshotGcPolicy policy = new SnapshotGcPolicy(tempDir.resolve(".git"), config);

        assertFalse(policy.recordPrunedAndShouldRun(1, now));
        assertTrue(policy.recordPrunedAndShouldRun(1,
                now.plusSeconds(25 * 60 * 60)));
    }

    @Test
    void disabledGcDoesNotCreateState(@TempDir Path tempDir) throws Exception {
        SnapshotConfig config = config(tempDir, false, 1, 1);
        Path gitDir = tempDir.resolve(".git");
        SnapshotGcPolicy policy = new SnapshotGcPolicy(gitDir, config);

        assertFalse(policy.recordPrunedAndShouldRun(10, Instant.now()));
        assertFalse(java.nio.file.Files.exists(gitDir.resolve("devcli-gc.properties")));
    }

    @Test
    void snapshotGcConfigurationLoadsFromSystemProperties(@TempDir Path tempDir) {
        String[] names = {
                "devcli.snapshot.dir",
                "devcli.snapshot.gc.enabled",
                "devcli.snapshot.gc.pruned.threshold",
                "devcli.snapshot.gc.min.interval.hours",
                "devcli.snapshot.gc.max.seconds"
        };
        String[] previous = java.util.Arrays.stream(names)
                .map(System::getProperty)
                .toArray(String[]::new);
        try {
            System.setProperty(names[0], tempDir.toString());
            System.setProperty(names[1], "false");
            System.setProperty(names[2], "7");
            System.setProperty(names[3], "9");
            System.setProperty(names[4], "11");

            SnapshotConfig config = SnapshotConfig.fromEnvironment();

            assertFalse(config.gcEnabled());
            assertEquals(7, config.gcPrunedThreshold());
            assertEquals(9, config.gcMinIntervalHours());
            assertEquals(11, config.gcMaxSeconds());
        } finally {
            for (int index = 0; index < names.length; index++) {
                if (previous[index] == null) {
                    System.clearProperty(names[index]);
                } else {
                    System.setProperty(names[index], previous[index]);
                }
            }
        }
    }

    private static SnapshotConfig config(Path root, boolean enabled,
                                         int threshold, int intervalHours) {
        return new SnapshotConfig(true, root, 2, List.of(".git"),
                enabled, threshold, intervalHours, 30);
    }
}
