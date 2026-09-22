package com.devcli.policy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 加载期覆盖告警的定向测试。
 *
 * <p>{@link PermissionEvaluator} 按 {@code deny → allow → ask} 的固定次序短路，因此被更宽的
 * {@code allow} 覆盖的 {@code ask} 永远不会生效。这里钉住两件事：该报的要报出来，不该报的
 * 不能误报——告警噪音会让人直接忽略它，那和没有告警一样。</p>
 */
class PermissionRuleSetTest {

    private static PermissionRuleSet rules(List<String> deny, List<String> ask, List<String> allow) {
        return PermissionRuleSet.parse(deny, ask, allow);
    }

    @Test
    void emptySetHasNoShadowingWarnings() {
        assertTrue(PermissionRuleSet.EMPTY.shadowedAskRules().isEmpty());
    }

    @Test
    void broaderAllowShadowsNarrowerAsk() {
        PermissionRuleSet set = rules(
                List.of(),
                List.of("write_file(src/secret/**)"),
                List.of("write_file(src/**)"));

        List<String> warnings = set.shadowedAskRules();

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("write_file(src/secret/**)"), warnings.get(0));
        assertTrue(warnings.get(0).contains("write_file(src/**)"), warnings.get(0));
        assertTrue(warnings.get(0).contains("deny"), "提示必须给出可执行的替代做法: " + warnings.get(0));
    }

    @Test
    void bareAllowShadowsEveryAskOnTheSameTool() {
        PermissionRuleSet set = rules(
                List.of(),
                List.of("write_file(src/**)"),
                List.of("write_file"));

        assertEquals(1, set.shadowedAskRules().size());
    }

    @Test
    void identicalSpecifierInBothGroupsIsReported() {
        PermissionRuleSet set = rules(
                List.of(),
                List.of("write_file(src/**)"),
                List.of("write_file(src/**)"));

        assertEquals(1, set.shadowedAskRules().size(),
                "同一条规则同时写进 allow 与 ask 时，ask 一侧是死文本");
    }

    @Test
    void unrelatedAllowDoesNotShadowAsk() {
        PermissionRuleSet set = rules(
                List.of(),
                List.of("write_file(src/secret/**)"),
                List.of("write_file(docs/**)"));

        assertTrue(set.shadowedAskRules().isEmpty(), set.shadowedAskRules().toString());
    }

    @Test
    void allWildcardAllowShadowsEverythingOnTheTool() {
        PermissionRuleSet set = rules(
                List.of(),
                List.of("write_file(src/secret/**)"),
                List.of("write_file(**)"));

        assertEquals(1, set.shadowedAskRules().size(),
                "整条规则都是通配符时确定覆盖该工具的全部调用");
    }

    @Test
    void leadingWildcardAllowIsNotReportedAsShadowing() {
        PermissionRuleSet set = rules(
                List.of(),
                List.of("write_file(src/a.java)"),
                List.of("write_file(**/secret/**)"));

        assertTrue(set.shadowedAskRules().isEmpty(),
                "通配符在首位时前缀无信息量，宁可不报也不要误报: " + set.shadowedAskRules());
    }

    @Test
    void askWithoutSpecifierIsNotShadowedByNarrowerAllow() {
        PermissionRuleSet set = rules(
                List.of(),
                List.of("write_file"),
                List.of("write_file(src/**)"));

        assertTrue(set.shadowedAskRules().isEmpty(),
                "更宽的 ask 不会被更窄的 allow 完全覆盖，其中仍有需要审批的调用");
    }

    @Test
    void differentToolIsNotReported() {
        PermissionRuleSet set = rules(
                List.of(),
                List.of("execute_command(git push:*)"),
                List.of("write_file(src/**)"));

        assertTrue(set.shadowedAskRules().isEmpty());
    }

    @Test
    void denyDoesNotParticipateInShadowing() {
        PermissionRuleSet set = rules(
                List.of("write_file(src/secret/**)"),
                List.of("write_file(src/secret/**)"),
                List.of());

        assertTrue(set.shadowedAskRules().isEmpty(),
                "deny 先于 allow 生效，不会让 ask 失效");
    }
}
