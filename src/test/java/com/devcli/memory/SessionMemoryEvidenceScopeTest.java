package com.devcli.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具证据的出处（scope）与跨 scope 公平淘汰。
 *
 * <p>Multi-Agent 下多个 Worker 共享同一份 {@link SessionMemory}。角色视图（PLANNER / WORKER /
 * REVIEWER）只决定<b>渲染哪些段落</b>，不区分证据由哪个步骤产生，于是：
 * <ul>
 *   <li>Reviewer 审步骤 B 时会看到步骤 A 的无标签证据，无法分辨出处——这是主动误导</li>
 *   <li>全局 FIFO 上限让话多的步骤把安静步骤的关键证据挤出去</li>
 * </ul>
 *
 * <p>这里不做硬隔离：跨步骤可见性本身是需要的（Worker 要知道别人改了哪些文件，
 * 否则会照着过期接口写代码）。要修的是<b>出处不明</b>和<b>淘汰不公平</b>。
 */
class SessionMemoryEvidenceScopeTest {

    @Test
    void recordsAndRendersEvidenceProvenance() {
        SessionMemory memory = new SessionMemory(8, 16, 8);
        memory.recordToolResult("read_file", "{\"path\":\"A.java\"}", "content-a",
                List.of(), "step_1");
        memory.recordToolResult("read_file", "{\"path\":\"B.java\"}", "content-b",
                List.of(), "step_2");

        String rendered = memory.renderForPrompt(SessionMemory.SessionView.REVIEWER);

        assertTrue(rendered.contains("step_1"),
                "证据必须标出产生它的步骤，否则 Reviewer 无法分辨出处：" + rendered);
        assertTrue(rendered.contains("step_2"), rendered);
    }

    @Test
    void unscopedEvidenceRendersWithoutProvenanceLabel() {
        SessionMemory memory = new SessionMemory(8, 16, 8);
        memory.recordToolResult("read_file", "{\"path\":\"A.java\"}", "content-a");

        String rendered = memory.renderForPrompt(SessionMemory.SessionView.WORKER);

        assertTrue(rendered.contains("read_file"), rendered);
        assertTrue(!rendered.contains("scope="),
                "单 Agent 路径没有步骤概念，不应渲染空标签污染 prompt：" + rendered);
    }

    @Test
    void chattyScopeDoesNotStarveQuietScope() {
        SessionMemory memory = new SessionMemory(4, 16, 8);
        // 安静步骤先写入一条，随后话多步骤连续写入超过总容量
        memory.recordToolResult("read_file", "{\"path\":\"quiet.java\"}", "quiet-evidence",
                List.of(), "step_quiet");
        for (int i = 0; i < 8; i++) {
            memory.recordToolResult("read_file", "{\"path\":\"chatty" + i + ".java\"}",
                    "chatty-" + i, List.of(), "step_chatty");
        }

        List<SessionMemory.ToolEvidence> results = memory.getRecentToolResults();
        assertEquals(4, results.size(), "总量仍受 maxToolResults 约束");
        assertTrue(results.stream().anyMatch(e -> "step_quiet".equals(e.stepId)),
                "话多步骤不应把安静步骤的证据挤空，实际保留: "
                        + results.stream().map(e -> e.stepId).toList());
    }

    @Test
    void sideEffectEvidenceStillOutranksReadOnlyWithinFairEviction() {
        SessionMemory memory = new SessionMemory(2, 16, 8);
        memory.recordToolResult("write_file", "{\"path\":\"Kept.java\"}", "已写入",
                List.of(), "step_1");
        for (int i = 0; i < 5; i++) {
            memory.recordToolResult("read_file", "{\"path\":\"r" + i + ".java\"}", "r" + i,
                    List.of(), "step_1");
        }

        List<SessionMemory.ToolEvidence> results = memory.getRecentToolResults();
        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(e -> "write_file".equals(e.toolName)),
                "公平淘汰不得推翻既有约定：副作用证据仍优先于只读证据保留");
    }
}
