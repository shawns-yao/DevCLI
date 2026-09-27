package com.devcli.policy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则层求值次序的定向测试。
 *
 * <p>{@code hard_deny} 必须最先且不可覆盖；非自动模式无法判断用户意图，因此显式
 * {@code soft_deny} 必须先于宽泛 {@code allow}，避免授权静默吞掉人工确认边界。</p>
 */
class PermissionEvaluatorTest {

    private static PermissionRuleSet rules(List<String> hardDeny, List<String> softDeny,
                                           List<String> allow) {
        return PermissionRuleSet.parse(hardDeny, softDeny, allow, List.of());
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
    void hardDenyBeatsAllowAndSoftDeny() {
        PermissionRuleSet set = rules(
                List.of("execute_command(git push:*)"),
                List.of("execute_command(git push:*)"),
                List.of("execute_command(git:*)"));

        PermissionEvaluator.Result result =
                PermissionEvaluator.evaluate("execute_command", List.of("git push origin main"), set);

        assertEquals(PermissionEvaluator.Outcome.HARD_DENY, result.outcome());
        assertTrue(result.reason().contains("git push:*"), result.reason());
    }

    @Test
    void narrowerSoftDenyOverridesBroadAllow() {
        PermissionRuleSet set = rules(
                List.of(),
                List.of("write_file(src/secret/**)"),
                List.of("write_file(src/**)"));

        PermissionEvaluator.Result result =
                PermissionEvaluator.evaluate("write_file", List.of("src/secret/key.txt"), set);

        assertEquals(PermissionEvaluator.Outcome.SOFT_DENY, result.outcome());
        assertTrue(result.reason().contains("src/secret/**"), result.reason());
    }

    @Test
    void softDenyIsEvaluatedBeforeAllow() {
        PermissionRuleSet set = rules(
                List.of(),
                List.of("write_file"),
                List.of("write_file(src/**)"));

        assertEquals(PermissionEvaluator.Outcome.SOFT_DENY,
                PermissionEvaluator.evaluate("write_file", List.of("src/a.java"), set).outcome());
    }

    @Test
    void identicalRuleInBothGroupsResolvesToSoftDeny() {
        PermissionRuleSet set = rules(
                List.of(),
                List.of("write_file(src/**)"),
                List.of("write_file(src/**)"));

        assertEquals(PermissionEvaluator.Outcome.SOFT_DENY,
                PermissionEvaluator.evaluate("write_file", List.of("src/a.java"), set).outcome(),
                "同一条规则同时出现时，显式边界优先");
    }

    @Test
    void hardDenyWinsRegardlessOfRuleGroups() {
        PermissionRuleSet set = rules(
                List.of("write_file"),
                List.of("write_file"),
                List.of("write_file(src/**)"));

        assertEquals(PermissionEvaluator.Outcome.HARD_DENY,
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
    void softDenyIsReportedAsBoundaryNotAsImmediateRefusal() {
        PermissionRuleSet set = rules(List.of(), List.of("delete_files(src/**)"), List.of());

        PermissionEvaluator.Result result =
                PermissionEvaluator.evaluate("delete_files", List.of("src/a.java"), set);

        assertEquals(PermissionEvaluator.Outcome.SOFT_DENY, result.outcome());
        assertTrue(result.reason().contains("软阻止规则"), result.reason());
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

        assertEquals(PermissionEvaluator.Outcome.HARD_DENY,
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
                () -> PermissionRuleSet.parse(List.of(), List.of(), List.of("mcp__x__y(page)"),
                        List.of()));
    }

    @Test
    void compactSummaryIsEmptyWithoutRules() {
        assertEquals("", PermissionRuleSet.EMPTY.compactSummary(),
                "无规则时状态栏不能出现长期占位的空字段");

        PermissionRuleSet set = rules(List.of("apply_patch"), List.of(), List.of("write_file(src/**)"));
        assertEquals("hard_deny 1 · allow 1", set.compactSummary());
    }

    @Test
    void environmentAloneIsNotAnEmptyRuleSet() {
        // 环境事实不产生判定，但它是用户写下的配置：摘要里要显示，否则用户会以为自己没保存成功。
        PermissionRuleSet set = PermissionRuleSet.parse(List.of(), List.of(), List.of(),
                List.of("可信域名：github.com"));

        assertEquals("environment 1", set.compactSummary());
        assertEquals(PermissionEvaluator.Outcome.UNDECIDED,
                PermissionEvaluator.evaluate("write_file", List.of("src/a.java"), set).outcome(),
                "环境事实不得影响任何判定");
    }
}
