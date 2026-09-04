package com.devcli.runtime.api;

import com.devcli.runtime.event.RunEvent;
import com.devcli.tool.ToolPresentation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Runtime API 运行事件的稳定 JSON 投影。 */
public final class RunEventJsonCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RunEventJsonCodec() {
    }

    public static String encode(RunEvent event, String turnId) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("schema_version", 2);
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
        } else if (event instanceof RunEvent.ModelContext context) {
            payload.put("iteration", context.iteration());
            ArrayNode messages = payload.putArray("messages");
            context.messages().forEach(message -> writeModelMessage(messages.addObject(), message));
        } else if (event instanceof RunEvent.ModelMessage message) {
            writeModelMessage(payload.putObject("message"), message.message());
        } else if (event instanceof RunEvent.ModelUsage usage) {
            payload.put("input_tokens", usage.inputTokens());
            payload.put("output_tokens", usage.outputTokens());
            payload.put("cached_input_tokens", usage.cachedInputTokens());
            payload.put("estimated_cost_cny", usage.estimatedCostCny());
        } else if (event instanceof RunEvent.ExecutionStateChanged state) {
            payload.put("iteration", state.iteration());
            payload.put("state", state.state().name());
            payload.put("reason", state.reason());
        } else if (event instanceof RunEvent.FailureGuidance guidance) {
            payload.put("category", guidance.category());
            payload.put("reason", guidance.reason());
            payload.put("suggestion", guidance.suggestion());
            ArrayNode actions = payload.putArray("actions");
            for (RunEvent.FailureAction action : guidance.actions()) {
                ObjectNode item = actions.addObject();
                item.put("type", action.type());
                item.put("label", action.label());
                item.put("instruction", action.instruction());
            }
        } else if (event instanceof RunEvent.ContextRefresh refresh) {
            payload.put("scope", refresh.scope());
            payload.put("state", refresh.state().name());
            payload.put("reason", refresh.reason());
            ArrayNode resources = payload.putArray("resources");
            refresh.resources().forEach(resources::add);
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
                writePresentation(item.putObject("presentation"), call.presentation());
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
                writePresentation(item.putObject("presentation"), result.presentation());
            }
        } else if (event instanceof RunEvent.HookInvocationStarted hook) {
            payload.put("invocation_id", hook.invocationId());
            payload.put("hook_id", hook.hookId());
            payload.put("hook_event", hook.hookEvent());
            payload.put("tool_name", hook.toolName());
        } else if (event instanceof RunEvent.HookInvocationCompleted hook) {
            payload.put("invocation_id", hook.invocationId());
            payload.put("hook_id", hook.hookId());
            payload.put("hook_event", hook.hookEvent());
            payload.put("tool_name", hook.toolName());
            payload.put("status", hook.status());
            payload.put("decision", hook.decision());
            payload.put("elapsed_millis", hook.elapsedMillis());
            payload.put("error", hook.error());
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
        } else if (event instanceof RunEvent.ContextCompacted compacted) {
            payload.put("source_event_start", compacted.sourceEventStart());
            payload.put("source_event_end", compacted.sourceEventEnd());
            payload.put("source_hash", compacted.sourceHash());
            payload.put("projection_hash", compacted.projectionHash());
            payload.put("mode", compacted.mode());
        } else {
            throw new IllegalArgumentException("不支持的运行事件: " + event.getClass().getName());
        }
        return payload.toString();
    }

    static Optional<RunEvent.ModelContext> decodeModelContext(String data) {
        if (data == null || data.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(data);
            JsonNode values = root.path("messages");
            if (!values.isArray()) {
                return Optional.empty();
            }
            List<RunEvent.ModelMessageData> messages = new ArrayList<>();
            for (JsonNode value : values) {
                messages.add(readModelMessage(value));
            }
            return Optional.of(new RunEvent.ModelContext(
                    root.path("iteration").asInt(0), messages));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    static Optional<RunEvent.ModelMessage> decodeModelMessage(String data) {
        if (data == null || data.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(data);
            JsonNode value = root.path("message");
            return value.isObject()
                    ? Optional.of(new RunEvent.ModelMessage(readModelMessage(value)))
                    : Optional.empty();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static void writeModelMessage(ObjectNode target, RunEvent.ModelMessageData message) {
        target.put("role", message.role());
        target.put("source", message.source());
        target.put("content", message.content());
        target.put("reasoning_content", message.reasoningContent());
        target.put("tool_call_id", message.toolCallId());
        target.put("image_count", message.imageCount());
        ArrayNode toolCalls = target.putArray("tool_calls");
        for (RunEvent.ToolCallData call : message.toolCalls()) {
            ObjectNode item = toolCalls.addObject();
            item.put("id", call.id());
            item.put("name", call.name());
            item.set("arguments", parseArguments(call.argumentsJson()));
        }
    }

    private static RunEvent.ModelMessageData readModelMessage(JsonNode value) {
        List<RunEvent.ToolCallData> calls = new ArrayList<>();
        JsonNode toolCalls = value.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode call : toolCalls) {
                calls.add(new RunEvent.ToolCallData(
                        call.path("id").asText(""),
                        call.path("name").asText(""),
                        argumentsJson(call.get("arguments"))));
            }
        }
        return new RunEvent.ModelMessageData(
                value.path("role").asText(""),
                value.path("source").asText(""),
                value.path("content").asText(""),
                value.path("reasoning_content").asText(""),
                calls,
                value.path("tool_call_id").asText(""),
                value.path("image_count").asInt(0));
    }

    private static void writePresentation(ObjectNode target, ToolPresentation presentation) {
        ToolPresentation value = presentation == null
                ? ToolPresentation.generic("")
                : presentation;
        target.put("kind", value.kind().name());
        target.put("title", value.title());
        target.put("primary_argument", value.primaryArgument());
        ObjectNode metadata = target.putObject("metadata");
        value.metadata().forEach(metadata::put);
    }

    static ToolPresentation readPresentation(JsonNode node, String toolName) {
        if (node == null || !node.isObject()) {
            return ToolPresentation.defaultFor(toolName);
        }
        ToolPresentation.Kind kind;
        try {
            kind = ToolPresentation.Kind.valueOf(node.path("kind").asText("GENERIC"));
        } catch (IllegalArgumentException ignored) {
            kind = ToolPresentation.Kind.GENERIC;
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        node.path("metadata").fields().forEachRemaining(entry ->
                metadata.put(entry.getKey(), entry.getValue().asText("")));
        return new ToolPresentation(
                kind,
                node.path("title").asText(toolName),
                node.path("primary_argument").asText(""),
                metadata);
    }

    private static String argumentsJson(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "{}";
        }
        return node.isTextual() ? node.asText() : node.toString();
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
