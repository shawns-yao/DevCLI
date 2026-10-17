package com.devcli.tool;

import com.devcli.tool.command.CommandExecutionService;
import com.devcli.tool.environment.LocalExecutionEnvironment;
import com.devcli.workspace.PatchSet;
import com.devcli.workspace.WorkspaceExecutionSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionEnvironmentTest {
    @TempDir Path root;

    @Test
    void registryRoutesFilesSearchAndCommandsThroughOneEnvironment() throws Exception {
        var environment = new RecordingEnvironment();
        try (var tools = new ToolRegistry()) {
            tools.setProjectPath(root.toString());
            tools.setExecutionEnvironment(environment);
            assertSuccess(tools, "write_file", "{\"path\":\"src/a.txt\",\"content\":\"first\\nsecond\"}");
            assertSuccess(tools, "read_file", "{\"path\":\"src/a.txt\",\"limit\":5}");
            assertSuccess(tools, "edit_file", "{\"path\":\"src/a.txt\",\"old_string\":\"first\",\"new_string\":\"changed\"}");
            assertTrue(tools.executeToolOutput("list_dir", "{\"path\":\"src\"}").text().contains("a.txt"));
            assertTrue(tools.executeToolOutput("glob", "{\"pattern\":\"**/*.txt\"}").text().contains("src/a.txt"));
            assertTrue(tools.executeToolOutput("grep_code", "{\"pattern\":\"changed\"}").text().contains("changed"));
            assertSuccess(tools, "execute_command", "{\"command\":\"test\",\"timeout\":10}");
            assertTrue(environment.operations.containsAll(List.of("write", "read", "list", "find", "command")));
            int writes = environment.writes.size();
            assertEquals(ToolErrorCode.POLICY_DENIED,
                    tools.executeToolOutput("write_file", "{\"path\":\"../escape.txt\",\"content\":\"bad\"}").errorCode());
            assertEquals(ToolErrorCode.POLICY_DENIED,
                    tools.executeToolOutput("write_file", "{\"path\":\".env\",\"content\":\"bad\"}").errorCode());
            assertEquals(writes, environment.writes.size());
            assertSuccess(tools, "delete_files", "{\"paths\":[\"src/a.txt\"]}");
            assertTrue(environment.operations.contains("patch"));
            assertFalse(Files.exists(root.resolve("src/a.txt")));
        }
    }

    @Test
    void isolatedForkUsesBackendAndParentOnlyChangesAfterVersionCheckedCommit() throws Exception {
        Files.writeString(root.resolve("a.txt"), "base");
        var environment = new RecordingEnvironment();
        try (var parent = new ToolRegistry()) {
            parent.setProjectPath(root.toString());
            parent.setExecutionEnvironment(environment);
            try (var workspace = WorkspaceExecutionSession.open(parent, "step")) {
                assertSame(environment, workspace.toolRegistry().executionEnvironment());
                assertSuccess(workspace.toolRegistry(), "write_file", "{\"path\":\"a.txt\",\"content\":\"candidate\"}");
                assertEquals(workspace.workspacePath().resolve("a.txt").toRealPath(), environment.writes.getFirst());
                assertEquals("base", Files.readString(root.resolve("a.txt")));
                assertTrue(workspace.commit(workspace.patchSet(), ignored -> { }).applied());
                assertEquals("candidate", Files.readString(root.resolve("a.txt")));
                assertEquals(root.toRealPath(), environment.patchRoots.getLast().toRealPath());
            }
            try (var workspace = WorkspaceExecutionSession.open(parent, "conflict")) {
                assertSuccess(workspace.toolRegistry(), "write_file", "{\"path\":\"a.txt\",\"content\":\"old candidate\"}");
                Files.writeString(root.resolve("a.txt"), "external change");
                assertFalse(workspace.commit(workspace.patchSet(), ignored -> { }).applied());
                assertEquals("external change", Files.readString(root.resolve("a.txt")));
            }
        }
    }

    private void assertSuccess(ToolRegistry tools, String name, String arguments) {
        var output = tools.executeToolOutput(name, arguments);
        assertTrue(output.isSuccess(), output.text());
    }

    private static final class RecordingEnvironment extends LocalExecutionEnvironment {
        final List<String> operations = new ArrayList<>();
        final List<Path> writes = new ArrayList<>();
        final List<Path> patchRoots = new ArrayList<>();
        @Override public CommandExecutionService commands() {
            return new CommandExecutionService() {
                public boolean providesIsolation(boolean required) { return required; }
                public Result execute(Request request) { operations.add("command"); return Result.completed(0, "ok"); }
            };
        }
        @Override public InputStream openInput(Path path) throws IOException {
            operations.add("read"); return super.openInput(path);
        }
        @Override public void writeString(Path path, String content) throws IOException {
            operations.add("write"); writes.add(path); super.writeString(path, content);
        }
        @Override public List<DirectoryEntry> list(Path path) throws IOException {
            operations.add("list"); return super.list(path);
        }
        @Override public List<Path> findFiles(Path root, Path scope) {
            operations.add("find"); return super.findFiles(root, scope);
        }
        @Override public PatchSet.ApplyResult applyPatch(Path root, PatchSet patch) throws IOException {
            operations.add("patch"); patchRoots.add(root); return super.applyPatch(root, patch);
        }
    }
}
