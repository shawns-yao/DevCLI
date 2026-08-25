package com.devcli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** RuleContext 的持久化、渲染和软上限契约。 */
class RuleContextTest {

    @TempDir
    Path tempDir;

    @Test
    void emptyStateRendersEmptyString() {
        RuleContext context = new RuleContext(tempDir);
        context.reloadFiles(tempDir);
        assertEquals("", context.renderForPrompt());
    }

    @Test
    void rulePersistsAndReloads() {
        RuleContext context = new RuleContext(tempDir);
        RuleContext.Rule rule = context.addRule("用户偏好简体中文", "user-cli");
        assertNotNull(rule.id);
        assertEquals("用户偏好简体中文", rule.content);

        RuleContext reloaded = new RuleContext(tempDir);
        List<RuleContext.Rule> rules = reloaded.listRules();
        assertEquals(1, rules.size());
        assertEquals("用户偏好简体中文", rules.get(0).content);
        assertEquals("user-cli", rules.get(0).source);
    }

    @Test
    void ruleDeduplicatesIdenticalContent() {
        RuleContext context = new RuleContext(tempDir);
        RuleContext.Rule first = context.addRule("项目根 /home/dev/myapp", "user-cli");
        RuleContext.Rule second = context.addRule("项目根 /home/dev/myapp", "llm-tool");
        assertEquals(first.id, second.id);
        assertEquals(1, context.listRules().size());
        assertEquals("llm-tool", context.listRules().get(0).source);
    }

    @Test
    void removeRulePersists() {
        RuleContext context = new RuleContext(tempDir);
        RuleContext.Rule rule = context.addRule("可删除规则", "user-cli");
        assertTrue(context.removeRule(rule.id));
        assertTrue(context.listRules().isEmpty());
        assertTrue(new RuleContext(tempDir).listRules().isEmpty());
    }

    @Test
    void removeUnknownRuleReturnsFalse() {
        assertFalse(new RuleContext(tempDir).removeRule("rule-doesnotexist"));
    }

    @Test
    void renderIncludesAllSourcesInOrder() throws IOException {
        Path projectRoot = tempDir.resolve("project");
        Path home = tempDir.resolve("home");
        Files.createDirectories(home.resolve(".devcli"));
        Files.createDirectories(projectRoot.resolve(".devcli"));
        Files.writeString(home.resolve(".devcli/DEVCLI.md"), "用户全局：用中文沟通");
        Files.writeString(projectRoot.resolve("DEVCLI.md"), "项目约定：Java 17 + Maven");
        Files.writeString(projectRoot.resolve(".devcli/DEVCLI.local.md"), "本地补充：跳过测试快速打包");

        String originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", home.toString());
            RuleContext context = new RuleContext(tempDir.resolve("memory"));
            context.reloadFiles(projectRoot);
            context.addRule("最终决策：使用 SymbolSolver 不引入", "user-cli");
            String rendered = context.renderForPrompt();

            int user = rendered.indexOf("用户全局");
            int project = rendered.indexOf("项目约定");
            int local = rendered.indexOf("本地补充");
            int rule = rendered.indexOf("最终决策");
            assertTrue(user >= 0 && user < project);
            assertTrue(project < local);
            assertTrue(local < rule);
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void missingFilesAreIgnored() {
        RuleContext context = new RuleContext(tempDir);
        context.reloadFiles(tempDir);
        assertEquals("", context.renderForPrompt());
    }

    @Test
    void statusSummaryShowsOverCap() {
        RuleContext context = new RuleContext(tempDir);
        context.addRule("x ".repeat(RuleContext.MAX_RULE_TOKENS * 5), "user-cli");
        String summary = context.getStatusSummary();
        assertTrue(summary.contains("超限"));
        assertTrue(summary.contains("cap " + RuleContext.MAX_RULE_TOKENS));
    }

    @Test
    void rejectsBlankRule() {
        RuleContext context = new RuleContext(tempDir);
        assertThrows(IllegalArgumentException.class, () -> context.addRule("", "user-cli"));
        assertThrows(IllegalArgumentException.class, () -> context.addRule("   ", "user-cli"));
        assertThrows(IllegalArgumentException.class, () -> context.addRule(null, "user-cli"));
    }
}
