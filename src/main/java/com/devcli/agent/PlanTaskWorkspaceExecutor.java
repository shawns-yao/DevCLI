package com.devcli.agent;

import com.devcli.runtime.CancellationContext;
import com.devcli.tool.ToolRegistry;
import com.devcli.workspace.PatchSet;
import com.devcli.workspace.WorkspaceExecutionSession;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 统一 Plan 任务的能力范围、隔离工作区、资源租约和补丁应用生命周期。
 */
final class PlanTaskWorkspaceExecutor {
    private final ToolRegistry parentRegistry;

    PlanTaskWorkspaceExecutor(ToolRegistry parentRegistry) {
        this.parentRegistry = Objects.requireNonNull(parentRegistry, "parentRegistry");
    }

    <T> Execution<T> execute(String taskId, boolean isolated,
                             Function<ToolRegistry, T> taskAction) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(taskAction, "taskAction");
        return isolated
                ? executeIsolated(taskId, taskAction)
                : executeReadOnly(taskId, taskAction);
    }

    private <T> Execution<T> executeReadOnly(String taskId,
                                             Function<ToolRegistry, T> taskAction) {
        try {
            T value = parentRegistry.runWithToolAccess(
                    ToolRegistry.ToolAccessScope.READ_ONLY,
                    () -> parentRegistry.runWithResourceLease(taskId,
                            () -> taskAction.apply(parentRegistry)));
            return Execution.success(value, parentRegistry.consumeStepModifiedFiles(taskId));
        } catch (Exception e) {
            return Execution.failure(e, parentRegistry.consumeStepModifiedFiles(taskId));
        } finally {
            parentRegistry.releaseResourceLeases(taskId);
        }
    }

    private <T> Execution<T> executeIsolated(String taskId,
                                             Function<ToolRegistry, T> taskAction) {
        try (WorkspaceExecutionSession session =
                     WorkspaceExecutionSession.open(parentRegistry, taskId)) {
            ToolRegistry isolatedRegistry = session.toolRegistry();
            try {
                T value = isolatedRegistry.runWithToolAccess(
                        ToolRegistry.ToolAccessScope.ISOLATED_PROJECT,
                        () -> isolatedRegistry.runWithResourceLease(taskId,
                                () -> taskAction.apply(isolatedRegistry)));
                if (CancellationContext.isCancelled()) {
                    return Execution.failure(new IOException("用户取消"), List.of());
                }
                PatchSet.ApplyResult applyResult = session.apply(session.patchSet());
                if (!applyResult.applied()) {
                    return Execution.failure(
                            new IOException(applyResult.failureDescription()), List.of());
                }
                return Execution.success(value, applyResult.modifiedResources());
            } finally {
                isolatedRegistry.releaseResourceLeases(taskId);
                parentRegistry.releaseResourceLeases(taskId);
            }
        } catch (Exception e) {
            return Execution.failure(e, List.of());
        }
    }

    record Execution<T>(T value, List<String> modifiedFiles, Exception error) {
        Execution {
            modifiedFiles = modifiedFiles == null ? List.of() : List.copyOf(modifiedFiles);
        }

        static <T> Execution<T> success(T value, List<String> modifiedFiles) {
            return new Execution<>(value, modifiedFiles, null);
        }

        static <T> Execution<T> failure(Exception error, List<String> modifiedFiles) {
            return new Execution<>(null, modifiedFiles,
                    error == null ? new IllegalStateException("任务执行失败") : error);
        }

        boolean failed() {
            return error != null;
        }
    }
}
