package com.devcli.workspace;

import com.devcli.tool.ToolRegistry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 隔离工作区和项目级 ToolRegistry 的统一生命周期入口。
 */
public final class WorkspaceExecutionSession implements AutoCloseable {
    private final Path projectRoot;
    private final IsolatedWorkspace workspace;
    private final ToolRegistry toolRegistry;
    private final ToolRegistry parentToolRegistry;
    private final String stepId;
    private final List<String> allowedWritePaths;

    private WorkspaceExecutionSession(Path projectRoot,
                                      IsolatedWorkspace workspace,
                                      ToolRegistry toolRegistry,
                                      ToolRegistry parentToolRegistry,
                                      String stepId, List<String> allowedWritePaths) {
        this.projectRoot = projectRoot;
        this.workspace = workspace;
        this.toolRegistry = toolRegistry;
        this.parentToolRegistry = parentToolRegistry;
        this.stepId = stepId;
        this.allowedWritePaths = allowedWritePaths == null ? List.of() : List.copyOf(allowedWritePaths);
    }

    public static WorkspaceExecutionSession open(ToolRegistry parent, String stepId) throws IOException {
        return open(parent, stepId, List.of());
    }

    public static WorkspaceExecutionSession open(ToolRegistry parent, String stepId,
                                                  List<String> allowedWritePaths) throws IOException {
        Objects.requireNonNull(parent, "parent");
        Path projectRoot = Path.of(parent.getProjectPath()).toAbsolutePath().normalize();
        IsolatedWorkspace workspace = IsolatedWorkspace.create(projectRoot, stepId);
        try {
            ToolRegistry fork = parent.forkForProject(workspace.path());
            return new WorkspaceExecutionSession(projectRoot, workspace, fork, parent, stepId, allowedWritePaths);
        } catch (Exception e) {
            workspace.close();
            throw e;
        }
    }

    public ToolRegistry toolRegistry() {
        return toolRegistry;
    }

    public Path workspacePath() {
        return workspace.path();
    }

    public PatchSet patchSet() throws IOException {
        return workspace.createPatchSet();
    }

    public PatchSet.ApplyResult apply(PatchSet patchSet) {
        try {
            return commit(patchSet, ignored -> { }, result -> { });
        } catch (Exception e) {
            return PatchSet.ApplyResult.failure("PatchSet 提交失败: " + e.getMessage());
        }
    }

    public PatchSet.ApplyResult commit(PatchSet patchSet,
                                       Consumer<PatchSet.ApplyResult> terminalDecision) throws Exception {
        return commit(patchSet, ignored -> { }, terminalDecision);
    }

    public PatchSet.ApplyResult commit(PatchSet patchSet,
                                       CommitPreparation preparation,
                                       Consumer<PatchSet.ApplyResult> terminalDecision) throws Exception {
        Objects.requireNonNull(patchSet, "patchSet");
        CommitPreparation beforeApply = preparation == null ? ignored -> { } : preparation;
        Consumer<PatchSet.ApplyResult> decision = terminalDecision == null
                ? result -> { }
                : terminalDecision;
        return ProjectCommitCoordinator.withProjectLock(projectRoot, () -> {
            ContextVersionLedger.PatchPreparation prepared = toolRegistry.contextVersionLedger()
                    .preparePatchSet(stepId, patchSet, projectRoot);
            WriteGateResult writeGate = prepared.writeGate();
            if (!writeGate.isAllowed()) {
                PatchSet.ApplyResult result = PatchSet.ApplyResult.contextStale(writeGate.reason());
                decision.accept(result);
                return result;
            }
            PatchSet effectivePatchSet = prepared.patchSet();
            List<String> deniedPaths = toolRegistry.runWithAllowedWritePaths(allowedWritePaths,
                    () -> effectivePatchSet.changes().stream()
                            .map(PatchSet.FileChange::relativePath)
                            .filter(path -> !toolRegistry.isWritePathAllowed(path))
                            .toList());
            if (!deniedPaths.isEmpty()) {
                PatchSet.ApplyResult result = PatchSet.ApplyResult.failure(
                        "写入路径超出委派范围: " + String.join(", ", deniedPaths));
                decision.accept(result);
                return result;
            }
            beforeApply.prepare(effectivePatchSet);
            PatchSet.ApplyResult result = effectivePatchSet.apply(projectRoot);
            if (result.applied()) {
                parentToolRegistry.invalidateToolResultCache();
                toolRegistry.contextVersionLedger().publishPatchSet(stepId, effectivePatchSet, projectRoot);
                toolRegistry.markRagIndexDirty(effectivePatchSet.changes().stream()
                        .map(PatchSet.FileChange::relativePath)
                        .toList());
            }
            decision.accept(result);
            return result;
        });
    }

    @FunctionalInterface
    public interface CommitPreparation {
        void prepare(PatchSet effectivePatchSet) throws Exception;
    }

    @Override
    public void close() throws IOException {
        try {
            toolRegistry.close();
        } finally {
            workspace.close();
        }
    }
}
