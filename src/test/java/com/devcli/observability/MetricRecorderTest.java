package com.devcli.observability;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class MetricRecorderTest {
    @Test
    void recorderFailureNeverChangesBusinessOutcome() {
        MetricRecorder recorder = MetricRecorder.safe((name, value, unit, attributes) -> {
            throw new IllegalStateException("metrics unavailable");
        });
        assertDoesNotThrow(() -> recorder.increment("devcli.run.event", Map.of("type", "turn.started")));
    }
}
