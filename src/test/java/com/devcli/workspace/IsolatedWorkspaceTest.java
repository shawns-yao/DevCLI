package com.devcli.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IsolatedWorkspaceTest {

    @Test
    void buildsAndAppliesStructuredPatchSet(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("project");
        Path workspaceBase = tempDir.resolve("workspaces");
        Files.createDirectories(project);
        Files.writeString(project.resolve("modify.txt"), "before");
        Files.writeString(project.resolve("delete.txt"), "delete");

        PatchSet patchSet;
        Path workspacePath;
        try (IsolatedWorkspace workspace = IsolatedWorkspace.create(project, workspaceBase, "step-1")) {
            workspacePath = workspace.path();
            Files.writeString(workspace.path().resolve("modify.txt"), "after");
            Files.delete(workspace.path().resolve("delete.txt"));
            Files.writeString(workspace.path().resolve("add.txt"), "added");
            patchSet = workspace.createPatchSet();

            assertEquals(List.of("add.txt", "delete.txt", "modify.txt"),
                    patchSet.changes().stream().map(PatchSet.FileChange::relativePath).sorted().toList());
            PatchSet.ApplyResult result = patchSet.apply(project);
            assertTrue(result.applied());
        }

        assertFalse(Files.exists(workspacePath));
        assertEquals("after", Files.readString(project.resolve("modify.txt")));
        assertFalse(Files.exists(project.resolve("delete.txt")));
        assertEquals("added", Files.readString(project.resolve("add.txt")));
    }

    @Test
    void rejectsWholePatchWhenOriginalProjectChanged(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("project");
        Path workspaceBase = tempDir.resolve("workspaces");
        Files.createDirectories(project);
        Files.writeString(project.resolve("shared.txt"), "base");

        try (IsolatedWorkspace workspace = IsolatedWorkspace.create(project, workspaceBase, "step-2")) {
            Files.writeString(workspace.path().resolve("shared.txt"), "worker");
            Files.writeString(workspace.path().resolve("new.txt"), "new");
            PatchSet patchSet = workspace.createPatchSet();

            Files.writeString(project.resolve("shared.txt"), "user-change");
            PatchSet.ApplyResult result = patchSet.apply(project);

            assertFalse(result.applied());
            assertEquals(List.of("shared.txt"), result.conflicts());
            assertEquals("user-change", Files.readString(project.resolve("shared.txt")));
            assertFalse(Files.exists(project.resolve("new.txt")),
                    "冲突时不得部分应用其他文件");
        }
    }
}
