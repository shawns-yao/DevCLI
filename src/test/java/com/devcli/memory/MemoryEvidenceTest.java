package com.devcli.memory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryEvidenceTest {

    @Test
    void explicitPolicyMemoryIsReviewedAndKeepsSourceQuote() {
        MemoryEvidence evidence = MemoryEvidence.fromPolicy(
                Map.of(
                        "source", "explicit",
                        "confidence", "HIGH",
                        "reason_code", "EXPLICIT_STABLE_MEMORY"),
                "记住：我默认使用 Java 17");

        assertEquals(MemoryEvidence.Confidence.HIGH, evidence.confidence());
        assertEquals(MemoryEvidence.ReviewState.REVIEWED, evidence.reviewState());
        assertEquals("记住：我默认使用 Java 17", evidence.sourceQuote());
        assertEquals("EXPLICIT_STABLE_MEMORY", evidence.reasoning());
        assertTrue(evidence.isRecallable());
    }

    @Test
    void heuristicMemoryStartsUnreviewed() {
        MemoryEvidence evidence = MemoryEvidence.fromPolicy(
                Map.of("source", "heuristic", "confidence", "MEDIUM"),
                "用户偏好简体中文");

        assertEquals(MemoryEvidence.ReviewState.UNREVIEWED, evidence.reviewState());
        assertTrue(evidence.retrievalWeight() < 1.0);
    }

    @Test
    void confidenceIsDowngradedWhenSourceEvidenceIsMissing() {
        MemoryEvidence evidence = new MemoryEvidence(
                MemoryEvidence.Confidence.HIGH,
                "",
                "policy",
                MemoryEvidence.ReviewState.UNREVIEWED,
                List.of());

        assertEquals(MemoryEvidence.Confidence.LOW, evidence.confidence());
    }

    @Test
    void curatedMemoryIsRecallableWithoutPretendingToBeReviewed() {
        MemoryEvidence evidence = new MemoryEvidence(
                MemoryEvidence.Confidence.HIGH,
                "结果",
                "task_curator",
                MemoryEvidence.ReviewState.CURATED,
                List.of());

        assertEquals(MemoryEvidence.Confidence.HIGH, evidence.confidence());
        assertTrue(evidence.isRecallable());
        assertTrue(evidence.retrievalWeight() < 1.0);
    }

    @Test
    void rejectedMemoryIsNotRecallableAndConflictsAreDeduplicated() {
        MemoryEvidence evidence = new MemoryEvidence(
                MemoryEvidence.Confidence.LOW,
                "source",
                "reason",
                MemoryEvidence.ReviewState.REJECTED,
                List.of("old-1", "old-1", "old-2"));

        assertFalse(evidence.isRecallable());
        assertEquals(List.of("old-1", "old-2"), evidence.conflictsWith());
    }
}
