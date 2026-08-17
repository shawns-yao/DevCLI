package com.devcli.runtime;

/** 运行取消的机器可读归因。 */
public enum CancellationReason {
    NONE,
    USER_REQUEST,
    PARENT_CANCELLED,
    TOOL_TIMEOUT,
    BATCH_TIMEOUT,
    CALLER_INTERRUPTED,
    ENGINE_SHUTDOWN,
    THREAD_INTERRUPTED
}
