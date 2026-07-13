package com.devcli.tool.command;

import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolOutput;

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
                return ToolOutput.timedOut(output);
            }
            if (cancelled) {
                return ToolOutput.cancelled(output);
            }
            String text = "命令执行完成 (exit code: " + exitCode + ")\n" + output;
            return exitCode == 0
                    ? ToolOutput.success(text)
                    : ToolOutput.error(ToolErrorCode.EXECUTION_FAILED, text, false);
        }

        public String toToolText() {
            return toToolOutput().text();
        }
    }

    record Request(String command, Path projectRoot, long timeoutSeconds,
                   boolean sandboxRequired) {
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
            timeoutSeconds = Math.max(1, timeoutSeconds);
        }
    }
}
