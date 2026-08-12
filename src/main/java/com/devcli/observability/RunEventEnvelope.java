package com.devcli.observability;

import com.devcli.runtime.event.RunEvent;

import java.time.Instant;

/** 带稳定关联字段的事件信封；RunEvent 保持业务载荷。 */
public record RunEventEnvelope(int schemaVersion, RunTelemetry context,
                               long sequence, Instant occurredAt, RunEvent event) {
    public static final int CURRENT_SCHEMA_VERSION = 2;

    public RunEventEnvelope {
        schemaVersion = Math.max(1, schemaVersion);
        context = context == null ? RunTelemetry.empty() : context;
        sequence = Math.max(0, sequence);
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        if (event == null) throw new IllegalArgumentException("event is required");
    }

    public static RunEventEnvelope of(RunTelemetry context, long sequence, RunEvent event) {
        return new RunEventEnvelope(CURRENT_SCHEMA_VERSION, context, sequence, Instant.now(), event);
    }

    public RunEventEnvelope withContext(RunTelemetry value) {
        return new RunEventEnvelope(schemaVersion, value, sequence, occurredAt, event);
    }
}
