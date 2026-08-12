package com.devcli.agent;

import com.devcli.budget.PricingCatalog;
import com.devcli.budget.RunBudget;
import com.devcli.budget.RunBudgetPolicy;
import com.devcli.hook.HookDispatcher;
import com.devcli.hook.HookLifecycle;
import com.devcli.llm.LlmClient;
import com.devcli.llm.LlmBudgetContext;
import com.devcli.llm.SamplingRequestCoordinator;
import com.devcli.runtime.CancellationContext;
import com.devcli.runtime.RunContext;
import com.devcli.runtime.event.RunEvent;
import com.devcli.runtime.event.RunEventSink;
import com.devcli.runtime.event.RunEventStreamListener;
import com.devcli.tool.ToolRegistry;

import java.io.IOException;
import java.util.List;
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
    private final RunBudget runBudget;
    private final String budgetPhase;
    private final String budgetAgent;
    private final String budgetAttempt;
    private final String engineId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);

    AgentExecutionEngine(LlmClient llmClient, AgentBudget budget) {
        this(llmClient, budget, null, SamplingRequestCoordinator.shared(), null,
                "agent", "agent", "attempt-1");
    }

    AgentExecutionEngine(LlmClient llmClient, AgentBudget budget, HookLifecycle hookLifecycle) {
        this(llmClient, budget, hookLifecycle, SamplingRequestCoordinator.shared(), null,
                "agent", "agent", "attempt-1");
    }

    AgentExecutionEngine(LlmClient llmClient, AgentBudget budget, HookLifecycle hookLifecycle,
                         SamplingRequestCoordinator samplingRequests) {
        this(llmClient, budget, hookLifecycle, samplingRequests, null,
                "agent", "agent", "attempt-1");
    }

    AgentExecutionEngine(LlmClient llmClient, AgentBudget budget, HookLifecycle hookLifecycle,
                         SamplingRequestCoordinator samplingRequests, RunBudget runBudget,
                         String budgetPhase, String budgetAgent, String budgetAttempt) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.hookLifecycle = hookLifecycle;
        this.samplingRequests = Objects.requireNonNull(samplingRequests, "samplingRequests");
        this.runBudget = runBudget == null ? resolveRunBudget() : runBudget;
        this.budgetPhase = text(budgetPhase, "agent");
        this.budgetAgent = text(budgetAgent, "agent");
        this.budgetAttempt = text(budgetAttempt, "attempt-1");
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
        while (true) {
            if (delegate.isCancelled()) {
                return delegate.cancelled(budget);
            }
            if (budget.iteration() >= delegate.maxIterations()) {
                return delegate.iterationLimitReached(budget);
            }
            AgentBudget.ExitReason exitReason = budget.check();
            if (exitReason != AgentBudget.ExitReason.WITHIN_BUDGET) {
                return delegate.budgetExceeded(exitReason, budget);
            }

            int iteration = budget.beginIteration();
            if (hookLifecycle != null) {
                hookLifecycle.startTurn(iteration);
            }
            delegate.beforeIteration(iteration, budget);

            RunBudget.Admission logicalAdmission = null;
            try {
                RunEventSink eventSink = RunEventSink.composite(
                        delegate.eventSink(),
                        RunEventSink.fromStreamListener(delegate.streamListener()));
                LlmClient.ChatResponse response;
                List<LlmClient.Tool> toolDefinitions = delegate.toolDefinitions(iteration);
                long requestReservation = estimateRequestReservation(delegate.history());
                try (LlmBudgetContext.Scope budgetScope = LlmBudgetContext.open(
                        runBudget, budgetPhase, budgetAgent,
                        budgetAttempt + "-iteration-" + iteration, requestReservation);
                     SamplingRequestCoordinator.RequestScope ignored =
                             samplingRequests.begin(samplingRequestId(iteration))) {
                    if (!budgetScope.allowed()) {
                        eventSink.emit(new RunEvent.BudgetExhausted(
                                runBudget.runId(), budgetScope.denialReason()));
                        return delegate.budgetExceeded(
                                AgentBudget.ExitReason.RUN_BUDGET_EXCEEDED, budget);
                    }
                    logicalAdmission = budgetScope.firstAdmission();
                    response = llmClient.chat(
                            delegate.history(),
                            toolDefinitions,
                            new RunEventStreamListener(eventSink),
                            delegate.toolChoice(iteration));
                    budgetScope.recordUsage(
                            llmClient.getProviderName(), llmClient.getModelName(),
                            response.inputTokens(), response.outputTokens(), response.cachedInputTokens());
                }
                if (delegate.isCancelled()) {
                    return delegate.cancelled(budget);
                }

                budget.recordTokens(
                        response.inputTokens(),
                        response.outputTokens(),
                        response.cachedInputTokens());
                eventSink.emit(new RunEvent.LlmRequestCompleted(
                        runBudget.runId(), budgetPhase, budgetAgent, budgetAttempt,
                        llmClient.getProviderName(), llmClient.getModelName(),
                        response.inputTokens(), response.outputTokens(), response.cachedInputTokens()));
                eventSink.emit(runBudget.usageEvent(budgetPhase, budgetAgent, budgetAttempt));
                delegate.afterResponse(response, iteration, budget);
                response = Objects.requireNonNullElse(
                        delegate.normalizeResponse(response, iteration, budget), response);
                if (hookLifecycle != null) {
                    hookLifecycle.assistantMessageCompleted(
                            iteration,
                            response.toolCalls() == null ? 0 : response.toolCalls().size());
                }

                if (response.hasToolCalls()) {
                    if (!runBudget.tryRecordToolCalls(response.toolCalls().size())) {
                        eventSink.emit(new RunEvent.BudgetExhausted(
                                runBudget.runId(), "tool_call_limit"));
                        return delegate.budgetExceeded(AgentBudget.ExitReason.RUN_BUDGET_EXCEEDED, budget);
                    }
                    eventSink.emit(runBudget.usageEvent(budgetPhase, budgetAgent, budgetAttempt));
                    budget.recordToolCalls(response.toolCalls());
                    delegate.history().add(LlmClient.Message.assistant(
                            response.reasoningContent(),
                            response.content(),
                            response.toolCalls()));
                    delegate.beforeToolExecution(response, iteration, budget);
                    eventSink.emit(RunEvent.ToolCalls.from(response.toolCalls()));
                    if (hookLifecycle != null) {
                        hookLifecycle.toolExecutionsStarted(iteration, response.toolCalls());
                    }

                    List<ToolRegistry.ToolExecutionResult> toolResults = delegate.executeTools(
                            response.toolCalls(), iteration);
                    if (toolResults == null) {
                        toolResults = List.of();
                    }
                    for (ToolRegistry.ToolExecutionResult toolResult : toolResults) {
                        budget.recordToolResult(toolResult);
                        delegate.history().add(LlmClient.Message.tool(
                                toolResult.id(), toolResult.result()));
                    }
                    eventSink.emit(RunEvent.ToolResults.from(toolResults));
                    if (hookLifecycle != null) {
                        hookLifecycle.toolResultsReceived(iteration, toolResults);
                    }
                    delegate.afterToolResults(response, toolResults, iteration, budget);
                    Optional<R> completed = delegate.completedAfterToolResults(
                            response, toolResults, iteration, budget);
                    if (completed.isPresent()) {
                        return completed.get();
                    }
                    deliverQueuedMessages(delegate, AgentTurnInbox.Channel.STEERING,
                            delegate.drainSteeringMessages());
                    continue;
                }

                String retryInstruction = delegate.retryInstructionAfterResponseWithoutTools(
                        response, iteration, budget);
                if (retryInstruction != null && !retryInstruction.isBlank()) {
                    delegate.history().add(LlmClient.Message.assistant(
                            response.reasoningContent(), response.content()));
                    delegate.history().add(LlmClient.Message.user(retryInstruction));
                    continue;
                }

                delegate.history().add(LlmClient.Message.assistant(
                        response.reasoningContent(), response.content()));
                if (deliverQueuedMessages(delegate, AgentTurnInbox.Channel.STEERING,
                        delegate.drainSteeringMessages())) {
                    continue;
                }
                if (deliverQueuedMessages(delegate, AgentTurnInbox.Channel.FOLLOW_UP,
                        delegate.drainFollowUpMessages())) {
                    continue;
                }
                return delegate.completed(response, budget);
            } catch (IOException e) {
                return delegate.failed(e, budget);
            } finally {
                runBudget.releaseReservation(logicalAdmission);
            }
        }
    }

    private String samplingRequestId(int iteration) {
        RunContext runContext = CancellationContext.currentRun();
        String runId = runContext == null ? "local" : runContext.runId();
        return runId + ":engine_" + engineId + ":iteration_" + iteration;
    }

    private RunBudget resolveRunBudget() {
        RunContext context = CancellationContext.currentRun();
        if (context != null) return context.runBudget();
        return RunBudget.create("run_local_" + engineId,
                RunBudgetPolicy.fromConfiguration(), PricingCatalog.empty());
    }

    private long estimateRequestReservation(List<LlmClient.Message> messages) {
        // Provider usage 已包含工具定义；预留输出上限即可防止并行请求共同穿透 Token 上限。
        long input = com.devcli.memory.ContextWindowBudget.estimateMessagesTokens(messages);
        long output = Math.max(1, llmClient.maxOutputTokens());
        return Math.max(1, input + output);
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private boolean deliverQueuedMessages(Delegate<R> delegate,
                                          AgentTurnInbox.Channel channel,
                                          List<AgentTurnInbox.Item> items) {
        if (items == null || items.isEmpty()) {
            return false;
        }
        for (AgentTurnInbox.Item item : items) {
            delegate.history().add(LlmClient.Message.user(item.text()));
        }
        delegate.queuedMessagesDelivered(channel, List.copyOf(items));
        return true;
    }
}
