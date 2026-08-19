package com.devcli.agent;

import com.devcli.runtime.CancellationContext;
import com.devcli.runtime.RunContext;
import com.devcli.runtime.store.RecoveryEvidenceRef;
import com.devcli.runtime.store.RecoveryEvidenceSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCheckpointRecoveryEvidenceTest {

    @Test
    void checkpointSaveAndDeleteRegisterMetadataReferences(@TempDir Path tempDir) throws Exception {
        String previous = System.getProperty("devcli.checkpoint.dir");
        Path checkpointDir = tempDir.resolve("checkpoints");
        System.setProperty("devcli.checkpoint.dir", checkpointDir.toString());
        List<RecoveryEvidenceRef> captured = new ArrayList<>();
        try (RunContext ignored = CancellationContext.startRunContext(
                tempDir, "run-checkpoint", "thread-1", "main", captured::add)) {
            AgentCheckpoint checkpoint = new AgentCheckpoint("orch-1", "goal");
            checkpoint.saveStrict();
            checkpoint.delete();
        } finally {
            restore("devcli.checkpoint.dir", previous);
        }

        assertEquals(List.of(RecoveryEvidenceRef.State.PRESENT,
                        RecoveryEvidenceRef.State.DELETED),
                captured.stream().map(RecoveryEvidenceRef::state).toList());
        assertEquals("orch-1", captured.get(0).logicalKey());
        assertTrue(captured.get(0).normalizedReference().endsWith("orch-1.json"));
        assertEquals("run-checkpoint", captured.get(0).runId());
    }

    @Test
    void evidenceFailureDoesNotBreakCheckpointArtifact(@TempDir Path tempDir) throws Exception {
        String previous = System.getProperty("devcli.checkpoint.dir");
        Path checkpointDir = tempDir.resolve("checkpoints");
        System.setProperty("devcli.checkpoint.dir", checkpointDir.toString());
        Path checkpointFile = checkpointDir.resolve("orch-failure.json");
        try (RunContext ignored = CancellationContext.startRunContext(
                tempDir, "run-failure", "thread-1", "main", ref -> {
                    throw new IllegalStateException("store unavailable");
                })) {
            AgentCheckpoint checkpoint = new AgentCheckpoint("orch-failure", "goal");
            checkpoint.saveStrict();
            assertTrue(Files.exists(checkpointFile));
            checkpoint.delete();
            assertFalse(Files.exists(checkpointFile));
        } finally {
            restore("devcli.checkpoint.dir", previous);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
