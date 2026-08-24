package com.devcli.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamPlanReviewProtocolTest {

    @Test
    void acceptsPlanOnlyWithRequirementAndCriterionTraceability() {
        TeamPlanReviewProtocol.Evaluation result = TeamPlanReviewProtocol.evaluate("""
                {
                  "approved": true,
                  "summary": "计划覆盖完整",
                  "requirement_coverage": [
                    {
                      "requirement": "缺省参数使用默认值",
                      "status": "covered",
                      "step_ids": ["step_1"],
                      "criterion_ids": ["AC-01"]
                    }
                  ],
                  "criteria_reviews": [
                    {
                      "id": "AC-01",
                      "clear": true,
                      "verifiable": true,
                      "scope_valid": true,
                      "evidence": "step_1 实现，FINAL 复核"
                    }
                  ],
                  "issues": []
                }
                """, List.of("AC-01"), Set.of("step_1"));

        assertTrue(result.approved(), result.issues());
        assertTrue(result.summary().contains("覆盖完整"));
    }

    @Test
    void rejectsApprovalWithoutRequirementCoverage() {
        TeamPlanReviewProtocol.Evaluation result = TeamPlanReviewProtocol.evaluate("""
                {
                  "approved": true,
                  "summary": "通过",
                  "requirement_coverage": [],
                  "criteria_reviews": [
                    {"id":"AC-01","clear":true,"verifiable":true,"scope_valid":true,"evidence":"ok"}
                  ],
                  "issues": []
                }
                """, List.of("AC-01"), Set.of("step_1"));

        assertFalse(result.approved());
        assertTrue(result.issues().contains("需求覆盖"), result.issues());
    }

    @Test
    void rejectsApprovalWhenCriterionIsAmbiguousOrUnverifiable() {
        TeamPlanReviewProtocol.Evaluation result = TeamPlanReviewProtocol.evaluate("""
                {
                  "approved": true,
                  "summary": "通过",
                  "requirement_coverage": [
                    {"requirement":"默认参数","status":"covered","step_ids":["step_1"],"criterion_ids":["AC-01"]}
                  ],
                  "criteria_reviews": [
                    {"id":"AC-01","clear":false,"verifiable":false,"scope_valid":true,"evidence":"边界不清"}
                  ],
                  "issues": []
                }
                """, List.of("AC-01"), Set.of("step_1"));

        assertFalse(result.approved());
        assertTrue(result.issues().contains("AC-01"), result.issues());
    }

    @Test
    void rejectsMalformedJsonAndPreservesStructuredReviewIssues() {
        TeamPlanReviewProtocol.Evaluation malformed = TeamPlanReviewProtocol.evaluate(
                "not-json", List.of("AC-01"), Set.of("step_1"));
        TeamPlanReviewProtocol.Evaluation rejected = TeamPlanReviewProtocol.evaluate("""
                {
                  "approved": false,
                  "summary": "缺少错误处理",
                  "requirement_coverage": [
                    {"requirement":"错误处理","status":"missing","step_ids":[],"criterion_ids":[]}
                  ],
                  "criteria_reviews": [
                    {"id":"AC-01","clear":true,"verifiable":true,"scope_valid":true,"evidence":"仅覆盖正常路径"}
                  ],
                  "issues": [
                    {
                      "type":"missing_requirement",
                      "severity":"high",
                      "requirement":"错误处理",
                      "description":"计划没有失败路径",
                      "suggested_fix":"增加失败路径步骤和验收标准"
                    }
                  ]
                }
                """, List.of("AC-01"), Set.of("step_1"));

        assertFalse(malformed.approved());
        assertTrue(malformed.issues().contains("JSON"), malformed.issues());
        assertFalse(rejected.approved());
        assertTrue(rejected.issues().contains("missing_requirement"), rejected.issues());
        assertTrue(rejected.issues().contains("增加失败路径"), rejected.issues());
    }

    @Test
    void highSeverityCriterionRequiresAConcreteCounterexample() {
        String review = """
                {
                  "approved": true,
                  "summary": "计划覆盖完整",
                  "requirement_coverage": [
                    {"requirement":"错误输入必须失败","status":"covered","step_ids":["step_1"],"criterion_ids":["AC-01"]}
                  ],
                  "criteria_reviews": [
                    {"id":"AC-01","clear":true,"verifiable":true,"scope_valid":true,"evidence":"工具可验证"}
                  ],
                  "issues": []
                }
                """;

        TeamPlanReviewProtocol.Evaluation result = TeamPlanReviewProtocol.evaluate(
                review, List.of("AC-01"), Set.of("step_1"), Set.of("AC-01"));

        assertFalse(result.approved());
        assertTrue(result.issues().contains("反例"), result.issues());
    }
}
