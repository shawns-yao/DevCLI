package com.devcli.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Runs both benchmark modes as one retryable unit so the comparison stays paired. */
final class PairedBenchmarkRunner<S, P> {
    static final int DEFAULT_MAX_ATTEMPTS = 2;
    static final int MIN_MAX_ATTEMPTS = 1;
    static final int MAX_MAX_ATTEMPTS = 5;
    static final String MAX_ATTEMPTS_PROPERTY = "devcli.benchmark.checkout.maxAttempts";
    private static final String LEGACY_MAX_ATTEMPTS_PROPERTY = "devcli.benchmark.llm.maxAttempts";

    private final int maxAttempts;
    private final AttemptFunction<S, P> attemptFunction;

    PairedBenchmarkRunner(AttemptFunction<S, P> attemptFunction) {
        this(DEFAULT_MAX_ATTEMPTS, attemptFunction);
    }

    PairedBenchmarkRunner(int maxAttempts, AttemptFunction<S, P> attemptFunction) {
        this.maxAttempts = boundMaxAttempts(maxAttempts);
        this.attemptFunction = java.util.Objects.requireNonNull(attemptFunction, "attemptFunction");
    }

    static int configuredMaxAttempts() {
        String configured = System.getProperty(MAX_ATTEMPTS_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getProperty(LEGACY_MAX_ATTEMPTS_PROPERTY);
        }
        if (configured == null || configured.isBlank()) {
            return DEFAULT_MAX_ATTEMPTS;
        }
        try {
            return boundMaxAttempts(Integer.parseInt(configured.trim()));
        } catch (NumberFormatException ignored) {
            return DEFAULT_MAX_ATTEMPTS;
        }
    }

    static int boundMaxAttempts(int requested) {
        return Math.max(MIN_MAX_ATTEMPTS, Math.min(MAX_MAX_ATTEMPTS, requested));
    }

    int maxAttempts() {
        return maxAttempts;
    }

    Result<S, P> run(Path root) throws IOException {
        Files.createDirectories(root);
        List<AttemptRecord<S, P>> attempts = new ArrayList<>();
        AttemptRecord<S, P> validPair = null;
        for (int number = 1; number <= maxAttempts; number++) {
            Path workspace = root.resolve("attempt-" + number).toAbsolutePath().normalize();
            if (Files.exists(workspace)) {
                throw new IOException("attempt workspace already exists: " + workspace);
            }
            Files.createDirectories(workspace);

            Attempt<S, P> outcome = null;
            String failure = null;
            try {
                outcome = attemptFunction.run(number, workspace);
            } catch (Exception error) {
                failure = error.getClass().getSimpleName() + ": " + error.getMessage();
            }
            AttemptRecord<S, P> record = new AttemptRecord<>(number, workspace, outcome, failure);
            attempts.add(record);
            if (record.complete()) {
                validPair = record;
                break;
            }
        }
        return new Result<>(maxAttempts, attempts, validPair);
    }

    @FunctionalInterface
    interface AttemptFunction<S, P> {
        Attempt<S, P> run(int attempt, Path workspace) throws Exception;
    }

    record Attempt<S, P>(S single, P plannerWorkerReviewer,
                         boolean singleLlmRunCompleted, boolean plannerWorkerReviewerLlmRunCompleted) {
        boolean complete() {
            return single != null && plannerWorkerReviewer != null
                    && singleLlmRunCompleted && plannerWorkerReviewerLlmRunCompleted;
        }
    }

    record AttemptRecord<S, P>(int number, Path workspace, Attempt<S, P> outcome, String failure) {
        boolean complete() {
            return outcome != null && outcome.complete();
        }
    }

    record Result<S, P>(int maxAttempts, List<AttemptRecord<S, P>> attempts,
                       AttemptRecord<S, P> selectedPair) {
        Result {
            attempts = List.copyOf(attempts);
        }

        boolean valid() {
            return selectedPair != null && selectedPair.complete();
        }

        Optional<AttemptRecord<S, P>> validPair() {
            return Optional.ofNullable(selectedPair);
        }
    }
}
