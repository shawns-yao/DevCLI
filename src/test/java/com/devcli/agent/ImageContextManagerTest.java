package com.devcli.agent;

import com.devcli.event.ConversationJournal;
import com.devcli.event.RunEvent;
import com.devcli.image.ImageArtifactStore;
import com.devcli.llm.LlmClient;
import com.devcli.llm.ModelCapabilityRegistry;
import com.devcli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ImageContextManagerTest {
    @TempDir Path root;

    @Test
    void archivesDescribesOnceAndRetainsThreePixelsAcrossJournalReplay() throws Exception {
        var client = new VisionClient();
        var store = new ImageArtifactStore(root);
        List<LlmClient.Message> originals = new ArrayList<>();
        for (int i = 0; i < 5; i++) originals.add(LlmClient.Message.user(List.of(
                LlmClient.ContentPart.text("question " + i), image(i))));
        var journal = new ConversationJournal(originals);
        var facts = journal.events();
        var manager = new ImageContextManager(client, store);
        var budget = new AgentBudget(10_000, 3, 100);
        List<RunEvent> events = new ArrayList<>();
        manager.prepare(journal, budget, events::add);
        assertEquals(5, client.calls);
        assertEquals(50, budget.totalInputTokens());
        assertEquals(5, events.stream().filter(RunEvent.ModelContext.class::isInstance).count());
        assertEquals(5, events.stream().filter(event -> event instanceof RunEvent.CustomMessage message
                && message.messageType().equals("image.description") && message.content().equals("图中有一个色块")).count());
        assertEquals(3, journal.messages().stream().mapToInt(LlmClient.Message::imagePartCount).sum());
        var old = journal.messages().getFirst();
        assertTrue(old.content().contains("图中有一个色块"));
        assertTrue(old.content().contains("2x2"));
        assertTrue(old.content().contains(".img"));
        assertTrue(Files.isRegularFile(Path.of(store.archive(image(0)).path())));
        assertEquals(journal.messages(), ConversationJournal.restore(journal.events()).messages());
        assertEquals(5, ConversationJournal.restore(facts).messages().stream().mapToInt(LlmClient.Message::imagePartCount).sum());
        manager.prepare(journal, budget, events::add);
        var reloaded = new ConversationJournal(originals);
        new ImageContextManager(client, new ImageArtifactStore(root)).prepare(reloaded, budget, events::add);
        assertEquals(5, client.calls, "同图描述跨循环和存储重开复用");
    }

    @Test
    void archivedImagesReopenThroughRestrictedToolAndCannotReadArbitraryPaths() throws Exception {
        String previous = System.getProperty("devcli.runtime.dir");
        System.setProperty("devcli.runtime.dir", root.toString());
        try (var tools = new ToolRegistry()) {
            var store = new ImageArtifactStore();
            var saved = store.archive(image(1));
            store.describe(saved, "色块");
            String arguments = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                    java.util.Map.of("image_ref", saved.path()));
            var output = tools.executeToolOutput("read_image", arguments);
            assertTrue(output.isSuccess(), output.text());
            assertEquals(image(1).imageBase64(), output.imageParts().getFirst().imageBase64());
            assertEquals("色块", output.imageParts().getFirst().imageReference().description());
            Path outside = root.resolve("outside.img");
            Files.writeString(outside, "private");
            assertFalse(tools.executeToolOutput("read_image", new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(java.util.Map.of("image_ref", outside.toString()))).isSuccess());
        } finally {
            if (previous == null) System.clearProperty("devcli.runtime.dir");
            else System.setProperty("devcli.runtime.dir", previous);
        }
    }

    @Test
    void failedDescriptionDoesNotMutateJournalOrFabricateDescription() throws Exception {
        var client = new VisionClient() {
            @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
                throw new IOException("vision unavailable");
            }
        };
        var journal = new ConversationJournal(List.of(LlmClient.Message.user(List.of(image(0)))));
        var before = journal.events();
        assertThrows(IOException.class, () -> new ImageContextManager(client, new ImageArtifactStore(root))
                .prepare(journal, new AgentBudget(10_000, 3, 100), event -> { }));
        assertEquals(before, journal.events());
        assertTrue(new ImageArtifactStore(root).archive(image(0)).description().isEmpty());
    }

    @Test
    void exhaustedCaptionBudgetStopsBeforeTheNextCallAndLeavesFactsIntact() throws Exception {
        var client = new VisionClient();
        var journal = new ConversationJournal(List.of(LlmClient.Message.user(List.of(image(0), image(1)))));
        var facts = journal.events();
        var budget = new AgentBudget(5, 3, 100);
        assertFalse(new ImageContextManager(client, new ImageArtifactStore(root)).prepare(journal, budget, event -> { }));
        assertEquals(1, client.calls);
        assertEquals(AgentBudget.ExitReason.TOKEN_BUDGET_EXCEEDED, budget.check());
        assertEquals(facts, journal.events());
    }

    @Test
    void nonVisionModelArchivesWithoutAdditionalCall() throws Exception {
        var client = new VisionClient() {
            @Override public ModelCapabilityRegistry.Capabilities capabilities() {
                return ModelCapabilityRegistry.Capabilities.generic("test", "text");
            }
        };
        var journal = new ConversationJournal(List.of(LlmClient.Message.user(List.of(image(0)))));
        new ImageContextManager(client, new ImageArtifactStore(root)).prepare(journal, new AgentBudget(10_000, 3, 100), event -> { });
        assertEquals(0, client.calls);
        assertTrue(journal.messages().getFirst().withoutImageContent().content().contains("未生成视觉描述"));
    }

    @Test
    void productionLoopUsesArchivedProjectionAndRuntimeReplaysIt() throws Exception {
        String previous = System.getProperty("devcli.runtime.dir");
        System.setProperty("devcli.runtime.dir", root.toString());
        var client = new VisionClient() {
            @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
                if (messages.getFirst().content().startsWith("只描述图片")) return super.chat(messages, tools);
                assertEquals(3, messages.stream().mapToInt(Message::imagePartCount).sum());
                assertTrue(messages.stream().anyMatch(message -> message.content().contains("图中有一个色块")));
                return new ChatResponse("assistant", "done", null, List.of(), 10, 2);
            }
        };
        try (var store = new com.devcli.runtime.api.RuntimeThreadStore(root.resolve("runtime.db"));
             var tools = new ToolRegistry(); var agent = new Agent(client, tools)) {
            tools.setProjectPath(root.toString());
            List<LlmClient.Message> images = new ArrayList<>();
            for (int i = 0; i < 4; i++) images.add(LlmClient.Message.user(List.of(image(i))));
            agent.seedHistory(images);
            String thread = store.createThread();
            assertEquals("done", com.devcli.runtime.api.ConversationTurnRecorder.run(agent, store, thread,
                    "react", "continue", "continue", () -> agent.run("continue"), value -> value));
            assertEquals(4, client.calls);
            assertEquals(agent.getConversationHistory(), store.contextView(thread).messages());
            assertEquals(4, store.originalConversationMessages(thread).stream().mapToInt(LlmClient.Message::imagePartCount).sum());
        } finally {
            if (previous == null) System.clearProperty("devcli.runtime.dir");
            else System.setProperty("devcli.runtime.dir", previous);
        }
    }

    private LlmClient.ContentPart image(int color) throws Exception {
        var buffered = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        buffered.setRGB(0, 0, color);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(buffered, "png", bytes);
        return LlmClient.ContentPart.imageBase64(Base64.getEncoder().encodeToString(bytes.toByteArray()), "image/png");
    }

    private static class VisionClient implements LlmClient {
        int calls;
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            calls++;
            assertTrue(messages.getLast().hasImageContent());
            return new ChatResponse("assistant", "图中有一个色块", null, List.of(), 10, 2);
        }
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException { return chat(messages, tools); }
        public String getModelName() { return "vision"; }
        public String getProviderName() { return "test"; }
        public ModelCapabilityRegistry.Capabilities capabilities() {
            return new ModelCapabilityRegistry.Capabilities("test", "vision", 128_000, 8_000, false, "none", true, true, false);
        }
    }
}
