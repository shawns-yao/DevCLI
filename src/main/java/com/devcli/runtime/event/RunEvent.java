package com.devcli.runtime.event;

import com.devcli.llm.LlmClient;
import com.devcli.tool.ToolPresentation;
import com.devcli.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Agent、Renderer 与 Runtime API 共享的强类型运行事件。
 */
public sealed interface RunEvent permits RunEvent.ThreadCreated, RunEvent.TurnStarted,
        RunEvent.ReasoningDelta, RunEvent.MessageDelta, RunEvent.ModelContext,
        RunEvent.ModelMessage, RunEvent.ModelUsage, RunEvent.ExecutionStateChanged,
        RunEvent.FailureGuidance,
        RunEvent.QueueUpdated, RunEvent.ToolCalls,
        RunEvent.SessionStateChanged, RunEvent.CustomMessage,
        RunEvent.ContextRefresh,
        RunEvent.ToolResults, RunEvent.HookInvocationStarted, RunEvent.HookInvocationCompleted,
        RunEvent.TurnCompleted, RunEvent.TurnFailed,
        RunEvent.TurnRejected, RunEvent.CheckpointCreated, RunEvent.CheckpointFailed,
        RunEvent.ContextCompacted {

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

    record ModelContext(int iteration, List<ModelMessageData> messages) implements RunEvent {
        public ModelContext {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }

        public static ModelContext from(int iteration, List<LlmClient.Message> messages) {
            if (messages == null || messages.isEmpty()) {
                return new ModelContext(iteration, List.of());
            }
            return new ModelContext(iteration, messages.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(ModelMessageData::from)
                    .toList());
        }

        public List<LlmClient.Message> toLlmMessages() {
            return messages.stream().map(ModelMessageData::toLlmMessage).toList();
        }

        @Override
        public String type() {
            return "model.context";
        }
    }

    record ModelMessage(ModelMessageData message) implements RunEvent {
        public ModelMessage {
            message = message == null
                    ? ModelMessageData.from(LlmClient.Message.internalUser(""))
                    : message;
        }

        public static ModelMessage from(LlmClient.Message message) {
            return new ModelMessage(ModelMessageData.from(message));
        }

        @Override
        public String type() {
            return "model.message";
        }
    }

    record ModelUsage(int inputTokens, int outputTokens, int cachedInputTokens,
                      double estimatedCostCny) implements RunEvent {
        public ModelUsage {
            inputTokens = Math.max(0, inputTokens);
            outputTokens = Math.max(0, outputTokens);
            cachedInputTokens = Math.max(0, cachedInputTokens);
            estimatedCostCny = Math.max(0D, estimatedCostCny);
        }

        @Override
        public String type() {
            return "model.usage";
        }
    }

    enum ExecutionState {
        THINKING,
        TOOL_EXECUTING,
        TOOL_RESULTS_PAIRED,
        STALE_CONTEXT,
        REFRESHING_CONTEXT,
        FAILED_RETRYABLE,
        COMPLETED,
        CANCELLED,
        BUDGET_EXCEEDED,
        ITERATION_LIMIT_REACHED,
        FAILED
    }

    enum ContextRefreshState {
        STALE_CONTEXT,
        REFRESHING_CONTEXT,
        RUNNING,
        FAILED_RETRYABLE
    }

    record ContextRefresh(String scope, ContextRefreshState state,
                          List<String> resources, String reason) implements RunEvent {
        public ContextRefresh {
            scope = text(scope);
            state = state == null ? ContextRefreshState.FAILED_RETRYABLE : state;
            resources = resources == null ? List.of() : List.copyOf(resources);
            reason = text(reason);
        }

        @Override
        public String type() {
            return "context.refresh";
        }
    }

    record ExecutionStateChanged(int iteration, ExecutionState state, String reason)
            implements RunEvent {
        public ExecutionStateChanged {
            iteration = Math.max(0, iteration);
            state = state == null ? ExecutionState.FAILED : state;
            reason = text(reason);
        }

        @Override
        public String type() {
            return "execution.state";
        }
    }

    record FailureAction(String type, String label, String instruction) {
        public FailureAction {
            type = text(type);
            label = text(label);
            instruction = text(instruction);
        }
    }

    record FailureGuidance(String category, String reason, String suggestion,
                           List<FailureAction> actions) implements RunEvent {
        public FailureGuidance {
            category = text(category);
            reason = text(reason);
            suggestion = text(suggestion);
            actions = actions == null ? List.of() : List.copyOf(actions);
        }

        @Override
        public String type() {
            return "failure.guidance";
        }
    }

    record QueueUpdated(String channel, int steeringPending, int followUpPending, String action) implements RunEvent {
        public QueueUpdated {
            channel = text(channel);
            action = text(action);
        }

        @Override
        public String type() {
            return "queue.updated";
        }
    }

    record SessionStateChanged(String sessionId, String state, String reason) implements RunEvent {
        public SessionStateChanged {
            sessionId = text(sessionId);
            state = text(state);
            reason = text(reason);
        }

        @Override
        public String type() {
            return "session.state";
        }
    }

    record CustomMessage(String messageType, String content, Map<String, String> attributes)
            implements RunEvent {
        public CustomMessage {
            messageType = text(messageType);
            content = text(content);
            attributes = attributes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(attributes));
        }

        public CustomMessage(String messageType, String content) {
            this(messageType, content, Map.of());
        }

        @Override
        public String type() {
            return "message.custom";
        }
    }

    record ToolCalls(List<ToolCallData> calls) implements RunEvent {
        public ToolCalls {
            calls = calls == null ? List.of() : List.copyOf(calls);
        }

        public static ToolCalls from(List<LlmClient.ToolCall> toolCalls) {
            return from(toolCalls, ToolPresentation::defaultFor);
        }

        public static ToolCalls from(
                List<LlmClient.ToolCall> toolCalls,
                Function<String, ToolPresentation> presentationResolver) {
            if (toolCalls == null || toolCalls.isEmpty()) return new ToolCalls(List.of());
            List<ToolCallData> values = new ArrayList<>(toolCalls.size());
            for (LlmClient.ToolCall toolCall : toolCalls) {
                if (toolCall == null || toolCall.function() == null) continue;
                values.add(new ToolCallData(
                        toolCall.id(),
                        toolCall.function().name(),
                        toolCall.function().arguments(),
                        presentationResolver == null
                                ? ToolPresentation.defaultFor(toolCall.function().name())
                                : presentationResolver.apply(toolCall.function().name())));
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
                        result.imageParts() == null ? 0 : result.imageParts().size(),
                        result.presentation()));
            }
            return new ToolResults(values);
        }

        @Override
        public String type() {
            return "tool.results";
        }
    }

    record HookInvocationStarted(
            String invocationId,
            String hookId,
            String hookEvent,
            String toolName) implements RunEvent {
        public HookInvocationStarted {
            invocationId = text(invocationId);
            hookId = text(hookId);
            hookEvent = text(hookEvent);
            toolName = text(toolName);
        }

        @Override
        public String type() {
            return "hook.call";
        }
    }

    record HookInvocationCompleted(
            String invocationId,
            String hookId,
            String hookEvent,
            String toolName,
            String status,
            String decision,
            long elapsedMillis,
            String error) implements RunEvent {
        public HookInvocationCompleted {
            invocationId = text(invocationId);
            hookId = text(hookId);
            hookEvent = text(hookEvent);
            toolName = text(toolName);
            status = text(status);
            decision = text(decision);
            elapsedMillis = Math.max(0, elapsedMillis);
            error = text(error);
        }

        @Override
        public String type() {
            return "hook.result";
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

    /** 持久化上下文压缩边界，供恢复时校验摘要来源链。 */
    record ContextCompacted(long sourceEventStart,
                            long sourceEventEnd,
                            String sourceHash,
                            String projectionHash,
                            String mode) implements RunEvent {
        public ContextCompacted {
            sourceEventStart = Math.max(0L, sourceEventStart);
            sourceEventEnd = Math.max(sourceEventStart, sourceEventEnd);
            sourceHash = text(sourceHash);
            projectionHash = text(projectionHash);
            mode = text(mode);
        }

        @Override
        public String type() {
            return "context.compacted";
        }
    }

    record ToolCallData(String id, String name, String argumentsJson,
                        ToolPresentation presentation) {
        public ToolCallData {
            id = text(id);
            name = text(name);
            argumentsJson = text(argumentsJson);
            presentation = presentation == null
                    ? ToolPresentation.defaultFor(name)
                    : presentation;
        }

        public ToolCallData(String id, String name, String argumentsJson) {
            this(id, name, argumentsJson, ToolPresentation.defaultFor(name));
        }
    }

    record ToolResultData(String id, String name, String argumentsJson, String result,
                          String status, String errorCode, boolean retryable,
                          long elapsedMillis, int imageCount, ToolPresentation presentation) {
        public ToolResultData {
            id = text(id);
            name = text(name);
            argumentsJson = text(argumentsJson);
            result = text(result);
            status = text(status);
            errorCode = text(errorCode);
            presentation = presentation == null
                    ? ToolPresentation.defaultFor(name)
                    : presentation;
        }

        public ToolResultData(String id, String name, String argumentsJson, String result,
                              String status, String errorCode, boolean retryable,
                              long elapsedMillis, int imageCount) {
            this(id, name, argumentsJson, result, status, errorCode, retryable,
                    elapsedMillis, imageCount, ToolPresentation.defaultFor(name));
        }
    }

    record ModelMessageData(
            String role,
            String source,
            String content,
            String reasoningContent,
            List<ToolCallData> toolCalls,
            String toolCallId,
            int imageCount) {
        public ModelMessageData {
            role = text(role);
            source = text(source);
            content = text(content);
            reasoningContent = text(reasoningContent);
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            toolCallId = text(toolCallId);
            imageCount = Math.max(0, imageCount);
        }

        public static ModelMessageData from(LlmClient.Message message) {
            LlmClient.Message value = message == null
                    ? LlmClient.Message.internalUser("")
                    : message;
            List<ToolCallData> calls = value.toolCalls() == null
                    ? List.of()
                    : value.toolCalls().stream()
                            .filter(java.util.Objects::nonNull)
                            .filter(call -> call.function() != null)
                            .map(call -> new ToolCallData(
                                    call.id(), call.function().name(),
                                    call.function().arguments()))
                            .toList();
            return new ModelMessageData(
                    value.role(),
                    value.source().name(),
                    value.content(),
                    value.reasoningContent(),
                    calls,
                    value.toolCallId(),
                    value.imagePartCount());
        }

        public LlmClient.Message toLlmMessage() {
            LlmClient.MessageSource parsedSource;
            try {
                parsedSource = LlmClient.MessageSource.valueOf(source);
            } catch (Exception ignored) {
                parsedSource = null;
            }
            List<LlmClient.ToolCall> calls = toolCalls.isEmpty()
                    ? null
                    : toolCalls.stream()
                            .map(call -> new LlmClient.ToolCall(
                                    call.id(),
                                    new LlmClient.ToolCall.Function(
                                            call.name(), call.argumentsJson())))
                            .toList();
            return new LlmClient.Message(
                    role,
                    content,
                    reasoningContent.isBlank() ? null : reasoningContent,
                    calls,
                    toolCallId.isBlank() ? null : toolCallId,
                    null,
                    parsedSource);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
