package com.devcli.tool.command;

import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolExecutionContext;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.CommandResultMetadata;

import java.nio.file.Path;

@FunctionalInterface
public interface CommandExecutionService {
    Result execute(Request request);

    record Result(int exitCode, String output, boolean timedOut, boolean cancelled) {
        public Result {
            output = output == null ? "" : output;
        }

        public static Result completed(int exitCode, String output) {
            return new Result(exitCode, output, false, false);
        }

        public static Result timedOut(String output) {
            return new Result(-1, output, true, false);
        }

        public static Result cancelled(String output) {
            return new Result(-1, output, false, true);
        }

        public boolean succeeded() {
            return !timedOut && !cancelled && exitCode == 0;
        }

        public ToolOutput toToolOutput() {
            if (timedOut) {
                return ToolOutput.timedOut(output)
                        .withSideChannel(new CommandResultMetadata(exitCode, true, cancelled));
            }
            if (cancelled) {
                return ToolOutput.cancelled(output)
                        .withSideChannel(new CommandResultMetadata(exitCode, false, true));
            }
            String text = "命令执行完成 (exit code: " + exitCode + ")\n" + output;
            ToolOutput result = exitCode == 0
                    ? ToolOutput.success(text)
                    : ToolOutput.error(ToolErrorCode.EXECUTION_FAILED, text, false);
            return result.withSideChannel(new CommandResultMetadata(exitCode, false, false));
        }

        public String toToolText() {
            return toToolOutput().text();
        }
    }

    record Request(String command, Path projectRoot, long timeoutSeconds,
                   boolean sandboxRequired, ToolExecutionContext executionContext) {
        public Request(String command, Path projectRoot, long timeoutSeconds,
                       boolean sandboxRequired) {
            this(command, projectRoot, timeoutSeconds, sandboxRequired,
                    ToolExecutionContext.current(""));
        }

        public Request {
            if (command == null || command.isBlank()) {
                throw new IllegalArgumentException("command is required");
            }
            projectRoot = projectRoot == null
                    ? null
                    : projectRoot.toAbsolutePath().normalize();
            if (projectRoot == null) {
                throw new IllegalArgumentException("projectRoot is required");
            }
            if (timeoutSeconds <= 0) {
                throw new IllegalArgumentException("timeoutSeconds must be positive");
            }
            executionContext = executionContext == null
                    ? ToolExecutionContext.current("")
                    : executionContext;
        }
    }
}
