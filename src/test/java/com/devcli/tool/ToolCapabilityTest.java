package com.devcli.tool;

import com.devcli.mcp.config.McpToolTrustPolicy;
import com.devcli.mcp.protocol.McpToolDescriptor;
import com.devcli.tool.command.CommandExecutionService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCapabilityTest {

    @Test
    void readOnlyScopeHidesAndRejectsMaterialSideEffects(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        registry.runWithToolAccess(ToolRegistry.ToolAccessScope.READ_ONLY, () -> {
            List<String> names = registry.getToolDefinitions().stream().map(tool -> tool.name()).toList();
            assertTrue(names.contains("read_file"));
            assertFalse(names.contains("write_file"));
            assertFalse(names.contains("execute_command"));
            assertFalse(names.contains("browser_connect"));
            assertFalse(names.contains("browser_disconnect"));

            ToolOutput output = registry.executeToolOutput(
                    "write_file", "{\"path\":\"blocked.txt\",\"content\":\"x\"}");
            assertEquals(ToolStatus.REJECTED, output.status());
            assertEquals(ToolErrorCode.CAPABILITY_DENIED, output.errorCode());
            assertFalse(Files.exists(tempDir.resolve("blocked.txt")));
            return null;
        });
    }

    @Test
    void isolatedProjectScopeRejectsOpenWorldMcpButAllowsProjectWrite(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        McpToolDescriptor descriptor = descriptor("dangerous",
                new McpToolDescriptor.Annotations(false, true, true));
        registry.registerMcpToolOutput(descriptor, arguments -> ToolOutput.success("called"));

        registry.runWithToolAccess(ToolRegistry.ToolAccessScope.ISOLATED_PROJECT, () -> {
            ToolOutput write = registry.executeToolOutput(
                    "write_file", "{\"path\":\"allowed.txt\",\"content\":\"ok\"}");
            assertTrue(write.isSuccess(), write.text());

            ToolOutput browserConnect = registry.executeToolOutput("browser_connect", "{}");
            assertEquals(ToolStatus.REJECTED, browserConnect.status());
            assertEquals(ToolErrorCode.CAPABILITY_DENIED, browserConnect.errorCode());

            ToolOutput external = registry.executeToolOutput(descriptor.namespacedName(), "{}");
            assertEquals(ToolStatus.REJECTED, external.status());
            assertEquals(ToolErrorCode.CAPABILITY_DENIED, external.errorCode());
            return null;
        });

        assertTrue(Files.exists(tempDir.resolve("allowed.txt")));
    }

    @Test
    void searchToolCacheDoesNotLeakToolsAcrossCapabilityScopes() {
        ToolRegistry registry = new ToolRegistry();
        ToolOutput full = registry.executeToolOutput(
                "search_tools", "{\"query\":\"write file\"}");
        assertTrue(full.text().contains("write_file"), full.text());

        registry.runWithToolAccess(ToolRegistry.ToolAccessScope.READ_ONLY, () -> {
            ToolOutput readOnly = registry.executeToolOutput(
                    "search_tools", "{\"query\":\"write file\"}");
            assertFalse(readOnly.text().contains("write_file"), readOnly.text());
            return null;
        });
    }

    @Test
    void parallelToolBatchKeepsCallerCapabilityScope(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        registry.runWithToolAccess(ToolRegistry.ToolAccessScope.READ_ONLY, () -> {
            List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                    new ToolRegistry.ToolInvocation("first", "write_file",
                            "{\"path\":\"first.txt\",\"content\":\"x\"}"),
                    new ToolRegistry.ToolInvocation("second", "write_file",
                            "{\"path\":\"second.txt\",\"content\":\"x\"}")));

            assertTrue(results.stream().allMatch(result ->
                    result.errorCode() == ToolErrorCode.CAPABILITY_DENIED));
            return null;
        });

        assertFalse(Files.exists(tempDir.resolve("first.txt")));
        assertFalse(Files.exists(tempDir.resolve("second.txt")));
    }

    @Test
    void parallelToolBatchKeepsResourceLeaseAttribution(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        registry.runWithResourceLease("step-1", () ->
                registry.runWithToolAccess(ToolRegistry.ToolAccessScope.ISOLATED_PROJECT, () ->
                        registry.executeTools(List.of(
                                new ToolRegistry.ToolInvocation("first", "write_file",
                                        "{\"path\":\"first.txt\",\"content\":\"x\"}"),
                                new ToolRegistry.ToolInvocation("second", "write_file",
                                        "{\"path\":\"second.txt\",\"content\":\"x\"}")))));

        assertEquals(2, registry.consumeStepModifiedFiles("step-1").size());
    }

    @Test
    void isolatedCommandRequiresSandboxBackend(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        AtomicReference<CommandExecutionService.Request> captured = new AtomicReference<>();
        registry.setCommandExecutionService(request -> {
            captured.set(request);
            return CommandExecutionService.Result.completed(0, "sandbox");
        });

        ToolOutput output = registry.runWithToolAccess(
                ToolRegistry.ToolAccessScope.ISOLATED_PROJECT,
                () -> registry.executeToolOutput(
                        "execute_command", "{\"command\":\"pwd\"}"));

        assertTrue(output.isSuccess(), output.text());
        assertTrue(captured.get().sandboxRequired());
        assertEquals(tempDir.toAbsolutePath().normalize(), captured.get().projectRoot());
    }

    @Test
    void selfDeclaredReadOnlyMcpIsRejectedUntilServerIsTrusted() {
        ToolRegistry registry = new ToolRegistry();
        McpToolDescriptor descriptor = descriptor("inspect",
                new McpToolDescriptor.Annotations(true, false, false));
        registry.registerMcpToolOutput(descriptor, arguments -> ToolOutput.success("read"));

        registry.runWithToolAccess(ToolRegistry.ToolAccessScope.READ_ONLY, () -> {
            ToolOutput output = registry.executeToolOutput(descriptor.namespacedName(), "{}");
            assertEquals(ToolErrorCode.CAPABILITY_DENIED, output.errorCode());
            return null;
        });

        registry.setMcpToolTrustPolicy("test",
                new McpToolTrustPolicy(true, java.util.Set.of(), java.util.Set.of()));
        registry.registerMcpToolOutput(descriptor, arguments -> ToolOutput.success("read"));
        registry.runWithToolAccess(ToolRegistry.ToolAccessScope.READ_ONLY, () -> {
            ToolOutput output = registry.executeToolOutput(descriptor.namespacedName(), "{}");
            assertTrue(output.isSuccess(), output.text());
            return null;
        });
    }

    @Test
    void localMcpDenyListPreventsToolRegistration() {
        ToolRegistry registry = new ToolRegistry();
        McpToolDescriptor descriptor = descriptor("blocked",
                new McpToolDescriptor.Annotations(true, false, false));
        registry.setMcpToolTrustPolicy("test",
                new McpToolTrustPolicy(true, java.util.Set.of(), java.util.Set.of("blocked")));

        registry.registerMcpToolOutput(descriptor, arguments -> ToolOutput.success("called"));

        ToolOutput output = registry.executeToolOutput(descriptor.namespacedName(), "{}");
        assertEquals(ToolErrorCode.UNKNOWN_TOOL, output.errorCode());
    }

    private static McpToolDescriptor descriptor(String name, McpToolDescriptor.Annotations annotations) {
        String namespaced = McpToolDescriptor.namespaced("test", name);
        return new McpToolDescriptor(
                "test",
                name,
                namespaced,
                name,
                JsonNodeFactory.instance.objectNode().put("type", "object"),
                annotations
        );
    }
}
