package com.devcli.agent;

import com.devcli.plan.ResourceConflictDetector;
import com.devcli.tool.ToolRegistry;
import com.devcli.trace.TraceContext;
import com.devcli.trace.TraceRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Multi-Agent 可执行步骤的资源分波、Worker 并发协调和输出归并器。
 */
final class MultiAgentBatchExecutor {
    private static final Logger log = LoggerFactory.getLogger(MultiAgentBatchExecutor.class);

    @FunctionalInterface
    interface StepRunner {
        void run(AgentOrchestrator.ExecutionStep step,
                 String context,
                 PrintStream out);
    }

    record Hooks(
            BiConsumer<String, Integer> assignWorker,
            Function<AgentOrchestrator.ExecutionStep, String> contextBuilder,
            StepRunner stepRunner,
            BiConsumer<AgentOrchestrator.ExecutionStep, String> failureHandler
    ) {
        Hooks {
            Objects.requireNonNull(assignWorker, "assignWorker");
            Objects.requireNonNull(contextBuilder, "contextBuilder");
            Objects.requireNonNull(stepRunner, "stepRunner");
            Objects.requireNonNull(failureHandler, "failureHandler");
        }
    }

    private final PrintStream out;
    private final int parallelism;
    private final ToolRegistry toolRegistry;
    private final TraceRecorder traceRecorder;
    private final Hooks hooks;

    MultiAgentBatchExecutor(PrintStream out,
                            int parallelism,
                            ToolRegistry toolRegistry,
                            TraceRecorder traceRecorder,
                            Hooks hooks) {
        this.out = out;
        this.parallelism = parallelism;
        this.toolRegistry = toolRegistry;
        this.traceRecorder = traceRecorder;
        this.hooks = Objects.requireNonNull(hooks, "hooks");
    }

    void execute(int batchIndex, List<AgentOrchestrator.ExecutionStep> executable,
                 TraceContext traceContext) {
        List<List<AgentOrchestrator.ExecutionStep>> resourceWaves = ResourceConflictDetector.splitConflictFree(
                executable,
                AgentOrchestrator.ExecutionStep::id,
                AgentOrchestrator.ExecutionStep::description,
                AgentOrchestrator.ExecutionStep::type,
                Path.of(toolRegistry.getProjectPath()));
        List<List<AgentOrchestrator.ExecutionStep>> waves = com.devcli.plan.SingleWriterScheduling.split(
                resourceWaves, AgentOrchestrator.ExecutionStep::type);
        for (List<AgentOrchestrator.ExecutionStep> wave : waves) {
            traceRecorder.record(traceContext, "batch.wave", Map.of(
                    "batchIndex", batchIndex,
                    "size", wave.size(),
                    "stepIds", wave.stream().map(AgentOrchestrator.ExecutionStep::id).toList().toString()
            ));
            out.println("⚡ 批次 #" + batchIndex + "：" + wave.size()
                    + " 个独立步骤并行执行（最多 " + parallelism + " 个并发任务）\n");
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
        for (int index = 0; index < wave.size(); index++) {
            AgentOrchestrator.ExecutionStep step = wave.get(index);
            hooks.assignWorker().accept(step.id(), index);
        }
        Map<String, String> contexts = new LinkedHashMap<>();
        for (AgentOrchestrator.ExecutionStep step : wave) {
            contexts.put(step.id(), hooks.contextBuilder().apply(step));
        }

        OrchestrationWaveExecutor.execute(
                OrchestrationProfile.TEAM,
                out,
                wave,
                parallelism,
                step -> { },
                (step, stepOut) -> {
                    long executionStarted = System.nanoTime();
                    firstExecutionStarted.accumulateAndGet(executionStarted, Math::min);
                    int active = activeExecutions.incrementAndGet();
                    peakConcurrency.accumulateAndGet(active, Math::max);
                    try {
                        String context = contexts.get(step.id());
                        toolRegistry.runWithResourceLease(step.id(), () -> {
                            hooks.stepRunner().run(step, context, stepOut);
                            return null;
                        });
                    } finally {
                        long executionFinished = System.nanoTime();
                        aggregateExecutionNanos.add(executionFinished - executionStarted);
                        lastExecutionFinished.accumulateAndGet(executionFinished, Math::max);
                        activeExecutions.decrementAndGet();
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
                "workerCount", parallelism,
                "peakConcurrency", peakConcurrency,
                "wallMillis", wallMillis,
                "aggregateStepMillis", aggregateMillis,
                "parallelismFactor", parallelismFactor
        ));
    }

}
