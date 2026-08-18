package com.devcli.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamReviewerProtocolTest {

    @Test
    void rejectsMalformedOrIncompleteOutput() {
        assertFalse(TeamReviewerProtocol.evaluate("not-json", List.of()).approved());
        assertFalse(TeamReviewerProtocol.evaluate("{\"approved\":true}", List.of()).approved());
    }

    @Test
    void requiresCompleteAcceptanceCriteriaCoverage() {
        TeamReviewerProtocol.Evaluation evaluation = TeamReviewerProtocol.evaluate("""
                {
                  "approved": true,
                  "scores": {
                    "functional_correctness": 1.0,
                    "integration_completeness": 1.0,
                    "code_quality": 1.0
                  },
                  "criteria_results": []
                }
                """, List.of(new TeamReviewerProtocol.Criterion("AC-01", "high")));

        assertFalse(evaluation.approved());
    }

    @Test
    void plannerSeverityCannotBeDowngradedByReviewer() {
        TeamReviewerProtocol.Evaluation evaluation = TeamReviewerProtocol.evaluate("""
                {
                  "approved": true,
                  "scores": {
                    "functional_correctness": 1.0,
                    "integration_completeness": 1.0,
                    "code_quality": 1.0
                  },
                  "criteria_results": [
                    {"id":"AC-01","passed":false,"severity":"low","evidence":"未满足"}
                  ]
                }
                """, List.of(new TeamReviewerProtocol.Criterion("AC-01", "critical")));

        assertFalse(evaluation.approved());
        assertTrue(evaluation.issues().contains("AC-01"));
    }

    @Test
    void acceptsOnlyFullyScoredAndCoveredOutput() {
        TeamReviewerProtocol.Evaluation evaluation = TeamReviewerProtocol.evaluate("""
                {
                  "approved": true,
                  "scores": {
                    "functional_correctness": 1.0,
                    "integration_completeness": 0.8,
                    "code_quality": 0.9
                  },
                  "criteria_results": [
                    {"id":"AC-01","passed":true,"severity":"high","evidence":"测试通过"}
                  ],
                  "issues": []
                }
                """, List.of(new TeamReviewerProtocol.Criterion("AC-01", "high")));

        assertTrue(evaluation.approved());
    }

    @Test
    void rejectsReviewerThatClaimsToolPassForHumanCriterion() {
        TeamReviewerProtocol.Evaluation evaluation = TeamReviewerProtocol.evaluate("""
                {
                  "approved": true,
                  "scores": {
                    "functional_correctness": 1.0,
                    "integration_completeness": 1.0,
                    "code_quality": 1.0
                  },
                  "criteria_results": [
                    {
                      "id":"AC-01",
                      "passed":true,
                      "verification_method":"TOOL",
                      "evidence":"模型自行判断视觉效果正常"
                    }
                  ]
                }
                """, List.of(new TeamReviewerProtocol.Criterion("AC-01", "high", "HUMAN")));

        assertFalse(evaluation.approved());
    }

    @Test
    void allowsAutomatedReviewToLeaveHumanCriterionPending() {
        TeamReviewerProtocol.Evaluation evaluation = TeamReviewerProtocol.evaluate("""
                {
                  "approved": true,
                  "scores": {
                    "functional_correctness": 1.0,
                    "integration_completeness": 1.0,
                    "code_quality": 1.0
                  },
                  "criteria_results": [
                    {
                      "id":"AC-01",
                      "passed":false,
                      "status":"pending_human",
                      "verification_method":"HUMAN",
                      "evidence":"等待用户检查最终终端展示"
                    }
                  ]
                }
                """, List.of(new TeamReviewerProtocol.Criterion("AC-01", "high", "HUMAN")));

        assertTrue(evaluation.approved(), evaluation.issues());
    }

    @Test
    void rejectsToolCriterionWithoutConcreteEvidence() {
        TeamReviewerProtocol.Evaluation evaluation = TeamReviewerProtocol.evaluate("""
                {
                  "approved": true,
                  "scores": {
                    "functional_correctness": 1.0,
                    "integration_completeness": 1.0,
                    "code_quality": 1.0
                  },
                  "criteria_results": [
                    {
                      "id":"AC-01",
                      "passed":true,
                      "verification_method":"TOOL",
                      "evidence":""
                    }
                  ]
                }
                """, List.of(new TeamReviewerProtocol.Criterion("AC-01", "high", "TOOL")));

        assertFalse(evaluation.approved());
    }

    @Test
    void preservesStructuredFailureLocationAndSuggestedFix() {
        TeamReviewerProtocol.Evaluation evaluation = TeamReviewerProtocol.evaluate("""
                {
                  "approved": false,
                  "issues": [
                    {
                      "criterion_id":"AC-01",
                      "type":"integration",
                      "severity":"high",
                      "file":"src/main/java/example/Cli.java",
                      "description":"默认参数未生效",
                      "expected":"省略参数时使用默认值",
                      "actual":"抛出空指针异常",
                      "suggested_fix":"在入口补齐默认参数"
                    }
                  ]
                }
                """, List.of());

        assertTrue(evaluation.issues().contains("AC-01"), evaluation.issues());
        assertTrue(evaluation.issues().contains("Cli.java"), evaluation.issues());
        assertTrue(evaluation.issues().contains("省略参数时使用默认值"), evaluation.issues());
        assertTrue(evaluation.issues().contains("入口补齐默认参数"), evaluation.issues());
    }

    @Test
    void rejectsToolEvidenceWhenDeclaredVerifierWasNotActuallyCalled() {
        String approved = """
                {
                  "approved": true,
                  "scores": {
                    "functional_correctness": 1.0,
                    "integration_completeness": 1.0,
                    "code_quality": 1.0
                  },
                  "criteria_results": [
                    {
                      "id":"AC-01",
                      "passed":true,
                      "verification_method":"TOOL",
                      "evidence":"模型声称编译通过"
                    }
                  ]
                }
                """;
        List<TeamReviewerProtocol.Criterion> criteria = List.of(
                new TeamReviewerProtocol.Criterion(
                        "AC-01", "high", "TOOL", "execute_command"));

        assertFalse(TeamReviewerProtocol.evaluate(approved, criteria, List.of("read_file")).approved());
        assertTrue(TeamReviewerProtocol.evaluate(
                approved, criteria, List.of("execute_command")).approved());
    }
}
