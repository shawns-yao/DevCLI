package com.devcli.policy;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CommandPermissionRulesTest {
    @Test void onlyNarrowRulesSurviveAutoFiltering() {
        for (String rule : List.of("execute_command", "execute_command(*)", "execute_command(mvn:*)",
                "execute_command(python script.py:*)", "execute_command(pwsh -Command:*)", "*")) {
            assertFalse(CommandPermissionRules.isNarrow(PermissionRule.parse(rule)), rule);
        }
        for (String rule : List.of("execute_command(mvn test:*)", "execute_command(npm test:*)",
                "execute_command(python script.py)")) {
            assertTrue(CommandPermissionRules.isNarrow(PermissionRule.parse(rule)), rule);
        }
    }

    @Test void shellSyntaxCannotHideBehindAnAllowedPrefix() {
        var rules = PermissionRuleSet.parse(List.of(), List.of(), List.of("execute_command(mvn test:*)"), List.of());
        for (String command : List.of("mvn test $(evil)", "mvn test `evil`", "mvn test\nevil", "mvn test & evil",
                "mvn test > out.txt", "mvn test; mvn test")) {
            assertEquals(PermissionEvaluator.Outcome.UNDECIDED,
                    PermissionEvaluator.evaluate("execute_command", List.of(command), rules, true).outcome(), command);
            assertNull(CommandPermissionRules.suggestedRule(command));
        }
    }

    @Test void savedPrefixPreservesTheWholeCommandAndTokenBoundary() {
        String suggested = CommandPermissionRules.suggestedRule("mvn test -Pquick");
        assertEquals("execute_command(mvn test -Pquick:*)", suggested);
        var rule = PermissionRule.parse(suggested);
        assertTrue(rule.allows(ToolResourceSlot.COMMAND, List.of("mvn test -Pquick -o")));
        assertFalse(rule.allows(ToolResourceSlot.COMMAND, List.of("mvn test -Pquicker")));
        assertNull(CommandPermissionRules.suggestedRule("python script.py"));
    }
}
