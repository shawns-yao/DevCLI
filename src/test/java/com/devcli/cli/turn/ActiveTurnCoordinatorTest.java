package com.devcli.cli.turn;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveTurnCoordinatorTest {

    @Test
    void queuesOrdinaryInputWithoutCancellingCurrentTurn() {
        ActiveTurnCoordinator coordinator = new ActiveTurnCoordinator(2);
        AtomicInteger cancellations = new AtomicInteger();

        ActiveTurnCoordinator.Submission submission = coordinator.submit("next", cancellations::incrementAndGet);

        assertTrue(submission.accepted());
        assertFalse(submission.cancelledCurrent());
        assertEquals(0, cancellations.get());
        assertEquals("next", coordinator.poll().orElseThrow().text());
    }

    @Test
    void immediateInputCancelsOnlyAfterItHasBeenQueued() {
        ActiveTurnCoordinator coordinator = new ActiveTurnCoordinator(1);
        AtomicInteger cancellations = new AtomicInteger();
        coordinator.submit("existing", cancellations::incrementAndGet);

        ActiveTurnCoordinator.Submission rejected = coordinator.submit("/now urgent", cancellations::incrementAndGet);

        assertFalse(rejected.accepted());
        assertEquals(0, cancellations.get());
        assertEquals("existing", coordinator.poll().orElseThrow().text());
    }

    @Test
    void immediateInputPreemptsQueuedWorkAndCancelsCurrentTurn() {
        ActiveTurnCoordinator coordinator = new ActiveTurnCoordinator(2);
        AtomicInteger cancellations = new AtomicInteger();
        coordinator.submit("later", cancellations::incrementAndGet);

        ActiveTurnCoordinator.Submission submission = coordinator.submit("/now urgent", cancellations::incrementAndGet);

        assertTrue(submission.cancelledCurrent());
        assertEquals(1, cancellations.get());
        assertEquals("urgent", coordinator.poll().orElseThrow().text());
        assertEquals("later", coordinator.poll().orElseThrow().text());
    }
}
