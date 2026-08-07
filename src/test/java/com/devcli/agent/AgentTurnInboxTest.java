package com.devcli.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTurnInboxTest {

    @Test
    void prioritizesSteeringAndDrainsOneMessageByDefault() {
        AgentTurnInbox inbox = new AgentTurnInbox(4);

        inbox.enqueueFollowUp("follow-up");
        inbox.enqueueSteering("steering");

        assertEquals("steering", inbox.drainSteering().get(0).text());
        assertEquals("follow-up", inbox.drainFollowUp().get(0).text());
        assertFalse(inbox.hasMessages());
    }

    @Test
    void enforcesSharedCapacityAndSupportsBatchDrain() {
        AgentTurnInbox inbox = new AgentTurnInbox(2);

        assertTrue(inbox.enqueueFollowUp("one").accepted());
        assertTrue(inbox.enqueueSteering("two").accepted());
        AgentTurnInbox.EnqueueResult rejected = inbox.enqueueFollowUp("three");

        assertFalse(rejected.accepted());
        assertEquals(2, rejected.snapshot().size());

        inbox.setFollowUpMode(AgentTurnInbox.QueueMode.ALL);
        List<AgentTurnInbox.Item> drained = inbox.drainFollowUp();
        assertEquals(List.of("one"), drained.stream().map(AgentTurnInbox.Item::text).toList());
        assertEquals(1, inbox.snapshot().size());
    }

    @Test
    void normalizesInputAndRejectsBlankMessages() {
        AgentTurnInbox inbox = new AgentTurnInbox();

        assertEquals("hello", inbox.enqueueFollowUp("  hello  ").item().text());
        assertFalse(inbox.enqueueSteering(" \t ").accepted());
    }
}
