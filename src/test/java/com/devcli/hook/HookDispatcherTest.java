package com.devcli.hook;

import com.devcli.hitl.ApprovalRequest;
import com.devcli.hitl.ApprovalResult;
import com.devcli.hitl.HitlHandler;
import com.devcli.hitl.HitlToolRegistry;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookDispatcherTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void executesReadOnlyHookAndSubstitutesContext(@TempDir Path projectRoot) throws Exception {
        try (RecordingRegistry registry = new RecordingRegistry(ToolRegistry.ToolEffect.READ_ONLY)) {
            registry.setProjectPath(projectRoot.toString());
            HookDefinition hook = new HookDefinition(
                    "audit", "audit", HookEvent.TOOL_EXECUTION_END, true,
                    "record", JSON.readTree("""
                            {"message":"${event}:${tool_name}:${status}:${iteration}"}
                            """), HookDefinition.FailureMode.REQUIRED, false);
            HookDispatcher dispatcher = HookDispatcher.create(registry, List.of(hook));

            dispatcher.dispatch(HookEvent.TOOL_EXECUTION_END,
                    new HookDispatcher.HookContext(
                            projectRoot.toString(), "run_1", 3,
                            "read_file", "call_1", "SUCCESS"));

            assertTrue(registry.arguments.get().contains(
                    "tool_execution_end:read_file:SUCCESS:3"));
        }
    }

    @Test
    void warnHookFailureDoesNotChangeCoreFlow(@TempDir Path projectRoot) throws Exception {
        try (RecordingRegistry registry = new RecordingRegistry(ToolRegistry.ToolEffect.READ_ONLY)) {
            registry.output = ToolOutput.error(
                    com.devcli.tool.ToolErrorCode.EXECUTION_FAILED, "failed", false);
            HookDefinition hook = definition(
                    HookDefinition.FailureMode.WARN, false);

            HookDispatcher.create(registry, List.of(hook))
                    .dispatch(HookEvent.AGENT_START, HookDispatcher.HookContext.empty());

            assertEquals(1, registry.calls.get());
        }
    }

    @Test
    void requiredHookFailureStopsCoreFlow(@TempDir Path projectRoot) throws Exception {
        try (RecordingRegistry registry = new RecordingRegistry(ToolRegistry.ToolEffect.READ_ONLY)) {
            registry.output = ToolOutput.error(
                    com.devcli.tool.ToolErrorCode.EXECUTION_FAILED, "failed", false);
            HookDefinition hook = definition(
                    HookDefinition.FailureMode.REQUIRED, false);

            assertThrows(HookDispatcher.HookExecutionException.class, () ->
                    HookDispatcher.create(registry, List.of(hook))
                            .dispatch(HookEvent.AGENT_START, HookDispatcher.HookContext.empty()));
        }
    }

    @Test
    void sideEffectHookRequiresExplicitHitlPath(@TempDir Path projectRoot) throws Exception {
        try (RecordingRegistry registry = new RecordingRegistry(ToolRegistry.ToolEffect.HOST_PROCESS)) {
            HookDefinition hook = definition(
                    HookDefinition.FailureMode.REQUIRED, true);

            HookDispatcher.HookExecutionException error = assertThrows(
                    HookDispatcher.HookExecutionException.class, () ->
                            HookDispatcher.create(registry, List.of(hook))
                                    .dispatch(HookEvent.AGENT_START,
                                            HookDispatcher.HookContext.empty()));

            assertTrue(error.getMessage().contains("需要启用 HITL"));
            assertEquals(0, registry.calls.get());
        }
    }

    @Test
    void approvedSideEffectHookStillUsesHitlPipeline(@TempDir Path projectRoot) throws Exception {
        ApprovingHitlHandler handler = new ApprovingHitlHandler();
        AtomicInteger executions = new AtomicInteger();
        try (HitlToolRegistry registry = new HitlToolRegistry(handler)) {
            registry.setProjectPath(projectRoot.toString());
            registry.registerTool(new ToolRegistry.Tool(
                    "write_file", "test hook", JSON.readTree("""
                            {"type":"object","properties":{},"additionalProperties":true}
                            """), args -> {
                        executions.incrementAndGet();
                        return "ok";
                    }, ToolRegistry.ToolEffect.PROJECT_MUTATION));
            HookDefinition hook = new HookDefinition(
                    "write", "write", HookEvent.AGENT_END, true,
                    "write_file", JSON.createObjectNode(),
                    HookDefinition.FailureMode.REQUIRED, true);

            HookDispatcher.create(registry, List.of(hook))
                    .dispatch(HookEvent.AGENT_END, HookDispatcher.HookContext.empty());

            assertEquals(1, handler.approvals.get());
            assertEquals(1, executions.get());
        }
    }

    private static HookDefinition definition(
            HookDefinition.FailureMode failureMode, boolean allowSideEffects) throws Exception {
        return new HookDefinition(
                "hook", "hook", HookEvent.AGENT_START, true,
                "record", JSON.createObjectNode(), failureMode, allowSideEffects);
    }

    private static final class RecordingRegistry extends ToolRegistry {
        private final ToolEffect effect;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<String> arguments = new AtomicReference<>();
        private ToolOutput output = ToolOutput.success("ok");

        private RecordingRegistry(ToolEffect effect) {
            this.effect = effect;
        }

        @Override
        public ToolEffect toolEffect(String name) {
            return effect;
        }

        @Override
        public ToolOutput executeToolOutput(String name, String argumentsJson) {
            calls.incrementAndGet();
            arguments.set(argumentsJson);
            return output;
        }
    }

    private static final class ApprovingHitlHandler implements HitlHandler {
        private final AtomicInteger approvals = new AtomicInteger();

        @Override
        public ApprovalResult requestApproval(ApprovalRequest request) {
            approvals.incrementAndGet();
            return ApprovalResult.approve();
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void setEnabled(boolean enabled) {
        }
    }
}
