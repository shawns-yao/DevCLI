package com.devcli.cli;

import com.devcli.agent.ExecutionReviewPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredRunCommandTest {

    @Test
    void parsesPlanTask() {
        StructuredRunCommand command = StructuredRunCommand.parse("--review=plan 重构入口");

        assertEquals(ExecutionReviewPolicy.PLAN_REVIEW, command.policy());
        assertEquals("重构入口", command.task());
        assertFalse(command.resume());
    }

    @Test
    void parsesTeamResume() {
        StructuredRunCommand command = StructuredRunCommand.parse("--review team resume orch-1234");

        assertEquals(ExecutionReviewPolicy.TEAM_REVIEW, command.policy());
        assertTrue(command.resume());
        assertEquals("orch-1234", command.checkpointId());
    }

    @Test
    void rejectsPlanResumeAndMissingPolicy() {
        assertThrows(IllegalArgumentException.class,
                () -> StructuredRunCommand.parse("--review=plan resume"));
        assertThrows(IllegalArgumentException.class,
                () -> StructuredRunCommand.parse("重构入口"));
    }
}
