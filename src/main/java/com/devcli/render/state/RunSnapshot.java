package com.devcli.render.state;

import com.devcli.observability.RunTelemetry;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Renderer 和 Runtime 查询共用的不可变运行快照。 */
public record RunSnapshot(long version, RunTelemetry context, String state, String phase,
                          String activity, long inputTokens, long outputTokens,
                          long cachedInputTokens, long llmCalls, long toolCalls,
                          String estimatedCost, String currency, String budgetDecision,
                          String securityDomain, String sandboxState, String retryState,
                          String recoveryState, String checkpointRef, String snapshotRef,
                          List<String> transcript, Map<String, Long> metrics,
                          @JsonSerialize(using = ToStringSerializer.class) Instant updatedAt) {
    public RunSnapshot {
        version = Math.max(0, version);
        context = context == null ? RunTelemetry.empty() : context;
        state = text(state);
        phase = text(phase);
        activity = text(activity);
        estimatedCost = text(estimatedCost);
        currency = text(currency);
        budgetDecision = text(budgetDecision);
        securityDomain = text(securityDomain);
        sandboxState = text(sandboxState);
        retryState = text(retryState);
        recoveryState = text(recoveryState);
        checkpointRef = text(checkpointRef);
        snapshotRef = text(snapshotRef);
        transcript = transcript == null ? List.of() : List.copyOf(transcript);
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    public static RunSnapshot empty() {
        return new RunSnapshot(0, RunTelemetry.empty(), "idle", "idle", "",
                0, 0, 0, 0, 0, "", "", "", "", "", "", "",
                "", "", List.of(), Map.of(), Instant.now());
    }

    public long totalTokens() {
        return Math.max(0, inputTokens) + Math.max(0, outputTokens);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
