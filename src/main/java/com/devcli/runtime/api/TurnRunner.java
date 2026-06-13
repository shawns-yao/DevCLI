package com.devcli.runtime.api;

/**
 * Runtime API turn 执行器：与 {@code TaskRunner} 的区别是带 threadId，
 * 让执行侧能按 thread 重放历史上下文（存储即状态，计算无状态）。
 */
@FunctionalInterface
public interface TurnRunner {
    String run(String threadId, String input) throws Exception;
}
