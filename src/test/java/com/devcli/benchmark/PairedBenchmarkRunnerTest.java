package com.devcli.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PairedBenchmarkRunnerTest {
    private static final String LEGACY_MAX_ATTEMPTS_PROPERTY = "devcli.benchmark.llm.maxAttempts";

    @Test
    void incompleteSideRetriesTheWholePairAndKeepsEveryAttempt(@TempDir Path root) throws Exception {
        List<String> calls = new ArrayList<>();
        PairedBenchmarkRunner<FakeRun, FakeRun> runner = new PairedBenchmarkRunner<>(3,
                (attempt, workspace) -> {
                    calls.add(attempt + ":single");
                    calls.add(attempt + ":plan");
                    Files.writeString(workspace.resolve("attempt-marker.txt"), Integer.toString(attempt));
                    return new PairedBenchmarkRunner.Attempt<>(
                            new FakeRun("single-" + attempt),
                            new FakeRun("plan-" + attempt),
                            true,
                            attempt == 2);
                });

        PairedBenchmarkRunner.Result<FakeRun, FakeRun> result = runner.run(root);

        assertTrue(result.valid());
        assertEquals(List.of("1:single", "1:plan", "2:single", "2:plan"), calls);
        assertEquals(2, result.attempts().size());
        assertEquals(1, result.attempts().get(0).number());
        assertEquals(2, result.attempts().get(1).number());
        assertNotEquals(result.attempts().get(0).workspace(), result.attempts().get(1).workspace());
        assertTrue(Files.exists(result.attempts().get(0).workspace().resolve("attempt-marker.txt")));
        assertTrue(Files.exists(result.attempts().get(1).workspace().resolve("attempt-marker.txt")));
        assertEquals(2, result.validPair().orElseThrow().number());
    }

    @Test
    void firstCompletePairStopsWithoutExtraAttempts(@TempDir Path root) throws Exception {
        List<Integer> attempts = new ArrayList<>();
        PairedBenchmarkRunner<FakeRun, FakeRun> runner = new PairedBenchmarkRunner<>(5,
                (attempt, workspace) -> {
                    attempts.add(attempt);
                    return new PairedBenchmarkRunner.Attempt<>(
                            new FakeRun("single"), new FakeRun("plan"), true, true);
                });

        PairedBenchmarkRunner.Result<FakeRun, FakeRun> result = runner.run(root);

        assertTrue(result.valid());
        assertEquals(List.of(1), attempts);
        assertEquals(1, result.attempts().size());
        assertEquals(1, result.validPair().orElseThrow().number());
    }

    @Test
    void reachesBoundAndReturnsInvalidPairWhenNoAttemptIsComplete(@TempDir Path root) throws Exception {
        List<Integer> attempts = new ArrayList<>();
        PairedBenchmarkRunner<FakeRun, FakeRun> runner = new PairedBenchmarkRunner<>(3,
                (attempt, workspace) -> {
                    attempts.add(attempt);
                    return new PairedBenchmarkRunner.Attempt<>(
                            new FakeRun("single"), new FakeRun("plan"), attempt % 2 == 0, false);
                });

        PairedBenchmarkRunner.Result<FakeRun, FakeRun> result = runner.run(root);

        assertFalse(result.valid());
        assertTrue(result.validPair().isEmpty());
        assertEquals(List.of(1, 2, 3), attempts);
        assertEquals(3, result.attempts().size());
        assertTrue(result.attempts().stream().allMatch(record -> record.outcome() != null));
    }

    @Test
    void boundsConfiguredMaximumAttemptsToOneThroughFive() {
        PairedBenchmarkRunner<FakeRun, FakeRun> defaultRunner = new PairedBenchmarkRunner<>(
                (attempt, workspace) -> new PairedBenchmarkRunner.Attempt<>(
                        new FakeRun("single"), new FakeRun("plan"), true, true));
        assertEquals(2, defaultRunner.maxAttempts());
        assertEquals(1, PairedBenchmarkRunner.boundMaxAttempts(0));
        assertEquals(1, PairedBenchmarkRunner.boundMaxAttempts(1));
        assertEquals(2, PairedBenchmarkRunner.boundMaxAttempts(2));
        assertEquals(5, PairedBenchmarkRunner.boundMaxAttempts(5));
        assertEquals(5, PairedBenchmarkRunner.boundMaxAttempts(6));
    }

    @Test
    void configuredMaximumPrefersNewPropertyAndClampsBothBounds() {
        String previousNew = System.getProperty(PairedBenchmarkRunner.MAX_ATTEMPTS_PROPERTY);
        String previousLegacy = System.getProperty(LEGACY_MAX_ATTEMPTS_PROPERTY);
        try {
            System.setProperty(LEGACY_MAX_ATTEMPTS_PROPERTY, "4");
            System.setProperty(PairedBenchmarkRunner.MAX_ATTEMPTS_PROPERTY, "0");
            assertEquals(1, PairedBenchmarkRunner.configuredMaxAttempts());

            System.setProperty(LEGACY_MAX_ATTEMPTS_PROPERTY, "1");
            System.setProperty(PairedBenchmarkRunner.MAX_ATTEMPTS_PROPERTY, "99");
            assertEquals(5, PairedBenchmarkRunner.configuredMaxAttempts());
        } finally {
            restoreProperty(PairedBenchmarkRunner.MAX_ATTEMPTS_PROPERTY, previousNew);
            restoreProperty(LEGACY_MAX_ATTEMPTS_PROPERTY, previousLegacy);
        }
    }

    @Test
    void configuredMaximumFallsBackToLegacyProperty() {
        String previousNew = System.getProperty(PairedBenchmarkRunner.MAX_ATTEMPTS_PROPERTY);
        String previousLegacy = System.getProperty(LEGACY_MAX_ATTEMPTS_PROPERTY);
        try {
            System.clearProperty(PairedBenchmarkRunner.MAX_ATTEMPTS_PROPERTY);
            System.setProperty(LEGACY_MAX_ATTEMPTS_PROPERTY, "4");
            assertEquals(4, PairedBenchmarkRunner.configuredMaxAttempts());
        } finally {
            restoreProperty(PairedBenchmarkRunner.MAX_ATTEMPTS_PROPERTY, previousNew);
            restoreProperty(LEGACY_MAX_ATTEMPTS_PROPERTY, previousLegacy);
        }
    }

    @Test
    void invalidNewMaximumFallsBackToDefault() {
        String previousNew = System.getProperty(PairedBenchmarkRunner.MAX_ATTEMPTS_PROPERTY);
        String previousLegacy = System.getProperty(LEGACY_MAX_ATTEMPTS_PROPERTY);
        try {
            System.setProperty(PairedBenchmarkRunner.MAX_ATTEMPTS_PROPERTY, "not-a-number");
            System.setProperty(LEGACY_MAX_ATTEMPTS_PROPERTY, "5");
            assertEquals(PairedBenchmarkRunner.DEFAULT_MAX_ATTEMPTS,
                    PairedBenchmarkRunner.configuredMaxAttempts());
        } finally {
            restoreProperty(PairedBenchmarkRunner.MAX_ATTEMPTS_PROPERTY, previousNew);
            restoreProperty(LEGACY_MAX_ATTEMPTS_PROPERTY, previousLegacy);
        }
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private record FakeRun(String id) {
    }
}
