package com.devcli.observability;

import java.util.Objects;

/** run/turn/step/agent/attempt 的统一关联上下文。 */
public record RunTelemetry(String runId, String turnId, String stepId,
                           String agentId, String attemptId, String traceId) {
    public RunTelemetry {
        runId = text(runId);
        turnId = text(turnId);
        stepId = text(stepId);
        agentId = text(agentId);
        attemptId = text(attemptId);
        traceId = text(traceId);
    }

    public static RunTelemetry empty() {
        return new RunTelemetry("", "", "", "", "", "");
    }

    public RunTelemetry merge(RunTelemetry fallback) {
        RunTelemetry other = Objects.requireNonNullElse(fallback, empty());
        return new RunTelemetry(
                choose(runId, other.runId()), choose(turnId, other.turnId()),
                choose(stepId, other.stepId()), choose(agentId, other.agentId()),
                choose(attemptId, other.attemptId()), choose(traceId, other.traceId()));
    }

    public RunTelemetry withTurn(String value) {
        return new RunTelemetry(runId, value, stepId, agentId, attemptId, traceId);
    }

    public RunTelemetry withTrace(String value) {
        return new RunTelemetry(runId, turnId, stepId, agentId, attemptId, value);
    }

    private static String choose(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
