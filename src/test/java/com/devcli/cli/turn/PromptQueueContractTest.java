package com.devcli.cli.turn;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptQueueContractTest {

    @Test
    void keepsAcceptedPromptsBoundedAndFifo() {
        PromptQueue queue = new PromptQueue(2);

        PromptQueue.EnqueueResult first = queue.enqueue(" first ");
        PromptQueue.EnqueueResult second = queue.enqueue("second");
        PromptQueue.EnqueueResult overflow = queue.enqueue("third");

        assertTrue(first.accepted());
        assertTrue(second.accepted());
        assertFalse(overflow.accepted());
        assertEquals(2, queue.size());
        assertEquals("first", queue.poll().orElseThrow().text());
        assertEquals("second", queue.poll().orElseThrow().text());
        assertEquals(Optional.empty(), queue.poll());
    }

    @Test
    void rejectsBlankPromptsAndKeepsImmutableSnapshot() {
        PromptQueue queue = new PromptQueue(2);

        assertFalse(queue.enqueue("  ").accepted());
        queue.enqueue("one");
        List<PromptQueue.Entry> snapshot = queue.snapshot();
        queue.enqueue("two");

        assertEquals(List.of("one"), snapshot.stream().map(PromptQueue.Entry::text).toList());
        assertEquals(List.of(1L, 2L), queue.snapshot().stream().map(PromptQueue.Entry::sequence).toList());
    }

    @Test
    void placesImmediatePromptBeforeExistingPrompts() {
        PromptQueue queue = new PromptQueue(3);
        queue.enqueue("later");
        queue.enqueueFirst("now");

        assertEquals("now", queue.poll().orElseThrow().text());
        assertEquals("later", queue.poll().orElseThrow().text());
    }
}
