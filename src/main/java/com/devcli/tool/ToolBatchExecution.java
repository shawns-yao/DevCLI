package com.devcli.tool;

import com.devcli.concurrent.CancellationContext;
import com.devcli.concurrent.CancellationToken;
import com.devcli.skill.SkillContextBuffer;

import java.util.*;
import java.util.concurrent.*;

import static com.devcli.tool.ToolContract.*;

/** 工具批次的串并行调度、期限与取消传递。 */
final class ToolBatchExecution {
    private final ToolRegistry registry;
    ToolBatchExecution(ToolRegistry registry) { this.registry = registry; }
    /**
     * 叶子副作用工具（写文件 / 命令 / 外部变更）与非幂等只读工具在进程内串行。
     *
     * <p>资源租约按 stepId 判定，同一并行批次共享 stepId 时重入放行，拦不住“同一轮两个 write_file
     * 写同一文件”或“write_file 与 execute_command 同改一文件”。此锁在 ToolRegistry 实例内把叶子
     * 副作用串行化，允许并行的只有声明幂等的只读/本地上下文工具；
     * 跨进程一致性仍由项目锁 / PatchSet beforeHash 承担。</p>
     */
    final java.util.concurrent.locks.ReentrantLock sideEffectSerializeLock =
            new java.util.concurrent.locks.ReentrantLock(true);

    /** 叶子副作用工具需要工作区级串行；delegate_task 是编排工具，其子操作各自加锁，这里不持锁以防跨线程自锁。 */
    boolean isSerializedSideEffect(String toolName) {
        return isSerializedSideEffect(toolName, null);
    }

    boolean isSerializedSideEffect(String toolName, ToolSnapshot snapshot) {
        if (ToolAccessContext.isDelegationTool(toolName)) {
            return false;
        }
        ToolBinding binding = snapshot == null ? null : snapshot.binding(toolName);
        // 无快照的直接执行路径仍要从注册表取回真实契约，不能按工具名重新推导。
        Tool tool = binding != null ? binding.tool() : registry.activeTool(toolName);
        if (tool == null) {
            return true;
        }
        return !isConcurrencySafeRead(tool.effect(), tool.idempotency());
    }

    /** 只有"只读/本地上下文 + 声明幂等"的工具才允许进入同批并行。 */
    static boolean isConcurrencySafeRead(ToolEffect effect, Idempotency idempotency) {
        return (effect == ToolEffect.READ_ONLY || effect == ToolEffect.LOCAL_CONTEXT)
                && idempotency == Idempotency.IDEMPOTENT;
    }

    enum ToolBatchKind { READ_ONLY, DELEGATION, SERIAL }

    ToolBatchKind toolBatchKind(ToolInvocation invocation, ToolSnapshot snapshot) {
        ToolBinding binding = snapshot == null ? null : snapshot.binding(invocation.name());
        Tool tool = binding == null ? registry.activeTool(invocation.name()) : binding.tool();
        if (tool == null) return ToolBatchKind.SERIAL;
        if (DelegateTaskTool.NAME.equals(invocation.name())) {
            try {
                if (registry.delegateRoles().require(ToolRegistry.mapper.readTree(invocation.argumentsJson()).path("role").asText()).writes()) {
                    return ToolBatchKind.SERIAL;
                }
            } catch (Exception ignored) {
                // 非法参数按串行交给正常参数校验，不能借解析失败进入并发路径。
                return ToolBatchKind.SERIAL;
            }
            return ToolBatchKind.DELEGATION;
        }
        return tool.effect() == ToolEffect.READ_ONLY && tool.idempotency() == Idempotency.IDEMPOTENT
                ? ToolBatchKind.READ_ONLY : ToolBatchKind.SERIAL;
    }

