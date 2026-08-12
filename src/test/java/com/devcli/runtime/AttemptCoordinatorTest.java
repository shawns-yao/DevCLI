package com.devcli.runtime;

import com.devcli.runtime.event.RunEvent;
import com.devcli.runtime.store.AttemptStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AttemptCoordinatorTest {
    @Test
    void correctionAttemptIsDistinctFromInfrastructureRetry() {
        List<RunEvent> events = new ArrayList<>();
        List<AttemptPersistence.AttemptData> persisted = new ArrayList<>();
        List<AttemptStatus> outcomes = new ArrayList<>();
        AttemptCoordinator coordinator = new AttemptCoordinator(
                "run_1", events::add, new AttemptPersistence() {
            @Override
            public void started(AttemptData attempt) {
                persisted.add(attempt);
            }

            @Override
            public void finished(String attemptId, AttemptStatus status, String outcome) {
                outcomes.add(status);
            }
        }, "run_1:attempt:1");

        coordinator.scheduled(AttemptKind.INFRASTRUCTURE_RETRY,
                "worker", "rate_limited", 2, 500);
        try (AttemptCoordinator.AttemptScope correction = coordinator.start(
                AttemptKind.CORRECTION, "step_1", "reviewer_rejected", 1, 0)) {
            correction.complete("approved");
        }

        RunEvent.RetryScheduled scheduled = (RunEvent.RetryScheduled) events.get(0);
        RunEvent.AttemptStarted started = (RunEvent.AttemptStarted) events.get(1);
        RunEvent.AttemptFinished finished = (RunEvent.AttemptFinished) events.get(2);
        assertEquals("INFRASTRUCTURE_RETRY", scheduled.kind());
        assertEquals("CORRECTION", started.kind());
        assertEquals("CORRECTION", finished.kind());
        assertEquals("COMPLETED", finished.status());
        assertEquals("run_1:attempt:1", started.parentAttemptId());
        assertEquals(AttemptKind.CORRECTION, persisted.getFirst().kind());
        assertEquals(AttemptStatus.COMPLETED, outcomes.getFirst());
    }
}
