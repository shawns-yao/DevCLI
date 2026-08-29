package com.devcli.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DelegationReviewGateTest {
    @Test
    void requiresReviewForLargePatch() {
        assertTrue(DelegationReviewGate.requiresIndependentReview(
                new DelegationReviewGate.Signals(List.of("a.java", "b.java", "c.java"), false)));
    }

    @Test
    void requiresReviewAfterAnyMutationFailure() {
        assertTrue(DelegationReviewGate.requiresIndependentReview(
                new DelegationReviewGate.Signals(List.of("a.java"), true)));
    }

    @Test
    void requiresReviewForCriticalResources() {
        assertTrue(DelegationReviewGate.requiresIndependentReview(
                new DelegationReviewGate.Signals(List.of("src/main/resources/db/migration/V1.sql"), false)));
    }

    @Test
    void doesNotRequireReviewForSmallOrdinaryPatch() {
        assertFalse(DelegationReviewGate.requiresIndependentReview(
                new DelegationReviewGate.Signals(List.of("src/main/java/Foo.java"), false)));
    }
}
