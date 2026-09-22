package com.devcli.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DelegationReviewProtocolTest {
    @Test
    void advisoryIssueDoesNotBlockApproval() {
        DelegationReviewProtocol.Decision decision = DelegationReviewProtocol.evaluate("""
                {"approved":false,"issues":[{"severity":"normal","description":"命名可优化"}]}
                """);

        assertTrue(decision.approved());
        assertTrue(decision.advisories() > 0);
        assertFalse(decision.hasBlockingIssue());
    }

    @Test
    void criticalIssueBlocksApproval() {
        DelegationReviewProtocol.Decision decision = DelegationReviewProtocol.evaluate("""
                {"approved":false,"issues":[{"severity":"critical","description":"鉴权绕过"}]}
                """);

        assertFalse(decision.approved());
        assertTrue(decision.hasBlockingIssue());
    }

    @Test
    void acceptsReviewerJsonWrappedByChildReport() {
        DelegationReviewProtocol.Decision decision = DelegationReviewProtocol.evaluate(
                "{\"summary\":\"{\\\"approved\\\":true,\\\"issues\\\":[{\\\"severity\\\":\\\"normal\\\",\\\"description\\\":\\\"命名可再统一\\\"}]}\"}");

        assertTrue(decision.protocolValid());
        assertTrue(decision.approved());
        assertTrue(decision.advisories() > 0);
    }

    @Test
    void blockingIssueWinsOverAdvisoryInOneTerminalDecision() {
        DelegationReviewProtocol.Decision decision = DelegationReviewProtocol.evaluate("""
                {"approved":true,"issues":[
                  {"severity":"normal","description":"命名可优化"},
                  {"severity":"critical","description":"鉴权绕过"}
                ]}
                """);

        assertFalse(decision.approved(), "硬阻断必须覆盖 advisory 的继续建议");
        assertTrue(decision.hasBlockingIssue());
        assertEquals(1, decision.blockingIssues().size());
        assertEquals(1, decision.advisories());
        assertFalse(decision.protocolValid() && decision.approved(),
                "同一裁决不能同时报告通过和硬阻断");
    }
}
