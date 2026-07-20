package com.devcli.agent;

import com.devcli.llm.LlmClient;
import com.devcli.tool.ToolRegistry;
import com.devcli.trace.TraceContext;
import com.devcli.trace.TraceRecorder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiAgentBatchExecutorTest {

    @Test
    void runsIndependentStepsConcurrentlyAndFlushesByStepOrder() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            try (ToolRegistry registry = new ToolRegistry()) {
                LlmClient llmClient = stubLlmClient();
                List<SubAgent> workers = List.of(
                        new SubAgent("worker-1", AgentRole.WORKER, llmClient, registry),
                        new SubAgent("worker-2", AgentRole.WORKER, llmClient, registry));
                SubAgent reviewer = new SubAgent("reviewer", AgentRole.REVIEWER, llmClient, registry);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                AtomicInteger active = new AtomicInteger();
                AtomicInteger peak = new AtomicInteger();
                CountDownLatch bothStarted = new CountDownLatch(2);
                CountDownLatch secondPrinted = new CountDownLatch(1);

                MultiAgentBatchExecutor executor = new MultiAgentBatchExecutor(
                        new PrintStream(output, true, StandardCharsets.UTF_8),
                        workers,
                        reviewer,
                        llmClient,
                        registry,
                        new TraceRecorder(),
                        new MultiAgentBatchExecutor.Hooks(
                                (stepId, index) -> workers.get(index % workers.size()),
                                agent -> { },
                                agent -> { },
                                AgentOrchestrator.ExecutionStep::id,
                                (step, worker, localReviewer, context, stepOut,
                                 workerForkContext, reviewerForkContext) -> {
                                    int current = active.incrementAndGet();
                                    peak.accumulateAndGet(current, Math::max);
                                    bothStarted.countDown();
                                    await(bothStarted);
                                    if (step.id().equals("first")) {
                                        await(secondPrinted);
                                        stepOut.println("first-output");
                                    } else {
                                        stepOut.println("second-output");
                                        secondPrinted.countDown();
                                    }
                                    active.decrementAndGet();
                                },
                                (step, reason) -> { }));

                executor.execute(1, List.of(
                                step("first", "分析模块 A", "ANALYSIS"),
                                step("second", "分析模块 B", "ANALYSIS")),
                        TraceContext.root("test"));

                String rendered = output.toString(StandardCharsets.UTF_8);
                assertEquals(2, peak.get());
                assertTrue(rendered.indexOf("first-output") < rendered.indexOf("second-output"));
            }
        });
    }

    @Test
    void separatesStepsThatWriteTheSameResource() {
        try (ToolRegistry registry = new ToolRegistry()) {
            LlmClient llmClient = stubLlmClient();
            List<SubAgent> workers = List.of(
                    new SubAgent("worker-1", AgentRole.WORKER, llmClient, registry),
                    new SubAgent("worker-2", AgentRole.WORKER, llmClient, registry));
            SubAgent reviewer = new SubAgent("reviewer", AgentRole.REVIEWER, llmClient, registry);
            AtomicInteger active = new AtomicInteger();
            AtomicInteger peak = new AtomicInteger();

            MultiAgentBatchExecutor executor = new MultiAgentBatchExecutor(
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                    workers,
                    reviewer,
                    llmClient,
                    registry,
                    new TraceRecorder(),
                    new MultiAgentBatchExecutor.Hooks(
                            (stepId, index) -> workers.get(index % workers.size()),
                            agent -> { },
                            agent -> { },
                            AgentOrchestrator.ExecutionStep::id,
                            (step, worker, localReviewer, context, stepOut,
                             workerForkContext, reviewerForkContext) -> {
                                int current = active.incrementAndGet();
                                peak.accumulateAndGet(current, Math::max);
                                LockSupport.parkNanos(Duration.ofMillis(40).toNanos());
                                active.decrementAndGet();
                            },
                            (step, reason) -> { }));

            executor.execute(2, List.of(
                            step("first", "修改 src/A.java", "FILE_WRITE"),
                            step("second", "写入 src/A.java", "FILE_WRITE")),
                    TraceContext.root("test"));

            assertEquals(1, peak.get());
        }
    }

    @Test
    void reportsUnexpectedStepFailureThroughHostCallback() {
        try (ToolRegistry registry = new ToolRegistry()) {
            LlmClient llmClient = stubLlmClient();
            List<SubAgent> workers = List.of(
                    new SubAgent("worker-1", AgentRole.WORKER, llmClient, registry),
                    new SubAgent("worker-2", AgentRole.WORKER, llmClient, registry));
            SubAgent reviewer = new SubAgent("reviewer", AgentRole.REVIEWER, llmClient, registry);
            Map<String, String> failures = new ConcurrentHashMap<>();

            MultiAgentBatchExecutor executor = new MultiAgentBatchExecutor(
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                    workers,
                    reviewer,
                    llmClient,
                    registry,
                    new TraceRecorder(),
                    new MultiAgentBatchExecutor.Hooks(
                            (stepId, index) -> workers.get(index % workers.size()),
                            agent -> { },
                            agent -> { },
                            AgentOrchestrator.ExecutionStep::id,
                            (step, worker, localReviewer, context, stepOut,
                             workerForkContext, reviewerForkContext) -> {
                                if (step.id().equals("second")) {
                                    throw new IllegalStateException("执行失败");
                                }
                            },
                            (step, reason) -> failures.put(step.id(), reason)));

            executor.execute(3, List.of(
                            step("first", "分析模块 A", "ANALYSIS"),
                            step("second", "分析模块 B", "ANALYSIS")),
                    TraceContext.root("test"));

            assertTrue(failures.get("second").contains("执行失败"));
        }
    }

    private static AgentOrchestrator.ExecutionStep step(String id, String description, String type) {
        return AgentOrchestrator.ExecutionStep.pending(id, description, type, List.of());
    }

    private static LlmClient stubLlmClient() {
        return new LlmClient() {
            @Override
            public ChatResponse chat(List<Message> messages, List<Tool> tools) {
                return new ChatResponse("assistant", "", List.of(), 0, 0);
            }

            @Override
            public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                     StreamListener listener) {
                return chat(messages, tools);
            }

            @Override
            public String getModelName() {
                return "test-model";
            }

            @Override
            public String getProviderName() {
                return "test-provider";
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待并行步骤时中断", e);
        }
    }
}
