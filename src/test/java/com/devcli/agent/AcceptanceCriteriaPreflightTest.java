package com.devcli.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcceptanceCriteriaPreflightTest {

    @Test
    void rejectsCriterionWithoutVerificationMethod() {
        AcceptanceCriterion criterion = new AcceptanceCriterion(
                "AC-01", "behavior", "默认模型可用", "默认请求成功", "high",
                null, "execute_command");

        AcceptanceCriteriaPreflight.Report report = AcceptanceCriteriaPreflight.validate(
                List.of(criterion), Set.of("execute_command")::contains);

        assertFalse(report.executable());
        assertTrue(report.describeIssues().contains("verification_method"));
    }

    @Test
    void rejectsToolCriterionWithoutKnownVerifier() {
        AcceptanceCriterion criterion = new AcceptanceCriterion(
                "AC-01", "behavior", "默认模型可用", "默认请求成功", "high",
                AcceptanceCriterion.VerificationMethod.TOOL, "missing_tool");

        AcceptanceCriteriaPreflight.Report report = AcceptanceCriteriaPreflight.validate(
                List.of(criterion), Set.of("execute_command")::contains);

        assertFalse(report.executable());
        assertTrue(report.describeIssues().contains("missing_tool"));
    }

    @Test
    void rejectsCriterionWithoutExpectedEvidence() {
        AcceptanceCriterion criterion = new AcceptanceCriterion(
                "AC-01", "behavior", "默认模型可用", "", "high",
                AcceptanceCriterion.VerificationMethod.TOOL, "execute_command");

        AcceptanceCriteriaPreflight.Report report = AcceptanceCriteriaPreflight.validate(
                List.of(criterion), Set.of("execute_command")::contains);

        assertFalse(report.executable());
        assertTrue(report.describeIssues().contains("test_signal"));
    }

    @Test
    void rejectsDuplicateCriterionIds() {
        AcceptanceCriterion first = toolCriterion("AC-01");
        AcceptanceCriterion second = toolCriterion("AC-01");

        AcceptanceCriteriaPreflight.Report report = AcceptanceCriteriaPreflight.validate(
                List.of(first, second), Set.of("execute_command")::contains);

        assertFalse(report.executable());
        assertTrue(report.describeIssues().contains("重复"));
    }

    @Test
    void acceptsExplicitHumanCriterionAndMarksReviewRequired() {
        AcceptanceCriterion criterion = new AcceptanceCriterion(
                "AC-02", "user_experience", "终端层级清晰", "人工确认信息层级无歧义", "medium",
                AcceptanceCriterion.VerificationMethod.HUMAN, "用户检查终端最终展示");

        AcceptanceCriteriaPreflight.Report report = AcceptanceCriteriaPreflight.validate(
                List.of(criterion), Set.of("execute_command")::contains);

        assertTrue(report.executable());
        assertTrue(report.requiresHumanReview());
    }

    @Test
    void rejectsCriterionWithoutDagTarget() {
        AcceptanceCriterion criterion = new AcceptanceCriterion(
                "AC-03", "behavior", "结果可用", "命令退出码为 0", "high",
                AcceptanceCriterion.VerificationMethod.TOOL, "execute_command", List.of());

        AcceptanceCriteriaPreflight.Report report = AcceptanceCriteriaPreflight.validate(
                List.of(criterion), Set.of("execute_command")::contains, Set.of("step_1"));

        assertFalse(report.executable());
        assertTrue(report.describeIssues().contains("applies_to"));
    }

    @Test
    void rejectsCriterionTargetingUnknownDagNode() {
        AcceptanceCriterion criterion = new AcceptanceCriterion(
                "AC-04", "behavior", "结果可用", "命令退出码为 0", "high",
                AcceptanceCriterion.VerificationMethod.TOOL, "execute_command", List.of("step_9"));

        AcceptanceCriteriaPreflight.Report report = AcceptanceCriteriaPreflight.validate(
                List.of(criterion), Set.of("execute_command")::contains, Set.of("step_1"));

        assertFalse(report.executable());
        assertTrue(report.describeIssues().contains("step_9"));
    }

    private static AcceptanceCriterion toolCriterion(String id) {
        return new AcceptanceCriterion(
                id, "behavior", "默认模型可用", "命令退出码为 0", "high",
                AcceptanceCriterion.VerificationMethod.TOOL, "execute_command");
    }
}
