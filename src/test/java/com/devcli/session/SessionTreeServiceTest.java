package com.devcli.session;

import com.devcli.agent.Agent;
import com.devcli.llm.GLMClient;
import com.devcli.llm.LlmClient;
import com.devcli.runtime.api.RuntimeThreadStore;
import com.devcli.runtime.store.RunStore;
import com.devcli.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionTreeServiceTest {

    @AfterEach
    void clearConfiguredSession() {
        System.clearProperty("devcli.session.id");
    }

    @Test
    void persistsBranchesAcrossProcessesAndOnlySwitchesConversation(@TempDir Path tempDir)
            throws Exception {
        Path db = tempDir.resolve("runtime.db");
        Path workspaceMarker = tempDir.resolve("workspace-marker.txt");
        Files.writeString(workspaceMarker, "unchanged");
        String threadId;

        try (RuntimeThreadStore store = new RuntimeThreadStore(db);
             ToolRegistry registry = new ToolRegistry()) {
            Agent agent = new Agent(new GLMClient("test-key"), registry);
            SessionTreeService sessions = SessionTreeService.open(agent, store);
            threadId = sessions.threadId();

            sessions.runTurn("plan", "first", "first", () -> "answer-one", value -> value);
            assertEquals(1, store.list(RunStore.Source.INTERACTIVE, 10).size());

            sessions.fork("feature");
            assertTrue(contents(agent).contains("answer-one"));
            sessions.runTurn("plan", "second", "second", () -> "answer-two", value -> value);
            assertTrue(contents(agent).contains("answer-two"));

            sessions.use("main");
            assertTrue(contents(agent).contains("answer-one"));
            assertFalse(contents(agent).contains("answer-two"));
            assertEquals(2, sessions.tree().branches().size());
            assertEquals("unchanged", Files.readString(workspaceMarker));
        }

        try (RuntimeThreadStore reopenedStore = new RuntimeThreadStore(db);
             ToolRegistry registry = new ToolRegistry()) {
            Agent reopenedAgent = new Agent(new GLMClient("test-key"), registry);
            SessionTreeService reopened = SessionTreeService.open(reopenedAgent, reopenedStore);

            assertEquals(threadId, reopened.threadId());
            assertTrue(contents(reopenedAgent).contains("answer-one"));
            assertFalse(contents(reopenedAgent).contains("answer-two"));

            reopened.use("feature");
            assertTrue(contents(reopenedAgent).contains("answer-two"));
            assertEquals("unchanged", Files.readString(workspaceMarker));
        }
    }

    @Test
    void clearCreatesNewRootWithoutDeletingOldHistory(@TempDir Path tempDir) throws Exception {
        try (RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("runtime.db"));
             ToolRegistry registry = new ToolRegistry()) {
            Agent agent = new Agent(new GLMClient("test-key"), registry);
            SessionTreeService sessions = SessionTreeService.open(agent, store);
            sessions.runTurn("plan", "first", "first", () -> "answer-one", value -> value);

            sessions.clearCurrent();

            assertFalse(contents(agent).contains("answer-one"));
            assertEquals(2, sessions.tree().branches().size());
            sessions.use("main");
            assertTrue(contents(agent).contains("answer-one"));
        }
    }

    private static List<String> contents(Agent agent) {
        return agent.getConversationHistory().stream()
                .map(LlmClient.Message::content)
                .toList();
    }
}
