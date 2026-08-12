package com.devcli.observability;

import java.time.Instant;
import java.util.Map;

/** 专用追踪记录；不承担指标或审计职责。 */
public record TraceSpan(String name, RunTelemetry context, String parentSpanId,
                        Instant startedAt, Instant endedAt, String status,
                        Map<String, Object> attributes) {
    public TraceSpan {
        name = name == null ? "" : name;
        context = context == null ? RunTelemetry.empty() : context;
        parentSpanId = parentSpanId == null ? "" : parentSpanId;
        startedAt = startedAt == null ? Instant.now() : startedAt;
        endedAt = endedAt == null ? startedAt : endedAt;
        status = status == null ? "" : status;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public long durationMillis() {
        return Math.max(0, java.time.Duration.between(startedAt, endedAt).toMillis());
    }
}
