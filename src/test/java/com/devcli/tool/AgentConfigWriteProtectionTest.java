package com.devcli.tool;

import com.devcli.hitl.ApprovalRequest;
import com.devcli.hitl.ApprovalResult;
import com.devcli.hitl.HitlHandler;
import com.devcli.hitl.HitlToolRegistry;
import com.devcli.policy.TaskGrant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Targeted checks through the public tool entry; only human approval is scripted. */
class AgentConfigWriteProtectionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path root;

    private ToolRegistry registry() {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(root.toString());
        return registry;
    }

    private ToolOutput call(ToolRegistry registry, String name, Map<String, ?> arguments) throws Exception {
        return registry.executeToolOutput(name, JSON.writeValueAsString(arguments));
    }

    private void file(String name, String content) throws Exception {
        Files.createDirectories(root.resolve(name).getParent());
        Files.writeString(root.resolve(name), content);
    }

    private void denied(ToolOutput output) {
        assertFalse(output.isSuccess(), output.text());
        assertTrue(output.text().contains("受保护"), output.text());
    }

    @Test
    void creationIsBlockedWithoutAnyApprovalHandler() throws Exception {
        try (var registry = registry()) {
            for (String name : List.of("hooks.json", "mcp.json", "config.json")) {
                denied(call(registry, "write_file", Map.of("path", ".devcli/" + name, "content", "{}")));
                assertFalse(Files.exists(root.resolve(".devcli/" + name)));
            }
        }
    }

    @Test
    void editsAndDeletionLeaveConfigurationUnchanged() throws Exception {
        file(".devcli/hooks.json", "{}");
        file("ordinary.txt", "keep");
        try (var registry = registry()) {
            denied(call(registry, "edit_file", Map.of("path", ".devcli/hooks.json",
                    "old_string", "{}", "new_string", "{\"hooks\":[]}")));
            denied(call(registry, "delete_files", Map.of("paths", List.of("ordinary.txt", ".devcli/hooks.json"))));
            assertEquals("{}", Files.readString(root.resolve(".devcli/hooks.json")));
            assertEquals("keep", Files.readString(root.resolve("ordinary.txt")));
        }
    }

    @Test
    void patchCannotCreateConfigOrPartiallyApplyOrdinaryChanges() throws Exception {
        try (var registry = registry()) {
            denied(call(registry, "apply_patch", Map.of("patch", """
                    *** Begin Patch
                    *** Add File: ordinary.txt
                    +hello
                    *** Add File: .devcli/mcp.json
                    +{}
                    *** End Patch
                    """)));
            assertFalse(Files.exists(root.resolve("ordinary.txt")));
            assertFalse(Files.exists(root.resolve(".devcli/mcp.json")));
        }
    }

    @Test
    void patchCannotMoveIntoOrDeleteConfiguration() throws Exception {
        file("ordinary.txt", "before\n");
        file(".devcli/mcp.json", "{}\n");
        try (var registry = registry()) {
            denied(call(registry, "apply_patch", Map.of("patch", """
                    *** Begin Patch
                    *** Update File: ordinary.txt
                    *** Move to: .devcli/hooks.json
                    @@
                    -before
                    +after
                    *** End Patch
                    """)));
            denied(call(registry, "apply_patch", Map.of("patch", """
                    *** Begin Patch
                    *** Delete File: .devcli/mcp.json
                    *** End Patch
                    """)));
            assertEquals("before\n", Files.readString(root.resolve("ordinary.txt")));
            assertEquals("{}\n", Files.readString(root.resolve(".devcli/mcp.json")));
        }
    }

    @Test
    void customHookPathCannotBeOverwrittenOrPreemptedByParentFile() throws Exception {
        String old = System.getProperty("devcli.hooks.file");
        System.setProperty("devcli.hooks.file", root.resolve("custom/hooks.json").toString());
        try (var registry = registry()) {
            denied(call(registry, "write_file", Map.of("path", "custom/hooks.json", "content", "{}")));
            denied(call(registry, "write_file", Map.of("path", "custom", "content", "{}")));
            assertFalse(Files.exists(root.resolve("custom")));
        } finally {
            if (old == null) System.clearProperty("devcli.hooks.file");
            else System.setProperty("devcli.hooks.file", old);
        }
    }

    @Test
    void physicalAliasOfConfigurationIsProtected() throws Exception {
        file(".devcli/hooks.json", "{}");
        try {
            Files.createLink(root.resolve("alias.json"), root.resolve(".devcli/hooks.json"));
        } catch (Exception unsupported) {
            assumeTrue(false, "Hard links unavailable");
        }
        try (var registry = registry()) {
            denied(call(registry, "write_file", Map.of("path", "alias.json", "content", "changed")));
            assertEquals("{}", Files.readString(root.resolve(".devcli/hooks.json")));
        }
    }

    @Test
    void approvalAndTaskGrantCannotOverrideProtection() throws Exception {
        var handler = new HitlHandler() {
            int calls;
            public ApprovalResult requestApproval(ApprovalRequest request) {
                calls++;
                return ApprovalResult.approve();
            }
            public boolean isEnabled() { return true; }
            public void setEnabled(boolean enabled) { }
            public boolean isApprovedAllByTool(String name) { return true; }
        };
        try (var registry = new HitlToolRegistry(handler)) {
            registry.setProjectPath(root.toString());
            String args = JSON.writeValueAsString(Map.of("path", ".devcli/mcp.json", "content", "{}"));
            denied(registry.runWithTaskGrant(new TaskGrant(List.of("**"), false),
                    () -> registry.executeToolOutput("write_file", args)));
            assertEquals(0, handler.calls);
            assertFalse(Files.exists(root.resolve(".devcli/mcp.json")));
        }
    }

    @Test
    void ordinarySourcesExamplesAndInternalNotesRemainWritable() throws Exception {
        try (var registry = registry()) {
            for (String path : List.of("src/Main.java", "config/hooks.json",
                    ".devcli/notes.txt", ".devcli/hooks.json.example")) {
                ToolOutput output = call(registry, "write_file", Map.of("path", path, "content", "ordinary"));
                assertTrue(output.isSuccess(), output.text());
                assertEquals("ordinary", Files.readString(root.resolve(path)));
            }
        }
    }

    @Test
    void isolatedRegistryAlsoProtectsCustomHookPathInAuthoritativeProject() throws Exception {
        String old = System.getProperty("devcli.hooks.file");
        System.setProperty("devcli.hooks.file", root.resolve("custom/hooks.json").toString());
        Path isolated = Files.createDirectories(root.resolve("isolated"));
        try (var parent = registry(); var child = parent.forkForProject(isolated)) {
            denied(call(child, "write_file", Map.of("path", "custom/hooks.json", "content", "{}")));
            assertFalse(Files.exists(isolated.resolve("custom/hooks.json")));
        } finally {
            if (old == null) System.clearProperty("devcli.hooks.file");
            else System.setProperty("devcli.hooks.file", old);
        }
    }

    @Test
    void restoreToolPreservesCurrentConfigurationWhileRestoringOrdinaryFile() throws Exception {
        String oldDir = System.getProperty("devcli.snapshot.dir");
        String oldEnabled = System.getProperty("devcli.snapshot.enabled");
        System.setProperty("devcli.snapshot.dir", root.resolve(".devcli/snapshots").toString());
        System.setProperty("devcli.snapshot.enabled", "true");
        file(".devcli/hooks.json", "{\"hooks\":[]}");
        file("ordinary.txt", "before");
        try (var registry = registry()) {
            registry.snapshotService().snapshotBeforeTurn("config-protection", "test fixture");
            file(".devcli/hooks.json", "{\"hooks\":[],\"schemaVersion\":1}");
            file("ordinary.txt", "after");
            ToolOutput result = call(registry, "revert_turn", Map.of("offset", 1));
            assertTrue(result.isSuccess(), result.text());
            assertTrue(result.text().contains("安全配置不参与"));
            assertEquals("before", Files.readString(root.resolve("ordinary.txt")));
            assertEquals("{\"hooks\":[],\"schemaVersion\":1}",
                    Files.readString(root.resolve(".devcli/hooks.json")));
        } finally {
            if (oldDir == null) System.clearProperty("devcli.snapshot.dir");
            else System.setProperty("devcli.snapshot.dir", oldDir);
            if (oldEnabled == null) System.clearProperty("devcli.snapshot.enabled");
            else System.setProperty("devcli.snapshot.enabled", oldEnabled);
        }
    }
}
