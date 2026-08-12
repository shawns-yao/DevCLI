package com.devcli.runtime;

import com.devcli.tool.ToolRegistry;
import com.devcli.workspace.PatchSet;
import com.devcli.workspace.WorkspaceExecutionSession;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** ReAct 单轮项目写入事务：工具只修改隔离工作区，结束时通过 PatchSet 原子提交。 */
public final class ReactWorkspaceTransaction implements AutoCloseable {
    private final WorkspaceExecutionSession workspace;

    private ReactWorkspaceTransaction(WorkspaceExecutionSession workspace) {
        this.workspace = workspace;
    }

    public static ReactWorkspaceTransaction open(ToolRegistry parent, String turnId)
            throws IOException {
        return new ReactWorkspaceTransaction(WorkspaceExecutionSession.open(
                Objects.requireNonNull(parent, "parent"), "react-" + safe(turnId)));
    }

    public ToolRegistry toolRegistry() {
        return workspace.toolRegistry();
    }

    public CommitResult commit() throws IOException {
        PatchSet patchSet = workspace.patchSet();
        if (patchSet.isEmpty()) return new CommitResult(true, List.of(), "");
        PatchSet.ApplyResult result = workspace.apply(patchSet);
        return new CommitResult(result.applied(), result.modifiedResources(),
                result.applied() ? "" : result.failureDescription());
    }

    @Override
    public void close() throws IOException {
        workspace.close();
    }

    public record CommitResult(boolean success, List<String> modifiedResources, String message) {
        public CommitResult {
            modifiedResources = modifiedResources == null ? List.of() : List.copyOf(modifiedResources);
            message = message == null ? "" : message;
        }
    }

    private static String safe(String value) {
        String normalized = value == null ? "turn" : value.replaceAll("[^a-zA-Z0-9._-]", "-");
        return normalized.isBlank() ? "turn" : normalized;
    }
}
