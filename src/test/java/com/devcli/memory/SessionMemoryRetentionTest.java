package com.devcli.memory;

import com.devcli.tool.ToolResultArtifact;
import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionMemoryRetentionTest {
    @Test
    void constraintExtractionDoesNotDiscardTailRequirements() {
        String longConstraint = "Do not modify " + "module/".repeat(100) + "protected.txt";
        StringBuilder message = new StringBuilder(longConstraint);
        for (int i = 0; i < 70; i++) {
            message.append("\nDo not modify file-").append(i);
        }
        List<String> constraints = CompactionSemanticGuard.extractConstraints(
                List.of(LlmClient.Message.user(message.toString())));
        assertEquals(71, constraints.size());
        assertTrue(constraints.contains(longConstraint));
    }

    @Test
    void constraintsSurviveCountLengthAndRenderingPressure() {
        SessionMemory memory = new SessionMemory();
        String longConstraint = "Do not modify " + "project/module/".repeat(60) + "protected.txt";
        memory.addProtectedConstraint(longConstraint);
        for (int i = 0; i < 70; i++) {
            memory.addProtectedConstraint("Do not modify file-" + i);
        }
        memory.setTaskState("goal", "large task state ".repeat(500));

        String rendered = memory.render(SessionMemory.SessionView.FULL, 256);

        assertEquals(71, memory.getProtectedConstraints().size());
        assertTrue(rendered.contains(longConstraint));
        assertTrue(rendered.contains("Do not modify file-0"));
        assertTrue(rendered.contains("Do not modify file-69"));
    }

    @Test
    void patchEvidenceHasTheSameProtectionAsOtherWrites() {
        SessionMemory memory = new SessionMemory(1, 16, 8);
        memory.recordToolResult("apply_patch", "{\"patch\":\"update\"}", "patch applied");
        memory.recordToolResult("read_file", "{\"path\":\"unrelated.txt\"}", "unrelated");

        assertEquals("apply_patch", memory.getRecentToolResults().getFirst().toolName);
        assertEquals(SessionMemory.EvidenceKind.CRITICAL,
                memory.snapshot().evidenceJournal().getFirst().kind());
    }

    @Test
    void activeStepReadOutranksOldWriteWithoutLosingChangedFileFact() {
        SessionMemory memory = new SessionMemory(1, 16, 8);
        memory.recordToolResult("write_file", "{\"path\":\"README.md\"}", "formatting updated");
        memory.accept(new SessionMemory.StepChanged("build", TaskLedger.StepStatus.RUNNING,
                "", "worker", 0));
        memory.recordToolResult("read_file", "{\"path\":\"pom.xml\"}", "build configuration",
                List.of(), "build");

        assertEquals("read_file", memory.getRecentToolResults().getFirst().toolName);
        assertTrue(memory.snapshot().modifiedFiles().contains("README.md"));
        assertTrue(memory.render(SessionMemory.SessionView.FULL, 2000).contains("README.md"));
    }

    @Test
    void completedStepLosesRelevanceBoost() {
        SessionMemory memory = new SessionMemory(1, 16, 8);
        memory.accept(new SessionMemory.StepChanged("build", TaskLedger.StepStatus.RUNNING,
                "", "worker", 0));
        memory.recordToolResult("read_file", "{\"path\":\"pom.xml\"}", "configuration",
                List.of(), "build");
        memory.accept(new SessionMemory.StepChanged("build", TaskLedger.StepStatus.DONE,
                "", "worker", 0));
        memory.recordToolResult("write_file", "{\"path\":\"Main.java\"}", "updated");

        assertEquals("write_file", memory.getRecentToolResults().getFirst().toolName);
    }

    @Test
    void renderPrioritizesActiveReadAndKeepsOrdinaryContentWithinBudget() {
        SessionMemory memory = new SessionMemory();
        memory.addProtectedConstraint("Do not add dependencies");
        memory.recordToolResult("write_file", "{\"path\":\"README.md\"}", "old output ".repeat(100));
        memory.accept(new SessionMemory.StepChanged("build", TaskLedger.StepStatus.RUNNING,
                "", "worker", 0));
        memory.recordToolResult("read_file", "{\"path\":\"pom.xml\"}", "current configuration",
                List.of(), "build");

        String rendered = memory.render(SessionMemory.SessionView.FULL, 600);

        assertTrue(rendered.contains("Do not add dependencies"));
        assertTrue(rendered.indexOf("**read_file**") < rendered.indexOf("**write_file**"));
        assertTrue(MemoryEntry.estimateTokens(rendered) <= 620);
    }

    @Test
    void foldedCriticalEvidenceKeepsItsRecoveryReference() {
        SessionMemory memory = new SessionMemory(1, 16, 8);
        ToolResultArtifact artifact = new ToolResultArtifact(
                "PERSISTED_PREVIEW", 10000, 10000, 2000, "run/old-write", "2000", "abc");
        memory.recordToolResult("write_file", "{\"path\":\"README.md\"}",
                "output ".repeat(400), List.of(artifact));
        memory.recordToolResult("write_file", "{\"path\":\"Main.java\"}", "updated");

        String rendered = memory.render(SessionMemory.SessionView.FULL, 2000);
        assertTrue(rendered.contains("result_ref=run/old-write"));
        assertTrue(memory.snapshot().modifiedFiles().contains("README.md"));
    }

    @Test
    void structuredRecoveryReferenceSurvivesSummaryAndRestore() {
        SessionMemory memory = new SessionMemory();
        ToolResultArtifact artifact = new ToolResultArtifact(
                "PERSISTED_PREVIEW", 10000, 10000, 2000, "run/result-123", "2000", "abc");
        memory.recordToolResult("execute_command", "{\"command\":\"check\"}",
                "output ".repeat(400), List.of(artifact));

        assertEquals("run/result-123", memory.snapshot().evidenceJournal().getFirst().reference());
        assertTrue(memory.render(SessionMemory.SessionView.FULL, 2000)
                .contains("result_ref=run/result-123"));
        assertTrue(memory.renderForPostCompactRestore().contains("result_ref=run/result-123"));
        assertFalse(memory.render(SessionMemory.SessionView.FULL, 2000)
                .contains("next_cursor=2000"));
        assertTrue(memory.renderForPostCompactRestore().contains("offset=0"));
    }
}
