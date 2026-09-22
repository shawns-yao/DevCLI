package com.devcli.policy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则层求值次序的定向测试。
 *
 * <p>次序本身就是安全语义：{@code deny} 必须最先且不可覆盖，{@code allow} 先于 {@code ask}
 * 短路。这个次序与 WorkBuddy 一致，代价是一条宽泛的 {@code allow} 会吞掉更窄的 {@code ask}——
 * 下面用 {@link #broaderAllowShadowsNarrowerAsk()} 把这个代价钉成显式契约，任何调整都必须
 * 先改 ADR 再改测试。</p>
 */
class PermissionEvaluatorTest {

    private static PermissionRuleSet rules(List<String> deny, List<String> ask, List<String> allow) {
        return PermissionRuleSet.parse(deny, ask, allow);
    }

    @Test
    void emptyRuleSetIsUndecided() {
        assertEquals(PermissionEvaluator.Outcome.UNDECIDED,
                PermissionEvaluator.evaluate("write_file", List.of("a.java"),
                        PermissionRuleSet.EMPTY).outcome());
        assertEquals(PermissionEvaluator.Outcome.UNDECIDED,
                PermissionEvaluator.evaluate("write_file", List.of("a.java"), null).outcome());
    }

    @Test
    void noMatchingRuleIsUndecided() {
        PermissionRuleSet set = rules(List.of(), List.of(), List.of("write_file(docs/**)"));

        PermissionEvaluator.Result result =
                PermissionEvaluator.evaluate("write_file", List.of("src/a.java"), set);

        assertEquals(PermissionEvaluator.Outcome.UNDECIDED, result.outcome());
    }

    @Test
    void denyBeatsAllowAndAsk() {
        PermissionRuleSet set = rules(
                List.of("execute_command(git push:*)"),
                List.of("execute_command(git push:*)"),
                List.of("execute_command(git:*)"));

        PermissionEvaluator.Result result =
                PermissionEvaluator.evaluate("execute_command", List.of("git push origin main"), set);

        assertEquals(PermissionEvaluator.Outcome.DENY, result.outcome());
        assertTrue(result.reason().contains("git push:*"), result.reason());
    }

    @Test
    void broaderAllowShadowsNarrowerAsk() {
        // 这是固定次序的真实代价，不是缺陷：allow 先短路，更窄的 ask 永远轮不到。
        // 加载期由 PermissionRuleSet.shadowedAskRules() 提示用户，次序本身不为此让步。
        PermissionRuleSet set = rules(
                List.of(),
                List.of("write_file(src/secret/**)"),
                List.of("write_file(src/**)"));

        PermissionEvaluator.Result result =
                PermissionEvaluator.evaluate("write_file", List.of("src/secret/key.txt"), set);

        assertEquals(PermissionEvaluator.Outcome.ALLOW, result.outcome());
        assertTrue(result.reason().contains("src/**"), result.reason());
    }

    @Test
    void allowIsEvaluatedBeforeAsk() {
        PermissionRuleSet set = rules(
                List.of(),
                List.of("write_file"),
                List.of("write_file(src/**)"));

        assertEquals(PermissionEvaluator.Outcome.ALLOW,
                PermissionEvaluator.evaluate("write_file", List.of("src/a.java"), set).outcome());
    }

    @Test
    void identicalRuleInBothGroupsResolvesToAllow() {
        PermissionRuleSet set = rules(
                List.of(),
                List.of("write_file(src/**)"),
                List.of("write_file(src/**)"));

        assertEquals(PermissionEvaluator.Outcome.ALLOW,
                PermissionEvaluator.evaluate("write_file", List.of("src/a.java"), set).outcome(),
                "同一条规则同时出现在 allow 与 ask 时，allow 先短路");
    }

    @Test
    void denyWinsRegardlessOfRuleGroups() {
        PermissionRuleSet set = rules(
                List.of("write_file"),
                List.of("write_file"),
                List.of("write_file(src/**)"));

        assertEquals(PermissionEvaluator.Outcome.DENY,
                PermissionEvaluator.evaluate("write_file", List.of("src/a.java"), set).outcome());
    }

    @Test
    void firstMatchingAllowRuleShortCircuits() {
        PermissionRuleSet set = rules(
                List.of(),
                List.of(),
                List.of("write_file(src/**)", "write_file(src/generated/**)"));

        PermissionEvaluator.Result result =
                PermissionEvaluator.evaluate("write_file", List.of("src/generated/a.java"), set);

        assertEquals(PermissionEvaluator.Outcome.ALLOW, result.outcome());
        assertTrue(result.reason().contains("write_file(src/**)"), result.reason());
    }

    @Test
    void askForcesApprovalWithoutDenying() {
        PermissionRuleSet set = rules(List.of(), List.of("delete_files(src/**)"), List.of());

        PermissionEvaluator.Result result =
                PermissionEvaluator.evaluate("delete_files", List.of("src/a.java"), set);

        assertEquals(PermissionEvaluator.Outcome.ASK, result.outcome());
        assertTrue(result.reason().contains("询问规则"), result.reason());
    }

    @Test
    void specifierRuleIsSkippedWhenResourceValuesAreUnavailable() {
        PermissionRuleSet set = rules(List.of("write_file(src/**)"), List.of(), List.of());

        assertEquals(PermissionEvaluator.Outcome.UNDECIDED,
                PermissionEvaluator.evaluate("write_file", null, set).outcome(),
                "资源值不可解析时必须退回人工审批，不能凭规则误拒绝");
    }

    @Test
    void ruleWithoutSpecifierMatchesToolWithoutResourceSlot() {
        PermissionRuleSet set = rules(List.of("apply_patch"), List.of(), List.of());

        assertEquals(PermissionEvaluator.Outcome.DENY,
                PermissionEvaluator.evaluate("apply_patch", List.of(), set).outcome());
    }

    @Test
    void wildcardToolRuleCoversWholeMcpServer() {
        PermissionRuleSet set = rules(List.of(), List.of(), List.of("mcp__*"));

        assertEquals(PermissionEvaluator.Outcome.ALLOW,
                PermissionEvaluator.evaluate("mcp__puppeteer__navigate", List.of(), set).outcome());
        assertEquals(PermissionEvaluator.Outcome.UNDECIDED,
                PermissionEvaluator.evaluate("write_file", List.of("a.java"), set).outcome());
    }

    @Test
    void parseRejectsAnyIllegalRuleInsteadOfPartiallyAccepting() {
        assertThrows(IllegalArgumentException.class,
                () -> PermissionRuleSet.parse(List.of(), List.of(), List.of("mcp__x__y(page)")));
    }

    @Test
    void compactSummaryIsEmptyWithoutRules() {
        assertEquals("", PermissionRuleSet.EMPTY.compactSummary(),
                "无规则时状态栏不能出现长期占位的空字段");

        PermissionRuleSet set = rules(List.of("apply_patch"), List.of(), List.of("write_file(src/**)"));
        assertEquals("deny 1 · allow 1", set.compactSummary());
    }
}
