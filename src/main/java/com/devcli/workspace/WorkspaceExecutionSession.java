package com.devcli.workspace;

import com.devcli.tool.ToolRegistry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 隔离工作区和项目级 ToolRegistry 的统一生命周期入口。
 */
public final class WorkspaceExecutionSession implements AutoCloseable {
    private final Path projectRoot;
    private final IsolatedWorkspace workspace;
    private final ToolRegistry toolRegistry;
    private final String stepId;

    private WorkspaceExecutionSession(Path projectRoot,
                                      IsolatedWorkspace workspace,
                                      ToolRegistry toolRegistry,
                                      String stepId) {
        this.projectRoot = projectRoot;
        this.workspace = workspace;
        this.toolRegistry = toolRegistry;
        this.stepId = stepId;
    }

    public static WorkspaceExecutionSession open(ToolRegistry parent, String stepId) throws IOException {
        Objects.requireNonNull(parent, "parent");
        Path projectRoot = Path.of(parent.getProjectPath()).toAbsolutePath().normalize();
        IsolatedWorkspace workspace = IsolatedWorkspace.create(projectRoot, stepId);
        try {
            ToolRegistry fork = parent.forkForProject(workspace.path());
            return new WorkspaceExecutionSession(projectRoot, workspace, fork, stepId);
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
            WriteGateResult writeGate = toolRegistry.contextVersionLedger()
                    .validatePatchSet(stepId, patchSet, projectRoot);
            if (!writeGate.isAllowed()) {
                PatchSet.ApplyResult result = PatchSet.ApplyResult.contextStale(writeGate.reason());
                decision.accept(result);
                return result;
            }
            PatchSet effectivePatchSet = toolRegistry.contextVersionLedger()
                    .rebaseRefreshedChanges(stepId, patchSet, projectRoot);
            beforeApply.prepare(effectivePatchSet);
            PatchSet.ApplyResult result = effectivePatchSet.apply(projectRoot);
            if (result.applied()) {
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
