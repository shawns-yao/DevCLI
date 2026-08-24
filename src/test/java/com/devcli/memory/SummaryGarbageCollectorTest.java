package com.devcli.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SummaryGarbageCollectorTest {

    @Test
    void noOpWhenUnderBudget() {
        RollingSummary s = new RollingSummary();
        s.set("主要请求与意图", "短内容");
        int before = s.totalChars();
        new SummaryGarbageCollector().gc(s, 10_000);
        assertEquals(before, s.totalChars(), "未超预算不应改动");
    }

    @Test
    void collapsesUserMessagesBeyondLimit() {
        RollingSummary s = new RollingSummary();
        StringBuilder msgs = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            msgs.append("- 消息").append(i).append("\n");
        }
        s.set("逐条用户消息", msgs.toString());

        new SummaryGarbageCollector(20).gc(s, 1); // 强制 GC

        String result = s.get("逐条用户消息");
        assertTrue(result.contains("已折叠"), "应折叠更早消息");
        assertTrue(result.contains("消息49"), "应保留最近的消息");
        assertTrue(result.contains("消息30"), "应保留最近 20 条(消息30..49)");
        assertFalse(result.contains("消息29"), "更早的消息(消息0..29)应被折叠");
    }

    @Test
    void truncatesLowPrioritySectionToFitBudget() {
        RollingSummary s = new RollingSummary();
        s.set("主要请求与意图", "重要意图"); // 高优先，应保留
        s.set("问题解决过程", "x".repeat(5_000)); // 低优先，应被截

        new SummaryGarbageCollector().gc(s, 500);

        assertTrue(s.totalChars() <= 600, "应裁剪到接近预算(留标记余量)，实际: " + s.totalChars());
        assertEquals("重要意图", s.get("主要请求与意图"), "高优先段应保留");
        assertTrue(s.get("问题解决过程").contains("已折叠"), "低优先段应被截断");
    }

    @Test
    void keepsHighPrioritySectionsWhenTruncating() {
        RollingSummary s = new RollingSummary();
        s.set("主要请求与意图", "P1 九段");
        s.set("文件和代码", "x".repeat(3_000));

        new SummaryGarbageCollector().gc(s, 300);

        assertEquals("P1 九段", s.get("主要请求与意图"), "高优先段不参与截断");
        assertTrue(s.get("文件和代码").contains("已折叠"), "低优先段被截以腾预算");
    }

    @Test
    void aggressiveGcNeverDeletesStableOrUnresolvedItemsOnlyBecauseTheyAreOld() {
        RollingSummary summary = new RollingSummary();
        summary.addItem(SummaryItem.create(
                "主要请求与意图", "decision", "保留九段摘要",
                SummaryItem.Lifecycle.STABLE, 100, List.of()).withCompactionCount(20));
        summary.addItem(SummaryItem.create(
                "主要请求与意图", "open", "实现生命周期更新",
                SummaryItem.Lifecycle.UNRESOLVED, 90, List.of()).withCompactionCount(20));

        new SummaryGarbageCollector().gc(summary, 10_000, true);

        assertTrue(summary.findItem("主要请求与意图", "decision").isPresent());
        assertTrue(summary.findItem("主要请求与意图", "open").isPresent());
    }

    @Test
    void aggressiveGcRemovesExpiredAndOldSupersededAuditButKeepsResolvedOutcome() {
        RollingSummary summary = new RollingSummary();
        summary.addItem(SummaryItem.create(
                "问题解决过程", "expired", "已经无用",
                SummaryItem.Lifecycle.EXPIRED, 10, List.of()).withCompactionCount(1));
        summary.addItem(SummaryItem.create(
                "关键技术概念", "old", "旧值",
                SummaryItem.Lifecycle.STABLE, 50, List.of()).supersede("new").withCompactionCount(5));
        summary.addItem(SummaryItem.create(
                "文件和代码", "file:A", "文件 A 已修改，测试通过",
                SummaryItem.Lifecycle.RESOLVED, 90, List.of("test-1")).withCompactionCount(8));

        new SummaryGarbageCollector().gc(summary, 10_000, true);

        assertTrue(summary.findItem("问题解决过程", "expired").isEmpty());
        assertTrue(summary.findItem("关键技术概念", "old").isEmpty());
        SummaryItem resolved = summary.findItem("文件和代码", "file:A").orElseThrow();
        assertEquals("文件 A 已修改，测试通过", resolved.content());
        assertEquals(List.of("test-1"), resolved.evidenceRefs());
    }
}
