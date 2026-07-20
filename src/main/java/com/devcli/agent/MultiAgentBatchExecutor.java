package com.devcli.agent;

import com.devcli.llm.LlmClient;
import com.devcli.plan.ResourceConflictDetector;
import com.devcli.tool.ToolRegistry;
import com.devcli.trace.TraceContext;
import com.devcli.trace.TraceRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Multi-Agent 可执行步骤的资源分波、Worker 并发协调和输出归并器。
 */
final class MultiAgentBatchExecutor {
    private static final Logger log = LoggerFactory.getLogger(MultiAgentBatchExecutor.class);

    @FunctionalInterface
    interface StepRunner {
        void run(AgentOrchestrator.ExecutionStep step,
                 SubAgent worker,
                 SubAgent reviewer,
                 String context,
                 PrintStream out,
                 SubAgent.ForkContext workerForkContext,
                 SubAgent.ForkContext reviewerForkContext);
    }

    record Hooks(
            BiFunction<String, Integer, SubAgent> workerResolver,
            Consumer<SubAgent> subAgentConfigurer,
            Consumer<SubAgent> recoveryConfigurer,
            Function<AgentOrchestrator.ExecutionStep, String> contextBuilder,
            StepRunner stepRunner,
            BiConsumer<AgentOrchestrator.ExecutionStep, String> failureHandler
    ) {
        Hooks {
            Objects.requireNonNull(workerResolver, "workerResolver");
            Objects.requireNonNull(subAgentConfigurer, "subAgentConfigurer");
            Objects.requireNonNull(recoveryConfigurer, "recoveryConfigurer");
            Objects.requireNonNull(contextBuilder, "contextBuilder");
            Objects.requireNonNull(stepRunner, "stepRunner");
            Objects.requireNonNull(failureHandler, "failureHandler");
        }
    }

    private final PrintStream out;
    private final List<SubAgent> workers;
    private final SubAgent reviewer;
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final TraceRecorder traceRecorder;
    private final Hooks hooks;

    MultiAgentBatchExecutor(PrintStream out,
                            List<SubAgent> workers,
                            SubAgent reviewer,
                            LlmClient llmClient,
                            ToolRegistry toolRegistry,
                            TraceRecorder traceRecorder,
                            Hooks hooks) {
        if (workers == null || workers.isEmpty()) {
            throw new IllegalArgumentException("Multi-Agent 并行执行至少需要一个 Worker");
        }
        this.out = out;
        this.workers = List.copyOf(workers);
        this.reviewer = reviewer;
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.traceRecorder = traceRecorder;
        this.hooks = Objects.requireNonNull(hooks, "hooks");
    }

    void execute(int batchIndex, List<AgentOrchestrator.ExecutionStep> executable,
                 TraceContext traceContext) {
        List<List<AgentOrchestrator.ExecutionStep>> waves = ResourceConflictDetector.splitConflictFree(
                executable,
                AgentOrchestrator.ExecutionStep::id,
                AgentOrchestrator.ExecutionStep::description,
                AgentOrchestrator.ExecutionStep::type);
        for (List<AgentOrchestrator.ExecutionStep> wave : waves) {
            traceRecorder.record(traceContext, "batch.wave", Map.of(
                    "batchIndex", batchIndex,
                    "size", wave.size(),
                    "stepIds", wave.stream().map(AgentOrchestrator.ExecutionStep::id).toList().toString()
            ));
            out.println("⚡ 批次 #" + batchIndex + "：" + wave.size()
                    + " 个独立步骤并行执行（最多 " + workers.size() + " 个并发 Worker）\n");
            executeWave(wave);
        }
    }

