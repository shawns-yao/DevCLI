package com.devcli.hook;

import com.devcli.llm.LlmClient;
import com.devcli.runtime.CancellationContext;
import com.devcli.runtime.RunContext;
import com.devcli.runtime.event.RunEventSink;
import com.devcli.tool.ToolRegistry;

import java.util.List;

/**
 * 幂等 Hook 生命周期状态机，确保 message/turn 在异常出口也能闭合。
 */
public final class HookLifecycle {
    private final HookDispatcher dispatcher;
    private final HookDispatcher.HookContext baseContext;
    private boolean agentStarted;
    private boolean agentEnded;
    private int activeIteration;
    private boolean turnStarted;
    private boolean turnEnded;
    private boolean messageStarted;
    private boolean messageEnded;
    private int pendingToolExecutions;

    private HookLifecycle(HookDispatcher dispatcher, HookDispatcher.HookContext baseContext) {
        this.dispatcher = dispatcher;
        this.baseContext = baseContext;
    }

    public static HookLifecycle load(ToolRegistry registry) {
        HookDispatcher dispatcher = HookDispatcher.load(registry);
        RunContext runContext = CancellationContext.currentRun();
        String runId = runContext == null ? "" : runContext.runId();
        HookDispatcher.HookContext context = new HookDispatcher.HookContext(
                registry.getProjectPath(), runId, 0, "", "", "");
        return new HookLifecycle(dispatcher, context);
    }

    public static HookLifecycle create(
            HookDispatcher dispatcher, HookDispatcher.HookContext context) {
        return new HookLifecycle(dispatcher, context == null
                ? HookDispatcher.HookContext.empty()
                : context);
    }

    public boolean isEmpty() {
        return dispatcher.isEmpty();
    }

    public void bindEventSink(RunEventSink eventSink) {
        dispatcher.setEventSink(eventSink);
    }

    public void startAgent() {
        if (agentStarted) return;
        agentStarted = true;
        dispatcher.dispatch(HookEvent.AGENT_START, baseContext);
    }

    public void startTurn(int iteration) {
        if (turnStarted && !turnEnded) {
            endTurn(activeIteration);
        }
        activeIteration = iteration;
        turnStarted = true;
        turnEnded = false;
        messageStarted = true;
        messageEnded = false;
        pendingToolExecutions = 0;
        HookDispatcher.HookContext context = baseContext.withIteration(iteration);
        dispatcher.dispatch(HookEvent.TURN_START, context);
        dispatcher.dispatch(HookEvent.MESSAGE_START, context);
    }

    public void assistantMessageCompleted(int iteration, int toolCallCount) {
        ensureMessageEnded();
        pendingToolExecutions = Math.max(0, toolCallCount);
        if (pendingToolExecutions == 0) {
            endTurn(iteration);
        }
    }

    public void toolExecutionsStarted(int iteration, List<LlmClient.ToolCall> toolCalls) {
        if (toolCalls == null) return;
        HookDispatcher.HookContext context = baseContext.withIteration(iteration);
        for (LlmClient.ToolCall call : toolCalls) {
            if (call == null || call.function() == null) continue;
            dispatcher.dispatch(HookEvent.TOOL_EXECUTION_START,
                    context.withTool(call.function().name(), call.id(), ""));
        }
    }

    public void toolResultsReceived(
            int iteration, List<ToolRegistry.ToolExecutionResult> toolResults) {
        if (toolResults != null) {
            HookDispatcher.HookContext context = baseContext.withIteration(iteration);
            for (ToolRegistry.ToolExecutionResult result : toolResults) {
                if (result == null) continue;
                dispatcher.dispatch(HookEvent.TOOL_EXECUTION_END,
                        context.withTool(
                                result.name(),
                                result.id(),
                                result.status() == null ? "" : result.status().name()));
                pendingToolExecutions = Math.max(0, pendingToolExecutions - 1);
            }
        }
        if (pendingToolExecutions == 0) {
            endTurn(iteration);
        }
    }

    public void ensureMessageEnded() {
        if (!messageStarted || messageEnded) return;
        messageEnded = true;
        dispatcher.dispatch(HookEvent.MESSAGE_END,
                baseContext.withIteration(activeIteration));
    }

    public void endTurn(int iteration) {
        if (!turnStarted || turnEnded || activeIteration != iteration) return;
        ensureMessageEnded();
        turnEnded = true;
        pendingToolExecutions = 0;
        dispatcher.dispatch(HookEvent.TURN_END,
                baseContext.withIteration(iteration));
    }

    public void endAgent() {
        if (!agentStarted || agentEnded) return;
        if (turnStarted && !turnEnded) {
            endTurn(activeIteration);
        }
        dispatcher.awaitPending();
        agentEnded = true;
        dispatcher.dispatch(HookEvent.AGENT_END, baseContext);
        dispatcher.awaitPending();
    }
}
