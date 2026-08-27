package com.devcli.memory;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryAuditReportTest {

    private MemoryEntry entry(String id, String content, MemoryEntry.MemoryType type) {
        return new MemoryEntry(id, content, type, Instant.now(), Map.of(), 10);
    }

    @Test
    void emptyReportStillHasHeaderAndReadonlyNotice() {
        String md = MemoryAuditReport.render(List.of(), Instant.parse("2026-08-27T10:00:00Z"));
        assertTrue(md.contains("# DevCLI 长期记忆审计快照"));
        assertTrue(md.contains("只读导出"));
        assertTrue(md.contains("共 0 条"));
    }

    @Test
    void rendersIdContentStateAndGroupsByType() {
        MemoryEntry fact = new MemoryEntry("mem-1", "使用 Java 17",
                MemoryEntry.MemoryType.FACT, Instant.now(),
                Map.of("scope_type", "project", "scope_key", "demo"), 10);
        MemoryEntry tool = entry("mem-2", "ls 输出", MemoryEntry.MemoryType.TOOL_RESULT);

        String md = MemoryAuditReport.render(List.of(tool, fact), Instant.now());

        assertTrue(md.contains("`mem-1`"), "应含稳定 ID");
        assertTrue(md.contains("使用 Java 17"), "应含正文");
        assertTrue(md.contains("project/demo"), "应含作用域");
        assertTrue(md.contains("生效中"));
        // FACT 组必须排在 TOOL_RESULT 组前
        assertTrue(md.indexOf("事实 / 偏好") < md.indexOf("工具结果"),
                "事实类应排在工具结果前");
    }

    @Test
    void rendersSupersededAndArchivedState() {
        MemoryEntry superseded = new MemoryEntry("mem-3", "旧事实",
                MemoryEntry.MemoryType.FACT, Instant.now(), Map.of(), 10,
                "", true, "mem-9");
        MemoryEntry archived = new MemoryEntry("mem-4", "归档事实",
                MemoryEntry.MemoryType.FACT, Instant.now(), Map.of(), 10,
                "", false, "");

        String md = MemoryAuditReport.render(List.of(superseded, archived), Instant.now());
        assertTrue(md.contains("已被 mem-9 取代"));
        assertTrue(md.contains("已归档"));
    }

    @Test
    void nullEntriesTreatedAsEmpty() {
        assertTrue(MemoryAuditReport.render(null, Instant.now()).contains("共 0 条"));
    }
}
