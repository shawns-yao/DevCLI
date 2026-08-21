package com.devcli.memory;

import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactionSemanticGuardTest {

    @Test
    void repairsSummaryWhenCriticalConstraintsAreMissing() {
        List<LlmClient.Message> source = List.of(
                LlmClient.Message.user("必须使用 Java 17，禁止修改 public API。默认测试命令是 mvn test -Pquick。"),
                LlmClient.Message.assistant("收到。"));

        CompactionSemanticGuard.Validation result = CompactionSemanticGuard.validateAndRepair(
                source, "用户正在修改 Java 项目。", 2_000);

        assertFalse(result.validBeforeRepair());
        assertTrue(result.repairedSummary().contains("Java 17"));
        assertTrue(result.repairedSummary().contains("禁止修改 public API"));
        assertTrue(result.repairedSummary().contains("mvn test -Pquick"));
        assertTrue(result.missingConstraints().size() >= 2);
    }

    @Test
    void acceptsSummaryContainingProtectedConstraints() {
        List<LlmClient.Message> source = List.of(
                LlmClient.Message.user("server.port=8443，必须保留兼容入口。"));

        CompactionSemanticGuard.Validation result = CompactionSemanticGuard.validateAndRepair(
                source, "关键约束：server.port=8443，必须保留兼容入口。", 2_000);

        assertTrue(result.validBeforeRepair());
        assertTrue(result.missingConstraints().isEmpty());
    }

    @Test
    void keepsOnlyLatestValueForSameStructuredClaim() {
        List<LlmClient.Message> source = List.of(
                LlmClient.Message.user("server.port=8080"),
                LlmClient.Message.user("server.port=8443"));

        CompactionSemanticGuard.Validation result = CompactionSemanticGuard.validateAndRepair(
                source, "端口配置尚未记录。", 2_000);

        assertFalse(result.validBeforeRepair());
        assertTrue(result.repairedSummary().contains("server.port=8443"));
        assertFalse(result.repairedSummary().contains("server.port=8080"));
        assertTrue(result.protectedConstraintCount() == 1);
    }

    @Test
    void unrelatedNegativeSentenceDoesNotSatisfyProhibition() {
        List<LlmClient.Message> source = List.of(
                LlmClient.Message.user("禁止修改 public API。"));

        CompactionSemanticGuard.Validation result = CompactionSemanticGuard.validateAndRepair(
                source, "不要删除测试。public API 需要检查。", 2_000);

        assertFalse(result.validBeforeRepair());
        assertTrue(result.repairedSummary().contains("禁止修改 public API"));
    }

    @Test
    void latestNaturalLanguageClaimSupersedesOlderValue() {
        List<LlmClient.Message> source = List.of(
                LlmClient.Message.user("项目默认 Java 版本是 17。"),
                LlmClient.Message.user("项目默认 Java 版本是 21。"));

        CompactionSemanticGuard.Validation result = CompactionSemanticGuard.validateAndRepair(
                source, "项目约束待恢复。", 2_000);

        assertTrue(result.repairedSummary().contains("Java 版本是 21"));
        assertFalse(result.repairedSummary().contains("Java 版本是 17"));
    }

    @Test
    void restoresConstraintsInsideExistingNineSections() {
        RollingSummary summary = new RollingSummary();
        summary.set("当前在做什么", "修改压缩器");

        CompactionSemanticGuard.Validation result = CompactionSemanticGuard.validateAndRepair(
                List.of(LlmClient.Message.user("必须保留九段摘要。")), summary.render(), 4_000);

        RollingSummary repaired = RollingSummary.parse(result.repairedSummary());
        assertTrue(repaired.get("主要请求与意图").contains("必须保留九段摘要"));
        assertFalse(result.repairedSummary().contains("## 压缩语义守卫恢复的关键约束"));
        assertTrue(RollingSummary.SECTIONS.stream()
                .allMatch(section -> result.repairedSummary().contains("## " + section)));
    }
}
