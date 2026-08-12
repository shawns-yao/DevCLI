package com.devcli.render.state;

import com.devcli.observability.MetricRecorder;
import com.devcli.observability.RunEventEnvelope;
import com.devcli.observability.RunTelemetry;
import com.devcli.runtime.event.RunEvent;
import com.devcli.runtime.event.RunEventSink;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** RunEvent 到 RunSnapshot 的唯一有状态投影。 */
public final class RunProjection implements RunEventSink {
    private final MetricRecorder metrics;
    private RunSnapshot snapshot = RunSnapshot.empty();

    public RunProjection() {
        this(MetricRecorder.NO_OP);
    }

    public RunProjection(MetricRecorder metrics) {
        this.metrics = MetricRecorder.safe(metrics);
    }

    @Override
    public synchronized void emit(RunEvent event) {
        apply(RunEventEnvelope.of(snapshot.context(), snapshot.version() + 1, event));
    }

    @Override
    public synchronized void emit(RunEventEnvelope envelope) {
        if (envelope != null) apply(envelope);
    }

    @Override
    public synchronized RunTelemetry telemetry() {
        return snapshot.context();
    }

    public synchronized RunSnapshot snapshot() {
        return snapshot;
    }

    private void apply(RunEventEnvelope envelope) {
        RunEvent event = envelope.event();
        RunTelemetry context = envelope.context().merge(snapshot.context());
        String state = snapshot.state();
        String phase = snapshot.phase();
        String activity = snapshot.activity();
        long input = snapshot.inputTokens();
        long output = snapshot.outputTokens();
        long cached = snapshot.cachedInputTokens();
        long llmCalls = snapshot.llmCalls();
        long toolCalls = snapshot.toolCalls();
        String cost = snapshot.estimatedCost();
        String currency = snapshot.currency();
        String budget = snapshot.budgetDecision();
        String security = snapshot.securityDomain();
        String sandbox = snapshot.sandboxState();
        String retry = snapshot.retryState();
        String recovery = snapshot.recoveryState();
        String checkpoint = snapshot.checkpointRef();
        String sideSnapshot = snapshot.snapshotRef();
        List<String> transcript = new ArrayList<>(snapshot.transcript());
        Map<String, Long> counters = new LinkedHashMap<>(snapshot.metrics());

        if (event instanceof RunEvent.TurnStarted) {
            state = "running";
            phase = "turn";
            activity = "thinking";
        } else if (event instanceof RunEvent.ReasoningDelta reasoning) {
            activity = compact(reasoning.content());
        } else if (event instanceof RunEvent.MessageDelta message) {
            appendBounded(transcript, message.content());
            activity = "responding";
        } else if (event instanceof RunEvent.ToolCalls calls) {
            activity = calls.calls().isEmpty() ? "tool" : "tool " + calls.calls().get(0).name();
            toolCalls += calls.calls().size();
        } else if (event instanceof RunEvent.ToolResults results) {
            counters.merge("tool.results", (long) results.results().size(), Long::sum);
        } else if (event instanceof RunEvent.TurnCompleted completed) {
            state = completed.status();
            phase = "idle";
            activity = "";
        } else if (event instanceof RunEvent.TurnFailed failed) {
            state = "failed";
            activity = compact(failed.error());
        } else if (event instanceof RunEvent.TurnRejected rejected) {
            state = "rejected";
            activity = compact(rejected.error());
        } else if (event instanceof RunEvent.BudgetUsageUpdated usage) {
            input = usage.inputTokens();
            output = usage.outputTokens();
            cached = usage.cachedInputTokens();
            llmCalls = usage.llmCalls();
            toolCalls = Math.max(toolCalls, usage.toolCalls());
            cost = usage.estimatedCost();
            currency = usage.currency();
            budget = usage.decision();
            phase = usage.phase().isBlank() ? phase : usage.phase();
        } else if (event instanceof RunEvent.BudgetExhausted exhausted) {
            budget = "STOP";
            activity = compact(exhausted.reason());
        } else if (event instanceof RunEvent.LlmRequestCompleted) {
            counters.merge("llm.requests", 1L, Long::sum);
        } else if (event instanceof RunEvent.RetryScheduled scheduled) {
            retry = scheduled.kind() + ":" + scheduled.nextSequence();
            activity = "retry " + scheduled.scope();
        } else if (event instanceof RunEvent.AttemptStarted attempt) {
            context = new RunTelemetry(context.runId(), context.turnId(), context.stepId(),
                    context.agentId(), attempt.attemptId(), context.traceId());
        } else if (event instanceof RunEvent.AttemptFinished attempt) {
            retry = attempt.kind() + ":" + attempt.status();
        } else if (event instanceof RunEvent.RecoveryReconciled reconciled) {
            recovery = reconciled.decision();
            checkpoint = reconciled.checkpointRef();
        } else if (event instanceof RunEvent.SecurityDecisionMade decision) {
            security = decision.domain() + ":" + (decision.allowed() ? "allowed" : "denied");
        } else if (event instanceof RunEvent.SandboxExecution execution) {
            sandbox = execution.commandProfile() + ":" + execution.state();
        } else if (event instanceof RunEvent.CheckpointCreated created) {
            checkpoint = "event:" + created.coveredThroughEventId();
        } else if (event instanceof RunEvent.RecoveryReferenceUpdated reference) {
            checkpoint = referenceValue(reference.checkpointRef(), checkpoint);
            sideSnapshot = referenceValue(reference.snapshotRef(), sideSnapshot);
            recovery = reference.state();
        }

        counters.merge(event.type(), 1L, Long::sum);
        metrics.increment("devcli.run.event", Map.of("type", event.type()));
        snapshot = new RunSnapshot(
                Math.max(snapshot.version() + 1, envelope.sequence()), context, state, phase,
                activity, input, output, cached, llmCalls, toolCalls, cost, currency, budget,
                security, sandbox, retry, recovery, checkpoint, sideSnapshot,
                transcript, counters, Instant.now());
    }

    private static void appendBounded(List<String> transcript, String value) {
        String text = value == null ? "" : value;
        if (!text.isBlank()) transcript.add(text);
        while (transcript.size() > 64) transcript.remove(0);
    }

    private static String compact(String value) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return text.length() <= 160 ? text : text.substring(0, 157) + "...";
    }

    private static String choose(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String referenceValue(String value, String fallback) {
        return "<cleared>".equals(value) ? "" : choose(value, fallback);
    }
}
