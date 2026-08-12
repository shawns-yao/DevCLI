package com.devcli.observability;

import com.devcli.render.state.RunProjection;
import com.devcli.render.state.RunSnapshot;
import com.devcli.runtime.event.RunEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunProjectionTest {
    @Test
    void projectsCorrelatedBudgetSecurityRetryAndRecoveryState() {
        RunProjection projection = new RunProjection();
        RunTelemetry context = new RunTelemetry(
                "run-1", "turn-1", "step-1", "worker-1", "attempt-1", "trace-1");

        projection.emit(new RunEventEnvelope(2, context, 1, Instant.now(),
                new RunEvent.TurnStarted("task")));
        projection.emit(new RunEventEnvelope(2, context, 2, Instant.now(),
                new RunEvent.BudgetUsageUpdated("run-1", "worker", "worker-1", "attempt-1",
                        100, 20, 50, 2, 3, "0.01", "USD", "CONTINUE")));
        projection.emit(new RunEventEnvelope(2, context, 3, Instant.now(),
                new RunEvent.SecurityDecisionMade("run-1", "write_file", "project-patch",
                        "isolated", true, false, "allowed")));
        projection.emit(new RunEventEnvelope(2, context, 4, Instant.now(),
                new RunEvent.RetryScheduled("run-1", "INFRASTRUCTURE", "llm", "timeout", 2, 500)));
        projection.emit(new RunEventEnvelope(2, context, 5, Instant.now(),
                new RunEvent.RecoveryReferenceUpdated("run-1", "checkpoint-1", "journal-1",
                        "snapshot-1", "saved")));

        RunSnapshot snapshot = projection.snapshot();
        assertEquals("run-1", snapshot.context().runId());
        assertEquals(120, snapshot.totalTokens());
        assertEquals("project-patch:allowed", snapshot.securityDomain());
        assertEquals("INFRASTRUCTURE:2", snapshot.retryState());
        assertEquals("checkpoint-1", snapshot.checkpointRef());
        assertEquals("snapshot-1", snapshot.snapshotRef());
        assertTrue(snapshot.metrics().containsKey("budget.usage.updated"));
    }
}
