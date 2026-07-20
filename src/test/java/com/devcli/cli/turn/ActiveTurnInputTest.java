package com.devcli.cli.turn;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActiveTurnInputTest {

    @Test
    void parsesQueueInterruptAndCancelActions() {
        assertEquals(new ActiveTurnInput.Parsed(ActiveTurnInput.Action.QUEUE, "next task"),
                ActiveTurnInput.parse(" next task "));
        assertEquals(new ActiveTurnInput.Parsed(ActiveTurnInput.Action.INTERRUPT, "urgent task"),
                ActiveTurnInput.parse("/now urgent task"));
        assertEquals(new ActiveTurnInput.Parsed(ActiveTurnInput.Action.CANCEL, ""),
                ActiveTurnInput.parse("/cancel"));
        assertEquals(new ActiveTurnInput.Parsed(ActiveTurnInput.Action.IGNORE, ""),
                ActiveTurnInput.parse("/now"));
    }
}
