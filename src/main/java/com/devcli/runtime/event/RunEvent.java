package com.devcli.runtime.event;

import com.devcli.llm.LlmClient;
import com.devcli.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent、Renderer 与 Runtime API 共享的强类型运行事件。
 */
public sealed interface RunEvent permits RunEvent.ThreadCreated, RunEvent.TurnStarted,
        RunEvent.ReasoningDelta, RunEvent.MessageDelta, RunEvent.ToolCalls,
        RunEvent.ToolResults, RunEvent.TurnCompleted, RunEvent.TurnFailed,
        RunEvent.TurnRejected, RunEvent.CheckpointCreated, RunEvent.CheckpointFailed {

    String type();

    record ThreadCreated(String threadId) implements RunEvent {
        public ThreadCreated {
            threadId = text(threadId);
        }

        @Override
        public String type() {
            return "thread.created";
        }
    }

    record TurnStarted(String input) implements RunEvent {
        public TurnStarted {
            input = text(input);
        }

        @Override
        public String type() {
            return "turn.started";
        }
    }

    record ReasoningDelta(String content) implements RunEvent {
        public ReasoningDelta {
            content = text(content);
        }

        @Override
        public String type() {
            return "reasoning.delta";
        }
    }

    record MessageDelta(String content) implements RunEvent {
        public MessageDelta {
            content = text(content);
        }

        @Override
        public String type() {
            return "message.delta";
        }
    }

    record ToolCalls(List<ToolCallData> calls) implements RunEvent {
        public ToolCalls {
            calls = calls == null ? List.of() : List.copyOf(calls);
        }

        public static ToolCalls from(List<LlmClient.ToolCall> toolCalls) {
            if (toolCalls == null || toolCalls.isEmpty()) return new ToolCalls(List.of());
            List<ToolCallData> values = new ArrayList<>(toolCalls.size());
            for (LlmClient.ToolCall toolCall : toolCalls) {
                if (toolCall == null || toolCall.function() == null) continue;
                values.add(new ToolCallData(
                        toolCall.id(),
                        toolCall.function().name(),
                        toolCall.function().arguments()));
            }
            return new ToolCalls(values);
        }

        public List<LlmClient.ToolCall> toLlmToolCalls() {
            return calls.stream()
                    .map(call -> new LlmClient.ToolCall(
                            call.id(), new LlmClient.ToolCall.Function(
                                    call.name(), call.argumentsJson())))
                    .toList();
        }

        @Override
        public String type() {
            return "tool.calls";
        }
    }

    record ToolResults(List<ToolResultData> results) implements RunEvent {
        public ToolResults {
            results = results == null ? List.of() : List.copyOf(results);
        }

        public static ToolResults from(List<ToolRegistry.ToolExecutionResult> toolResults) {
            if (toolResults == null || toolResults.isEmpty()) return new ToolResults(List.of());
            List<ToolResultData> values = new ArrayList<>(toolResults.size());
            for (ToolRegistry.ToolExecutionResult result : toolResults) {
                if (result == null) continue;
                values.add(new ToolResultData(
                        result.id(),
                        result.name(),
                        result.argumentsJson(),
                        result.result(),
                        result.status() == null ? "" : result.status().name(),
                        result.errorCode() == null ? "" : result.errorCode().name(),
                        result.retryable(),
                        result.elapsedMillis(),
                        result.imageParts() == null ? 0 : result.imageParts().size()));
            }
            return new ToolResults(values);
        }

        @Override
        public String type() {
            return "tool.results";
        }
    }

    record TurnCompleted(String status) implements RunEvent {
        public TurnCompleted {
            status = status == null || status.isBlank() ? "completed" : status;
        }

        @Override
        public String type() {
            return "turn.completed";
        }
    }

    record TurnFailed(String error) implements RunEvent {
        public TurnFailed {
            error = text(error);
        }

        @Override
        public String type() {
            return "turn.failed";
        }
    }

    record TurnRejected(String error) implements RunEvent {
        public TurnRejected {
            error = text(error);
        }

        @Override
        public String type() {
            return "turn.rejected";
        }
    }

    record CheckpointCreated(long coveredThroughEventId, int preTokens,
                             int postTokens, String semanticGuard) implements RunEvent {
        public CheckpointCreated {
            semanticGuard = text(semanticGuard);
        }

        @Override
        public String type() {
            return "thread.checkpoint.created";
        }
    }

    record CheckpointFailed(long coveredThroughEventId, String error) implements RunEvent {
        public CheckpointFailed {
            error = text(error);
        }

        @Override
        public String type() {
            return "thread.checkpoint.failed";
        }
    }

    record ToolCallData(String id, String name, String argumentsJson) {
        public ToolCallData {
            id = text(id);
            name = text(name);
            argumentsJson = text(argumentsJson);
        }
    }

    record ToolResultData(String id, String name, String argumentsJson, String result,
                          String status, String errorCode, boolean retryable,
                          long elapsedMillis, int imageCount) {
        public ToolResultData {
            id = text(id);
            name = text(name);
            argumentsJson = text(argumentsJson);
            result = text(result);
            status = text(status);
            errorCode = text(errorCode);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
