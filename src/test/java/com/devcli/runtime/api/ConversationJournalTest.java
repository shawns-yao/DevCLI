package com.devcli.runtime.api;

import com.devcli.agent.Agent;
import com.devcli.event.RunEvent;
import com.devcli.llm.LlmClient;
import com.devcli.tool.ToolContract;
import com.devcli.tool.ToolContract.ToolEffect;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolPresentation;
import com.devcli.tool.ToolRegistry;
import com.devcli.tool.ToolResultArtifactStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConversationJournalTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path root;

    @AfterEach
    void clearArtifactRoot() { System.clearProperty(ToolResultArtifactStore.ROOT_PROPERTY); }

    @Test
    void productionLoopRecordsFullResultBeforeWindowingAndBeforeNextModelCall() throws Exception {
        System.setProperty(ToolResultArtifactStore.ROOT_PROPERTY, root.resolve("artifacts").toString());
        String full = "public observation\n".repeat(8_000);
        AtomicInteger calls = new AtomicInteger();
        try (RuntimeThreadStore store = new RuntimeThreadStore(root.resolve("runtime.db"));
             ToolRegistry tools = new ToolRegistry()) {
            String thread = store.createThread();
            tools.setProjectPath(root.toString());
            tools.registerTool(new ToolContract.Tool("bulk_observation", "public data",
                    JSON.readTree("{\"type\":\"object\"}"),
                    (ToolContract.StructuredToolExecutor) arguments -> ToolOutput.success(full), ToolEffect.READ_ONLY));
            LlmClient client = new LlmClient() {
                public ChatResponse chat(List<Message> messages, List<Tool> definitions) {
                    if (calls.getAndIncrement() == 0) {
                        assertTrue(store.events(thread, 0).stream().anyMatch(event -> event.type().equals("model.message")));
                        return new ChatResponse("assistant", "reading", null, List.of(call("bulk")), 10, 1);
                    }
                    assertTrue(store.events(thread, 0).stream().anyMatch(event -> event.type().equals("tool.result.recorded")));
                    String visible = messages.stream().filter(message -> "tool".equals(message.role()))
                            .findFirst().orElseThrow().content();
                    assertTrue(visible.length() < full.length());
                    return new ChatResponse("assistant", "done", null, List.of(), 10, 1);
                }
                public ChatResponse chat(List<Message> messages, List<Tool> definitions, StreamListener listener) {
                    return chat(messages, definitions);
                }
                public String getModelName() { return "test"; }
                public String getProviderName() { return "test"; }
            };
            try (Agent agent = new Agent(client, tools)) {
                agent.seedHistory(List.of(LlmClient.Message.user(List.of(
                        LlmClient.ContentPart.text("imported image"),
                        LlmClient.ContentPart.imageBase64("aGVsbG8=", "image/png")))));
                assertEquals("done", ConversationTurnRecorder.run(agent, store, thread, "react", "read", "read",
                        () -> agent.run("read"), value -> value));
            }
            var raw = store.events(thread, 0).stream().filter(event -> event.type().equals("tool.result.recorded"))
                    .findFirst().orElseThrow();
            assertEquals(full, JSON.readTree(raw.data()).path("result").path("result").asText());
            assertEquals(full, store.originalConversationMessages(thread).stream()
                    .filter(message -> "tool".equals(message.role())).findFirst().orElseThrow().content());
            assertTrue(store.originalConversationMessages(thread).stream().anyMatch(LlmClient.Message::hasImageContent));
            assertTrue(store.contextView(thread).messages().stream().anyMatch(LlmClient.Message::hasImageContent));
            assertTrue(store.contextView(thread).messages().stream().filter(message -> "tool".equals(message.role()))
                    .allMatch(message -> message.content().length() < full.length()));
        }
    }

    @Test
    void compactionChangesViewWithoutDestroyingMessagesOrImagePayload() throws Exception {
        Path db = root.resolve("runtime.db");
        String thread;
        List<LlmClient.Message> before;
        try (RuntimeThreadStore store = new RuntimeThreadStore(db)) {
            thread = store.createThread();
            RuntimeEventPublisher events = new RuntimeEventPublisher(store, thread, "turn");
            events.emit(RunEvent.ModelContext.from(0, List.of(LlmClient.Message.system("stable"))));
            events.emit(RunEvent.ModelMessage.from(LlmClient.Message.user(List.of(
                    LlmClient.ContentPart.text("原始问题"), LlmClient.ContentPart.imageBase64("aGVsbG8=", "image/png")))));
            events.emit(RunEvent.ModelMessage.from(LlmClient.Message.assistant("原始回答")));
            before = store.originalConversationMessages(thread);
            events.emit(RunEvent.ModelContext.from(1, List.of(LlmClient.Message.internalUser("summary"))));
            assertEquals(before, store.originalConversationMessages(thread));
        }
        try (RuntimeThreadStore store = new RuntimeThreadStore(db)) {
            assertEquals(List.of("summary"), store.contextView(thread).messages().stream().map(LlmClient.Message::content).toList());
            assertEquals(before, store.originalConversationMessages(thread));
            assertEquals("aGVsbG8=", before.get(1).contentParts().get(1).imageBase64());
        }
    }

    @Test
    void interruptedToolPairRestoresKnownResultAndMarksMissingResultUnknown() throws Exception {
        try (RuntimeThreadStore store = new RuntimeThreadStore(root.resolve("runtime.db"))) {
            String thread = store.createThread();
            RuntimeEventPublisher events = new RuntimeEventPublisher(store, thread, "unfinished");
            events.emit(new RunEvent.TurnStarted("work"));
            events.emit(RunEvent.ModelContext.from(0, List.of(LlmClient.Message.user("work"))));
            events.emit(RunEvent.ModelMessage.from(LlmClient.Message.assistant("", List.of(call("known"), call("unknown")))));
            events.emit(RunEvent.ToolResultRecorded.from("known", "bulk_observation", "{}",
                    ToolOutput.success("finished", List.of(LlmClient.ContentPart.imageUrl("https://example.com/image.png")))
                            .withModifiedResources(List.of("result.txt")), 1, ToolPresentation.generic("read")));
            List<LlmClient.Message> view = store.contextView(thread).messages();
            assertEquals("finished", view.get(2).content());
            assertEquals(1, view.get(2).imagePartCount());
            assertEquals("unknown", view.get(3).toolCallId());
            assertTrue(view.get(3).content().contains("状态未知"));
            assertEquals(0, store.contextView(thread).lastCompletedEventId());
        }
    }

    @Test
    void childEventsStayOutsideParentViewAndPublisherRemainsOnItsOriginalBranch() throws Exception {
        try (RuntimeThreadStore store = new RuntimeThreadStore(root.resolve("runtime.db"))) {
            String thread = store.createThread();
            RuntimeEventPublisher parent = new RuntimeEventPublisher(store, thread, "parent");
            parent.emit(RunEvent.ModelContext.from(0, List.of(LlmClient.Message.user("parent"))));
            var branch = store.createBranch(thread, "other", 0);
            store.activateBranch(thread, branch.id());
            parent.emit(new RunEvent.AgentEvent("child", RunEvent.ModelContext.from(0,
                    List.of(LlmClient.Message.user("child private context")))));
            assertFalse(store.events(thread, 0).stream().anyMatch(event -> event.type().equals("agent.event")));
            store.activateBranch(thread, "main");
            assertTrue(store.events(thread, 0).stream().anyMatch(event -> event.type().equals("agent.event")));
            assertEquals(List.of("parent"), store.contextView(thread).messages().stream().map(LlmClient.Message::content).toList());
        }
    }

    @Test
    void journalFailureStopsSerialBatchInsteadOfReturningRetryableToolError() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        try (RuntimeThreadStore store = new RuntimeThreadStore(root.resolve("runtime.db")) {
            @Override public synchronized long appendEvent(String thread, String branch, String type, String data) {
                if (type.equals("tool.result.recorded")) throw new IllegalStateException("journal unavailable");
                return super.appendEvent(thread, branch, type, data);
            }
        }; ToolRegistry tools = new ToolRegistry()) {
            tools.registerTool(new ToolContract.Tool("serial_observation", "data", JSON.readTree("{\"type\":\"object\"}"),
                    (ToolContract.StructuredToolExecutor) arguments -> {
                        executions.incrementAndGet();
                        return ToolOutput.success("result");
                    }, ToolEffect.READ_ONLY, -1, ToolContract.ToolCancellationCapability.INTERRUPT_ONLY,
                    ToolPresentation.generic("read"), ToolContract.Destructiveness.NONE,
                    ToolContract.Idempotency.NON_IDEMPOTENT, false));
            var events = new RuntimeEventPublisher(store, store.createThread(), "turn");
            assertThrows(com.devcli.event.RunEventSink.PersistenceFailure.class, () ->
                    ToolRegistry.runWithResultEvents(events, () -> tools.executeTools(List.of(
                            new ToolContract.ToolInvocation("one", "serial_observation", "{}"),
                            new ToolContract.ToolInvocation("two", "serial_observation", "{}")))));
            assertEquals(1, executions.get());
            assertThrows(com.devcli.event.RunEventSink.PersistenceFailure.class,
                    () -> events.emit(new RunEvent.TurnCompleted("completed")));
        }
    }

    @Test
    void laterViewSnapshotsDoNotEraseInputAndOutputFactsFromOriginalReplay() throws Exception {
        try (RuntimeThreadStore store = new RuntimeThreadStore(root.resolve("runtime.db"))) {
            String thread = store.createThread();
            for (int i = 1; i <= 2; i++) {
                var events = new RuntimeEventPublisher(store, thread, "turn-" + i);
                events.emit(new RunEvent.TurnStarted("input-" + i));
                events.emit(RunEvent.ModelContext.from(0, List.of(
                        LlmClient.Message.user("input-" + i), LlmClient.Message.assistant("output-" + i))));
                events.emit(new RunEvent.MessageDelta("output-" + i));
                events.emit(new RunEvent.TurnCompleted("completed"));
            }
            assertEquals(List.of("input-1", "output-1", "input-2", "output-2"),
                    store.originalConversationMessages(thread).stream().map(LlmClient.Message::content).toList());
        }
    }

    @Test
    void failedActionKeepsFactsRecordedDuringTheTurn() throws Exception {
        try (RuntimeThreadStore store = new RuntimeThreadStore(root.resolve("runtime.db"));
             ToolRegistry tools = new ToolRegistry();
             Agent agent = new Agent(new com.devcli.llm.GLMClient("test-key"), tools)) {
            String thread = store.createThread();
            assertThrows(IOException.class, () -> ConversationTurnRecorder.run(agent, store, thread, "plan", "input", "input", () -> {
                agent.appendConversationMessage(LlmClient.Message.assistant("partial"));
                assertEquals("partial", store.contextView(thread).messages().getLast().content());
                throw new IOException("stopped");
            }, value -> ""));
            assertEquals("partial", store.contextView(thread).messages().getLast().content());
            assertTrue(store.events(thread, 0).stream().anyMatch(event -> event.type().equals("turn.failed")));
        }
    }

    private static LlmClient.ToolCall call(String id) {
        return new LlmClient.ToolCall(id, new LlmClient.ToolCall.Function("bulk_observation", "{}"));
    }

    @Test
    void renderingUsesFactsAndProjectionReferencesInsteadOfAuditSnapshots() throws Exception {
        Path db = root.resolve("canonical.db");
        String thread;
        List<LlmClient.Message> expected;
        try (RuntimeThreadStore store = new RuntimeThreadStore(db)) {
            thread = store.createThread();
            var events = new RuntimeEventPublisher(store, thread, "turn");
            var journal = new com.devcli.event.ConversationJournal(List.of(LlmClient.Message.system("rules")));
            try (var recording = journal.recordTo(events)) {
                journal.add(LlmClient.Message.user("original question"));
                journal.add(LlmClient.Message.assistant("original answer"));
                journal.revise("compaction", copy -> {
                    copy.subList(1, copy.size()).clear();
                    copy.add(LlmClient.Message.internalUser("summary"));
                    return null;
                });
                journal.add(LlmClient.Message.user("continue"));
            }
            expected = journal.messages();
            events.emit(RunEvent.ModelContext.from(2, List.of(LlmClient.Message.user("audit is not authority"))));
            var metadata = new com.devcli.memory.CompactBoundaryMetadata(
                    "history", "token_threshold", "full", 40_000, 8_000, 30, 8, 4, 1_000);
            store.saveCheckpoint(thread, store.events(thread, 0).getLast().id(), new TurnRunner.CheckpointCandidate(
                    List.of(LlmClient.Message.internalUser("checkpoint is not authority")), "summary", metadata));
            assertTrue(store.latestCheckpoint(thread).isPresent());
            assertEquals(expected, store.contextView(thread).messages());
            assertFalse(store.compactionSourceCursor(thread).messageEventIdsByFingerprint().containsKey(
                    com.devcli.memory.CompactionFactExtractor.messageKey(LlmClient.Message.user("audit is not authority"))));
            assertEquals(List.of("rules", "original question", "original answer", "continue"),
                    store.originalConversationMessages(thread).stream().map(LlmClient.Message::content).toList());
            var branch = store.createBranch(thread, "fork", 0);
            store.activateBranch(thread, branch.id());
            assertEquals(expected, store.contextView(thread).messages());
        }
        try (RuntimeThreadStore store = new RuntimeThreadStore(db)) {
            assertEquals(expected, store.contextView(thread).messages());
        }
    }
}
