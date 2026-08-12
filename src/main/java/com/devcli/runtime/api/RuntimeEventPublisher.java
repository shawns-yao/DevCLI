package com.devcli.runtime.api;

import com.devcli.runtime.event.RunEvent;
import com.devcli.runtime.event.RunEventSink;
import com.devcli.observability.RunEventEnvelope;
import com.devcli.observability.RunTelemetry;

import java.util.Objects;

/** 将强类型运行事件持久化到指定 Runtime thread/turn。 */
final class RuntimeEventPublisher implements RunEventSink {
    private final RuntimeThreadStore store;
    private final String threadId;
    private final String turnId;
    private final String runId;
    private boolean messageDeltaEmitted;

    RuntimeEventPublisher(RuntimeThreadStore store, String threadId, String turnId) {
        this(store, threadId, turnId, "");
    }

    RuntimeEventPublisher(RuntimeThreadStore store, String threadId, String turnId, String runId) {
        this.store = Objects.requireNonNull(store, "store");
        this.threadId = Objects.requireNonNullElse(threadId, "");
        this.turnId = Objects.requireNonNullElse(turnId, "");
        this.runId = Objects.requireNonNullElse(runId, "");
    }

    @Override
    public synchronized void emit(RunEvent event) {
        publish(event);
    }

    @Override
    public synchronized void emit(RunEventEnvelope envelope) {
        if (envelope == null) return;
        RunTelemetry context = envelope.context().merge(telemetry());
        publish(envelope.event(), context);
    }

    @Override
    public RunTelemetry telemetry() {
        return new RunTelemetry(runId, turnId, "", "", "", runId);
    }

    synchronized long publish(RunEvent event) {
        return publish(event, telemetry());
    }

    private long publish(RunEvent event, RunTelemetry context) {
        Objects.requireNonNull(event, "event");
        long eventId = store.appendEvent(
                threadId,
                event.type(),
                RunEventJsonCodec.encode(event, context));
        if (event instanceof RunEvent.MessageDelta) {
            messageDeltaEmitted = true;
        }
        return eventId;
    }

    synchronized boolean hasMessageDelta() {
        return messageDeltaEmitted;
    }
}
