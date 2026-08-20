package com.devcli.agent;

import com.devcli.hook.HookDispatcher;
import com.devcli.hook.HookLifecycle;
import com.devcli.context.TokenUsageFormatter;
import com.devcli.llm.LlmClient;
import com.devcli.llm.SamplingRequestCoordinator;
import com.devcli.runtime.CancellationContext;
import com.devcli.runtime.RunContext;
import com.devcli.runtime.event.RunEvent;
import com.devcli.runtime.event.RunEventSink;
import com.devcli.runtime.event.RunEventStreamListener;
import com.devcli.tool.ToolRegistry;
import com.devcli.tool.ToolPresentation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * ReAct、Plan task 和 SubAgent 共用的单轮 LLM/工具循环。
 */
final class AgentExecutionEngine<R> {

    interface Delegate<R> {
        List<LlmClient.Message> history();

        List<LlmClient.Tool> toolDefinitions(int iteration);

        LlmClient.StreamListener streamListener();

        default RunEventSink eventSink() {
            return RunEventSink.NO_OP;
        }

        default LlmClient.ToolChoice toolChoice(int iteration) {
            return LlmClient.ToolChoice.AUTO;
        }

        default ToolPresentation toolPresentation(String toolName) {
            return ToolPresentation.defaultFor(toolName);
        }

        default int maxIterations() {
            return Integer.MAX_VALUE;
        }

        default boolean isCancelled() {
            return CancellationContext.isCancelled();
        }

        default List<AgentTurnInbox.Item> drainSteeringMessages() {
            return List.of();
        }

        default List<AgentTurnInbox.Item> drainFollowUpMessages() {
            return List.of();
        }

        default void queuedMessagesDelivered(AgentTurnInbox.Channel channel,
                                             List<AgentTurnInbox.Item> messages) {
        }

        void beforeIteration(int iteration, AgentBudget budget);

        default void afterResponse(LlmClient.ChatResponse response, int iteration, AgentBudget budget) {
        }

        default LlmClient.ChatResponse normalizeResponse(
                LlmClient.ChatResponse response, int iteration, AgentBudget budget) {
            return response;
        }

        default String retryInstructionAfterResponseWithoutTools(
                LlmClient.ChatResponse response, int iteration, AgentBudget budget) {
            return "";
        }

        default void beforeToolExecution(LlmClient.ChatResponse response, int iteration,
                                         AgentBudget budget) {
        }

        List<ToolRegistry.ToolExecutionResult> executeTools(List<LlmClient.ToolCall> toolCalls,
                                                            int iteration);

        default void afterToolResults(LlmClient.ChatResponse response,
                                      List<ToolRegistry.ToolExecutionResult> toolResults,
                                      int iteration,
                                      AgentBudget budget) {
        }

        /** 返回工具证据触发的确定性内部纠偏指令；空串表示无需追加。 */
        default String instructionAfterToolResults(
                LlmClient.ChatResponse response,
                List<ToolRegistry.ToolExecutionResult> toolResults,
                int iteration,
                AgentBudget budget) {
            return "";
        }

        default Optional<R> completedAfterToolResults(
                LlmClient.ChatResponse response,
                List<ToolRegistry.ToolExecutionResult> toolResults,
                int iteration,
                AgentBudget budget) {
            return Optional.empty();
        }

        R completed(LlmClient.ChatResponse response, AgentBudget budget);

        R cancelled(AgentBudget budget);

        R budgetExceeded(AgentBudget.ExitReason reason, AgentBudget budget);

        R iterationLimitReached(AgentBudget budget);

        R failed(IOException error, AgentBudget budget);
    }

