package com.devcli.cli;

import com.devcli.agent.Agent;
import com.devcli.llm.GLMClient;
import com.devcli.runtime.api.RuntimeThreadStore;
import com.devcli.runtime.store.RecoveryEvidenceSink;
import com.devcli.runtime.store.RunStore;
import com.devcli.session.SessionTreeService;
import com.devcli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainRunIdPersistenceTest {

    @Test
    void executionRunIdIsReturnedAndReusedForSessionPersistence(@TempDir Path tempDir)
            throws Exception {
        Path db = tempDir.resolve("runtime.db");
        String runId = "run-main-stable";
        try (RuntimeThreadStore store = new RuntimeThreadStore(db);
             ToolRegistry registry = new ToolRegistry()) {
            Agent agent = new Agent(new GLMClient("test-key"), registry);
            SessionTreeService sessions = SessionTreeService.open(agent, store);
            RecoveryEvidenceSink sink = sessions.recoveryEvidenceSink(runId);

            Main.TurnRunResult result = Main.runWithCancelSupport(
                    null, null, null,
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                    tempDir, null, false, runId, sessions.threadId(),
                    sessions.activeBranchId(), sink, () -> "answer");

            assertEquals(runId, result.runId());
            assertTrue(sessions.recordTurn(result.runId(),
                    "react", "prompt", "prompt", "answer", List.of()).isEmpty());
            assertEquals(runId, store.list(RunStore.Source.INTERACTIVE, 10).get(0).id());
        }
    }
}
