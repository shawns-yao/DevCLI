package com.devcli.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试 WorkingMemory 工具证据淘汰策略：副作用证据（write_file / execute_command）优先保留，
 * 不被只读操作（read_file / search 等）挤出——这样多步任务里早期步骤的文件修改仍能被后续看到。
 */
class WorkingMemoryEvictionTest {

    @Test
    void keepsSideEffectEvidenceOverReadOnly() {
        WorkingMemory memory = new WorkingMemory(2, 16, 8); // maxToolResults=2
        memory.recordToolResult("write_file", "{\"path\":\"UserService.java\"}", "文件已写入: UserService.java");
        memory.recordToolResult("read_file", "{\"path\":\"a.java\"}", "content a");
        memory.recordToolResult("read_file", "{\"path\":\"b.java\"}", "content b");
        memory.recordToolResult("read_file", "{\"path\":\"c.java\"}", "content c");

        List<WorkingMemory.ToolEvidence> results = memory.getRecentToolResults();
        assertEquals(2, results.size(), "总量仍受 maxToolResults 约束");
        assertTrue(results.stream().anyMatch(e -> "write_file".equals(e.toolName)
                        && e.argsJson.contains("UserService.java")),
                "write_file 副作用证据应优先保留，不被只读 read_file 挤出");
    }

    @Test
    void evictsOldestSideEffectWhenAllSideEffect() {
        WorkingMemory memory = new WorkingMemory(2, 16, 8);
        memory.recordToolResult("write_file", "{\"path\":\"a.java\"}", "wrote a");
        memory.recordToolResult("write_file", "{\"path\":\"b.java\"}", "wrote b");
        memory.recordToolResult("write_file", "{\"path\":\"c.java\"}", "wrote c");

        List<WorkingMemory.ToolEvidence> results = memory.getRecentToolResults();
        assertEquals(2, results.size());
        assertFalse(results.stream().anyMatch(e -> e.argsJson.contains("a.java")),
                "全是副作用且超限时，淘汰最旧副作用");
        assertTrue(results.stream().anyMatch(e -> e.argsJson.contains("c.java")));
    }

    @Test
    void readOnlyEvidenceStillFifoWhenNoSideEffect() {
        WorkingMemory memory = new WorkingMemory(2, 16, 8);
        memory.recordToolResult("read_file", "{\"path\":\"a.java\"}", "a");
        memory.recordToolResult("read_file", "{\"path\":\"b.java\"}", "b");
        memory.recordToolResult("read_file", "{\"path\":\"c.java\"}", "c");

        List<WorkingMemory.ToolEvidence> results = memory.getRecentToolResults();
        assertEquals(2, results.size());
        assertFalse(results.stream().anyMatch(e -> e.argsJson.contains("a.java")),
                "纯只读证据按 FIFO 淘汰最旧");
    }
}
