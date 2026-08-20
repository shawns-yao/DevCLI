package com.devcli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleContextArchitectureTest {

    @TempDir
    Path tempDir;

    @Test
    void explicitRulesPersistOutsideLongTermMemory() {
        RuleContext rules = new RuleContext(tempDir);
        RuleContext.Rule added = rules.addRule("禁止修改生成目录", "test");

        RuleContext reloaded = new RuleContext(tempDir);
        assertTrue(reloaded.renderForPrompt().contains("禁止修改生成目录"));
        assertTrue(added.id.startsWith("rule-"));
        assertFalse(tempDir.resolve("pinned_facts.json").toFile().exists());
    }

    @Test
    void legacyPinnedFactsAreReportedWithoutBeingInjectedAsRules() throws Exception {
        Files.writeString(tempDir.resolve("pinned_facts.json"), """
                [{"id":"pin-old","content":"用户偏好简体中文","source":"legacy"}]
                """, StandardCharsets.UTF_8);

        RuleContext rules = new RuleContext(tempDir);

        assertFalse(rules.renderForPrompt().contains("用户偏好简体中文"));
        assertTrue(rules.renderManagementReport().contains("旧 pinned facts 待分类: 1 条"));
        assertTrue(rules.renderManagementReport().contains("pin-old"));
    }

    @Test
    void ruleContextRejectsNewSensitiveValuesAndRedactsLegacyCandidates() throws Exception {
        Files.writeString(tempDir.resolve("pinned_facts.json"), """
                [{"id":"pin-secret","content":"token=legacy-secret","source":"legacy"}]
                """, StandardCharsets.UTF_8);
        RuleContext rules = new RuleContext(tempDir);

        assertThrows(IllegalArgumentException.class,
                () -> rules.addRule("password=plain-secret", "test"));
        assertFalse(rules.renderManagementReport().contains("legacy-secret"));
        assertTrue(rules.renderManagementReport().contains("token=***"));
    }
}
