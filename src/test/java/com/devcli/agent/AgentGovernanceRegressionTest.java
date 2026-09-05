package com.devcli.agent;

import com.devcli.llm.LlmClient;
import com.devcli.memory.LongTermMemory;
import com.devcli.memory.LongTermMemoryStore;
import com.devcli.memory.MemoryEntry;
import com.devcli.memory.MemoryEvidence;
import com.devcli.memory.MemoryManager;
import com.devcli.memory.TokenBudget;
import com.devcli.tool.ResourceLeaseMaintenance;
import com.devcli.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Targeted regressions through the public Agent entry point, without a database or remote model. */
class AgentGovernanceRegressionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path root;
    private final Map<String, String> originalProperties = new LinkedHashMap<>();

    @AfterEach
    void restoreProperties() {
        originalProperties.forEach((key, value) -> {
            if (value == null) System.clearProperty(key);
            else System.setProperty(key, value);
        });
    }

    @Test
    void rejectedReviewDoesNotPublishWorkerPatch() throws Exception {
        ScriptedClient client = new ScriptedClient(
                call("delegate_task", Map.of("role", "worker", "task", "update security.txt")),
                call("write_file", Map.of("path", "security.txt", "content", "candidate")),
                answer("worker done"),
                call("read_file", Map.of("path", "security.txt")),
                answer("{\"approved\":false,\"issues\":[{\"severity\":\"high\",\"description\":\"unsafe\"}]}"),
                answer("parent done"));
        try (Fixture fixture = fixture(client)) {
            Files.writeString(fixture.project.resolve("security.txt"), "original");
            fixture.agent.run("Delegate the security file update");
            assertEquals("original", Files.readString(fixture.project.resolve("security.txt")));
            JsonNode report = parentDelegationReport(client);
            assertEquals("failed", report.path("status").asText());
            assertTrue(client.requests.stream().flatMap(List::stream)
                    .anyMatch(message -> "tool".equals(message.role())
                            && message.content().contains("candidate")),
                    "Reviewer must inspect the candidate in the isolated workspace");
        }
    }

    @Test
    void approvedReviewInspectsCandidateBeforePublishing() throws Exception {
        ScriptedClient client = new ScriptedClient(
                call("delegate_task", Map.of("role", "worker", "task", "update security.txt")),
                call("write_file", Map.of("path", "security.txt", "content", "candidate")),
                answer("worker done"),
                call("read_file", Map.of("path", "security.txt")),
                answer("{\"approved\":true,\"issues\":[]}"),
                answer("parent done"));
        try (Fixture fixture = fixture(client)) {
            Files.writeString(fixture.project.resolve("security.txt"), "original");
            client.beforeResponse = () -> {
                if (client.requests.size() == 4 || client.requests.size() == 5) {
                    assertEquals("original", Files.readString(fixture.project.resolve("security.txt")));
                }
            };
            fixture.agent.run("Delegate the security file update");
            assertEquals("candidate", Files.readString(fixture.project.resolve("security.txt")));
            assertEquals("done", parentDelegationReport(client).path("status").asText());
        }
    }

    @Test
    void commandGeneratedFileOutsideWriteAllowlistIsNotPublished() throws Exception {
        property("devcli.command.sandbox.mode", "HOST_WARN");
        ScriptedClient client = new ScriptedClient(
                call("delegate_task", Map.of("role", "worker", "task", "generate JNI header",
                        "allowed_write_paths", List.of("allowed.txt"))),
                call("execute_command", Map.of("command", "javac -h . NativeApi.java")),
                answer("worker done"), answer("parent done"));
        try (Fixture fixture = fixture(client)) {
            Files.writeString(fixture.project.resolve("NativeApi.java"),
                    "public class NativeApi { public native void invoke(); }");
            fixture.agent.run("Delegate JNI header generation within the supplied write scope");
            assertTrue(client.requests.stream().flatMap(List::stream)
                    .anyMatch(message -> "tool".equals(message.role())
                            && message.content().contains("exit code: 0")),
                    "The real command must succeed before the patch policy is tested");
            assertFalse(Files.exists(fixture.project.resolve("NativeApi.h")));
            assertEquals("failed", parentDelegationReport(client).path("status").asText());
        }
    }

    @Test
    void currentProjectObservationCannotInvalidateAnotherProjectMemory() throws Exception {
        ScriptedClient client = new ScriptedClient(
                call("list_dir", Map.of("path", ".")), answer("parent done"));
        try (Fixture fixture = fixture(client)) {
            Files.writeString(fixture.project.resolve("pom.xml"), "<project/>");
            fixture.memory.store(projectBuildMemory("current", fixture.project));
            fixture.memory.store(projectBuildMemory("other", root.resolve("other-project")));
            fixture.agent.run("Inspect the build system");
            assertTrue(fixture.memory.retrieve("other").orElseThrow().isRecallable());
            assertFalse(fixture.memory.retrieve("current").orElseThrow().isRecallable());
            MemoryEntry negative = fixture.memory.getAll().stream()
                    .filter(entry -> "true".equals(entry.getMetadata().get("negative_fact")))
                    .findFirst().orElseThrow();
            assertEquals(fixture.project.toString(), negative.getMetadata().get("scope_key"));
            assertEquals("PROJECT", negative.getMetadata().get("scope_type"));
        }
    }

    @Test
    void rejectedWriteDoesNotBecomeModifiedFileEvidence() throws Exception {
        ScriptedClient client = new ScriptedClient(
                call("write_file", Map.of("path", "../outside.txt", "content", "not allowed")),
                answer("parent done"));
        try (Fixture fixture = fixture(client)) {
            fixture.agent.run("Attempt the supplied write");
            var snapshot = fixture.manager.getSessionMemory().snapshot();
            assertTrue(snapshot.modifiedFiles().isEmpty(), snapshot.modifiedFiles().toString());
            assertFalse(snapshot.attemptDigests().isEmpty());
            assertFalse(Files.exists(root.resolve("outside.txt")));
        }
    }

    @Test
    void singleUserTurnCompactsCompletedToolBatches() throws Exception {
        ScriptedClient client = new ScriptedClient(
                call("read_file", Map.of("path", "first.txt")),
                call("read_file", Map.of("path", "second.txt")),
                call("read_file", Map.of("path", "third.txt")),
                answer("parent done"));
        client.window = 16_000;
        try (Fixture fixture = fixture(client)) {
            for (String name : List.of("first.txt", "second.txt", "third.txt")) {
                Files.writeString(fixture.project.resolve(name), (name + " ").repeat(40_000));
            }
            fixture.agent.run("Inspect these files. Keep the existing API unchanged.");
            assertTrue(client.summaryCalls > 0,
                    "A single user turn must be semantically compactable");
            List<LlmClient.Message> finalRequest = fixture.agent.getConversationHistory();
            assertTrue(finalRequest.stream().anyMatch(message ->
                    message.content() != null && message.content()
                            .contains("<compact_boundary>")));
            assertTrue(TokenBudget.estimateMessagesTokens(finalRequest) < client.window);
            assertToolPairs(finalRequest);
        }
    }

    private Fixture fixture(ScriptedClient client) throws IOException {
        property(MemoryManager.SESSION_PRE_SUMMARY_ENABLED_PROPERTY, "false");
        property("devcli.context.compaction.enabled", "true");
        property("devcli.context.compression.trigger.tokens", "2000");
        property("devcli.react.token.budget", "1000000");
        Path project = Files.createDirectories(root.resolve("project"));
        ToolRegistry registry = new NoIndexRegistry();
        registry.setProjectPath(project.toString());
        LongTermMemory memory = new LongTermMemory(new InMemoryStore(), root.resolve("memory"));
        MemoryManager manager = new MemoryManager(client, 1_000, client.window, memory);
        Agent agent = new Agent(client, registry, manager);
        return new Fixture(project, registry, memory, manager, agent);
    }

    private void property(String key, String value) {
        if (!originalProperties.containsKey(key)) originalProperties.put(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    private static MemoryEntry projectBuildMemory(String id, Path scope) {
        return new MemoryEntry(id, "project.build_system = gradle", MemoryEntry.MemoryType.FACT,
                Instant.now(), Map.of("scope_type", "PROJECT", "scope_key", scope.toString(),
                        "claim_scope", scope.toString()), 20, "project.build_system",
                true, "", MemoryEntry.CURRENT_SCHEMA_VERSION, 1, null,
                new MemoryEvidence(MemoryEvidence.Confidence.HIGH, "user", "confirmed",
                        MemoryEvidence.ReviewState.REVIEWED, List.of()));
    }

    private static JsonNode parentDelegationReport(ScriptedClient client) throws IOException {
        for (LlmClient.Message message : client.requests.getLast()) {
            if ("tool".equals(message.role()) && message.content().contains("\"report_id\"")) {
                return JSON.readTree(message.content());
            }
        }
        throw new AssertionError("Parent did not receive a delegation report");
    }

    private static void assertToolPairs(List<LlmClient.Message> messages) {
        var pending = new java.util.HashSet<String>();
        for (LlmClient.Message message : messages) {
            if (message.toolCalls() != null) {
                assertTrue(pending.isEmpty(), "Previous tool batch must be complete");
                message.toolCalls().forEach(call -> pending.add(call.id()));
            }
            if ("tool".equals(message.role())) {
                assertTrue(pending.remove(message.toolCallId()), "Orphan tool result");
            }
        }
        assertTrue(pending.isEmpty(), "Missing tool results");
    }

    private static LlmClient.ChatResponse call(String name, Map<String, ?> arguments) throws IOException {
        return new LlmClient.ChatResponse("assistant", "", null,
                List.of(new LlmClient.ToolCall("call-" + java.util.UUID.randomUUID(),
                        new LlmClient.ToolCall.Function(name, JSON.writeValueAsString(arguments)))), 10, 2);
    }

    private static LlmClient.ChatResponse answer(String content) {
        return new LlmClient.ChatResponse("assistant", content, null, null, 10, 2);
    }

    private record Fixture(Path project, ToolRegistry registry, LongTermMemory memory,
                           MemoryManager manager, Agent agent) implements AutoCloseable {
        @Override public void close() {
            agent.close();
            registry.close();
        }
    }

    private static final class NoIndexRegistry extends ToolRegistry {
        NoIndexRegistry() { super(); }
        NoIndexRegistry(ResourceLeaseMaintenance maintenance) { super(maintenance); }
        @Override public void markRagIndexDirty(Collection<String> paths) { }
        @Override protected ToolRegistry createProjectForkRegistry(ResourceLeaseMaintenance maintenance) {
            return new NoIndexRegistry(maintenance);
        }
    }

    private static final class InMemoryStore implements LongTermMemoryStore {
        private final Map<String, MemoryEntry> entries = new LinkedHashMap<>();
        @Override public List<MemoryEntry> loadAll() { return List.copyOf(entries.values()); }
        @Override public boolean upsert(MemoryEntry entry) { entries.put(entry.getId(), entry); return true; }
        @Override public boolean isPersistent() { return false; }
        @Override public void delete(String id) { entries.remove(id); }
        @Override public void clear() { entries.clear(); }
        @Override public void close() { }
    }

    @FunctionalInterface
    private interface BeforeResponse { void run() throws IOException; }

    private static final class ScriptedClient implements LlmClient {
        private final ArrayDeque<ChatResponse> responses;
        private final List<List<Message>> requests = new ArrayList<>();
        private int window = 128_000;
        private int summaryCalls;
        private BeforeResponse beforeResponse = () -> {};

        ScriptedClient(ChatResponse... responses) { this.responses = new ArrayDeque<>(List.of(responses)); }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                           StreamListener listener) throws IOException {
            if (tools == null || tools.isEmpty()) {
                summaryCalls++;
                return answer("## Key facts\n- Earlier files were read; keep the existing API unchanged.");
            }
            requests.add(List.copyOf(messages));
            beforeResponse.run();
            if (responses.isEmpty()) throw new IOException("Unexpected extra model request");
            return responses.removeFirst();
        }
        @Override public String getModelName() { return "governance-test"; }
        @Override public String getProviderName() { return "test"; }
        @Override public int maxContextWindow() { return window; }
        @Override public int maxOutputTokens() { return 2_048; }
    }
}
