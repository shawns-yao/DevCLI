package com.devcli.trace;

import com.devcli.observability.RunTelemetry;
import com.devcli.runtime.CancellationContext;
import com.devcli.runtime.RunContext;

import java.util.UUID;

public record TraceContext(String traceId, String phase, RunTelemetry telemetry) {
    public TraceContext(String traceId, String phase) {
        this(traceId, phase, RunTelemetry.empty());
    }

    public TraceContext {
        traceId = traceId == null ? "" : traceId;
        phase = phase == null ? "" : phase;
        telemetry = telemetry == null ? RunTelemetry.empty() : telemetry;
    }

    public static TraceContext root(String phase) {
        RunContext run = CancellationContext.currentRun();
        String traceId = run == null ? UUID.randomUUID().toString() : run.telemetry().traceId();
        RunTelemetry telemetry = run == null
                ? RunTelemetry.empty().withTrace(traceId)
                : run.telemetry().withTrace(traceId);
        return new TraceContext(traceId, phase, telemetry);
    }
}
