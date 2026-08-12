package com.devcli.runtime.api;

import com.devcli.runtime.event.RunEvent;
import com.devcli.observability.RunTelemetry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/** Runtime API 运行事件的稳定 JSON 投影。 */
final class RunEventJsonCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RunEventJsonCodec() {
    }

    static String encode(RunEvent event, String turnId) {
        return encode(event, new RunTelemetry("", turnId, "", "", "", ""));
    }

    static String encode(RunEvent event, RunTelemetry telemetry) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("schema_version", 2);
        RunTelemetry context = telemetry == null ? RunTelemetry.empty() : telemetry;
        putIfPresent(payload, "run_id", context.runId());
        putIfPresent(payload, "turn_id", context.turnId());
        putIfPresent(payload, "step_id", context.stepId());
        putIfPresent(payload, "agent_id", context.agentId());
        putIfPresent(payload, "attempt_id", context.attemptId());
        putIfPresent(payload, "trace_id", context.traceId());
        if (event instanceof RunEvent.ThreadCreated created) {
            payload.put("thread_id", created.threadId());
        } else if (event instanceof RunEvent.TurnStarted started) {
            payload.put("input", started.input());
        } else if (event instanceof RunEvent.ReasoningDelta reasoning) {
            payload.put("content", reasoning.content());
        } else if (event instanceof RunEvent.MessageDelta message) {
            payload.put("content", message.content());
        } else if (event instanceof RunEvent.QueueUpdated queue) {
            payload.put("channel", queue.channel());
            payload.put("steering_pending", queue.steeringPending());
            payload.put("follow_up_pending", queue.followUpPending());
            payload.put("action", queue.action());
        } else if (event instanceof RunEvent.SessionStateChanged session) {
            payload.put("session_id", session.sessionId());
            payload.put("state", session.state());
            payload.put("reason", session.reason());
        } else if (event instanceof RunEvent.CustomMessage custom) {
            payload.put("message_type", custom.messageType());
            payload.put("content", custom.content());
            ObjectNode attributes = payload.putObject("attributes");
            custom.attributes().forEach(attributes::put);
        } else if (event instanceof RunEvent.ToolCalls toolCalls) {
            ArrayNode calls = payload.putArray("calls");
            for (RunEvent.ToolCallData call : toolCalls.calls()) {
                ObjectNode item = calls.addObject();
                item.put("id", call.id());
                item.put("name", call.name());
                item.set("arguments", parseArguments(call.argumentsJson()));
            }
        } else if (event instanceof RunEvent.ToolResults toolResults) {
            ArrayNode results = payload.putArray("results");
            for (RunEvent.ToolResultData result : toolResults.results()) {
                ObjectNode item = results.addObject();
                item.put("id", result.id());
                item.put("name", result.name());
                item.set("arguments", parseArguments(result.argumentsJson()));
                item.put("result", result.result());
                item.put("status", result.status());
                item.put("error_code", result.errorCode());
                item.put("retryable", result.retryable());
                item.put("elapsed_millis", result.elapsedMillis());
                item.put("image_count", result.imageCount());
            }
        } else if (event instanceof RunEvent.TurnCompleted completed) {
            payload.put("status", completed.status());
        } else if (event instanceof RunEvent.TurnFailed failed) {
            payload.put("error", failed.error());
        } else if (event instanceof RunEvent.TurnRejected rejected) {
            payload.put("error", rejected.error());
        } else if (event instanceof RunEvent.CheckpointCreated checkpoint) {
            payload.put("covered_through_event_id", checkpoint.coveredThroughEventId());
            payload.put("pre_tokens", checkpoint.preTokens());
            payload.put("post_tokens", checkpoint.postTokens());
            payload.put("semantic_guard", checkpoint.semanticGuard());
        } else if (event instanceof RunEvent.CheckpointFailed checkpoint) {
            payload.put("covered_through_event_id", checkpoint.coveredThroughEventId());
            payload.put("error", checkpoint.error());
        } else if (event instanceof RunEvent.BudgetConfigured budget) {
            putIfAbsent(payload, "run_id", budget.runId());
            payload.put("tier", budget.tier());
            payload.put("max_total_tokens", budget.maxTotalTokens());
            payload.put("max_llm_calls", budget.maxLlmCalls());
            payload.put("max_tool_calls", budget.maxToolCalls());
            payload.put("max_wall_clock_millis", budget.maxWallClockMillis());
            payload.put("max_estimated_cost", budget.maxEstimatedCost());
        } else if (event instanceof RunEvent.BudgetUsageUpdated budget) {
            putIfAbsent(payload, "run_id", budget.runId());
            payload.put("phase", budget.phase());
            payload.put("agent", budget.agent());
            payload.put("attempt", budget.attempt());
            payload.put("input_tokens", budget.inputTokens());
            payload.put("output_tokens", budget.outputTokens());
            payload.put("cached_input_tokens", budget.cachedInputTokens());
            payload.put("llm_calls", budget.llmCalls());
            payload.put("tool_calls", budget.toolCalls());
            payload.put("estimated_cost", budget.estimatedCost());
            payload.put("currency", budget.currency());
            payload.put("decision", budget.decision());
        } else if (event instanceof RunEvent.BudgetThresholdReached budget) {
            putIfAbsent(payload, "run_id", budget.runId());
            payload.put("threshold", budget.threshold());
            payload.put("reason", budget.reason());
        } else if (event instanceof RunEvent.BudgetExhausted budget) {
            putIfAbsent(payload, "run_id", budget.runId());
            payload.put("reason", budget.reason());
        } else if (event instanceof RunEvent.LlmRequestCompleted request) {
            putIfAbsent(payload, "run_id", request.runId());
            payload.put("phase", request.phase());
            payload.put("agent", request.agent());
            payload.put("attempt", request.attempt());
            payload.put("provider", request.provider());
            payload.put("model", request.model());
            payload.put("input_tokens", request.inputTokens());
            payload.put("output_tokens", request.outputTokens());
            payload.put("cached_input_tokens", request.cachedInputTokens());
        } else if (event instanceof RunEvent.AttemptStarted attempt) {
            putIfAbsent(payload, "run_id", attempt.runId());
            putIfAbsent(payload, "attempt_id", attempt.attemptId());
            payload.put("parent_attempt_id", attempt.parentAttemptId());
            payload.put("kind", attempt.kind());
            payload.put("scope", attempt.scope());
            payload.put("reason", attempt.reason());
            payload.put("sequence", attempt.sequence());
            payload.put("backoff_millis", attempt.backoffMillis());
        } else if (event instanceof RunEvent.RetryScheduled retry) {
            putIfAbsent(payload, "run_id", retry.runId());
            payload.put("kind", retry.kind());
            payload.put("scope", retry.scope());
            payload.put("reason", retry.reason());
            payload.put("next_sequence", retry.nextSequence());
            payload.put("backoff_millis", retry.backoffMillis());
        } else if (event instanceof RunEvent.AttemptFinished attempt) {
            putIfAbsent(payload, "run_id", attempt.runId());
            putIfAbsent(payload, "attempt_id", attempt.attemptId());
            payload.put("parent_attempt_id", attempt.parentAttemptId());
            payload.put("kind", attempt.kind());
            payload.put("scope", attempt.scope());
            payload.put("sequence", attempt.sequence());
            payload.put("status", attempt.status());
            payload.put("outcome", attempt.outcome());
        } else if (event instanceof RunEvent.RecoveryReconciled recovery) {
            putIfAbsent(payload, "run_id", recovery.runId());
            payload.put("checkpoint_ref", recovery.checkpointRef());
            payload.put("patch_journal_action", recovery.patchJournalAction());
            payload.put("decision", recovery.decision());
            payload.put("reason", recovery.reason());
        } else if (event instanceof RunEvent.SecurityDecisionMade security) {
            putIfAbsent(payload, "run_id", security.runId());
            payload.put("tool", security.tool());
            payload.put("domain", security.domain());
            payload.put("profile", security.profile());
            payload.put("allowed", security.allowed());
            payload.put("approval_required", security.approvalRequired());
            payload.put("reason", security.reason());
        } else if (event instanceof RunEvent.SandboxExecution sandbox) {
            putIfAbsent(payload, "run_id", sandbox.runId());
            payload.put("command_profile", sandbox.commandProfile());
            payload.put("state", sandbox.state());
            payload.put("reason", sandbox.reason());
        } else if (event instanceof RunEvent.RecoveryReferenceUpdated reference) {
            putIfAbsent(payload, "run_id", reference.runId());
            payload.put("checkpoint_ref", reference.checkpointRef());
            payload.put("patch_journal_ref", reference.patchJournalRef());
            payload.put("snapshot_ref", reference.snapshotRef());
            payload.put("state", reference.state());
        } else {
            throw new IllegalArgumentException("不支持的运行事件: " + event.getClass().getName());
        }
        return payload.toString();
    }

    private static void putIfPresent(ObjectNode payload, String name, String value) {
        if (value != null && !value.isBlank()) payload.put(name, value);
    }

    private static void putIfAbsent(ObjectNode payload, String name, String value) {
        if (!payload.has(name)) putIfPresent(payload, name, value);
    }

    private static JsonNode parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(argumentsJson);
        } catch (Exception ignored) {
            return MAPPER.getNodeFactory().textNode(argumentsJson);
        }
    }

    static RunTelemetry telemetry(String data) {
        try {
            JsonNode payload = MAPPER.readTree(data == null ? "{}" : data);
            return new RunTelemetry(
                    payload.path("run_id").asText(""), payload.path("turn_id").asText(""),
                    payload.path("step_id").asText(""), payload.path("agent_id").asText(""),
                    payload.path("attempt_id").asText(""), payload.path("trace_id").asText(""));
        } catch (Exception ignored) {
            return RunTelemetry.empty();
        }
    }

    static RunEvent decode(String type, String data) throws java.io.IOException {
        JsonNode payload = MAPPER.readTree(data == null ? "{}" : data);
        return switch (type == null ? "" : type) {
            case "thread.created" -> new RunEvent.ThreadCreated(payload.path("thread_id").asText(""));
            case "turn.started" -> new RunEvent.TurnStarted(payload.path("input").asText(""));
            case "reasoning.delta" -> new RunEvent.ReasoningDelta(payload.path("content").asText(""));
            case "message.delta" -> new RunEvent.MessageDelta(payload.path("content").asText(""));
            case "turn.completed" -> new RunEvent.TurnCompleted(payload.path("status").asText("completed"));
            case "turn.failed" -> new RunEvent.TurnFailed(payload.path("error").asText(""));
            case "turn.rejected" -> new RunEvent.TurnRejected(payload.path("error").asText(""));
            case "session.state" -> new RunEvent.SessionStateChanged(
                    payload.path("session_id").asText(""), payload.path("state").asText(""),
                    payload.path("reason").asText(""));
            case "queue.updated" -> new RunEvent.QueueUpdated(
                    payload.path("channel").asText(""), payload.path("steering_pending").asInt(),
                    payload.path("follow_up_pending").asInt(), payload.path("action").asText(""));
            case "message.custom" -> decodeCustomMessage(payload);
            case "tool.calls" -> decodeToolCalls(payload);
            case "tool.results" -> decodeToolResults(payload);
            case "budget.configured" -> new RunEvent.BudgetConfigured(
                    payload.path("run_id").asText(""), payload.path("tier").asText(""),
                    payload.path("max_total_tokens").asLong(), payload.path("max_llm_calls").asLong(),
                    payload.path("max_tool_calls").asLong(), payload.path("max_wall_clock_millis").asLong(),
                    payload.path("max_estimated_cost").asText(""));
            case "budget.usage.updated" -> new RunEvent.BudgetUsageUpdated(
                    payload.path("run_id").asText(""), payload.path("phase").asText(""),
                    payload.path("agent").asText(""), payload.path("attempt").asText(""),
                    payload.path("input_tokens").asLong(), payload.path("output_tokens").asLong(),
                    payload.path("cached_input_tokens").asLong(), payload.path("llm_calls").asLong(),
                    payload.path("tool_calls").asLong(), payload.path("estimated_cost").asText(""),
                    payload.path("currency").asText(""), payload.path("decision").asText(""));
            case "budget.exhausted" -> new RunEvent.BudgetExhausted(
                    payload.path("run_id").asText(""), payload.path("reason").asText(""));
            case "budget.threshold.reached" -> new RunEvent.BudgetThresholdReached(
                    payload.path("run_id").asText(""), payload.path("threshold").asText(""),
                    payload.path("reason").asText(""));
            case "llm.request.completed" -> new RunEvent.LlmRequestCompleted(
                    payload.path("run_id").asText(""), payload.path("phase").asText(""),
                    payload.path("agent").asText(""), payload.path("attempt").asText(""),
                    payload.path("provider").asText(""), payload.path("model").asText(""),
                    payload.path("input_tokens").asLong(), payload.path("output_tokens").asLong(),
                    payload.path("cached_input_tokens").asLong());
            case "retry.scheduled" -> new RunEvent.RetryScheduled(
                    payload.path("run_id").asText(""), payload.path("kind").asText(""),
                    payload.path("scope").asText(""), payload.path("reason").asText(""),
                    payload.path("next_sequence").asInt(), payload.path("backoff_millis").asLong());
            case "attempt.started" -> new RunEvent.AttemptStarted(
                    payload.path("run_id").asText(""), payload.path("attempt_id").asText(""),
                    payload.path("parent_attempt_id").asText(""), payload.path("kind").asText(""),
                    payload.path("scope").asText(""), payload.path("reason").asText(""),
                    payload.path("sequence").asInt(), payload.path("backoff_millis").asLong());
            case "attempt.finished" -> new RunEvent.AttemptFinished(
                    payload.path("run_id").asText(""), payload.path("attempt_id").asText(""),
                    payload.path("parent_attempt_id").asText(""), payload.path("kind").asText(""),
                    payload.path("scope").asText(""), payload.path("sequence").asInt(),
                    payload.path("status").asText(""), payload.path("outcome").asText(""));
            case "recovery.reconciled" -> new RunEvent.RecoveryReconciled(
                    payload.path("run_id").asText(""), payload.path("checkpoint_ref").asText(""),
                    payload.path("patch_journal_action").asText(""), payload.path("decision").asText(""),
                    payload.path("reason").asText(""));
            case "security.decision" -> new RunEvent.SecurityDecisionMade(
                    payload.path("run_id").asText(""), payload.path("tool").asText(""),
                    payload.path("domain").asText(""), payload.path("profile").asText(""),
                    payload.path("allowed").asBoolean(), payload.path("approval_required").asBoolean(),
                    payload.path("reason").asText(""));
            case "sandbox.execution" -> new RunEvent.SandboxExecution(
                    payload.path("run_id").asText(""), payload.path("command_profile").asText(""),
                    payload.path("state").asText(""), payload.path("reason").asText(""));
            case "thread.checkpoint.created" -> new RunEvent.CheckpointCreated(
                    payload.path("covered_through_event_id").asLong(), payload.path("pre_tokens").asInt(),
                    payload.path("post_tokens").asInt(), payload.path("semantic_guard").asText(""));
            case "thread.checkpoint.failed" -> new RunEvent.CheckpointFailed(
                    payload.path("covered_through_event_id").asLong(), payload.path("error").asText(""));
            case "recovery.reference.updated" -> new RunEvent.RecoveryReferenceUpdated(
                    payload.path("run_id").asText(""), payload.path("checkpoint_ref").asText(""),
                    payload.path("patch_journal_ref").asText(""), payload.path("snapshot_ref").asText(""),
                    payload.path("state").asText(""));
            default -> new RunEvent.CustomMessage(type, payload.path("content").asText(""));
        };
    }

    private static RunEvent.CustomMessage decodeCustomMessage(JsonNode payload) {
        java.util.Map<String, String> attributes = new java.util.LinkedHashMap<>();
        payload.path("attributes").fields().forEachRemaining(entry ->
                attributes.put(entry.getKey(), entry.getValue().asText("")));
        return new RunEvent.CustomMessage(payload.path("message_type").asText(""),
                payload.path("content").asText(""), attributes);
    }

    private static RunEvent.ToolCalls decodeToolCalls(JsonNode payload) {
        List<RunEvent.ToolCallData> calls = new ArrayList<>();
        for (JsonNode item : payload.path("calls")) {
            calls.add(new RunEvent.ToolCallData(item.path("id").asText(""),
                    item.path("name").asText(""), item.path("arguments").toString()));
        }
        return new RunEvent.ToolCalls(calls);
    }

    private static RunEvent.ToolResults decodeToolResults(JsonNode payload) {
        List<RunEvent.ToolResultData> results = new ArrayList<>();
        for (JsonNode item : payload.path("results")) {
            results.add(new RunEvent.ToolResultData(
                    item.path("id").asText(""), item.path("name").asText(""),
                    item.path("arguments").toString(), item.path("result").asText(""),
                    item.path("status").asText(""), item.path("error_code").asText(""),
                    item.path("retryable").asBoolean(), item.path("elapsed_millis").asLong(),
                    item.path("image_count").asInt()));
        }
        return new RunEvent.ToolResults(results);
    }
}
