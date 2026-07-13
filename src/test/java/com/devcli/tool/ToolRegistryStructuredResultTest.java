package com.devcli.tool;

import com.devcli.runtime.CancellationContext;
import com.devcli.runtime.RunContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryStructuredResultTest {

    @Test
    void reportsUnknownToolWithoutParsingMessageText() {
        try (ToolRegistry registry = new ToolRegistry()) {
            ToolOutput output = registry.executeToolOutput("missing_tool", "{}");

            assertEquals(ToolStatus.ERROR, output.status());
            assertEquals(ToolErrorCode.UNKNOWN_TOOL, output.errorCode());
            assertTrue(output.retryable());
        }
    }

    @Test
    void reportsSchemaFailureAsRetryableStructuredResult() {
        try (ToolRegistry registry = new ToolRegistry()) {
            ToolOutput output = registry.executeToolOutput("read_file", "{}");

            assertEquals(ToolStatus.REJECTED, output.status());
            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, output.errorCode());
            assertTrue(output.retryable());
        }
    }

    @Test
    void propagatesStructuredStatusThroughBatchResult() {
        try (ToolRegistry registry = new ToolRegistry()) {
            ToolRegistry.ToolExecutionResult result = registry.executeTools(java.util.List.of(
                    new ToolRegistry.ToolInvocation("call_1", "missing_tool", "{}")
            )).get(0);

            assertEquals(ToolStatus.ERROR, result.status());
            assertEquals(ToolErrorCode.UNKNOWN_TOOL, result.errorCode());
            assertTrue(result.retryable());
        }
    }

    @Test
    void reportsCancellationBeforeExecution(@TempDir Path projectRoot) {
        try (RunContext context = CancellationContext.startRunContext(projectRoot);
             ToolRegistry registry = new ToolRegistry()) {
            context.cancel();

            ToolOutput output = registry.executeToolOutput("read_file", "{\"path\":\"x\"}");

            assertEquals(ToolStatus.CANCELLED, output.status());
            assertEquals(ToolErrorCode.CANCELLED, output.errorCode());
            assertFalse(output.retryable());
        }
    }

    @Test
    void reportsProviderValidationFailuresWithStableCodes() {
        try (ToolRegistry registry = new ToolRegistry()) {
            ToolOutput grep = registry.executeToolOutput("grep_code", "{\"pattern\":\" \"}");
            ToolOutput command = registry.executeToolOutput("execute_command", "{\"command\":\" \"}");
            ToolOutput search = registry.executeToolOutput("search_tools", "{\"query\":\" \"}");
            ToolOutput skill = registry.executeToolOutput("load_skill", "{\"name\":\"missing\"}");
            ToolOutput memory = registry.executeToolOutput("save_memory", "{\"fact\":\"value\"}");

            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, grep.errorCode());
            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, command.errorCode());
            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, search.errorCode());
            assertEquals(ToolErrorCode.EXECUTION_FAILED, skill.errorCode());
            assertEquals(ToolErrorCode.EXECUTION_FAILED, memory.errorCode());
        }
    }

    @Test
    void reportsCommandExitFailureWithoutParsingText(@TempDir Path projectRoot) {
        try (ToolRegistry registry = new ToolRegistry()) {
            registry.setProjectPath(projectRoot.toString());
            registry.setCommandExecutionService(request ->
                    com.devcli.tool.command.CommandExecutionService.Result.completed(7, "failed"));

            ToolOutput output = registry.executeToolOutput(
                    "execute_command", "{\"command\":\"build\"}");

            assertEquals(ToolStatus.ERROR, output.status());
            assertEquals(ToolErrorCode.EXECUTION_FAILED, output.errorCode());
            assertFalse(output.retryable());
        }
    }
}