    private final LlmClient llmClient;
    private final AgentBudget budget;
    private final HookLifecycle hookLifecycle;
    private final SamplingRequestCoordinator samplingRequests;
    private final RepeatToolAdvisor repeatToolAdvisor;
    private final ContextReferenceGuard.ReferenceRegistry contextReferenceRegistry;
    private final String engineId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);

    AgentExecutionEngine(LlmClient llmClient, AgentBudget budget) {
        this(llmClient, budget, null, SamplingRequestCoordinator.shared());
    }

    AgentExecutionEngine(LlmClient llmClient, AgentBudget budget, HookLifecycle hookLifecycle) {
        this(llmClient, budget, hookLifecycle, SamplingRequestCoordinator.shared());
    }

    AgentExecutionEngine(LlmClient llmClient, AgentBudget budget, HookLifecycle hookLifecycle,
                         SamplingRequestCoordinator samplingRequests) {
        this(llmClient, budget, hookLifecycle, samplingRequests,
                new ContextReferenceGuard.ReferenceRegistry());
    }

    AgentExecutionEngine(LlmClient llmClient, AgentBudget budget, HookLifecycle hookLifecycle,
                         ContextReferenceGuard.ReferenceRegistry contextReferenceRegistry) {
        this(llmClient, budget, hookLifecycle, SamplingRequestCoordinator.shared(), contextReferenceRegistry);
    }

    AgentExecutionEngine(LlmClient llmClient, AgentBudget budget, HookLifecycle hookLifecycle,
                         SamplingRequestCoordinator samplingRequests,
                         ContextReferenceGuard.ReferenceRegistry contextReferenceRegistry) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.hookLifecycle = hookLifecycle;
        this.samplingRequests = Objects.requireNonNull(samplingRequests, "samplingRequests");
        this.contextReferenceRegistry = Objects.requireNonNullElseGet(
                contextReferenceRegistry, ContextReferenceGuard.ReferenceRegistry::new);
        this.repeatToolAdvisor = RepeatToolAdvisor.fromSystemProperties();
    }

    R run(Delegate<R> delegate) {
        Objects.requireNonNull(delegate, "delegate");
        if (hookLifecycle == null || hookLifecycle.isEmpty()) {
            return runLoop(delegate);
        }
        R result = null;
        HookDispatcher.HookExecutionException hookFailure = null;
        Throwable primaryFailure = null;
        try {
            hookLifecycle.bindEventSink(delegate.eventSink());
            hookLifecycle.startAgent();
            result = runLoop(delegate);
        } catch (HookDispatcher.HookExecutionException e) {
            hookFailure = e;
        } catch (RuntimeException | Error e) {
            primaryFailure = e;
        }
        try {
            hookLifecycle.endAgent();
        } catch (HookDispatcher.HookExecutionException e) {
            if (hookFailure == null) {
                hookFailure = e;
            } else {
                hookFailure.addSuppressed(e);
            }
        }
        if (primaryFailure instanceof RuntimeException runtimeException) {
            if (hookFailure != null) runtimeException.addSuppressed(hookFailure);
            throw runtimeException;
        }
        if (primaryFailure instanceof Error error) {
            if (hookFailure != null) error.addSuppressed(hookFailure);
            throw error;
        }
        if (hookFailure != null) {
            return delegate.failed(new IOException(hookFailure.getMessage(), hookFailure), budget);
        }
        return result;
    }

    private R runLoop(Delegate<R> delegate) {
        ContextReferenceGuard contextReferenceGuard = ContextReferenceGuard.fromHistory(
                delegate.history(), contextReferenceRegistry);
        while (true) {
            RunEventSink eventSink = RunEventSink.composite(
                    delegate.eventSink(),
                    RunEventSink.fromStreamListener(delegate.streamListener()));
            if (delegate.isCancelled()) {
                emitState(eventSink, RunEvent.ExecutionState.CANCELLED,
                        budget.iteration(), "运行已取消");
                return delegate.cancelled(budget);
            }
            if (budget.iteration() >= delegate.maxIterations()) {
                emitState(eventSink, RunEvent.ExecutionState.ITERATION_LIMIT_REACHED,
                        budget.iteration(), "达到当前执行入口的迭代上限");
                return delegate.iterationLimitReached(budget);
            }
            AgentBudget.ExitReason exitReason = budget.check();
            if (exitReason != AgentBudget.ExitReason.WITHIN_BUDGET) {
                String reason = budget.describeExit(exitReason);
                emitState(eventSink, RunEvent.ExecutionState.BUDGET_EXCEEDED,
                        budget.iteration(), reason);
                emitCircuitBreaker(eventSink, exitReason, reason);
                return delegate.budgetExceeded(exitReason, budget);
            }

            int iteration = budget.beginIteration();
            if (hookLifecycle != null) {
                hookLifecycle.startTurn(iteration);
            }
            delegate.beforeIteration(iteration, budget);

            try {
                emitState(eventSink, RunEvent.ExecutionState.THINKING,
                        iteration, "正在请求模型生成下一步动作");
                LlmClient.ChatResponse response;
                try (SamplingRequestCoordinator.RequestScope ignored =
                             samplingRequests.begin(samplingRequestId(iteration))) {
                    eventSink.emit(RunEvent.ModelContext.from(
                            iteration, List.copyOf(delegate.history())));
                    LlmClient.ToolChoice requestedToolChoice = delegate.toolChoice(iteration);
                    response = llmClient.chat(
                            delegate.history(),
                            delegate.toolDefinitions(iteration),
                            new RunEventStreamListener(eventSink),
                            contextReferenceGuard.toolChoice(requestedToolChoice));
                }
                if (delegate.isCancelled()) {
                    emitState(eventSink, RunEvent.ExecutionState.CANCELLED,
                            iteration, "模型响应后检测到运行取消");
                    return delegate.cancelled(budget);
                }

                budget.recordTokens(
                        response.inputTokens(),
                        response.outputTokens(),
                        response.cachedInputTokens());
                eventSink.emit(new RunEvent.ModelUsage(
                        response.inputTokens(),
                        response.outputTokens(),
                        response.cachedInputTokens(),
                        TokenUsageFormatter.estimatedCostCnyValue(
                                llmClient,
                                response.inputTokens(),
                                response.outputTokens(),
                                response.cachedInputTokens())));
                delegate.afterResponse(response, iteration, budget);
                response = Objects.requireNonNullElse(
                        delegate.normalizeResponse(response, iteration, budget), response);
                if (hookLifecycle != null) {
                    hookLifecycle.assistantMessageCompleted(
                            iteration,
                            response.toolCalls() == null ? 0 : response.toolCalls().size());
                }

                if (response.hasToolCalls()) {
                    budget.recordToolCalls(response.toolCalls());
                    LlmClient.Message assistantMessage = LlmClient.Message.assistant(
                            response.reasoningContent(),
                            response.content(),
                            response.toolCalls());
                    delegate.history().add(assistantMessage);
                    eventSink.emit(RunEvent.ModelMessage.from(assistantMessage));
                    delegate.beforeToolExecution(response, iteration, budget);
                    eventSink.emit(RunEvent.ToolCalls.from(
                            response.toolCalls(), delegate::toolPresentation));
                    if (hookLifecycle != null) {
                        hookLifecycle.toolExecutionsStarted(iteration, response.toolCalls());
                    }

                    emitState(eventSink, RunEvent.ExecutionState.TOOL_EXECUTING,
                            iteration, response.toolCalls().size() + " 个工具调用开始执行");
                    List<ToolRegistry.ToolExecutionResult> returnedResults = delegate.executeTools(
                            response.toolCalls(), iteration);
                    ToolResultReconciler.Reconciliation reconciliation =
                            ToolResultReconciler.reconcile(
                                    response.toolCalls(), returnedResults, delegate::toolPresentation);
                    List<ToolRegistry.ToolExecutionResult> toolResults = reconciliation.results();
                    emitPairingIssues(eventSink, reconciliation.issues());
                    emitState(eventSink, RunEvent.ExecutionState.TOOL_RESULTS_PAIRED,
                            iteration, toolResults.size() + " 个工具结果已按原调用顺序对账");
                    for (ToolRegistry.ToolExecutionResult toolResult : toolResults) {
                        budget.recordToolResult(toolResult);
                        LlmClient.Message toolMessage = LlmClient.Message.tool(
                                toolResult.id(), toolResult.result());
                        delegate.history().add(toolMessage);
                        eventSink.emit(RunEvent.ModelMessage.from(toolMessage));
                    }
                    eventSink.emit(RunEvent.ToolResults.from(toolResults));
                    if (hookLifecycle != null) {
                        hookLifecycle.toolResultsReceived(iteration, toolResults);
                    }
                    delegate.afterToolResults(response, toolResults, iteration, budget);
                    String toolResultInstruction = delegate.instructionAfterToolResults(
                            response, toolResults, iteration, budget);
                    if (toolResultInstruction != null && !toolResultInstruction.isBlank()) {
                        LlmClient.Message instructionMessage =
                                LlmClient.Message.internalUser(toolResultInstruction.trim());
                        delegate.history().add(instructionMessage);
                        eventSink.emit(RunEvent.ModelMessage.from(instructionMessage));
                    }
                    contextReferenceGuard.observe(toolResults);
                    String referenceFailure = contextReferenceGuard.terminalFailure();
                    if (!referenceFailure.isBlank()) {
                        emitState(eventSink, RunEvent.ExecutionState.FAILED, iteration, referenceFailure);
                        return delegate.failed(new IOException(referenceFailure), budget);
                    }
                    Optional<R> completed = contextReferenceGuard.isSatisfied()
                            ? delegate.completedAfterToolResults(response, toolResults, iteration, budget)
                            : Optional.empty();
                    if (completed.isPresent()) {
                        emitState(eventSink, RunEvent.ExecutionState.COMPLETED,
                                iteration, "工具结果满足当前执行入口的完成条件");
                        return completed.get();
                    }
                    deliverQueuedMessages(delegate, eventSink, AgentTurnInbox.Channel.STEERING,
                            delegate.drainSteeringMessages());
                    injectRepeatToolReminders(delegate, eventSink, toolResults);
                    continue;
                }

                String retryInstruction = combineRetryInstructions(
                        contextReferenceGuard.retryInstruction(),
                        delegate.retryInstructionAfterResponseWithoutTools(response, iteration, budget));
                if (retryInstruction != null && !retryInstruction.isBlank()) {
                    LlmClient.Message assistantMessage = LlmClient.Message.assistant(
                            response.reasoningContent(), response.content());
                    LlmClient.Message retryMessage = LlmClient.Message.internalUser(retryInstruction);
                    delegate.history().add(assistantMessage);
                    delegate.history().add(retryMessage);
                    eventSink.emit(RunEvent.ModelMessage.from(assistantMessage));
                    eventSink.emit(RunEvent.ModelMessage.from(retryMessage));
                    continue;
                }

                LlmClient.Message assistantMessage = LlmClient.Message.assistant(
                        response.reasoningContent(), response.content());
                delegate.history().add(assistantMessage);
                eventSink.emit(RunEvent.ModelMessage.from(assistantMessage));
                if (deliverQueuedMessages(delegate, eventSink, AgentTurnInbox.Channel.STEERING,
                        delegate.drainSteeringMessages())) {
                    continue;
                }
                if (deliverQueuedMessages(delegate, eventSink, AgentTurnInbox.Channel.FOLLOW_UP,
                        delegate.drainFollowUpMessages())) {
                    continue;
                }
                emitState(eventSink, RunEvent.ExecutionState.COMPLETED,
                        iteration, "模型返回最终答复");
                return delegate.completed(response, budget);
            } catch (IOException e) {
                emitState(eventSink, RunEvent.ExecutionState.FAILED,
                        iteration, e.getMessage());
                return delegate.failed(e, budget);
            }
        }
    }

    private static String combineRetryInstructions(String first, String second) {
        String left = first == null ? "" : first.trim();
        String right = second == null ? "" : second.trim();
        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty()) {
            return left;
        }
        return left + "\n\n" + right;
    }

    private String samplingRequestId(int iteration) {
        RunContext runContext = CancellationContext.currentRun();
        String runId = runContext == null ? "local" : runContext.runId();
        return runId + ":engine_" + engineId + ":iteration_" + iteration;
    }

    /**
     * 工具结果返回后观察连续重复调用：达到阈值时注入 advisory 提醒（不阻断、不改写），
     * 并暂缓停滞检测退出，把自我纠正机会留给提醒；超过最大阈值后停止暂缓，由
     * {@link AgentBudget} 的停滞检测作为最终兜底。
     */
    private void injectRepeatToolReminders(Delegate<R> delegate, RunEventSink eventSink,
                                           List<ToolRegistry.ToolExecutionResult> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return;
        }
        List<RepeatToolAdvisor.Reminder> reminders = new ArrayList<>();
        for (ToolRegistry.ToolExecutionResult toolResult : toolResults) {
            RepeatToolAdvisor.Reminder reminder = repeatToolAdvisor.observeAndMaybeRemind(toolResult);
            if (reminder != null) {
                reminders.add(reminder);
            }
        }
        if (!reminders.isEmpty()) {
            String text = joinReminders(reminders);
            LlmClient.Message reminderMessage = LlmClient.Message.plugin(text);
            delegate.history().add(reminderMessage);
            eventSink.emit(RunEvent.ModelMessage.from(reminderMessage));
            RepeatToolAdvisor.Reminder first = reminders.get(0);
            eventSink.emit(new RunEvent.CustomMessage(
                    "repeat_tool_reminder",
                    text,
                    Map.of("tool", first.toolName(),
                            "consecutive", String.valueOf(first.consecutiveCount()),
                            "gentle", String.valueOf(first.gentle()),
                            "action", "ADVISORY")));
            eventSink.emit(new RunEvent.CustomMessage(
                    "tool_loop_guard",
                    text,
                    Map.of("tool", first.toolName(),
                            "consecutive", String.valueOf(first.consecutiveCount()),
                            "action", "ADVISORY")));
        }
        if (repeatToolAdvisor.suspendsStagnationExit()) {
            budget.resetStagnation();
        }
    }

    private static String joinReminders(List<RepeatToolAdvisor.Reminder> reminders) {
        StringBuilder sb = new StringBuilder();
        for (RepeatToolAdvisor.Reminder reminder : reminders) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(reminder.text());
        }
        return sb.toString();
    }

    private void emitCircuitBreaker(RunEventSink eventSink,
                                    AgentBudget.ExitReason exitReason,
                                    String reason) {
        if (exitReason != AgentBudget.ExitReason.STAGNATION_DETECTED
                && exitReason != AgentBudget.ExitReason.REPEATED_TOOL_ERROR) {
            return;
        }
        eventSink.emit(new RunEvent.CustomMessage(
                "tool_loop_guard",
                reason,
                Map.of("action", "CIRCUIT_BREAKER",
                        "reason", exitReason.name(),
                        "window", String.valueOf(budget.stagnationWindow()))));
    }

    private static void emitPairingIssues(
            RunEventSink eventSink, List<ToolResultReconciler.Issue> issues) {
        if (issues == null || issues.isEmpty()) {
            return;
        }
        String codes = issues.stream()
                .map(ToolResultReconciler.Issue::code)
                .distinct()
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        String content = issues.stream()
                .map(issue -> issue.code() + "[" + issue.toolCallId() + "]: " + issue.detail())
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
        eventSink.emit(new RunEvent.CustomMessage(
                "tool_result_pairing_anomaly",
                content,
                Map.of("count", String.valueOf(issues.size()), "codes", codes)));
    }

    private static void emitState(RunEventSink eventSink,
                                  RunEvent.ExecutionState state,
                                  int iteration,
                                  String reason) {
        eventSink.emit(new RunEvent.ExecutionStateChanged(iteration, state, reason));
    }

    private boolean deliverQueuedMessages(Delegate<R> delegate,
                                          RunEventSink eventSink,
                                          AgentTurnInbox.Channel channel,
                                          List<AgentTurnInbox.Item> items) {
        if (items == null || items.isEmpty()) {
            return false;
        }
        for (AgentTurnInbox.Item item : items) {
            LlmClient.Message message = channel == AgentTurnInbox.Channel.STEERING
                    ? LlmClient.Message.steering(item.text())
                    : LlmClient.Message.followUp(item.text());
            delegate.history().add(message);
            eventSink.emit(RunEvent.ModelMessage.from(message));
        }
        delegate.queuedMessagesDelivered(channel, List.copyOf(items));
        return true;
    }
}
