package com.devcli.policy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则语法解析与匹配的定向测试。
 *
 * <p>重点覆盖三类容易写错、又直接影响安全边界的行为：给没有资源槽的工具写 specifier、
 * 复合命令的 allow / deny 非对称判定、以及重定向下通配符失效。</p>
 */
class PermissionRuleTest {

    // ------------------ 解析 ------------------

    @Test
    void parsesBareToolName() {
        PermissionRule rule = PermissionRule.parse("apply_patch");

        assertEquals("apply_patch", rule.tool());
        assertNull(rule.specifier());
    }

    @Test
    void parsesToolWithSpecifier() {
        PermissionRule rule = PermissionRule.parse("Edit(src/**)");

        assertEquals("edit_file", rule.tool());
        assertEquals("src/**", rule.specifier());
    }

    @Test
    void normalizesWorkbuddyStyleAliases() {
        assertEquals("edit_file", PermissionRule.parse("Edit(a/**)").tool());
        assertEquals("write_file", PermissionRule.parse("Write(a/**)").tool());
        assertEquals("read_file", PermissionRule.parse("Read").tool());
        assertEquals("execute_command", PermissionRule.parse("Bash(git:*)").tool());
        assertEquals("web_fetch", PermissionRule.parse("WebFetch(domain:a.com)").tool());
        assertEquals("web_search", PermissionRule.parse("WebSearch").tool());
    }

    @Test
    void acceptsMcpWildcardToolName() {
        PermissionRule rule = PermissionRule.parse("mcp__*");

        assertEquals("mcp__*", rule.tool());
        assertNull(rule.specifier());
    }

