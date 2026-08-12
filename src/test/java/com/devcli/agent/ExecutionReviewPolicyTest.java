package com.devcli.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionReviewPolicyTest {

    @Test
    void preservesPlanAndTeamReviewContracts() {
        assertFalse(ExecutionReviewPolicy.PLAN_REVIEW.usesWorkerPool());
        assertFalse(ExecutionReviewPolicy.PLAN_REVIEW.usesPreReview());
        assertFalse(ExecutionReviewPolicy.PLAN_REVIEW.usesReviewer());

        assertTrue(ExecutionReviewPolicy.TEAM_REVIEW.usesWorkerPool());
        assertTrue(ExecutionReviewPolicy.TEAM_REVIEW.usesPreReview());
        assertTrue(ExecutionReviewPolicy.TEAM_REVIEW.usesReviewer());
        assertEquals(ExecutionReviewPolicy.PLAN_REVIEW, ExecutionReviewPolicy.parse("plan-review"));
        assertEquals(ExecutionReviewPolicy.TEAM_REVIEW, ExecutionReviewPolicy.parse("TEAM"));
        assertNull(ExecutionReviewPolicy.parse("unknown"));
    }
}
