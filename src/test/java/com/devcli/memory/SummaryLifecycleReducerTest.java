package com.devcli.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SummaryLifecycleReducerTest {

    @Test
    void resolvesActiveFileTaskIntoFinalOutcome() {
        RollingSummary previous = new RollingSummary();
        previous.addItem(SummaryItem.create(
                "当前在做什么", "file:A.java", "正在修改文件 A",
                SummaryItem.Lifecycle.ACTIVE, 80, List.of("read-1")));

        SummaryLifecycleReducer.Result result = new SummaryLifecycleReducer().apply(
                previous.render(), """
                        {"operations":[{
                          "action":"RESOLVE",
                          "section":"当前在做什么",
                          "target_section":"文件和代码",
                          "subject":"file:A.java",
                          "content":"文件 A 已修改，相关测试通过",
                          "importance":95,
                          "evidence_refs":["write-2","test-3"]
                        }]}
                        """);

        assertTrue(result.applied(), result.reason());
        RollingSummary updated = RollingSummary.parse(result.summary());
        assertTrue(updated.get("当前在做什么").isBlank());
        SummaryItem completed = updated.findItem("文件和代码", "file:A.java").orElseThrow();
        assertEquals(SummaryItem.Lifecycle.RESOLVED, completed.lifecycle());
        assertEquals("文件 A 已修改，相关测试通过", completed.content());
        assertEquals(List.of("write-2", "test-3"), completed.evidenceRefs());
    }

    @Test
    void updateSupersedesOldFactAndKeepsOnlyNewValueActive() {
        RollingSummary previous = new RollingSummary();
        previous.addItem(SummaryItem.create(
                "关键技术概念", "build.tool", "构建工具是 Maven",
                SummaryItem.Lifecycle.STABLE, 90, List.of()));

        SummaryLifecycleReducer.Result result = new SummaryLifecycleReducer().apply(
                previous.render(), """
                        {"operations":[{
                          "action":"UPDATE",
                          "section":"关键技术概念",
                          "subject":"build.tool",
                          "content":"构建工具是 Gradle",
                          "lifecycle":"STABLE"
                        }]}
                        """);

        RollingSummary updated = RollingSummary.parse(result.summary());
        assertTrue(result.applied());
        assertEquals("构建工具是 Gradle", updated.get("关键技术概念"));
        assertTrue(updated.allItems().stream()
                .anyMatch(item -> item.lifecycle() == SummaryItem.Lifecycle.SUPERSEDED));
    }

    @Test
    void malformedOperationsPreservePreviousSummary() {
        RollingSummary previous = new RollingSummary();
        previous.set("主要请求与意图", "保留九段摘要");
        String rendered = previous.render();

        SummaryLifecycleReducer.Result result = new SummaryLifecycleReducer().apply(
                rendered, "not-json");

        assertFalse(result.applied());
        assertEquals(rendered, result.summary());
    }

    @Test
    void deleteAndExpireAreDeterministic() {
        RollingSummary previous = new RollingSummary();
        previous.addItem(SummaryItem.create(
                "当前在做什么", "temporary", "临时上下文",
                SummaryItem.Lifecycle.ACTIVE, 20, List.of()));
        previous.addItem(SummaryItem.create(
                "待办任务", "obsolete", "无用事项",
                SummaryItem.Lifecycle.UNRESOLVED, 20, List.of()));

        SummaryLifecycleReducer.Result result = new SummaryLifecycleReducer().apply(
                previous.render(), """
                        {"operations":[
                          {"action":"EXPIRE","section":"当前在做什么","subject":"temporary"},
                          {"action":"DELETE","section":"待办任务","subject":"obsolete"}
                        ]}
                        """);

        RollingSummary updated = RollingSummary.parse(result.summary());
        assertTrue(result.applied());
        assertTrue(updated.get("当前在做什么").isBlank());
        assertTrue(updated.findItem("待办任务", "obsolete").isEmpty());
    }
}
