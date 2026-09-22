package com.devcli.agent;

import com.devcli.llm.LlmClient;
import com.devcli.plan.ResourceConflictDetector;
import com.devcli.tool.ToolRegistry;
import com.devcli.trace.TraceContext;
import com.devcli.trace.TraceRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
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
                AgentOrchestrator.ExecutionStep::type,
                Path.of(toolRegistry.getProjectPath()));
        for (List<AgentOrchestrator.ExecutionStep> wave : waves) {
            traceRecorder.record(traceContext, "batch.wave", Map.of(
                    "batchIndex", batchIndex,
                    "size", wave.size(),
                    "stepIds", wave.stream().map(AgentOrchestrator.ExecutionStep::id).toList().toString()
            ));
            out.println("⚡ 批次 #" + batchIndex + "：" + wave.size()
                    + " 个独立步骤并行执行（最多 " + workers.size() + " 个并发 Worker）\n");
            executeWave(batchIndex, wave, traceContext);
        }
    }

    private void executeWave(int batchIndex, List<AgentOrchestrator.ExecutionStep> wave,
                             TraceContext traceContext) {
        long waveStarted = System.nanoTime();
        AtomicInteger activeExecutions = new AtomicInteger();
        AtomicInteger peakConcurrency = new AtomicInteger();
        AtomicLong firstExecutionStarted = new AtomicLong(Long.MAX_VALUE);
        AtomicLong lastExecutionFinished = new AtomicLong();
        LongAdder aggregateExecutionNanos = new LongAdder();
        Map<String, SubAgent> assignments = new LinkedHashMap<>();
        Map<String, ReentrantLock> workerLocks = new HashMap<>();
        workers.forEach(worker -> workerLocks.put(worker.getName(), new ReentrantLock(true)));
        Map<SubAgent, SubAgent.ForkContext> workerContexts = new HashMap<>();
        workers.forEach(worker -> workerContexts.put(worker, worker.createForkContext()));

        for (int index = 0; index < wave.size(); index++) {
            AgentOrchestrator.ExecutionStep step = wave.get(index);
            assignments.put(step.id(), hooks.workerResolver().apply(step.id(), index));
        }
        Map<String, String> contexts = new LinkedHashMap<>();
        for (AgentOrchestrator.ExecutionStep step : wave) {
            contexts.put(step.id(), hooks.contextBuilder().apply(step));
        }

        SubAgent reviewerForkTemplate = new SubAgent(
                reviewer.getName(), AgentRole.REVIEWER, llmClient, toolRegistry);
        hooks.subAgentConfigurer().accept(reviewerForkTemplate);
        hooks.recoveryConfigurer().accept(reviewerForkTemplate);
        SubAgent.ForkContext reviewerForkContext = reviewerForkTemplate.createForkContext();
        OrchestrationWaveExecutor.execute(
                OrchestrationProfile.TEAM,
                out,
                wave,
                workers.size(),
                step -> { },
                (step, stepOut) -> {
                    SubAgent worker = assignments.get(step.id());
                    ReentrantLock workerLock = workerLocks.get(worker.getName());
                    long executionStarted = 0L;
                    boolean executionMeasured = false;
                    SubAgent localReviewer = new SubAgent(
                            reviewer.getName(), AgentRole.REVIEWER, llmClient, toolRegistry);
                    hooks.subAgentConfigurer().accept(localReviewer);
                    hooks.recoveryConfigurer().accept(localReviewer);
                    try {
                        workerLock.lockInterruptibly();
                        executionStarted = System.nanoTime();
                        executionMeasured = true;
                        firstExecutionStarted.accumulateAndGet(executionStarted, Math::min);
                        int active = activeExecutions.incrementAndGet();
                        peakConcurrency.accumulateAndGet(active, Math::max);
                        SubAgent.ForkContext workerForkContext = workerContexts.get(worker);
                        String context = contexts.get(step.id());
                        toolRegistry.runWithResourceLease(step.id(), () -> {
                            hooks.stepRunner().run(step, worker, localReviewer, context, stepOut,
                                    workerForkContext, reviewerForkContext);
                            return null;
                        });
                    } finally {
                        if (executionMeasured) {
                            long executionFinished = System.nanoTime();
                            aggregateExecutionNanos.add(executionFinished - executionStarted);
                            lastExecutionFinished.accumulateAndGet(executionFinished, Math::max);
                            activeExecutions.decrementAndGet();
                        }
                        worker.clearHistory();
                        if (workerLock.isHeldByCurrentThread()) {
                            workerLock.unlock();
                        }
                        toolRegistry.releaseResourceLeases(step.id());
                    }
                    return null;
                },
                (step, error, stepOut) -> {
                    if (error instanceof InterruptedException) {
                        hooks.failureHandler().accept(step, "并行执行被中断");
                        stepOut.println("❌ 步骤 [" + step.id() + "] 被中断\n");
                        return null;
                    }
                    String message = error.getMessage() == null
                            ? error.getClass().getSimpleName()
                            : error.getMessage();
                    log.error("Parallel step {} failed unexpectedly", step.id(), error);
                    hooks.failureHandler().accept(step, "并行执行异常: " + message);
                    stepOut.println("❌ 步骤 [" + step.id() + "] 并行执行异常：" + message + "\n");
                    return null;
                });
        long firstStarted = firstExecutionStarted.get();
        long lastFinished = lastExecutionFinished.get();
        long executionWallNanos = firstStarted == Long.MAX_VALUE || lastFinished < firstStarted
                ? System.nanoTime() - waveStarted
                : lastFinished - firstStarted;
        recordWaveMetrics(batchIndex, wave.size(), peakConcurrency.get(),
                executionWallNanos, aggregateExecutionNanos.sum(), traceContext);
    }

    private void recordWaveMetrics(int batchIndex, int stepCount, int peakConcurrency,
                                   long wallNanos, long aggregateExecutionNanos,
                                   TraceContext traceContext) {
        long wallMillis = Math.max(0L, wallNanos / 1_000_000L);
        long aggregateMillis = Math.max(0L, aggregateExecutionNanos / 1_000_000L);
        double parallelismFactor = wallNanos <= 0L
                ? 0.0
                : (double) aggregateExecutionNanos / (double) wallNanos;
        traceRecorder.record(traceContext, "batch.wave.completed", Map.of(
                "batchIndex", batchIndex,
                "stepCount", stepCount,
                "workerCount", workers.size(),
                "peakConcurrency", peakConcurrency,
                "wallMillis", wallMillis,
                "aggregateStepMillis", aggregateMillis,
                "parallelismFactor", parallelismFactor
        ));
    }

}
