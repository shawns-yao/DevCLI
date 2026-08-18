package com.devcli.cli;

import com.devcli.agent.AgentOrchestrator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTeamPlanReviewTest {

    @Test
    void mapsSupplementDecisionForTeamPlanReview() {
        AgentOrchestrator.TeamPlanReviewDecision decision = Main.mapTeamReviewDecision(
                PlanReviewInputParser.parse("补充默认参数验证"));

        assertEquals(AgentOrchestrator.TeamPlanReviewAction.SUPPLEMENT, decision.action());
        assertEquals("补充默认参数验证", decision.feedback());
    }

    @Test
    void formatsHumanVerificationObligationsBeforeExecution() {
        AgentOrchestrator.TeamPlanReviewRequest request = new AgentOrchestrator.TeamPlanReviewRequest(
                "调整终端",
                "step_1 完成终端展示",
                List.of(new AgentOrchestrator.AcceptanceCriterionView(
                        "AC-01", "终端层级清晰", "HUMAN",
                        "用户检查最终终端展示", "用户确认不存在歧义", "high",
                        List.of("FINAL"))),
                true);

        String text = Main.formatTeamPlanReview(request);

        assertTrue(text.contains("Plan 执行前评审"));
        assertTrue(!text.contains("Team 执行前评审"));
        assertTrue(text.contains("AC-01"));
        assertTrue(text.contains("HUMAN"));
        assertTrue(text.contains("用户检查最终终端展示"));
        assertTrue(text.contains("需要人工验收"));
    }

    @Test
    void formatsSemanticReviewerApprovalBeforeHumanDecision() {
        AgentOrchestrator.TeamPlanReviewRequest request = new AgentOrchestrator.TeamPlanReviewRequest(
                "实现功能", "step_1 实现", List.of(), false,
                true, "原始目标、节点和验收标准已闭环");

        String text = Main.formatTeamPlanReview(request);

        assertTrue(text.contains("Reviewer 计划语义评审"));
        assertTrue(text.contains("已闭环"));
    }
}
