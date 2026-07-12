package com.devcli.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionArtifactTest {

    @Test
    void preservesStructuredStateAcrossExecutionAndRetry() {
        ExecutionArtifact pending = ExecutionArtifact.pending("step-1");
        ExecutionArtifact running = pending.start(100L);
        ExecutionArtifact failed = running.fail(
                "compile failed", "编译失败", List.of("src/A.java"), 200L);

        assertEquals(ExecutionGraph.NodeState.FAILED, failed.state());
        assertEquals("compile failed", failed.error());
        assertEquals(List.of("src/A.java"), failed.modifiedResources());
        assertTrue(failed.terminal());
        assertFalse(failed.successful());

        ExecutionArtifact retry = failed.resetForRetry();
        ExecutionArtifact completed = retry.start(300L).complete(
                "done", "实现完成", List.of("src/A.java", "src/B.java"), 400L);

        assertEquals(2, completed.attempt());
        assertEquals(ExecutionGraph.NodeState.COMPLETED, completed.state());
        assertEquals("done", completed.output());
        assertEquals("", completed.error());
        assertTrue(completed.successful());
    }
}
