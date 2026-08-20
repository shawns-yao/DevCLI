package com.devcli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
