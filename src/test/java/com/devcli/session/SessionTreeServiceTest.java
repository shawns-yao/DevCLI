package com.devcli.session;

import com.devcli.agent.Agent;
import com.devcli.llm.LlmClient;
import com.devcli.runtime.api.RuntimeThreadStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionTreeServiceTest {

    @Test
    void persistsBranchesAcrossProcessesAndRestoresSelectedContext(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("runtime.db");
        String sessionId = "cli_test";
        try (Agent agent = new Agent(new StubLlmClient());
             RuntimeThreadStore store = new RuntimeThreadStore(db);
             SessionTreeService tree = new SessionTreeService(store, agent, sessionId)) {
            tree.recordCompletedTurn("shared", "shared answer", agent.getConversationHistory());
            tree.fork("alternative");
            tree.recordCompletedTurn("branch", "branch answer", List.of());
        }

        try (Agent agent = new Agent(new StubLlmClient());
             RuntimeThreadStore store = new RuntimeThreadStore(db);
             SessionTreeService tree = new SessionTreeService(store, agent, sessionId)) {
            assertTrue(tree.formatTree().contains("alternative"));
            assertTrue(agent.getConversationHistory().stream()
                    .anyMatch(message -> "branch".equals(message.content())));
            tree.use("main");
            assertTrue(agent.getConversationHistory().stream()
                    .anyMatch(message -> "shared".equals(message.content())));
            assertTrue(agent.getConversationHistory().stream()
                    .noneMatch(message -> "branch".equals(message.content())));
        }
    }

    @Test
    void switchingConversationBranchesDoesNotChangeWorkspace(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("runtime.db");
        Path projectFile = tempDir.resolve("project.txt");
        Files.writeString(projectFile, "unchanged");
        try (Agent agent = new Agent(new StubLlmClient());
             RuntimeThreadStore store = new RuntimeThreadStore(db);
             SessionTreeService tree = new SessionTreeService(store, agent, "cli_workspace")) {
            tree.fork("alternative");
            tree.use("main");
        }
        assertEquals("unchanged", Files.readString(projectFile));
    }

    @Test
    void ordinaryTurnsStayEventBackedAndCanForkFromMessageNode(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("runtime.db");
        try (Agent agent = new Agent(new StubLlmClient());
             RuntimeThreadStore store = new RuntimeThreadStore(db);
             SessionTreeService tree = new SessionTreeService(store, agent, "cli_message_tree")) {
            tree.recordCompletedTurn("first", "first answer", List.of());
            tree.recordCompletedTurn("second", "second answer", List.of());

            SessionTree snapshot = tree.snapshot();
            assertEquals(4, snapshot.messages().size());
            assertFalse(store.latestCheckpoint("cli_message_tree").isPresent());

            String firstAssistant = snapshot.messages().get(1).id();
            tree.fork("from-first from " + firstAssistant);
            assertTrue(agent.getConversationHistory().stream()
                    .anyMatch(message -> "first".equals(message.content())));
            assertTrue(agent.getConversationHistory().stream()
                    .noneMatch(message -> "second".equals(message.content())));

            tree.recordCompletedTurn("branch", "branch answer", List.of());
        }

        try (Agent agent = new Agent(new StubLlmClient());
             RuntimeThreadStore store = new RuntimeThreadStore(db);
             SessionTreeService tree = new SessionTreeService(store, agent, "cli_message_tree")) {
            assertTrue(agent.getConversationHistory().stream()
                    .anyMatch(message -> "first".equals(message.content())));
            assertTrue(agent.getConversationHistory().stream()
                    .anyMatch(message -> "branch".equals(message.content())));
            assertTrue(agent.getConversationHistory().stream()
                    .noneMatch(message -> "second".equals(message.content())));
        }
    }

    private static final class StubLlmClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return new ChatResponse("assistant", "", List.of(), 0, 0);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                 StreamListener listener) throws IOException {
            return chat(messages, tools);
        }

        @Override public String getModelName() { return "test-model"; }
        @Override public String getProviderName() { return "test"; }
    }
}
