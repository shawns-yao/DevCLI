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
        } else if (event instanceof RunEvent.ExecutionStateChanged state) {
            payload.put("iteration", state.iteration());
            payload.put("state", state.state().name());
            payload.put("reason", state.reason());
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
