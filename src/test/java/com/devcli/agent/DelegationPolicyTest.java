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
            assertEquals(specific.yield().benefit() - 1, broad.yield().benefit(), glob);
            // 隔离度只影响诊断：范围宽窄都不改变放行结论。
            assertTrue(broad.allowed(), glob);
        }
        assertTrue(specific.allowed());
    }

    @Test
    void readOnlyRoleCannotImproveScoreWithUnusedWriteDeclaration() {
        var normal = DelegationPolicy.evaluate(AgentDelegationTest.brief(
                "role", "explorer", "allowed_write_paths", "[]"));
        var declared = DelegationPolicy.evaluate(AgentDelegationTest.brief(
                "role", "explorer", "allowed_write_paths", "[\"src/File.java\"]"));
        assertEquals(normal.yield().benefit(), declared.yield().benefit());
        assertEquals(normal.yield().score(), declared.yield().score());
    }

    @Test
    void longContextOnlyAddsCostNotIsolationBenefit() {
        var shortInput = DelegationPolicy.evaluate(AgentDelegationTest.brief("role", "worker"));
        var longInput = DelegationPolicy.evaluate(AgentDelegationTest.brief(
                "role", "worker", "context", "x".repeat(9000)));
        assertEquals(shortInput.yield().benefit(), longInput.yield().benefit());
        assertTrue(longInput.yield().coordinationCost() > shortInput.yield().coordinationCost());
    }

    @Test
    void lowYieldIsAdvisoryOnlyAndStillAdmitted() {
        var longInput = DelegationPolicy.evaluate(AgentDelegationTest.brief(
                "role", "worker", "context", "x".repeat(9000)));
        assertTrue(longInput.allowed(), "低收益不应被硬拒绝");
        assertTrue(longInput.yield().lowYield());
        var shortInput = DelegationPolicy.evaluate(AgentDelegationTest.brief("role", "worker"));
        assertFalse(shortInput.yield().lowYield());
    }

    @Test
    void singleToolOperationIsRejectedBySelfDeclaration() {
        var decision = DelegationPolicy.evaluate(AgentDelegationTest.brief(
                "role", "worker", "task_spec", """
                        {"execution_kind":"single_tool",
                         "inputs":"One file","scope":"One file","done_condition":"Read it"}
                        """));
        assertFalse(decision.allowed());
    }

    @Test
    void contractShapeIsNotJudgedByAdmissionLayer() {
        // 契约缺项由工具 schema 承担，准入层不再重复判定。详见 ADR 0010。
        var missingScope = DelegationPolicy.evaluate(AgentDelegationTest.brief(
                "role", "worker", "task_spec", """
                        {"execution_kind":"agent_loop",
                         "inputs":"Fixture","done_condition":"Report"}
                        """));
        assertTrue(missingScope.allowed());
    }

    @Test
    void writeScopeIsNotJudgedByAdmissionLayer() {
        // worker 写入范围的条件必填由语义校验层承担，准入层只看执行粒度。详见 ADR 0010。
        assertTrue(DelegationPolicy.evaluate(AgentDelegationTest.brief(
                "role", "worker", "allowed_write_paths", "[]")).allowed());
        assertTrue(DelegationPolicy.evaluate(AgentDelegationTest.brief(
                "role", "explorer", "allowed_write_paths", "[]")).allowed());
    }
}
