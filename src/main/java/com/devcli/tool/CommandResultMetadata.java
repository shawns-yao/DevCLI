package com.devcli.tool;

/** Exit status supplied by the process executor, never inferred from stdout. */
public record CommandResultMetadata(int exitCode, boolean timedOut, boolean cancelled)
        implements ToolSideChannel {
}
