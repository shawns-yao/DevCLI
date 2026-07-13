package com.devcli.snapshot;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;

final class SnapshotGcPolicy {
    private static final String LAST_GC = "lastGcEpochMillis";
    private static final String PRUNED = "prunedSinceGc";

    private final SnapshotConfig config;
    private final Path stateFile;

    SnapshotGcPolicy(Path gitDir, SnapshotConfig config) {
        this.config = config;
        this.stateFile = gitDir.resolve("devcli-gc.properties");
    }

    synchronized boolean recordPrunedAndShouldRun(int prunedSnapshots, Instant now)
            throws IOException {
        if (!config.gcEnabled() || prunedSnapshots <= 0) {
            return false;
        }
        State state = load(now);
        long total = state.prunedSinceGc() > Long.MAX_VALUE - prunedSnapshots
                ? Long.MAX_VALUE
                : state.prunedSinceGc() + prunedSnapshots;
        State updated = new State(state.lastGc(), total);
        save(updated);
        boolean thresholdReached = total >= config.gcPrunedThreshold();
        boolean intervalReached = !now.isBefore(state.lastGc().plus(
                Duration.ofHours(config.gcMinIntervalHours())));
        return thresholdReached || intervalReached;
    }

    synchronized void markCompleted(Instant now) throws IOException {
        save(new State(now, 0));
    }

    synchronized State state(Instant now) throws IOException {
        return load(now);
    }

    private State load(Instant now) throws IOException {
        if (!Files.isRegularFile(stateFile)) {
            return new State(now, 0);
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(stateFile)) {
            properties.load(input);
        }
        long last = parseLong(properties.getProperty(LAST_GC), now.toEpochMilli());
        long pruned = Math.max(0, parseLong(properties.getProperty(PRUNED), 0));
        return new State(Instant.ofEpochMilli(Math.max(0, last)), pruned);
    }

    private void save(State state) throws IOException {
        Files.createDirectories(stateFile.getParent());
        Properties properties = new Properties();
        properties.setProperty(LAST_GC, Long.toString(state.lastGc().toEpochMilli()));
        properties.setProperty(PRUNED, Long.toString(state.prunedSinceGc()));
        Path temporary = Files.createTempFile(stateFile.getParent(),
                "devcli-gc-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "DevCLI Side-Git GC state");
            }
            try {
                Files.move(temporary, stateFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    record State(Instant lastGc, long prunedSinceGc) {
    }
}
