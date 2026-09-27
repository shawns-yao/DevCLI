package com.devcli.tool.command;

import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolExecutionContext;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.CommandResultMetadata;
import com.devcli.tool.ToolResultArtifact;

import java.nio.file.Path;

@FunctionalInterface
public interface CommandExecutionService {
    Result execute(Request request);

    /** Unknown/custom backends are treated as host execution unless explicitly declared otherwise. */
    default boolean executesOnHost(boolean sandboxRequired) {
        return true;
    }

    /** Isolation is independent of execution location; unknown backends fail closed. */
    default boolean providesIsolation(boolean sandboxRequired) {
        return false;
    }

    /** Run deterministic backend policy checks before presenting an approval request. */
    default void validateRequest(Request request) {
    }

    record Result(int exitCode, String output, boolean timedOut, boolean cancelled,
                  ToolResultArtifact artifact, boolean outputIncomplete) {
        public Result(int exitCode, String output, boolean timedOut, boolean cancelled) {
            this(exitCode, output, timedOut, cancelled, null, false);
        }
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
            return !timedOut && !cancelled && !outputIncomplete && exitCode == 0;
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
            ToolOutput result = exitCode == 0 && !outputIncomplete
                    ? ToolOutput.success(text)
                    : ToolOutput.error(ToolErrorCode.EXECUTION_FAILED, text, false);
            result = result.withSideChannel(new CommandResultMetadata(exitCode, false, false));
            if (artifact != null) result = result.withSideChannel(new ToolResultArtifact(
                    artifact.classification(), artifact.originalChars(), artifact.originalBytes(),
                    artifact.previewChars(), artifact.artifactRef(), artifact.nextCursor(), artifact.sha256(),
                    artifact.toolCallId(), result.status().name(), result.errorCode().name(), exitCode, 0));
            return result;
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
