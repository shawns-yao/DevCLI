package com.devcli.runtime.event;

import com.devcli.llm.LlmClient;
import com.devcli.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent、Renderer 与 Runtime API 共享的强类型运行事件。
 */
public sealed interface RunEvent permits RunEvent.ThreadCreated, RunEvent.TurnStarted,
        RunEvent.ReasoningDelta, RunEvent.MessageDelta, RunEvent.QueueUpdated, RunEvent.ToolCalls,
        RunEvent.SessionStateChanged, RunEvent.CustomMessage,
        RunEvent.ToolResults, RunEvent.TurnCompleted, RunEvent.TurnFailed,
        RunEvent.TurnRejected, RunEvent.CheckpointCreated, RunEvent.CheckpointFailed,
        RunEvent.BudgetConfigured, RunEvent.BudgetUsageUpdated,
        RunEvent.BudgetThresholdReached, RunEvent.BudgetExhausted,
        RunEvent.LlmRequestCompleted, RunEvent.AttemptStarted,
        RunEvent.RetryScheduled, RunEvent.AttemptFinished, RunEvent.RecoveryReconciled,
        RunEvent.SecurityDecisionMade, RunEvent.SandboxExecution,
        RunEvent.RecoveryReferenceUpdated {

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

    record BudgetConfigured(String runId, String tier, long maxTotalTokens,
                            long maxLlmCalls, long maxToolCalls, long maxWallClockMillis,
                            String maxEstimatedCost) implements RunEvent {
        public BudgetConfigured {
            runId = text(runId);
            tier = text(tier);
            maxEstimatedCost = text(maxEstimatedCost);
        }

        @Override
        public String type() { return "budget.configured"; }
    }

    record BudgetUsageUpdated(String runId, String phase, String agent, String attempt,
                              long inputTokens, long outputTokens, long cachedInputTokens,
                              long llmCalls, long toolCalls, String estimatedCost,
                              String currency, String decision) implements RunEvent {
        public BudgetUsageUpdated {
            runId = text(runId);
            phase = text(phase);
            agent = text(agent);
            attempt = text(attempt);
            estimatedCost = text(estimatedCost);
            currency = text(currency);
            decision = text(decision);
        }

        @Override
        public String type() { return "budget.usage.updated"; }
    }

    record BudgetThresholdReached(String runId, String threshold, String reason)
            implements RunEvent {
        public BudgetThresholdReached {
            runId = text(runId);
            threshold = text(threshold);
            reason = text(reason);
        }

        @Override
        public String type() { return "budget.threshold.reached"; }
    }

    record BudgetExhausted(String runId, String reason) implements RunEvent {
        public BudgetExhausted {
            runId = text(runId);
            reason = text(reason);
        }

        @Override
        public String type() { return "budget.exhausted"; }
    }

    record LlmRequestCompleted(String runId, String phase, String agent, String attempt,
                               String provider, String model, long inputTokens,
                               long outputTokens, long cachedInputTokens) implements RunEvent {
        public LlmRequestCompleted {
            runId = text(runId);
            phase = text(phase);
            agent = text(agent);
            attempt = text(attempt);
            provider = text(provider);
            model = text(model);
        }

        @Override
        public String type() { return "llm.request.completed"; }
    }

    record AttemptStarted(String runId, String attemptId, String parentAttemptId,
                          String kind, String scope,
                          String reason, int sequence, long backoffMillis) implements RunEvent {
        public AttemptStarted {
            runId = text(runId);
            attemptId = text(attemptId);
            parentAttemptId = text(parentAttemptId);
            kind = text(kind);
            scope = text(scope);
            reason = text(reason);
            sequence = Math.max(1, sequence);
            backoffMillis = Math.max(0, backoffMillis);
        }

        @Override
        public String type() { return "attempt.started"; }
    }

    record RetryScheduled(String runId, String kind, String scope, String reason,
                          int nextSequence, long backoffMillis) implements RunEvent {
        public RetryScheduled {
            runId = text(runId);
            kind = text(kind);
            scope = text(scope);
            reason = text(reason);
            nextSequence = Math.max(1, nextSequence);
            backoffMillis = Math.max(0, backoffMillis);
        }

        @Override
        public String type() { return "retry.scheduled"; }
    }

    record AttemptFinished(String runId, String attemptId, String parentAttemptId,
                           String kind, String scope,
                           int sequence, String status, String outcome) implements RunEvent {
        public AttemptFinished {
            runId = text(runId);
            attemptId = text(attemptId);
            parentAttemptId = text(parentAttemptId);
            kind = text(kind);
            scope = text(scope);
            sequence = Math.max(1, sequence);
            status = text(status);
            outcome = text(outcome);
        }

        @Override
        public String type() { return "attempt.finished"; }
    }

    record RecoveryReconciled(String runId, String checkpointRef,
                              String patchJournalAction, String decision,
                              String reason) implements RunEvent {
        public RecoveryReconciled {
            runId = text(runId);
            checkpointRef = text(checkpointRef);
            patchJournalAction = text(patchJournalAction);
            decision = text(decision);
            reason = text(reason);
        }

        @Override
        public String type() { return "recovery.reconciled"; }
    }

    record SecurityDecisionMade(String runId, String tool, String domain,
                                String profile, boolean allowed,
                                boolean approvalRequired, String reason) implements RunEvent {
        public SecurityDecisionMade {
            runId = text(runId);
            tool = text(tool);
            domain = text(domain);
            profile = text(profile);
            reason = text(reason);
        }

        @Override
        public String type() { return "security.decision"; }
    }

    record SandboxExecution(String runId, String commandProfile,
                            String state, String reason) implements RunEvent {
        public SandboxExecution {
            runId = text(runId);
            commandProfile = text(commandProfile);
            state = text(state);
            reason = text(reason);
        }

        @Override
        public String type() { return "sandbox.execution"; }
    }

    record RecoveryReferenceUpdated(String runId, String checkpointRef,
                                    String patchJournalRef, String snapshotRef,
                                    String state) implements RunEvent {
        public RecoveryReferenceUpdated {
            runId = text(runId);
            checkpointRef = text(checkpointRef);
            patchJournalRef = text(patchJournalRef);
            snapshotRef = text(snapshotRef);
            state = text(state);
        }

        @Override
        public String type() { return "recovery.reference.updated"; }
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