    List<ToolExecutionResult> executeToolsInternal(List<ToolInvocation> invocations,
                                                           ToolSnapshot snapshot) {
        ToolResultSizeManager.resetTurnBudget();
        if (invocations == null || invocations.isEmpty()) {
            return List.of();
        }
        if (snapshot != null && !registry.isSnapshotCurrent(snapshot)) {
            return invocations.stream()
                    .map(invocation -> staleToolSnapshotResult(invocation, snapshot))
                    .toList();
        }
        if (CancellationContext.isCancelled()) {
            return invocations.stream()
                    .map(invocation -> ToolExecutionResult.cancelled(
                                invocation, "用户取消了此次工具调用", 0,
                                snapshot == null
                                        ? registry.toolPresentation(invocation.name())
                                        : snapshot.presentation(invocation.name())))
                    .toList();
        }
        int parallelism = Math.min(invocations.size(), registry.maxParallelTools);
        SkillContextBuffer activeSkillBuffer = registry.activeSkillContextBuffer();
        ToolAccessScope activeAccessScope = registry.currentToolAccessScope();
        Set<String> activeAllowedTools = registry.access.allowedToolNames.get();
        List<String> activeAllowedWriteGlobs = registry.access.allowedWriteGlobs.get();
        ToolAccessContext.GrantScope activeTaskGrant = registry.access.currentGrantScope();
        DelegateTaskTool.Handler activeDelegation = registry.access.delegationHandler.get();
        String activeResourceLeaseStep = registry.access.resourceLeaseStep.get();
        String activeToolTask = ToolAccessContext.toolTaskIdentity.get();
        ExecutorService executor = Executors.newFixedThreadPool(parallelism, r -> {
            Thread thread = new Thread(r, "devcli-tool-executor");
            thread.setDaemon(true);
            return thread;
        });
        ScheduledExecutorService deadlineExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "devcli-tool-deadline");
            thread.setDaemon(true);
            return thread;
        });
        CancellationToken upstream = CancellationContext.current();
        List<CancellationToken> callTokens = invocations.stream()
                .map(ignored -> upstream == null
                        ? new CancellationToken()
                        : upstream.childToken())
                .toList();
        long batchStartedAt = System.nanoTime();
        long batchDeadlineNanos = deadlineAfterSeconds(batchStartedAt, registry.toolBatchTimeoutSeconds);
        boolean restoreInterrupt = false;

        try {
            List<ToolExecutionResult> results = new ArrayList<>(invocations.size());
            for (int start = 0; start < invocations.size();) {
                ToolBatchKind kind = toolBatchKind(invocations.get(start), snapshot);
                int end = start + 1;
                while (kind != ToolBatchKind.SERIAL && end < invocations.size()
                        && toolBatchKind(invocations.get(end), snapshot) == kind) end++;
                List<Future<ToolExecutionResult>> futures = new ArrayList<>(end - start);
                for (int i = start; i < end; i++) {
                    ToolInvocation invocation = invocations.get(i);
                    CancellationToken callToken = callTokens.get(i);
                    long timeout = toolTimeoutSeconds(invocation, snapshot);
                    long invocationDeadline = timeout > registry.toolBatchTimeoutSeconds
                            || activeDelegation != null && DelegateTaskTool.NAME.equals(invocation.name())
                            ? deadlineAfterSeconds(batchStartedAt,
                                    Math.max(registry.toolBatchTimeoutSeconds,
                                            timeout))
                            : batchDeadlineNanos;
                    futures.add(executor.submit(() -> ToolRegistry.runWithToolTask(activeToolTask, () -> registry.runWithDelegation(activeDelegation, () ->
                            registry.runWithAllowedTools(activeAllowedTools, () ->
                                    registry.access.runWithGrantScope(activeTaskGrant, () ->
                                            registry.runWithAllowedWritePaths(activeAllowedWriteGlobs, () -> executeInvocation(
                                                    invocation,
                                                    callToken,
                                                    invocationDeadline,
                                                    deadlineExecutor,
                                                    activeSkillBuffer,
                                                    activeAccessScope,
                                                    activeResourceLeaseStep,
                                                    snapshot))))))));
                }
                for (int i = start; i < end; i++) {
                    ToolInvocation invocation = invocations.get(i);
                    Future<ToolExecutionResult> future = futures.get(i - start);
                    while (true) {
                        try {
                            results.add(future.get());
                            break;
                        } catch (CancellationException e) {
                            results.add(resultForCancellation(
                                    invocation, callTokens.get(i), 0, snapshot));
                            break;
                        } catch (InterruptedException e) {
                            restoreInterrupt = true;
                            cancelAll(callTokens, CancellationToken.Reason.UPSTREAM,
                                    "等待工具结果时收到上游中断");
                        } catch (ExecutionException e) {
                            CancellationToken token = callTokens.get(i);
                            results.add(token.cancellation().isPresent()
                                    ? resultForCancellation(invocation, token, 0, snapshot)
                                    : ToolExecutionResult.failed(
                                            invocation, causeMessage(e), 0,
                                            snapshot == null
                                                    ? registry.toolPresentation(invocation.name())
                                                    : snapshot.presentation(invocation.name())));
                            break;
                        }
                    }
                }
                start = end;
            }
            return results;
        } finally {
            deadlineExecutor.shutdownNow();
            executor.shutdown();
            callTokens.forEach(CancellationToken::close);
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    boolean hasLegacyExecuteToolsOverride() {
        try {
            return registry.getClass().getMethod("executeTools", List.class).getDeclaringClass()
                    != ToolRegistry.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    ToolExecutionResult staleToolSnapshotResult(
            ToolInvocation invocation, ToolSnapshot snapshot) {
        String message = "工具定义快照已失效，已拒绝执行 " + invocation.name()
                + "；请重新获取工具定义后重试";
        ToolPresentation presentation = snapshot == null
                ? registry.toolPresentation(invocation.name())
                : snapshot.presentation(invocation.name());
        return ToolExecutionResult.staleToolSnapshot(invocation, message, presentation);
    }

    ToolExecutionResult executeInvocation(
            ToolInvocation invocation,
            CancellationToken callToken,
            long batchDeadlineNanos,
            ScheduledExecutorService deadlineExecutor,
            SkillContextBuffer activeSkillBuffer,
            ToolAccessScope activeAccessScope,
            String activeResourceLeaseStep,
            ToolSnapshot snapshot) {
        long startedAt = System.nanoTime();
        if (snapshot != null && !registry.isSnapshotCurrent(snapshot)) {
            return staleToolSnapshotResult(invocation, snapshot);
        }
        long timeoutSeconds = toolTimeoutSeconds(invocation, snapshot);
        long invocationDeadline = Math.min(
                batchDeadlineNanos, deadlineAfterSeconds(startedAt, timeoutSeconds));
        ToolExecutionContext executionContext = new ToolExecutionContext(
                invocation.id(), callToken, startedAt, invocationDeadline);
        long delayNanos = invocationDeadline - System.nanoTime();
        ScheduledFuture<?> timeoutTask = null;
        if (delayNanos <= 0) {
            callToken.cancel(CancellationToken.Reason.TIMEOUT,
                    "工具调用期限已到");
        } else {
            timeoutTask = deadlineExecutor.schedule(
                    () -> callToken.cancel(
                            CancellationToken.Reason.TIMEOUT,
                            "工具调用超过期限 " + timeoutSeconds + " 秒"),
                    delayNanos,
                    TimeUnit.NANOSECONDS);
        }

        Thread worker = Thread.currentThread();
        try (CancellationToken.Registration ignored = callToken.onCancel(
                cancellation -> worker.interrupt())) {
            if (callToken.cancellation().isPresent()) {
                return resultForCancellation(
                        invocation, callToken, executionContext.elapsedMillis(), snapshot);
            }
            java.util.concurrent.atomic.AtomicReference<ToolOutput> output =
                    new java.util.concurrent.atomic.AtomicReference<>(ToolOutput.text(""));
            boolean serializeSideEffect = isSerializedSideEffect(invocation.name(), snapshot);
            registry.runWithToolAccess(activeAccessScope, () ->
                    registry.runWithResourceLease(activeResourceLeaseStep, () -> {
                        if (!serializeSideEffect) {
                            registry.runWithSkillContextBuffer(activeSkillBuffer,
                                    () -> output.set(registry.executeToolOutput(
                                            invocation, executionContext, snapshot)));
                            return null;
                        }
                        // 叶子副作用：工作区级公平锁串行，避免同一并行批次竞态同一资源
                        boolean locked;
                        try {
                            long remainingNanos = executionContext.remainingNanos();
                            if (remainingNanos == Long.MAX_VALUE) {
                                sideEffectSerializeLock.lockInterruptibly();
                                locked = true;
                            } else {
                                locked = sideEffectSerializeLock.tryLock(remainingNanos, TimeUnit.NANOSECONDS);
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new java.util.concurrent.CancellationException("等待副作用串行锁时被中断");
                        }
                        if (!locked) {
                            throw new ResourceLeaseException("另一处写入正在修改工作区，本次写入排队超时，请缩小并行写入或重试");
                        }
                        try {
                            registry.runWithSkillContextBuffer(activeSkillBuffer,
                                    () -> output.set(registry.executeToolOutput(
                                            invocation, executionContext, snapshot)));
                        } finally {
                            if (sideEffectSerializeLock.isHeldByCurrentThread()) {
                                sideEffectSerializeLock.unlock();
                            }
                        }
                        return null;
                    }));
            if (callToken.cancellation().isPresent()) {
                return resultForCancellation(
                        invocation, callToken, executionContext.elapsedMillis(), snapshot);
            }
            return ToolExecutionResult.completed(
                    invocation,
                    output.get(),
                    executionContext.elapsedMillis(),
                    snapshot == null
                            ? registry.toolPresentation(invocation.name())
                            : snapshot.presentation(invocation.name()));
        } catch (RuntimeException e) {
            if (callToken.cancellation().isPresent()) {
                return resultForCancellation(
                        invocation, callToken, executionContext.elapsedMillis(), snapshot);
            }
            return ToolExecutionResult.failed(
                    invocation,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                    executionContext.elapsedMillis(),
                    snapshot == null
                            ? registry.toolPresentation(invocation.name())
                            : snapshot.presentation(invocation.name()));
        } finally {
            if (timeoutTask != null) {
                timeoutTask.cancel(false);
            }
            if (callToken.cancellation().isPresent()) {
                Thread.interrupted();
            }
        }
    }

    ToolExecutionResult resultForCancellation(
            ToolInvocation invocation, CancellationToken token, long elapsedMillis,
            ToolSnapshot snapshot) {
        CancellationToken.Cancellation cancellation = token.cancellation()
                .orElse(new CancellationToken.Cancellation(
                        CancellationToken.Reason.INTERRUPTED, "工具执行被中断"));
        ToolPresentation presentation = snapshot == null
                ? registry.toolPresentation(invocation.name())
                : snapshot.presentation(invocation.name());
        if (cancellation.reason() == CancellationToken.Reason.TIMEOUT) {
            return ToolExecutionResult.timedOut(
                    invocation,
                    toolTimeoutSeconds(invocation, snapshot),
                    elapsedMillis,
                    presentation);
        }
        String message = cancellation.message().isBlank()
                ? "用户取消了此次工具调用"
                : cancellation.message();
        return ToolExecutionResult.cancelled(
                invocation, message, elapsedMillis, presentation);
    }

    static void cancelAll(List<CancellationToken> tokens,
                                  CancellationToken.Reason reason,
                                  String message) {
        for (CancellationToken token : tokens) {
            token.cancel(reason, message);
        }
    }

    static long deadlineAfterSeconds(long startedAtNanos, long timeoutSeconds) {
        long duration = TimeUnit.SECONDS.toNanos(timeoutSeconds);
        if (duration == Long.MAX_VALUE || Long.MAX_VALUE - startedAtNanos < duration) {
            return Long.MAX_VALUE;
        }
        return startedAtNanos + duration;
    }

    long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    static String causeMessage(ExecutionException e) {
        Throwable cause = e.getCause();
        return cause == null || cause.getMessage() == null ? "未知错误" : cause.getMessage();
    }

    /** 单个工具的执行上限：声明过独立超时用声明值，否则继承批次超时。 */
    long toolTimeoutSeconds(ToolInvocation invocation, ToolSnapshot snapshot) {
        Tool tool;
        if (snapshot == null) {
            tool = registry.activeTool(invocation.name());
        } else {
            ToolBinding binding = snapshot.binding(invocation.name());
            tool = binding == null ? null : binding.tool();
        }
        if (tool == null) return registry.toolBatchTimeoutSeconds;
        long configured = tool.hasOwnTimeout() ? tool.timeoutSeconds() : registry.toolBatchTimeoutSeconds;
        return tool.executor().executionTimeoutSeconds(invocation.argumentsJson(), configured);
    }
}
