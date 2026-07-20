package com.devcli.agent;

import com.devcli.plan.Task;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanTaskBatchExecutorTest {

    @Test
    void executesConflictFreeTasksInParallelAndFlushesOutputInTaskOrder() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            Task first = new Task("first", "分析模块 A", Task.TaskType.ANALYSIS);
            Task second = new Task("second", "分析模块 B", Task.TaskType.ANALYSIS);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maxActive = new AtomicInteger();
            AtomicInteger started = new AtomicInteger();
            CountDownLatch bothStarted = new CountDownLatch(2);
            CountDownLatch secondPrinted = new CountDownLatch(1);

            PlanTaskBatchExecutor executor = new PlanTaskBatchExecutor(
                    new PrintStream(output, true, StandardCharsets.UTF_8),
                    task -> started.incrementAndGet(),
                    (task, taskOut) -> {
                        int current = active.incrementAndGet();
                        maxActive.accumulateAndGet(current, Math::max);
                        bothStarted.countDown();
                        await(bothStarted);
                        if (task == first) {
                            await(secondPrinted);
                            taskOut.println("first-output");
                        } else {
                            taskOut.println("second-output");
                            secondPrinted.countDown();
                        }
                        active.decrementAndGet();
                        return PlanTaskExecutionResult.success(task, task.getId(), false, List.of());
                    },
                    task -> List.of());

            List<PlanTaskExecutionResult> results = executor.execute(List.of(first, second));
            String rendered = output.toString(StandardCharsets.UTF_8);

            assertEquals(2, started.get());
            assertEquals(2, maxActive.get());
            assertEquals(List.of("first", "second"),
                    results.stream().map(result -> result.task().getId()).toList());
            assertTrue(rendered.indexOf("first-output") < rendered.indexOf("second-output"));
        });
    }

    @Test
    void serializesTasksThatWriteTheSameResource() {
        Task first = new Task("first", "修改 src/A.java", Task.TaskType.FILE_WRITE);
        Task second = new Task("second", "写入 src/A.java", Task.TaskType.FILE_WRITE);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();

        PlanTaskBatchExecutor executor = new PlanTaskBatchExecutor(
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                task -> { },
                (task, taskOut) -> {
                    int current = active.incrementAndGet();
                    maxActive.accumulateAndGet(current, Math::max);
                    active.decrementAndGet();
                    return PlanTaskExecutionResult.success(task, task.getId(), false, List.of());
                },
                task -> List.of());

        List<PlanTaskExecutionResult> results = executor.execute(List.of(first, second));

        assertEquals(1, maxActive.get());
        assertEquals(List.of("first", "second"),
                results.stream().map(result -> result.task().getId()).toList());
    }

    @Test
    void associatesParallelFailureWithItsTaskAndModifiedFiles() {
        Task first = new Task("first", "分析模块 A", Task.TaskType.ANALYSIS);
        Task second = new Task("second", "分析模块 B", Task.TaskType.ANALYSIS);

        PlanTaskBatchExecutor executor = new PlanTaskBatchExecutor(
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                task -> { },
                (task, taskOut) -> {
                    if (task == second) {
                        throw new IllegalStateException("执行失败");
                    }
                    return PlanTaskExecutionResult.success(task, "完成", false, List.of());
                },
                task -> task == second ? List.of("src/Partial.java") : List.of());

        List<PlanTaskExecutionResult> results = executor.execute(List.of(first, second));

        assertEquals("first", results.get(0).task().getId());
        assertEquals("second", results.get(1).task().getId());
        assertTrue(results.get(1).failed());
        assertEquals(List.of("src/Partial.java"), results.get(1).modifiedFiles());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待并行任务时中断", e);
        }
    }
}
