package com.devcli.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepRedoTrackerTest {

    @Test
    void canRedoUntilLimitReached() {
        StepRedoTracker tracker = new StepRedoTracker(1);
        assertTrue(tracker.canRedo("step-1"));
        assertFalse(tracker.isRedo("step-1"));

        int attempt = tracker.recordRedo("step-1", "编译失败");
        assertEquals(1, attempt);
        assertTrue(tracker.isRedo("step-1"));
        assertFalse(tracker.canRedo("step-1"), "上限 1，重做一次后应不再允许");
    }

    @Test
    void recordRedoStoresFailureReasonForFeedback() {
        StepRedoTracker tracker = new StepRedoTracker(2);
        tracker.recordRedo("step-1", "签名不匹配");
        assertEquals("签名不匹配", tracker.lastFailureReason("step-1"));
        assertEquals("", tracker.lastFailureReason("step-unknown"));
    }

    @Test
    void recordRedoIncrementsAttemptAndKeepsLatestReason() {
        StepRedoTracker tracker = new StepRedoTracker(3);
        assertEquals(1, tracker.recordRedo("s", "a"));
        assertEquals(2, tracker.recordRedo("s", "b"));
        assertEquals("b", tracker.lastFailureReason("s"), "最新失败原因应覆盖旧的");
    }

    @Test
    void resetClearsState() {
        StepRedoTracker tracker = new StepRedoTracker(1);
        tracker.recordRedo("s", "x");
        tracker.reset();
        assertTrue(tracker.canRedo("s"));
        assertFalse(tracker.isRedo("s"));
        assertEquals("", tracker.lastFailureReason("s"));
    }

    @Test
    void nullFailureReasonNormalizedToEmpty() {
        StepRedoTracker tracker = new StepRedoTracker(1);
        tracker.recordRedo("s", null);
        assertEquals("", tracker.lastFailureReason("s"));
    }

    @Test
    void zeroMaxRedoNeverAllowsRedo() {
        StepRedoTracker tracker = new StepRedoTracker(0);
        assertFalse(tracker.canRedo("s"));
    }
}
