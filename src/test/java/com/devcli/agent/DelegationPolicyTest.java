package com.devcli.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DelegationPolicyTest {
    @Test
    void wildcardWithExtensionDoesNotCountAsSpecificFile() {
        var specific = DelegationPolicy.evaluate(AgentDelegationTest.brief(
                "role", "worker", "allowed_write_paths", "[\"src/Payment.java\"]"));
        for (String glob : new String[]{"*.java", "src/**/*.java", "src/?.java", "src/[AB].java"}) {
            var broad = DelegationPolicy.evaluate(AgentDelegationTest.brief(
                    "role", "worker", "allowed_write_paths", "[\"" + glob + "\"]"));
            assertEquals(specific.benefit() - 1, broad.benefit(), glob);
        }
    }

    @Test
    void readOnlyRoleCannotImproveScoreWithUnusedWriteDeclaration() {
        var normal = DelegationPolicy.evaluate(AgentDelegationTest.brief(
                "role", "explorer", "allowed_write_paths", "[]"));
        var declared = DelegationPolicy.evaluate(AgentDelegationTest.brief(
                "role", "explorer", "allowed_write_paths", "[\"src/File.java\"]"));
        assertEquals(normal.benefit(), declared.benefit());
        assertEquals(normal.score(), declared.score());
    }

    @Test
    void longContextOnlyAddsCostNotIsolationBenefit() {
        var shortInput = DelegationPolicy.evaluate(AgentDelegationTest.brief("role", "worker"));
        var longInput = DelegationPolicy.evaluate(AgentDelegationTest.brief(
                "role", "worker", "context", "x".repeat(9000)));
        assertEquals(shortInput.benefit(), longInput.benefit());
        assertTrue(longInput.coordinationCost() > shortInput.coordinationCost());
    }
}
