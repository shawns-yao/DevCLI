package com.devcli.agent;

import com.devcli.llm.LlmClient;
import com.devcli.runtime.CancellationContext;
import com.devcli.tool.ToolRegistry;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * ReAct、Plan task 和 SubAgent 共用的单轮 LLM/工具循环。
 */
final class AgentExecutionEngine<R> {

    interface Delegate<R> {
        List<LlmClient.Message> history();

        List<LlmClient.Tool> toolDefinitions(int iteration);

        LlmClient.StreamListener streamListener();

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

        R completed(LlmClient.ChatResponse response, AgentBudget budget);

        R cancelled(AgentBudget budget);

        R budgetExceeded(AgentBudget.ExitReason reason, AgentBudget budget);

        R iterationLimitReached(AgentBudget budget);

        R failed(IOException error, AgentBudget budget);
    }

    private final LlmClient llmClient;
    private final AgentBudget budget;

    AgentExecutionEngine(LlmClient llmClient, AgentBudget budget) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    R run(Delegate<R> delegate) {
        Objects.requireNonNull(delegate, "delegate");
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
            delegate.beforeIteration(iteration, budget);

            try {
                LlmClient.ChatResponse response = llmClient.chat(
                        delegate.history(),
                        delegate.toolDefinitions(iteration),
                        delegate.streamListener() == null
                                ? LlmClient.StreamListener.NO_OP
                                : delegate.streamListener(),
                        delegate.toolChoice(iteration));
                if (delegate.isCancelled()) {
                    return delegate.cancelled(budget);
                }

                budget.recordTokens(
                        response.inputTokens(),
                        response.outputTokens(),
                        response.cachedInputTokens());
                delegate.afterResponse(response, iteration, budget);

                if (response.hasToolCalls()) {
                    budget.recordToolCalls(response.toolCalls());
                    delegate.history().add(LlmClient.Message.assistant(
                            response.reasoningContent(),
                            response.content(),
                            response.toolCalls()));
                    delegate.beforeToolExecution(response, iteration, budget);

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
                    delegate.afterToolResults(response, toolResults, iteration, budget);
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
