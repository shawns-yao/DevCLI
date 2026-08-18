package com.devcli.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrchestrationProfileTest {

    @Test
    void standardAndTeamProfilesKeepExplicitCapabilityBoundaries() {
        assertEquals("plan", OrchestrationProfile.STANDARD.snapshotMode());
        assertFalse(OrchestrationProfile.STANDARD.usesWorkerPool());
        assertFalse(OrchestrationProfile.STANDARD.requiresReviewerGate());
        assertFalse(OrchestrationProfile.STANDARD.supportsCheckpointResume());

        assertEquals("plan", OrchestrationProfile.TEAM.snapshotMode());
        assertEquals("Plan", OrchestrationProfile.TEAM.displayName());
        assertTrue(OrchestrationProfile.TEAM.usesWorkerPool());
        assertTrue(OrchestrationProfile.TEAM.requiresReviewerGate());
        assertTrue(OrchestrationProfile.TEAM.supportsCheckpointResume());
    }

    @Test
    void parallelismIsBoundedByProfileAndWorkSize() {
        assertEquals(2, OrchestrationProfile.STANDARD.parallelismFor(2, 20));
        assertEquals(4, OrchestrationProfile.STANDARD.parallelismFor(20, 20));
        assertEquals(3, OrchestrationProfile.TEAM.parallelismFor(20, 3));
        assertEquals(8, OrchestrationProfile.TEAM.parallelismFor(20, 20));
    }
}
