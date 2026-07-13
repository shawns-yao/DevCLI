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
}