    private void executeWave(List<AgentOrchestrator.ExecutionStep> wave) {
        int parallelism = Math.min(wave.size(), workers.size());
        ExecutorService executor = Executors.newFixedThreadPool(parallelism, task -> {
            Thread thread = new Thread(task, "devcli-multi-agent");
            thread.setDaemon(true);
            return thread;
        });
        Map<String, ByteArrayOutputStream> buffers = new LinkedHashMap<>();
        Map<String, PrintStream> streams = new LinkedHashMap<>();
        Map<String, SubAgent> assignments = new LinkedHashMap<>();
        Map<String, ReentrantLock> workerLocks = new HashMap<>();
        workers.forEach(worker -> workerLocks.put(worker.getName(), new ReentrantLock(true)));
        Map<SubAgent, SubAgent.ForkContext> workerContexts = new HashMap<>();
        workers.forEach(worker -> workerContexts.put(worker, worker.createForkContext()));

        for (int index = 0; index < wave.size(); index++) {
            AgentOrchestrator.ExecutionStep step = wave.get(index);
            assignments.put(step.id(), hooks.workerResolver().apply(step.id(), index));
        }

        SubAgent reviewerForkTemplate = new SubAgent(
                reviewer.getName(), AgentRole.REVIEWER, llmClient, toolRegistry);
        hooks.subAgentConfigurer().accept(reviewerForkTemplate);
        hooks.recoveryConfigurer().accept(reviewerForkTemplate);
        SubAgent.ForkContext reviewerForkContext = reviewerForkTemplate.createForkContext();
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (AgentOrchestrator.ExecutionStep step : wave) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                PrintStream stepOut = new PrintStream(buffer, true, StandardCharsets.UTF_8);
                buffers.put(step.id(), buffer);
                streams.put(step.id(), stepOut);
                String context = hooks.contextBuilder().apply(step);

                futures.add(executor.submit(() -> {
                    SubAgent worker = assignments.get(step.id());
                    ReentrantLock workerLock = workerLocks.get(worker.getName());
                    SubAgent localReviewer = new SubAgent(
                            reviewer.getName(), AgentRole.REVIEWER, llmClient, toolRegistry);
                    hooks.subAgentConfigurer().accept(localReviewer);
                    hooks.recoveryConfigurer().accept(localReviewer);
                    try {
                        workerLock.lockInterruptibly();
                        SubAgent.ForkContext workerForkContext = workerContexts.get(worker);
                        toolRegistry.runWithResourceLease(step.id(), () -> {
                            hooks.stepRunner().run(step, worker, localReviewer, context, stepOut,
                                    workerForkContext, reviewerForkContext);
                            return null;
                        });
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        hooks.failureHandler().accept(step, "并行执行被中断");
                        stepOut.println("❌ 步骤 [" + step.id() + "] 被中断\n");
                    } catch (RuntimeException e) {
                        String message = e.getMessage() == null
                                ? e.getClass().getSimpleName()
                                : e.getMessage();
                        log.error("Parallel step {} failed unexpectedly", step.id(), e);
                        hooks.failureHandler().accept(step, "并行执行异常: " + message);
                        stepOut.println("❌ 步骤 [" + step.id() + "] 并行执行异常：" + message + "\n");
                    } finally {
                        worker.clearHistory();
                        if (workerLock.isHeldByCurrentThread()) {
                            workerLock.unlock();
                        }
                        toolRegistry.releaseResourceLeases(step.id());
                        stepOut.flush();
                    }
                    return null;
                }));
            }

            awaitCompletion(futures);
        } finally {
            executor.shutdownNow();
            flushOutput(wave, buffers);
            streams.values().forEach(PrintStream::close);
        }
    }

    private static void awaitCompletion(List<Future<?>> futures) {
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Batch wait interrupted");
            } catch (ExecutionException e) {
                log.error("Parallel step task failed", e.getCause());
            }
        }
    }

    private void flushOutput(List<AgentOrchestrator.ExecutionStep> wave,
                             Map<String, ByteArrayOutputStream> buffers) {
        for (AgentOrchestrator.ExecutionStep step : wave) {
            ByteArrayOutputStream buffer = buffers.get(step.id());
            if (buffer != null && buffer.size() > 0) {
                out.print(buffer.toString(StandardCharsets.UTF_8));
                out.flush();
            }
        }
    }
}
