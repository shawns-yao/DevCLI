package com.devcli.tool;

import com.devcli.runtime.CancellationContext;
import com.devcli.runtime.RunContext;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCancellationExecutionTest {

    @Test
    void appliesDeclaredTimeoutToSingleToolInvocation() {
        try (ToolRegistry registry = new ToolRegistry(5, 5)) {
            registry.registerTool(tool("slow", 1, ToolRegistry.ToolCancellationCapability.INTERRUPT_ONLY,
                    args -> {
                        sleepUntilInterrupted();
                        return "late";
                    }));

            long startedAt = System.nanoTime();
            ToolRegistry.ToolExecutionResult result = registry.executeTools(List.of(
                    new ToolRegistry.ToolInvocation("call_1", "slow", "{}"))).get(0);

            assertEquals(ToolStatus.TIMEOUT, result.status());
            assertTrue(elapsedMillis(startedAt) < 2_500,
                    "单工具调用必须经过统一 deadline，不能直接同步执行到自然结束");
        }
    }

    @Test
    void startsEachToolDeadlineWhenThatToolStartsInsteadOfWhenResultsAreCollected() {
        try (ToolRegistry registry = new ToolRegistry(5, 5)) {
            registry.registerTool(tool("first", 3, ToolRegistry.ToolCancellationCapability.INTERRUPT_ONLY,
                    args -> {
                        sleep(2_000);
                        return "first-done";
                    }));
            registry.registerTool(tool("second", 1, ToolRegistry.ToolCancellationCapability.INTERRUPT_ONLY,
                    args -> {
                        sleep(1_500);
                        return "second-late";
                    }));

            List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                    new ToolRegistry.ToolInvocation("call_1", "first", "{}"),
                    new ToolRegistry.ToolInvocation("call_2", "second", "{}")));

            assertEquals(ToolStatus.SUCCESS, results.get(0).status());
            assertEquals(ToolStatus.TIMEOUT, results.get(1).status(),
                    "第二个工具不能因为结果收集较晚而获得额外执行时间");
        }
    }

    @Test
    void parentCancellationWakesRunningToolAndKeepsCancellationAttribution(@TempDir Path projectRoot)
            throws Exception {
        CountDownLatch toolStarted = new CountDownLatch(1);
        AtomicReference<RunContext> activeRun = new AtomicReference<>();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try (ToolRegistry registry = new ToolRegistry(10, 10)) {
            registry.registerTool(tool("blocking", 10,
                    ToolRegistry.ToolCancellationCapability.INTERRUPT_ONLY, args -> {
                        toolStarted.countDown();
                        sleepUntilInterrupted();
                        return "late";
                    }));

            Future<List<ToolRegistry.ToolExecutionResult>> execution = caller.submit(() -> {
                try (RunContext context = CancellationContext.startRunContext(projectRoot)) {
                    activeRun.set(context);
                    return registry.executeTools(List.of(
                            new ToolRegistry.ToolInvocation("call_1", "blocking", "{}")));
                }
            });

            assertTrue(toolStarted.await(2, TimeUnit.SECONDS));
            activeRun.get().cancel();
            ToolRegistry.ToolExecutionResult result = execution.get(2, TimeUnit.SECONDS).get(0);

            assertEquals(ToolStatus.CANCELLED, result.status());
            assertEquals(ToolErrorCode.CANCELLED, result.errorCode());
            assertFalse(result.timedOut(), "上游取消不能错误归因为工具超时");
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    void cooperativeToolSeesChildCancellationBeforeInterrupt() {
        AtomicBoolean interruptedWhenCancelled = new AtomicBoolean();
        try (ToolRegistry registry = new ToolRegistry(5, 5)) {
            registry.registerTool(tool("cooperative", 1,
                    ToolRegistry.ToolCancellationCapability.COOPERATIVE, args -> {
                        while (!CancellationContext.isCancelled()) {
                            Thread.onSpinWait();
                        }
                        interruptedWhenCancelled.set(Thread.currentThread().isInterrupted());
                        return "stopped";
                    }));

            ToolRegistry.ToolExecutionResult result = registry.executeTools(List.of(
                    new ToolRegistry.ToolInvocation("call_1", "cooperative", "{}"))).get(0);

            assertEquals(ToolStatus.TIMEOUT, result.status());
            assertFalse(interruptedWhenCancelled.get(),
                    "协作型工具应先通过子令牌观察到取消，不应依赖线程中断才能停止");
        }
    }

    @Test
    void reportsUnconfirmedStopWhenToolIgnoresCancellation() {
        AtomicBoolean allowStop = new AtomicBoolean(false);
        CountDownLatch toolStarted = new CountDownLatch(1);
        try (ToolRegistry registry = new ToolRegistry(5, 1)) {
            registry.registerTool(tool("stubborn", 1,
                    ToolRegistry.ToolCancellationCapability.INTERRUPT_ONLY, args -> {
                        toolStarted.countDown();
                        while (!allowStop.get()) {
                            try {
                                Thread.sleep(25);
                            } catch (InterruptedException ignored) {
                                // 模拟无法通过线程中断停止的第三方工具。
                            }
                        }
                        return "eventually-stopped";
                    }));

            ToolRegistry.ToolExecutionResult result = registry.executeTools(List.of(
                    new ToolRegistry.ToolInvocation("call_1", "stubborn", "{}"))).get(0);
            allowStop.set(true);

            assertTrue(toolStarted.getCount() == 0);
            assertEquals("TERMINATION_UNCONFIRMED", result.errorCode().name(),
                    "没有确认线程停止时，不能谎报工具已经取消");
        } finally {
            allowStop.set(true);
        }
    }

    private static ToolRegistry.Tool tool(String name, long timeoutSeconds,
                                          ToolRegistry.ToolCancellationCapability capability,
                                          ToolRegistry.ToolExecutor executor) {
        return new ToolRegistry.Tool(name, name, JsonNodeFactory.instance.objectNode(), executor,
                ToolRegistry.ToolEffect.READ_ONLY, timeoutSeconds, capability);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepUntilInterrupted() {
        try {
            Thread.sleep(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }
}
