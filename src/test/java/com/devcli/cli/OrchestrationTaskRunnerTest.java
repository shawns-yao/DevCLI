package com.devcli.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OrchestrationTaskRunnerTest {

    @Test
    void parsesResumeCommandsWithoutTreatingNormalTasksAsResume() {
        assertEquals("", OrchestrationTaskRunner.parseResumeId("resume"));
        assertEquals("orch-123", OrchestrationTaskRunner.parseResumeId("resume orch-123"));
        assertNull(OrchestrationTaskRunner.parseResumeId("resumeX normal task"));
        assertNull(OrchestrationTaskRunner.parseResumeId("implement feature"));
    }
}
