package com.devcli.agent;

import com.devcli.llm.LlmClient;
import com.devcli.memory.LongTermMemory;
import com.devcli.memory.MemoryManager;
import com.devcli.render.Renderer;
import com.devcli.render.StatusInfo;
import com.devcli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentQueuedOutputTest {
    @TempDir Path project;

    @Test
    void queuedReplyDoesNotMergeWithUnterminatedPreviousReply() {
        LlmClient client = new ScriptedClient(false);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Renderer renderer = mock(Renderer.class);
        when(renderer.stream()).thenReturn(new PrintStream(output, true, StandardCharsets.UTF_8));
        try (LongTermMemory store = new LongTermMemory(project.resolve("memory"));
             MemoryManager memory = new MemoryManager(client, 4096, 128_000, store);
             ToolRegistry tools = new ToolRegistry();
             Agent agent = new Agent(client, tools, memory)) {
            tools.setProjectPath(project.toString());
            agent.setRenderer(renderer);
            agent.getTurnInbox().enqueueFollowUp("queued question");
            agent.run("first question");
            String text = output.toString(StandardCharsets.UTF_8);
            assertTrue(text.contains("first answer"), text);
            assertTrue(text.contains("> queued question"), text);
            assertTrue(text.contains("second answer"), text);
            assertFalse(text.contains("first answersecond answer"), text);
            assertTrue(text.indexOf("first answer") < text.indexOf("> queued question"), text);
            assertTrue(text.indexOf("> queued question") < text.indexOf("second answer"), text);
            assertEquals(2, agent.conversationHistorySnapshot().stream()
                    .filter(message -> "assistant".equals(message.role())).count());
        }
    }

    @Test
    void providerFailureLeavesStatusIdle() {
        LlmClient client = new ScriptedClient(true);
        Renderer renderer = mock(Renderer.class);
        when(renderer.stream()).thenReturn(new PrintStream(new ByteArrayOutputStream()));
        try (LongTermMemory store = new LongTermMemory(project.resolve("memory"));
             MemoryManager memory = new MemoryManager(client, 4096, 128_000, store);
             ToolRegistry tools = new ToolRegistry();
             Agent agent = new Agent(client, tools, memory)) {
            tools.setProjectPath(project.toString());
            agent.setRenderer(renderer);
            assertTrue(agent.run("question").contains("simulated provider failure"));
            ArgumentCaptor<StatusInfo> statuses = ArgumentCaptor.forClass(StatusInfo.class);
            verify(renderer, atLeastOnce()).updateStatus(statuses.capture());
            assertEquals("idle", statuses.getValue().phase());
        }
    }

    private static final class ScriptedClient implements LlmClient {
        private final boolean fail;
        private int calls;
        ScriptedClient(boolean fail) { this.fail = fail; }
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            if (fail) throw new IOException("simulated provider failure");
            String text = ++calls == 1 ? "first answer" : "second answer";
            listener.onContentDelta(text);
            return new ChatResponse("assistant", text, List.of(), 10, 10);
        }
        public String getModelName() { return "test"; }
        public String getProviderName() { return "test"; }
        public int maxContextWindow() { return 128_000; }
    }
}
