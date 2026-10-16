package com.devcli.runtime.api;

import com.devcli.runtime.api.RuntimeThreadStore.SessionProjection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 标题、状态和用量的事件投影；不参与模型上下文重放。 */
final class SessionEventProjection {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final String logIdentity;
    private String title = "";
    private String state = "idle";
    private long inputTokens;
    private long outputTokens;
    private long cachedInputTokens;
    private double estimatedCostCny;
    private long toolCalls;
    private long toolFailures;
    private long hookCalls;
    private long hookFailures;

    SessionEventProjection(String logIdentity) {
        this.logIdentity = logIdentity;
    }

    static SessionEventProjection from(SessionProjection projection) {
        SessionEventProjection value = new SessionEventProjection(projection.logIdentity());
        value.title = projection.title();
        value.state = projection.state();
        value.inputTokens = projection.inputTokens();
        value.outputTokens = projection.outputTokens();
        value.cachedInputTokens = projection.cachedInputTokens();
        value.estimatedCostCny = projection.estimatedCostCny();
        value.toolCalls = projection.toolCalls();
        value.toolFailures = projection.toolFailures();
        value.hookCalls = projection.hookCalls();
        value.hookFailures = projection.hookFailures();
        return value;
    }

    void apply(RuntimeEvent event) {
        try {
            JsonNode data = MAPPER.readTree(event.data());
            switch (event.type()) {
                case "turn.started" -> {
                    if (title.isBlank()) {
                        title = projectionTitle(data.path("input").asText(""));
                    }
                    state = "running";
                }
                case "turn.completed" -> state = "idle";
                case "turn.failed" -> state = "failed";
                case "turn.rejected" -> state = "rejected";
                case "session.state" -> state = data.path("state").asText(state);
                case "model.usage" -> {
                    inputTokens += data.path("input_tokens").asLong(0);
                    outputTokens += data.path("output_tokens").asLong(0);
                    cachedInputTokens += data.path("cached_input_tokens").asLong(0);
                    estimatedCostCny += data.path("estimated_cost_cny").asDouble(0D);
                }
                case "tool.calls" -> toolCalls += data.path("calls").size();
                case "tool.results" -> {
                    JsonNode results = data.path("results");
                    if (results.isArray()) {
                        for (JsonNode result : results) {
                            if (!"SUCCESS".equals(result.path("status").asText(""))) {
                                toolFailures++;
                            }
                        }
                    }
                }
                case "hook.result" -> {
                    hookCalls++;
                    String decision = data.path("decision").asText("CONTINUE");
                    if (!"CONTINUE".equals(decision)) {
                        hookFailures++;
                    }
                }
                default -> { }
            }
        } catch (Exception ignored) {
            // 单条坏事件不破坏投影；事件日志仍保留以供诊断。
        }
    }

    SessionProjection finish(long cursor) {
        return new SessionProjection(
                RuntimeThreadStore.SESSION_PROJECTION_VERSION,
                logIdentity,
                cursor,
                title,
                state,
                inputTokens,
                outputTokens,
                cachedInputTokens,
                estimatedCostCny,
                toolCalls,
                toolFailures,
                hookCalls,
                hookFailures);
    }

    private static String projectionTitle(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String normalized = input.replaceAll("\\s+", " ").trim();
        int codePoints = normalized.codePointCount(0, normalized.length());
        if (codePoints <= 80) {
            return normalized;
        }
        return normalized.substring(0, normalized.offsetByCodePoints(0, 80)) + "...";
    }
}
