package com.devcli.workspace;

import com.devcli.tool.ToolRegistry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 隔离工作区和项目级 ToolRegistry 的统一生命周期入口。
 */
public final class WorkspaceExecutionSession implements AutoCloseable {
    private final Path projectRoot;
    private final IsolatedWorkspace workspace;
    private final ToolRegistry toolRegistry;

    private WorkspaceExecutionSession(Path projectRoot,
                                      IsolatedWorkspace workspace,
                                      ToolRegistry toolRegistry) {
        this.projectRoot = projectRoot;
        this.workspace = workspace;
        this.toolRegistry = toolRegistry;
    }

    public static WorkspaceExecutionSession open(ToolRegistry parent, String stepId) throws IOException {
        Objects.requireNonNull(parent, "parent");
        Path projectRoot = Path.of(parent.getProjectPath()).toAbsolutePath().normalize();
        IsolatedWorkspace workspace = IsolatedWorkspace.create(projectRoot, stepId);
        try {
            ToolRegistry fork = parent.forkForProject(workspace.path());
            return new WorkspaceExecutionSession(projectRoot, workspace, fork);
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
        Objects.requireNonNull(patchSet, "patchSet");
        return patchSet.apply(projectRoot);
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
