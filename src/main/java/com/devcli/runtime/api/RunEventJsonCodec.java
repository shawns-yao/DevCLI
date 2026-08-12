package com.devcli.runtime.api;

import com.devcli.runtime.event.RunEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Runtime API 运行事件的稳定 JSON 投影。 */
final class RunEventJsonCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RunEventJsonCodec() {
    }

    static String encode(RunEvent event, String turnId) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("schema_version", 1);
        if (turnId != null && !turnId.isBlank()) {
            payload.put("turn_id", turnId);
        }
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
            payload.put("run_id", budget.runId());
            payload.put("tier", budget.tier());
            payload.put("max_total_tokens", budget.maxTotalTokens());
            payload.put("max_llm_calls", budget.maxLlmCalls());
            payload.put("max_tool_calls", budget.maxToolCalls());
            payload.put("max_wall_clock_millis", budget.maxWallClockMillis());
            payload.put("max_estimated_cost", budget.maxEstimatedCost());
        } else if (event instanceof RunEvent.BudgetUsageUpdated budget) {
            payload.put("run_id", budget.runId());
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
            payload.put("run_id", budget.runId());
            payload.put("threshold", budget.threshold());
            payload.put("reason", budget.reason());
        } else if (event instanceof RunEvent.BudgetExhausted budget) {
            payload.put("run_id", budget.runId());
            payload.put("reason", budget.reason());
        } else if (event instanceof RunEvent.LlmRequestCompleted request) {
            payload.put("run_id", request.runId());
            payload.put("phase", request.phase());
            payload.put("agent", request.agent());
            payload.put("attempt", request.attempt());
            payload.put("provider", request.provider());
            payload.put("model", request.model());
            payload.put("input_tokens", request.inputTokens());
            payload.put("output_tokens", request.outputTokens());
            payload.put("cached_input_tokens", request.cachedInputTokens());
        } else if (event instanceof RunEvent.AttemptStarted attempt) {
            payload.put("run_id", attempt.runId());
            payload.put("attempt_id", attempt.attemptId());
            payload.put("parent_attempt_id", attempt.parentAttemptId());
            payload.put("kind", attempt.kind());
            payload.put("scope", attempt.scope());
            payload.put("reason", attempt.reason());
            payload.put("sequence", attempt.sequence());
            payload.put("backoff_millis", attempt.backoffMillis());
        } else if (event instanceof RunEvent.RetryScheduled retry) {
            payload.put("run_id", retry.runId());
            payload.put("kind", retry.kind());
            payload.put("scope", retry.scope());
            payload.put("reason", retry.reason());
            payload.put("next_sequence", retry.nextSequence());
            payload.put("backoff_millis", retry.backoffMillis());
        } else if (event instanceof RunEvent.AttemptFinished attempt) {
            payload.put("run_id", attempt.runId());
            payload.put("attempt_id", attempt.attemptId());
            payload.put("parent_attempt_id", attempt.parentAttemptId());
            payload.put("kind", attempt.kind());
            payload.put("scope", attempt.scope());
            payload.put("sequence", attempt.sequence());
            payload.put("status", attempt.status());
            payload.put("outcome", attempt.outcome());
        } else if (event instanceof RunEvent.RecoveryReconciled recovery) {
            payload.put("run_id", recovery.runId());
            payload.put("checkpoint_ref", recovery.checkpointRef());
            payload.put("patch_journal_action", recovery.patchJournalAction());
            payload.put("decision", recovery.decision());
            payload.put("reason", recovery.reason());
        } else if (event instanceof RunEvent.SecurityDecisionMade security) {
            payload.put("run_id", security.runId());
            payload.put("tool", security.tool());
            payload.put("domain", security.domain());
            payload.put("profile", security.profile());
            payload.put("allowed", security.allowed());
            payload.put("approval_required", security.approvalRequired());
            payload.put("reason", security.reason());
        } else if (event instanceof RunEvent.SandboxExecution sandbox) {
            payload.put("run_id", sandbox.runId());
            payload.put("command_profile", sandbox.commandProfile());
            payload.put("state", sandbox.state());
            payload.put("reason", sandbox.reason());
        } else {
            throw new IllegalArgumentException("不支持的运行事件: " + event.getClass().getName());
        }
        return payload.toString();
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
}
