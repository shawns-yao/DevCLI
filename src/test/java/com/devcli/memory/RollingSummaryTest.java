package com.devcli.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RollingSummaryTest {

    @Test
    void parsesAllNineSections() {
        String md = """
                ## 主要请求与意图
                重构记忆系统
                ## 关键技术概念
                subject 冲突消解
                ## 文件和代码
                MemoryEntry.java
                ## 踩过的坑和修复
                去重×supersede 顺序
                ## 问题解决过程
                先 supersede 再 store
                ## 逐条用户消息
                - 讨论记忆机制
                ## 待办任务
                P1 九段摘要
                ## 当前在做什么
                写 RollingSummary
                ## 下一步
                集成 compactor
                """;
        RollingSummary s = RollingSummary.parse(md);
        assertEquals("重构记忆系统", s.get("主要请求与意图"));
        assertEquals("subject 冲突消解", s.get("关键技术概念"));
        assertEquals("先 supersede 再 store", s.get("问题解决过程"));
        assertEquals("集成 compactor", s.get("下一步"));
    }

    @Test
    void parseGracefulOnMissingSections() {
        RollingSummary s = RollingSummary.parse("## 主要请求与意图\n只有这一段");
        assertEquals("只有这一段", s.get("主要请求与意图"));
        assertTrue(s.get("下一步").isEmpty(), "缺失段应为空，不报错");
    }

    @Test
    void parseEmptyReturnsEmptySummary() {
        assertTrue(RollingSummary.parse("").isEmpty());
        assertTrue(RollingSummary.parse(null).isEmpty());
    }

    @Test
    void parseIgnoresNonSectionHeadingsAsContent() {
        // 内容里出现的 ## 噪声（非九段名）应并入当前段，不被当成段标题
        RollingSummary s = RollingSummary.parse("## 文件和代码\n## 这不是段名\nA.java");
        assertTrue(s.get("文件和代码").contains("## 这不是段名"));
        assertTrue(s.get("文件和代码").contains("A.java"));
    }

    @Test
    void renderProducesStableNineSectionOrder() {
        RollingSummary s = new RollingSummary();
        s.set("下一步", "X");
        s.set("主要请求与意图", "Y");
        String rendered = s.render();
        assertTrue(rendered.indexOf("## 主要请求与意图") < rendered.indexOf("## 下一步"),
                "渲染应按固定九段顺序，意图在下一步之前");
        for (String section : RollingSummary.SECTIONS) {
            assertTrue(rendered.contains("## " + section), "应包含段标题: " + section);
        }
    }

    @Test
    void roundtripPreservesContent() {
        RollingSummary s = new RollingSummary();
        s.set("主要请求与意图", "重构");
        s.set("文件和代码", "A.java\nB.java");
        RollingSummary reparsed = RollingSummary.parse(s.render());
        assertEquals("重构", reparsed.get("主要请求与意图"));
        assertEquals("A.java\nB.java", reparsed.get("文件和代码"));
    }

    @Test
    void parseHandlesOutOfOrderSectionsAndRenderNormalizes() {
        RollingSummary s = RollingSummary.parse("## 下一步\n先写这段\n## 主要请求与意图\n后写这段");
        assertEquals("先写这段", s.get("下一步"));
        assertEquals("后写这段", s.get("主要请求与意图"));
        String rendered = s.render();
        assertTrue(rendered.indexOf("## 主要请求与意图") < rendered.indexOf("## 下一步"),
                "render 应把乱序归一到固定顺序");
    }

    @Test
    void lifecycleItemsRoundTripWithoutChangingNineSections() {
        RollingSummary summary = new RollingSummary();
        summary.addItem(SummaryItem.create(
                "文件和代码", "file:A.java", "A.java 已修改，测试通过",
                SummaryItem.Lifecycle.RESOLVED, 90, List.of("tool-17", "test-18")));

        RollingSummary reparsed = RollingSummary.parse(summary.render());

        SummaryItem item = reparsed.findItem("文件和代码", "file:A.java").orElseThrow();
        assertEquals(SummaryItem.Lifecycle.RESOLVED, item.lifecycle());
        assertEquals(List.of("tool-17", "test-18"), item.evidenceRefs());
        assertEquals("A.java 已修改，测试通过", reparsed.get("文件和代码"));
        assertEquals(9, RollingSummary.SECTIONS.size());
    }

    @Test
    void supersededItemKeepsAuditButDoesNotRenderOldFactAsActive() {
        RollingSummary summary = new RollingSummary();
        SummaryItem old = SummaryItem.create(
                "关键技术概念", "build.tool", "构建工具是 Maven",
                SummaryItem.Lifecycle.STABLE, 95, List.of());
        summary.addItem(old.supersede("summary-new"));

        assertFalse(summary.get("关键技术概念").contains("构建工具是 Maven"));
        assertTrue(summary.render().contains("SUPERSEDED"));
        assertTrue(summary.render().contains("summary-new"));
    }

    @Test
    void legacyMarkdownMigratesToLifecycleItems() {
        RollingSummary summary = RollingSummary.parse("""
                ## 待办任务
                - 修复上下文压缩
                ## 当前在做什么
                实现生命周期摘要
                """);

        assertFalse(summary.items("待办任务").isEmpty());
        assertEquals(SummaryItem.Lifecycle.UNRESOLVED,
                summary.items("待办任务").getFirst().lifecycle());
        assertEquals(SummaryItem.Lifecycle.ACTIVE,
                summary.items("当前在做什么").getFirst().lifecycle());
    }
}
