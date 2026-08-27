package com.devcli.memory;

import java.io.IOException;

/** 当前任务快照到长期记忆候选的隔离决策端口。 */
@FunctionalInterface
public interface MemoryCurator {
    IsolatedMemoryCurator.Decision curate(TaskMemorySnapshot snapshot) throws IOException;
}
