package com.devcli.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SamplingRequestCoordinatorTest {

    @Test
    void replacingSameRequestCancelsOldGenerationWithoutRemovingNewOne() {
        SamplingRequestCoordinator coordinator = new SamplingRequestCoordinator();
        SamplingRequestCoordinator.RequestScope oldScope = coordinator.begin("request-1");
        SamplingRequestCoordinator.RequestScope newScope = coordinator.begin("request-1");

        assertTrue(oldScope.isCancelled());
        assertFalse(newScope.isCancelled());
        assertEquals(1, coordinator.activeCount());

        oldScope.close();
        assertEquals(1, coordinator.activeCount());
        newScope.close();
        assertEquals(0, coordinator.activeCount());
        assertFalse(SamplingRequestCoordinator.isCurrentCancelled());
    }

    @Test
    void explicitCancellationTargetsOnlyRequestedSamplingCall() {
        SamplingRequestCoordinator coordinator = new SamplingRequestCoordinator();
        try (SamplingRequestCoordinator.RequestScope first = coordinator.begin("first")) {
            try (SamplingRequestCoordinator.RequestScope second = coordinator.begin("second")) {
                assertTrue(coordinator.cancel("first"));
                assertTrue(first.isCancelled());
                assertFalse(second.isCancelled());
                assertEquals(2, coordinator.activeCount());
            }
            assertEquals(1, coordinator.activeCount());
        }
        assertEquals(0, coordinator.activeCount());
    }

    @Test
    void blankIdReceivesStableGeneratedIdentityForItsScope() {
        SamplingRequestCoordinator coordinator = new SamplingRequestCoordinator();
        try (SamplingRequestCoordinator.RequestScope scope = coordinator.begin("  ")) {
            assertTrue(scope.requestId().startsWith("sample_"));
            assertEquals(scope.requestId(), coordinator.find(scope.requestId()).orElseThrow().requestId());
        }
    }
}
