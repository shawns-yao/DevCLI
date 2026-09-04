package com.devcli.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DelegationToolTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path project;

    @Test
    void childCanRestoreItsOwnToolResultsButNotParentOrSiblingResults() throws Exception {
        String previous = System.getProperty(ToolResultArtifactStore.ROOT_PROPERTY);
        System.setProperty(ToolResultArtifactStore.ROOT_PROPERTY, project.resolve("artifacts").toString());
        try (ToolRegistry registry = new ToolRegistry();
             var parent = com.devcli.runtime.CancellationContext.startRunContext(project)) {
            var parentArtifact = ToolResultArtifactStore.store("parent", "private parent evidence");
            registry.restrictForDelegation();
            try (var child = com.devcli.runtime.CancellationContext.startRunContext(project)) {
                var own = ToolResultArtifactStore.store("child", "own evidence");
                var allowed = registry.runWithToolAccess(ToolRegistry.ToolAccessScope.READ_ONLY, () ->
                        registry.executeToolOutput("read_tool_result", "{\"result_ref\":\"" + own.ref() + "\"}"));
                assertTrue(allowed.isSuccess(), allowed.text());
                assertTrue(allowed.text().contains("own evidence"));
                var rejected = registry.executeToolOutput("read_tool_result", "{\"result_ref\":\"" + parentArtifact.ref() + "\"}");
                assertEquals(ToolErrorCode.CAPABILITY_DENIED, rejected.errorCode());
                assertFalse(rejected.text().contains("private parent evidence"));
            }
        } finally {
            if (previous == null) System.clearProperty(ToolResultArtifactStore.ROOT_PROPERTY);
            else System.setProperty(ToolResultArtifactStore.ROOT_PROPERTY, previous);
        }
    }

    @Test
    void delegationTimeoutCancelsTheHandlerAndRestoresBinding() {
        String previous = System.getProperty("devcli.delegate.timeout.seconds");
        System.setProperty("devcli.delegate.timeout.seconds", "1");
        try (ToolRegistry registry = new ToolRegistry()) {
            var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
            var results = registry.runWithDelegation((args, context) -> {
                try {
                    while (!context.isCancelled()) Thread.sleep(10);
                } catch (InterruptedException expected) {
                    Thread.currentThread().interrupt();
                }
                cancelled.set(context.isCancelled());
                return ToolOutput.cancelled("cancelled");
            }, () -> registry.executeTools(java.util.List.of(new ToolRegistry.ToolInvocation(
                    "deadline", "delegate_task", "{\"role\":\"explorer\",\"task\":\"wait\"}"))));
            assertTrue(cancelled.get());
            assertEquals(ToolErrorCode.TIMEOUT, results.getFirst().errorCode());
            assertTrue(registry.getToolDefinitions().stream().noneMatch(t -> t.name().equals("delegate_task")));
        } finally {
            if (previous == null) System.clearProperty("devcli.delegate.timeout.seconds");
            else System.setProperty("devcli.delegate.timeout.seconds", previous);
        }
    }

    @Test
    void bindsHandlerAcrossParallelToolsAndRestoresItAfterTheRun() {
        try (ToolRegistry registry = new ToolRegistry()) {
            var results = registry.runWithDelegation((args, context) -> ToolOutput.success(args.get("task")),
                    () -> registry.executeTools(java.util.List.of(
                            new ToolRegistry.ToolInvocation("a", "delegate_task", "{\"role\":\"explorer\",\"task\":\"first\"}"),
                            new ToolRegistry.ToolInvocation("b", "delegate_task", "{\"role\":\"reviewer\",\"task\":\"second\"}"))));
            assertEquals(java.util.List.of("first", "second"), results.stream().map(ToolRegistry.ToolExecutionResult::result).toList());
            assertTrue(registry.getToolDefinitions().stream().noneMatch(t -> t.name().equals("delegate_task")));
        }
    }

    @Test
    void registersDelegationButRejectsCallsOutsideAMainAgentRun() {
        try (ToolRegistry registry = new ToolRegistry()) {
            assertTrue(registry.hasTool("delegate_task"));
            assertTrue(registry.getToolDefinitions().stream()
                    .noneMatch(tool -> tool.name().equals("delegate_task")));
            ToolOutput result = registry.executeToolOutput("delegate_task",
                    "{\"role\":\"explorer\",\"task\":\"inspect sources\"}");
            assertEquals(ToolErrorCode.CAPABILITY_DENIED, result.errorCode());
        }
    }

    @Test
    void structuredDelegationArgumentsKeepArraysAndObjects() {
        try (ToolRegistry registry = new ToolRegistry()) {
            var results = registry.runWithDelegation((args, context) -> ToolOutput.success(
                            args.get("constraints") + "|" + args.get("budget")),
                    () -> registry.executeTools(java.util.List.of(new ToolRegistry.ToolInvocation(
                            "structured", "delegate_task",
                            "{\"role\":\"explorer\",\"task\":\"inspect\","
                                    + "\"constraints\":[\"read only\",\"no network\"],"
                                    + "\"budget\":{\"max_iterations\":3}}"))));
            assertEquals("[\"read only\",\"no network\"]|{\"max_iterations\":3}",
                    results.getFirst().result());
        }
    }
}
