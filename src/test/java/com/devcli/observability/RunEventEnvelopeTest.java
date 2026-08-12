package com.devcli.observability;

import com.devcli.runtime.event.RunEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RunEventEnvelopeTest {
    @Test
    void mergesSpecificContextWithoutLosingRunCorrelation() {
        RunTelemetry run = new RunTelemetry("run", "turn", "", "", "attempt", "trace");
        RunTelemetry step = new RunTelemetry("", "", "step", "worker", "", "");
        RunEventEnvelope envelope = RunEventEnvelope.of(step.merge(run), 3,
                new RunEvent.TurnCompleted("completed"));
        assertEquals("run", envelope.context().runId());
        assertEquals("step", envelope.context().stepId());
        assertEquals("worker", envelope.context().agentId());
        assertEquals("attempt", envelope.context().attemptId());
    }
}
