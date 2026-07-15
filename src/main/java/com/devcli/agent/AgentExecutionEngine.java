package com.devcli.agent;

import com.devcli.hook.HookDispatcher;
import com.devcli.hook.HookLifecycle;
import com.devcli.llm.LlmClient;
import com.devcli.runtime.CancellationContext;
import com.devcli.runtime.event.RunEvent;
import com.devcli.runtime.event.RunEventSink;
import com.devcli.runtime.event.RunEventStreamListener;
import com.devcli.tool.ToolRegistry;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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

    AgentExecutionEngine(LlmClient llmClient, AgentBudget budget) {
        this(llmClient, budget, null);
    }

    AgentExecutionEngine(LlmClient llmClient, AgentBudget budget, HookLifecycle hookLifecycle) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.hookLifecycle = hookLifecycle;
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

            try {
                RunEventSink eventSink = RunEventSink.composite(
                        delegate.eventSink(),
                        RunEventSink.fromStreamListener(delegate.streamListener()));
                LlmClient.ChatResponse response = llmClient.chat(
                        delegate.history(),
                        delegate.toolDefinitions(iteration),
                        new RunEventStreamListener(eventSink),
                        delegate.toolChoice(iteration));
                if (delegate.isCancelled()) {
                    return delegate.cancelled(budget);
                }

                budget.recordTokens(
                        response.inputTokens(),
                        response.outputTokens(),
                        response.cachedInputTokens());
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
                return delegate.completed(response, budget);
            } catch (IOException e) {
                return delegate.failed(e, budget);
            }
        }
    }
}