    @Test
    void rejectsSpecifierOnToolWithoutResourceSlot() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> PermissionRule.parse("mcp__puppeteer__navigate(page)"));

        assertTrue(failure.getMessage().contains("没有声明参数级资源槽"),
                "必须明确拒绝，不能留下一条永远不匹配的规则: " + failure.getMessage());
    }

    @Test
    void rejectsEmptySpecifierAndBlankRule() {
        assertThrows(IllegalArgumentException.class, () -> PermissionRule.parse("Edit()"));
        assertThrows(IllegalArgumentException.class, () -> PermissionRule.parse("   "));
    }

    @Test
    void rejectsWildcardOutsideToolNameTail() {
        assertThrows(IllegalArgumentException.class, () -> PermissionRule.parse("mc*p__tool"));
    }

    @Test
    void rejectsSpecifierThatEscapesProjectRoot() {
        assertThrows(IllegalArgumentException.class, () -> PermissionRule.parse("write_file(../escape/**)"));
        assertThrows(IllegalArgumentException.class, () -> PermissionRule.parse("write_file(/etc/passwd)"));
    }

    @Test
    void rejectsNetworkSpecifierWithSchemeOrWildcard() {
        assertThrows(IllegalArgumentException.class, () -> PermissionRule.parse("web_fetch(https://a.com)"));
        assertThrows(IllegalArgumentException.class, () -> PermissionRule.parse("web_fetch(domain:*.com)"));
        assertThrows(IllegalArgumentException.class, () -> PermissionRule.parse("web_fetch(domain:)"));
    }

    // ------------------ 工具名匹配 ------------------

    @Test
    void wildcardToolMatchesMcpPrefixOnly() {
        PermissionRule rule = PermissionRule.parse("mcp__*");

        assertTrue(rule.matchesTool("mcp__puppeteer__navigate"));
        assertTrue(rule.matchesTool("mcp__filesystem__read"));
        assertFalse(rule.matchesTool("write_file"));
    }

    @Test
    void starToolMatchesEverything() {
        assertTrue(PermissionRule.parse("*").matchesTool("write_file"));
    }

    @Test
    void toolNameMatchIgnoresCase() {
        assertTrue(PermissionRule.parse("write_file").matchesTool("WRITE_FILE"));
    }

    // ------------------ 路径匹配 ------------------

    @Test
    void pathGlobMatchesProjectRelativeKey() {
        PermissionRule rule = PermissionRule.parse("write_file(src/**)");

        assertTrue(rule.triggersOn(ToolResourceSlot.PATH, List.of("src/a/b/File.java")));
        assertTrue(rule.triggersOn(ToolResourceSlot.PATH, List.of("src")));
        assertFalse(rule.triggersOn(ToolResourceSlot.PATH, List.of("test/a.java")));
        assertFalse(rule.triggersOn(ToolResourceSlot.PATH, List.of("srcx/a.java")));
    }

    @Test
    void deleteFilesTriggersOnAnyPathButAllowsOnlyWhenEveryPathMatches() {
        PermissionRule rule = PermissionRule.parse("delete_files(src/**)");
        List<String> mixed = List.of("src/a.java", "docs/readme.md");

        assertTrue(rule.triggersOn(ToolResourceSlot.PATH_LIST, mixed),
                "deny / ask 语义下任一目标命中即触发");
        assertFalse(rule.allows(ToolResourceSlot.PATH_LIST, mixed),
                "allow 语义下必须每个目标都在范围内");
        assertTrue(rule.allows(ToolResourceSlot.PATH_LIST, List.of("src/a.java", "src/b.java")));
    }

    @Test
    void specifierRuleNeverMatchesWhenResourceValuesAreUnavailable() {
        PermissionRule rule = PermissionRule.parse("write_file(src/**)");

        assertFalse(rule.triggersOn(ToolResourceSlot.PATH, null),
                "无法解析资源值时不能当成命中，否则会误拒绝或误放行");
        assertFalse(rule.allows(ToolResourceSlot.PATH, null));
    }

    @Test
    void bareRuleMatchesWithoutResourceValues() {
        PermissionRule rule = PermissionRule.parse("apply_patch");

        assertTrue(rule.triggersOn(ToolResourceSlot.NONE, List.of()));
        assertTrue(rule.allows(ToolResourceSlot.NONE, List.of()));
    }

    // ------------------ 命令匹配 ------------------

    @Test
    void commandPrefixRuleRequiresWordBoundary() {
        PermissionRule rule = PermissionRule.parse("execute_command(git:*)");

        assertTrue(rule.allows(ToolResourceSlot.COMMAND, List.of("git")));
        assertTrue(rule.allows(ToolResourceSlot.COMMAND, List.of("git status")));
        assertFalse(rule.allows(ToolResourceSlot.COMMAND, List.of("gitleaks detect")),
                "git:* 不能匹配 gitleaks");
    }

    @Test
    void compoundCommandAllowRequiresEverySegmentToMatch() {
        PermissionRule rule = PermissionRule.parse("execute_command(git:*)");

        assertTrue(rule.allows(ToolResourceSlot.COMMAND, List.of("git add . && git commit -m x")));
        assertFalse(rule.allows(ToolResourceSlot.COMMAND, List.of("git status && rm -rf build")),
                "allow 规则下任一子命令未命中即整体不命中");
    }

    @Test
    void compoundCommandTriggerMatchesAnySegment() {
        PermissionRule rule = PermissionRule.parse("execute_command(rm:*)");

        assertTrue(rule.triggersOn(ToolResourceSlot.COMMAND, List.of("git status && rm -rf build")));
        assertTrue(rule.triggersOn(ToolResourceSlot.COMMAND, List.of("git status | rm x")));
        assertFalse(rule.triggersOn(ToolResourceSlot.COMMAND, List.of("git status")));
    }

    @Test
    void redirectionDisablesWildcardButKeepsExactMatch() {
        PermissionRule wildcard = PermissionRule.parse("execute_command(mvn test:*)");
        PermissionRule exact = PermissionRule.parse("execute_command(mvn test > out.log)");

        assertTrue(wildcard.allows(ToolResourceSlot.COMMAND, List.of("mvn test")));
        assertFalse(wildcard.allows(ToolResourceSlot.COMMAND, List.of("mvn test > out.log")),
                "重定向能改写未声明目标，通配放行必须失效");
        assertTrue(exact.allows(ToolResourceSlot.COMMAND, List.of("mvn test > out.log")));
    }

    @Test
    void trailingStarPatternMatchesWordBoundary() {
        PermissionRule rule = PermissionRule.parse("execute_command(npm run *)");

        assertTrue(rule.allows(ToolResourceSlot.COMMAND, List.of("npm run build")));
        assertFalse(rule.allows(ToolResourceSlot.COMMAND, List.of("npm runx build")));
    }

    // ------------------ 网络匹配 ------------------

    @Test
    void networkRuleMatchesDomainAndSubdomainOnly() {
        PermissionRule rule = PermissionRule.parse("web_fetch(domain:example.com)");

        assertTrue(rule.triggersOn(ToolResourceSlot.HOST, List.of("example.com")));
        assertTrue(rule.triggersOn(ToolResourceSlot.HOST, List.of("api.example.com")));
        assertFalse(rule.triggersOn(ToolResourceSlot.HOST, List.of("notexample.com")));
    }

    @Test
    void bareDomainSpecifierIsAccepted() {
        PermissionRule rule = PermissionRule.parse("web_fetch(github.com)");

        assertTrue(rule.triggersOn(ToolResourceSlot.HOST, List.of("raw.github.com")));
    }

    // ------------------ 字面量前缀（只服务于加载期覆盖告警） ------------------

    @Test
    void literalPrefixStopsAtFirstWildcard() {
        assertEquals("", PermissionRule.parse("write_file(**)").literalPrefix(),
                "通配符在首位时字面量前缀是空串");
        assertEquals("src/", PermissionRule.parse("write_file(src/**)").literalPrefix());
        assertEquals("src/secret/", PermissionRule.parse("write_file(src/secret/**)").literalPrefix());
        assertEquals("", PermissionRule.parse("write_file(**/secret/**)").literalPrefix(),
                "字面量前缀不是宽窄度量：这条语义上比 src/** 更窄，前缀反而更短");
    }

    @Test
    void literalPrefixIsNullWithoutSpecifier() {
        assertNull(PermissionRule.parse("write_file").literalPrefix());
    }

    @Test
    void literalPrefixIsWholeSpecifierWithoutWildcard() {
        assertEquals("src/a.java", PermissionRule.parse("write_file(src/a.java)").literalPrefix());
        assertEquals("git push:", PermissionRule.parse("execute_command(git push:*)").literalPrefix(),
                "第一个通配符就在 :* 的 * 上，因此冒号仍在字面量里");
    }
}
