package com.devcli.runtime;

import com.devcli.agent.Agent;
import com.devcli.agent.AgentCheckpoint;
import com.devcli.llm.GLMClient;
import com.devcli.runtime.api.RuntimeThreadStore;
import com.devcli.runtime.store.RecoveryEvidenceRef;
import com.devcli.runtime.store.RecoveryEvidenceSink;
import com.devcli.runtime.store.RunStore;
import com.devcli.session.SessionTreeService;
import com.devcli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunScopedRecoveryEvidenceTest {

    @Test
    void storeSinkPersistsCheckpointWithSameRunThreadAndBranchAsSessionTurn(
            @TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("runtime.db");
        String checkpointDirProperty = "devcli.checkpoint.dir";
        String previousCheckpointDir = System.getProperty(checkpointDirProperty);
        System.setProperty(checkpointDirProperty, tempDir.resolve("checkpoints").toString());
        String runId = "run-store-evidence";
        try (RuntimeThreadStore store = new RuntimeThreadStore(db);
             ToolRegistry registry = new ToolRegistry()) {
            Agent agent = new Agent(new GLMClient("test-key"), registry);
            SessionTreeService sessions = SessionTreeService.open(agent, store);
            String threadId = sessions.threadId();
            String branchId = sessions.activeBranchId();
            RecoveryEvidenceSink sink = sessions.recoveryEvidenceSink(runId);

            try (RunContext ignored = CancellationContext.startRunContext(
                    tempDir, runId, threadId, branchId, sink)) {
                AgentCheckpoint checkpoint = new AgentCheckpoint("orch-store-evidence", "goal");
                checkpoint.saveStrict();
                assertTrue(sessions.recordTurn(
                        runId, "react", "hello", "hello", "answer", List.of()).isEmpty());
            }

            RunStore.RunRecord run = store.find(runId).orElseThrow();
            List<RecoveryEvidenceRef> evidence = store.listRecoveryEvidence(runId, 10);
            assertEquals(runId, run.id());
            assertTrue(!evidence.isEmpty());
            assertTrue(evidence.stream().allMatch(ref ->
                    ref.runId().equals(run.id())
                            && ref.threadId().equals(threadId)
                            && ref.branchId().equals(branchId)));
            assertTrue(evidence.stream().anyMatch(ref ->
                    ref.kind() == RecoveryEvidenceRef.Kind.CHECKPOINT
                            && ref.state() == RecoveryEvidenceRef.State.PRESENT));
        } finally {
            if (previousCheckpointDir == null) {
                System.clearProperty(checkpointDirProperty);
            } else {
                System.setProperty(checkpointDirProperty, previousCheckpointDir);
            }
        }
    }

    @Test
    void stableRunIdentityIsAvailableBeforeExecutionAndReusedBySessionPersistence(
            @TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("runtime.db");
        String runId = "run-stable-1";
        String threadId;
        List<RecoveryEvidenceRef> captured = new ArrayList<>();

        try (RuntimeThreadStore store = new RuntimeThreadStore(db);
             ToolRegistry registry = new ToolRegistry()) {
            Agent agent = new Agent(new GLMClient("test-key"), registry);
            SessionTreeService sessions = SessionTreeService.open(agent, store);
            threadId = sessions.threadId();
            String branchId = sessions.activeBranchId();
            RecoveryEvidenceSink sink = captured::add;

            try (RunContext context = CancellationContext.startRunContext(
                    tempDir, runId, threadId, branchId, sink)) {
                assertEquals(runId, context.runId());
                assertEquals(threadId, context.threadId());
                assertEquals(branchId, context.branchId());
                assertTrue(sessions.recordTurn(
                        "react", "hello", "hello", "answer", List.of()).isEmpty());
            }

            assertEquals(runId, store.list(com.devcli.runtime.store.RunStore.Source.INTERACTIVE, 10)
                    .get(0).id());
            assertTrue(captured.isEmpty());
        }
    }
}
