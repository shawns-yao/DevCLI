package com.devcli.trace;

import java.util.UUID;

public record TraceContext(String traceId, String phase) {
    public static TraceContext root(String phase) {
        var run = com.devcli.concurrent.CancellationContext.currentRun();
        return new TraceContext(run == null ? UUID.randomUUID().toString() : run.runId(), phase);
    }
}
