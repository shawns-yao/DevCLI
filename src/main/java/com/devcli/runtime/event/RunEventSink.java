package com.devcli.runtime.event;

import com.devcli.llm.LlmClient;
import com.devcli.observability.RunEventEnvelope;
import com.devcli.observability.RunTelemetry;

import java.util.Arrays;
import java.util.List;

/** 接收强类型运行事件的最小出口。 */
@FunctionalInterface
public interface RunEventSink {
    RunEventSink NO_OP = event -> { };

    void emit(RunEvent event);

    default void emit(RunEventEnvelope envelope) {
        if (envelope != null) emit(envelope.event());
    }

    default RunTelemetry telemetry() {
        return RunTelemetry.empty();
    }

    static RunEventSink composite(RunEventSink... sinks) {
        if (sinks == null || sinks.length == 0) return NO_OP;
        List<RunEventSink> active = Arrays.stream(sinks)
                .filter(sink -> sink != null && sink != NO_OP)
                .toList();
        if (active.isEmpty()) return NO_OP;
        if (active.size() == 1) return active.get(0);
        return new RunEventSink() {
            @Override
            public void emit(RunEvent event) {
                for (RunEventSink sink : active) sink.emit(event);
            }

            @Override
            public void emit(RunEventEnvelope envelope) {
                for (RunEventSink sink : active) sink.emit(envelope);
            }
        };
    }

    static RunEventSink contextual(RunEventSink delegate, RunTelemetry context) {
        RunEventSink target = delegate == null ? NO_OP : delegate;
        RunTelemetry telemetry = context == null ? RunTelemetry.empty() : context;
        return new RunEventSink() {
            @Override
            public void emit(RunEvent event) {
                target.emit(RunEventEnvelope.of(telemetry, 0, event));
            }

            @Override
            public void emit(RunEventEnvelope envelope) {
                if (envelope == null) return;
                target.emit(new RunEventEnvelope(
                        envelope.schemaVersion(), envelope.context().merge(telemetry),
                        envelope.sequence(), envelope.occurredAt(), envelope.event()));
            }

            @Override
            public RunTelemetry telemetry() {
                return telemetry;
            }
        };
    }

    /** 单个事件追加更细粒度的 step/agent/attempt 关联。 */
    static void emit(RunEventSink sink, RunTelemetry context, RunEvent event) {
        RunEventSink target = sink == null ? NO_OP : sink;
        target.emit(RunEventEnvelope.of(context, 0, event));
    }

    static RunEventSink fromStreamListener(LlmClient.StreamListener listener) {
        if (listener == null || listener == LlmClient.StreamListener.NO_OP) return NO_OP;
        return event -> {
            if (event instanceof RunEvent.ReasoningDelta reasoning) {
                listener.onReasoningDelta(reasoning.content());
            } else if (event instanceof RunEvent.MessageDelta message) {
                listener.onContentDelta(message.content());
            }
        };
    }
}
