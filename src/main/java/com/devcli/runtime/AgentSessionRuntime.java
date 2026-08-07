package com.devcli.runtime;

import com.devcli.agent.Agent;
import com.devcli.agent.AgentTurnInbox;
import com.devcli.llm.LlmClient;
import com.devcli.runtime.event.RunEventSink;
import com.devcli.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 统一管理 CLI、Runtime API 和无头执行可复用的 Agent 会话生命周期。
 */
public final class AgentSessionRuntime implements AutoCloseable {
    private final Agent agent;
    private final ToolRegistry toolRegistry;
    private final Path projectPath;
    private final boolean ownsAgent;
    private final boolean ownsToolRegistry;
    private final ExecutorService executor;
    private final AgentTurnInbox inbox;
    private final AtomicReference<RunContext> activeContext = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<RunResult>> activeRun = new AtomicReference<>();

    public static AgentSessionRuntime create(LlmClient llmClient, Path projectPath,
                                             RunEventSink eventSink) {
        Objects.requireNonNull(llmClient, "llmClient");
        Path normalized = normalize(projectPath);
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(normalized.toString());
        Agent agent = new Agent(llmClient, registry);
        agent.setRunEventSink(eventSink);
        return new AgentSessionRuntime(agent, registry, normalized, true, true);
    }

    public static AgentSessionRuntime adopt(Agent agent, Path projectPath) {
        return new AgentSessionRuntime(agent, null, normalize(projectPath), false, false);
    }

    public AgentSessionRuntime(Agent agent, ToolRegistry toolRegistry, Path projectPath,
                               boolean ownsAgent, boolean ownsToolRegistry) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.toolRegistry = toolRegistry;
        this.projectPath = normalize(projectPath);
        this.ownsAgent = ownsAgent;
        this.ownsToolRegistry = ownsToolRegistry;
        this.inbox = agent.getTurnInbox();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "devcli-agent-session");
            thread.setDaemon(true);
            return thread;
        });
        this.agent.setTurnInbox(inbox);
    }

    public Agent agent() {
        return agent;
    }

    public Path projectPath() {
        return projectPath;
    }

    public AgentTurnInbox inbox() {
        return inbox;
    }

    public void seedHistory(List<LlmClient.Message> messages) {
        agent.seedHistory(messages);
    }

    public void setRunEventSink(RunEventSink eventSink) {
        agent.setRunEventSink(eventSink);
    }

    public synchronized CompletableFuture<RunResult> submit(String prompt) {
        if (activeRun.get() != null) {
            throw new IllegalStateException("Agent 会话已有正在运行的任务");
        }
        CompletableFuture<RunResult> result = new CompletableFuture<>();
        activeRun.set(result);
        executor.submit(() -> execute(prompt, result));
        return result;
    }

    public RunResult runBlocking(String prompt) {
        try {
            return submit(prompt).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abort();
            throw new IllegalStateException("等待 Agent 会话结束时被中断", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Agent 会话执行失败", e.getCause());
        }
    }

    public boolean isRunning() {
        return activeRun.get() != null;
    }

    public void abort() {
        RunContext context = activeContext.get();
        if (context != null) {
            context.cancel();
        }
        agent.abort();
    }

    public boolean awaitIdle(long timeoutMillis) {
        CompletableFuture<RunResult> current = activeRun.get();
        if (current == null) {
            return true;
        }
        try {
            current.get(Math.max(0L, timeoutMillis), java.util.concurrent.TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void execute(String prompt, CompletableFuture<RunResult> result) {
        RunContext context = CancellationContext.startRunContext(projectPath);
        activeContext.set(context);
        try (context) {
            String output = agent.run(prompt);
            result.complete(new RunResult(output, agent.getConversationHistory(), context.isCancelled()));
        } catch (Throwable error) {
            result.completeExceptionally(error);
        } finally {
            activeContext.compareAndSet(context, null);
            activeRun.compareAndSet(result, null);
        }
    }

    @Override
    public void close() {
        abort();
        awaitIdle(5_000L);
        executor.shutdownNow();
        if (ownsAgent) {
            agent.close();
        }
        if (ownsToolRegistry && toolRegistry != null) {
            toolRegistry.close();
        }
    }

    private static Path normalize(Path path) {
        return Objects.requireNonNull(path, "projectPath").toAbsolutePath().normalize();
    }

    public record RunResult(String output, List<LlmClient.Message> history, boolean cancelled) {
        public RunResult {
            output = output == null ? "" : output;
            history = history == null ? List.of() : List.copyOf(history);
        }
    }
}
