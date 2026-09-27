package com.devcli.policy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 四类权限规则的值对象契约。 */
class PermissionRuleSetTest {

    @Test
    void environmentIsTrimmedAndEmptyEntriesAreDiscarded() {
        PermissionRuleSet rules = PermissionRuleSet.parse(
                List.of(), List.of(), List.of(), List.of("  生产集群是共享资源  ", " "));

        assertEquals(List.of("生产集群是共享资源"), rules.environment());
        assertEquals("environment 1", rules.compactSummary());
    }

    @Test
    void allowRulesCanBeSelectedByTool() {
        PermissionRuleSet rules = PermissionRuleSet.parse(
                List.of(), List.of(),
                List.of("write_file(src/**)", "execute_command(mvn test:*)"), List.of());

        assertEquals(1, rules.allowRulesForTool("write_file").size());
        assertTrue(rules.allowRulesForTool("web_fetch").isEmpty());
    }
}
