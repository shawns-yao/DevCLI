package com.devcli.tool;

import com.devcli.tool.command.CommandExecutionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class CommandTimeoutTest {
    @TempDir Path root;

    @Test
    void commandTimeoutReachesBackendAndInvocationDeadline() {
        AtomicReference<CommandExecutionService.Request> captured = new AtomicReference<>();
        try (var tools = tools(captured)) {
            var result = tools.executeTools(List.of(new ToolContract.ToolInvocation("timeout", "execute_command",
                    "{\"command\":\"mvn test\",\"timeout\":2}")));
            assertEquals(ToolStatus.SUCCESS, result.getFirst().status());
            assertEquals(2, captured.get().timeoutSeconds());
            assertTrue(captured.get().executionContext().remainingNanos() <= TimeUnit.SECONDS.toNanos(2));
            assertTrue(captured.get().executionContext().remainingNanos() > 0);
        }
    }

    @Test
    void explicitTimeoutCanExceedTheDefaultBatchTimeout() {
        try (var tools = new ToolRegistry(1, 1)) {
            tools.setProjectPath(root.toString());
            tools.setExecutionEnvironment(new com.devcli.tool.environment.LocalExecutionEnvironment(new CommandExecutionService() {
                public Result execute(Request request) {
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1200));
                    return Result.completed(0, "finished after default deadline");
                }
                public boolean providesIsolation(boolean sandboxRequired) { return sandboxRequired; }
            }));
            var result = tools.executeTools(List.of(new ToolContract.ToolInvocation("longer", "execute_command",
                    "{\"command\":\"mvn test\",\"timeout\":3}")));
            assertEquals(ToolStatus.SUCCESS, result.getFirst().status(), result.getFirst().result());
        }
    }

    @Test
    void invalidTimeoutFailsBeforeBackendExecution() {
        AtomicReference<CommandExecutionService.Request> captured = new AtomicReference<>();
        try (var tools = tools(captured)) {
            var invalid = java.util.Map.of("0", ToolErrorCode.SEMANTIC_VALIDATION_FAILED,
                    "-1", ToolErrorCode.SEMANTIC_VALIDATION_FAILED, "86401", ToolErrorCode.SEMANTIC_VALIDATION_FAILED,
                    "1.5", ToolErrorCode.INVALID_ARGUMENTS, "\"2\"", ToolErrorCode.INVALID_ARGUMENTS);
            for (String timeout : invalid.keySet()) {
                var result = tools.executeToolOutput("execute_command", "{\"command\":\"mvn test\",\"timeout\":" + timeout + "}");
                assertEquals(invalid.get(timeout), result.errorCode(), result.text());
                assertNull(captured.get());
            }
        }
    }

    private ToolRegistry tools(AtomicReference<CommandExecutionService.Request> captured) {
        ToolRegistry tools = new ToolRegistry(1, 4);
        tools.setProjectPath(root.toString());
        tools.setExecutionEnvironment(new com.devcli.tool.environment.LocalExecutionEnvironment(new CommandExecutionService() {
            public Result execute(Request request) {
                captured.set(request);
                return Result.completed(0, "test backend");
            }
            public boolean providesIsolation(boolean sandboxRequired) { return sandboxRequired; }
        }));
        return tools;
    }
}
