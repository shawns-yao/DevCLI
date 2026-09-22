package com.devcli.hitl;

import com.devcli.policy.TaskGrant;
import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Targeted tests through the public tool-call entry; only the human interaction is scripted. */
class DeleteFilesApprovalTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path root;

    private String paths(String... paths) throws Exception {
        return JSON.writeValueAsString(java.util.Map.of("paths", paths));
    }

    private void file(String name) throws Exception {
        Files.createDirectories(root.resolve(name).getParent());
        Files.writeString(root.resolve(name), "original");
    }

    private HitlToolRegistry registry(Handler handler) {
        HitlToolRegistry registry = new HitlToolRegistry(handler);
        registry.setProjectPath(root.toString());
        return registry;
    }

    @Test
    void approvedDeletionDisplaysScopeAndReturnsActualResources() throws Exception {
        file("src/a.txt");
        file("src/b.txt");
        Handler handler = new Handler();
        try (var registry = registry(handler)) {
            ToolOutput output = registry.executeToolOutput("delete_files", paths("src/a.txt", "src/b.txt"));
            assertTrue(output.isSuccess(), output.text());
            assertEquals(1, handler.calls);
            assertTrue(handler.request.sensitiveNotice().contains("删除文件数量: 2"));
            assertTrue(handler.request.toDisplayText().contains("src/a.txt"));
            assertTrue(handler.request.sensitiveNotice().contains("不进入回收站"));
            assertFalse(Files.exists(root.resolve("src/a.txt")));
            assertFalse(Files.exists(root.resolve("src/b.txt")));
            assertTrue(Files.isDirectory(root.resolve("src")));
            assertEquals(List.of("src/a.txt", "src/b.txt"), output.modifiedResources());
        }
    }

    @Test
    void rejectionLeavesEveryFileUntouched() throws Exception {
        file("a.txt");
        Handler handler = new Handler();
        handler.answer = ignored -> ApprovalResult.reject("no");
        try (var registry = registry(handler)) {
            assertEquals(ToolErrorCode.HITL_REJECTED,
                    registry.executeToolOutput("delete_files", paths("a.txt")).errorCode());
            assertEquals("original", Files.readString(root.resolve("a.txt")));
        }
    }

    @Test
    void protectedTargetRejectsEntireBatchBeforeApproval() throws Exception {
        file("a.txt");
        file(".env");
        Handler handler = new Handler();
        try (var registry = registry(handler)) {
            assertEquals(ToolErrorCode.POLICY_DENIED,
                    registry.executeToolOutput("delete_files", paths("a.txt", ".env")).errorCode());
            assertEquals(0, handler.calls);
            assertTrue(Files.exists(root.resolve("a.txt")));
            assertTrue(Files.exists(root.resolve(".env")));
        }
    }

    @Test
    void protectedTargetRemainsDeniedWithApprovalDisabled() throws Exception {
        file(".git/config");
        Handler handler = new Handler();
        handler.enabled = false;
        try (var registry = registry(handler)) {
            assertEquals(ToolErrorCode.POLICY_DENIED,
                    registry.executeToolOutput("delete_files", paths(".git/config")).errorCode());
            assertTrue(Files.exists(root.resolve(".git/config")));
        }
    }

    @Test
    void smallDeletionWithinTaskGrantDoesNotPrompt() throws Exception {
        file("src/a.txt");
        Handler handler = new Handler();
        try (var registry = registry(handler)) {
            String arguments = paths("src/a.txt");
            ToolOutput output = registry.runWithTaskGrant(new TaskGrant(List.of("src/**"), false),
                    () -> registry.executeToolOutput("delete_files", arguments));
            assertTrue(output.isSuccess(), output.text());
            assertEquals(0, handler.calls);
        }
    }

    @Test
    void missingGrantCannotReuseApproveAll() throws Exception {
        file("a.txt");
        Handler handler = new Handler();
        handler.approvedAll = true;
        handler.answer = ignored -> ApprovalResult.reject("no");
        try (var registry = registry(handler)) {
            assertEquals(ToolErrorCode.HITL_REJECTED,
                    registry.executeToolOutput("delete_files", paths("a.txt")).errorCode());
            assertEquals(1, handler.calls);
        }
    }

    @Test
    void defaultThresholdForcesApprovalDespiteGrantAndApproveAll() throws Exception {
        String[] names = new String[50];
        for (int i = 0; i < names.length; i++) {
            names[i] = "f" + i + ".txt";
            file(names[i]);
        }
        Handler handler = new Handler();
        handler.approvedAll = true;
        handler.answer = ignored -> ApprovalResult.reject("no");
        try (var registry = registry(handler)) {
            String arguments = paths(names);
            ToolOutput output = registry.runWithTaskGrant(TaskGrant.WORKSPACE_WRITES,
                    () -> registry.executeToolOutput("delete_files", arguments));
            assertEquals(ToolErrorCode.HITL_REJECTED, output.errorCode());
            assertEquals(1, handler.calls);
            assertTrue(handler.request.sensitiveNotice().contains("数量: 50"));
            for (String name : names) assertTrue(Files.exists(root.resolve(name)));
        }
    }

    @Test
    void configurableThresholdIsApplied() throws Exception {
        file("a.txt");
        String property = "devcli.delete.approval.threshold";
        String previous = System.getProperty(property);
        System.setProperty(property, "1");
        Handler handler = new Handler();
        handler.answer = ignored -> ApprovalResult.reject("no");
        try (var registry = registry(handler)) {
            String arguments = paths("a.txt");
            assertEquals(ToolErrorCode.HITL_REJECTED,
                    registry.runWithTaskGrant(TaskGrant.WORKSPACE_WRITES,
                            () -> registry.executeToolOutput("delete_files", arguments)).errorCode());
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }

    @Test
    void changedFileAfterApprovalRejectsWholeBatch() throws Exception {
        file("a.txt");
        file("b.txt");
        Handler handler = new Handler();
        handler.answer = ignored -> {
            try {
                Files.writeString(root.resolve("b.txt"), "changed during approval");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            return ApprovalResult.approve();
        };
        try (var registry = registry(handler)) {
            assertEquals(ToolErrorCode.STALE_CONTEXT,
                    registry.executeToolOutput("delete_files", paths("a.txt", "b.txt")).errorCode());
            assertTrue(Files.exists(root.resolve("a.txt")));
            assertEquals("changed during approval", Files.readString(root.resolve("b.txt")));
        }
    }

    @Test
    void editedApprovalRequiresNewPreview() throws Exception {
        file("a.txt");
        file("b.txt");
        Handler handler = new Handler();
        String replacement = paths("b.txt");
        handler.answer = ignored -> ApprovalResult.modify(replacement);
        try (var registry = registry(handler)) {
            assertEquals(ToolErrorCode.HITL_REJECTED,
                    registry.executeToolOutput("delete_files", paths("a.txt")).errorCode());
            assertTrue(Files.exists(root.resolve("a.txt")));
            assertTrue(Files.exists(root.resolve("b.txt")));
        }
    }

    @Test
    void directoriesWildcardsAndDuplicateAliasesAreRejected() throws Exception {
        file("src/a.txt");
        Handler handler = new Handler();
        try (var registry = registry(handler)) {
            assertFalse(registry.executeToolOutput("delete_files", paths("src")).isSuccess());
            assertFalse(registry.executeToolOutput("delete_files", paths("src/*.txt")).isSuccess());
            assertFalse(registry.executeToolOutput("delete_files", paths("src/a.txt", "src/./a.txt")).isSuccess());
            assertEquals(0, handler.calls);
            assertTrue(Files.exists(root.resolve("src/a.txt")));
        }
    }

    @Test
    void invalidSchemaAndReadOnlyScopeDoNotPrompt() throws Exception {
        file("a.txt");
        Handler handler = new Handler();
        try (var registry = registry(handler)) {
            assertFalse(registry.executeToolOutput("delete_files", "{\"paths\":[123]}").isSuccess());
            String arguments = paths("a.txt");
            ToolOutput result = registry.runWithToolAccess(ToolRegistry.ToolAccessScope.READ_ONLY,
                    () -> registry.executeToolOutput("delete_files", arguments));
            assertEquals(ToolErrorCode.CAPABILITY_DENIED, result.errorCode());
            assertEquals(0, handler.calls);
            assertTrue(Files.exists(root.resolve("a.txt")));
        }
    }

    @Test
    void shellDeletionCannotReuseCommandGrantOrApproveAll() {
        Handler handler = new Handler();
        handler.approvedAll = true;
        handler.answer = ignored -> ApprovalResult.reject("no process allowed");
        try (var registry = registry(handler)) {
            ToolOutput output = registry.runWithTaskGrant(TaskGrant.PROJECT_COMMANDS,
                    () -> registry.executeToolOutput("execute_command",
                            "{\"command\":\"Remove-Item a.txt; mvn test\"}"));
            assertEquals(ToolErrorCode.HITL_REJECTED, output.errorCode());
            assertEquals(1, handler.calls);
            assertTrue(handler.request.sensitiveNotice().contains("无法可靠统计"));
        }
    }

    @Test
    void blacklistedCommandIsDeniedWithoutPrompt() {
        Handler handler = new Handler();
        try (var registry = registry(handler)) {
            assertEquals(ToolErrorCode.POLICY_DENIED, registry.executeToolOutput(
                    "execute_command", "{\"command\":\"rm -rf /\"}").errorCode());
            assertEquals(0, handler.calls);
        }
    }

    @Test
    void outOfScopeAndOutsideRootAreRejectedBeforeApproval() throws Exception {
        file("src/a.txt");
        Handler handler = new Handler();
        try (var registry = registry(handler)) {
            String arguments = paths("src/a.txt");
            assertEquals(ToolErrorCode.POLICY_DENIED,
                    registry.runWithAllowedWritePaths(List.of("other/**"),
                            () -> registry.executeToolOutput("delete_files", arguments)).errorCode());
            assertEquals(ToolErrorCode.POLICY_DENIED,
                    registry.executeToolOutput("delete_files", paths("../outside.txt")).errorCode());
            assertEquals(0, handler.calls);
            assertTrue(Files.exists(root.resolve("src/a.txt")));
        }
    }

    @Test
    void symlinkIsRejectedWithoutTouchingTarget() throws Exception {
        file("a.txt");
        try {
            Files.createSymbolicLink(root.resolve("alias.txt"), root.resolve("a.txt"));
        } catch (java.io.IOException | UnsupportedOperationException | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.abort("Symbolic links unavailable");
        }
        Handler handler = new Handler();
        try (var registry = registry(handler)) {
            assertEquals(ToolErrorCode.POLICY_DENIED,
                    registry.executeToolOutput("delete_files", paths("alias.txt")).errorCode());
            assertEquals(0, handler.calls);
            assertTrue(Files.exists(root.resolve("a.txt")));
        }
    }

    @Test
    void binaryFilesUseByteFingerprintWithoutTextDecoding() throws Exception {
        Files.write(root.resolve("data.bin"), new byte[] {(byte) 0xff, (byte) 0xfe, 0, 1});
        Handler handler = new Handler();
        try (var registry = registry(handler)) {
            ToolOutput result = registry.executeToolOutput("delete_files", paths("data.bin"));
            assertTrue(result.isSuccess(), result.text());
            assertFalse(Files.exists(root.resolve("data.bin")));
        }
    }

    @Test
    void missingFilePreventsPartialDeletion() throws Exception {
        file("a.txt");
        Handler handler = new Handler();
        try (var registry = registry(handler)) {
            assertFalse(registry.executeToolOutput("delete_files", paths("a.txt", "missing.txt")).isSuccess());
            assertTrue(Files.exists(root.resolve("a.txt")));
            assertEquals(0, handler.calls);
        }
    }

    private static final class Handler implements HitlHandler {
        boolean enabled = true;
        boolean approvedAll;
        int calls;
        ApprovalRequest request;
        Function<ApprovalRequest, ApprovalResult> answer = ignored -> ApprovalResult.approve();

        public ApprovalResult requestApproval(ApprovalRequest request) {
            this.request = request;
            calls++;
            return answer.apply(request);
        }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isApprovedAllByTool(String name) { return approvedAll; }
    }
}
