package com.devcli.agent;

import com.devcli.llm.LlmClient;
import com.devcli.memory.LongTermMemory;
import com.devcli.memory.MemoryManager;
import com.devcli.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.devcli.agent.AgentDelegationTest.answer;
import static com.devcli.agent.AgentDelegationTest.call;
import static org.junit.jupiter.api.Assertions.*;

/** Targeted tests through Agent.run; only the external model and memory storage are fixtures. */
class ToolGovernanceEntryTest {
    @TempDir Path project;

    @Test
    void narrowsCatalogAndKeepsDiscoveredToolVisible() throws Exception {
        var client = new AgentDelegationTest.ScriptedClient(
                call("search_tools", "{\"query\":\"lookup_journal\",\"limit\":\"1\"}"),
                answer("done"));
        try (Fixture fixture = fixture(client)) {
            for (int i = 0; i < 30; i++) register(fixture.registry, "lookup_" + i, () -> "unused");
            register(fixture.registry, "lookup_journal", () -> "journal");
            assertEquals("done", fixture.agent.run("Inspect lookup_9"));
            assertTrue(client.tools.getFirst().size() <= 9, client.tools.getFirst().toString());
            assertTrue(client.tools.getFirst().stream().anyMatch(tool -> tool.name().equals("lookup_9")));
            assertTrue(client.tools.get(1).stream().anyMatch(tool -> tool.name().equals("lookup_journal")));
            assertTrue(client.tools.stream().allMatch(tools ->
                    tools.stream().anyMatch(tool -> tool.name().equals("search_tools"))));
        }
    }

    @Test
    void rejectsOversizedBatchBeforeAnyExecutionAndPairsEveryCall() throws Exception {
        String key = "devcli.tool.budget.max.calls";
        String previous = System.getProperty(key);
        System.setProperty(key, "2");
        AtomicInteger executed = new AtomicInteger();
        var calls = List.of(
                new LlmClient.ToolCall("one", new LlmClient.ToolCall.Function("measure", "{}")),
                new LlmClient.ToolCall("two", new LlmClient.ToolCall.Function("measure", "{}")),
                new LlmClient.ToolCall("three", new LlmClient.ToolCall.Function("measure", "{}")));
        var client = new AgentDelegationTest.ScriptedClient(
                new LlmClient.ChatResponse("assistant", "", null, calls, 10, 2));
        try (Fixture fixture = fixture(client)) {
            java.util.List<com.devcli.event.RunEvent> events = new java.util.ArrayList<>();
            fixture.agent.setRunEventSink(events::add);
            register(fixture.registry, "measure", () -> Integer.toString(executed.incrementAndGet()));
            String output = fixture.agent.run("Measure");
            assertTrue(output.contains("工具调用额度不足"), output);
            assertEquals(0, executed.get());
            assertEquals(1, client.requests.size());
            var paired = events.stream().filter(com.devcli.event.RunEvent.ToolResults.class::isInstance)
                    .map(com.devcli.event.RunEvent.ToolResults.class::cast).findFirst().orElseThrow();
            assertEquals(3, paired.results().size());
        } finally {
            restore(key, previous);
        }
    }

    @Test
    void namedToolChoiceSurvivesCandidateRouting() throws Exception {
        var client = new AgentDelegationTest.ScriptedClient(call("required_probe", "{}"), answer("done"));
        try (Fixture fixture = fixture(client)) {
            for (int i = 0; i < 20; i++) register(fixture.registry, "lookup_" + i, () -> "value");
            register(fixture.registry, "required_probe", () -> "value");
            fixture.agent.run("Inspect lookup_1", LlmClient.ToolChoice.required("required_probe"));
            assertTrue(client.tools.getFirst().stream().anyMatch(tool -> tool.name().equals("required_probe")));
        }
    }

    @Test
    void stopsAlternatingUnchangedReadsButNotChangedReadResults() throws Exception {
        String key = "devcli.tool.budget.max.per.tool";
        String previous = System.getProperty(key);
        System.setProperty(key, "10");
        try {
        Files.writeString(project.resolve("a.txt"), "first");
        Files.writeString(project.resolve("b.txt"), "second");
        var responses = new LlmClient.ChatResponse[] {
                read("a.txt"), read("b.txt"), read("a.txt"), read("b.txt"), read("a.txt"), read("b.txt"),
                answer("new evidence")
        };
        var unchanged = new AgentDelegationTest.ScriptedClient(responses);
        try (Fixture fixture = fixture(unchanged)) {
            String result = fixture.agent.run("Compare a.txt and b.txt");
            assertTrue(result.contains("三个周期"), result);
            assertEquals(6, unchanged.requests.size());
        }
        var changed = new AgentDelegationTest.ScriptedClient(responses);
        AtomicInteger revision = new AtomicInteger();
        changed.beforeResponse = () -> Files.writeString(project.resolve("a.txt"),
                "revision " + revision.incrementAndGet());
        try (Fixture fixture = fixture(changed)) {
            assertEquals("new evidence", fixture.agent.run("Compare changing files"));
            assertTrue(changed.responses.isEmpty());
        }
        } finally {
            restore(key, previous);
        }
    }

    @Test
    void cachesOnlyWithinOneAgentTask() throws Exception {
        AtomicInteger executed = new AtomicInteger();
        var client = new AgentDelegationTest.ScriptedClient(
                call("fixed_value", "{}"), call("fixed_value", "{}"), answer("first"),
                call("fixed_value", "{}"), call("fixed_value", "{}"), answer("second"));
        try (Fixture fixture = fixture(client)) {
            register(fixture.registry, "fixed_value", () -> Integer.toString(executed.incrementAndGet()));
            assertEquals("first", fixture.agent.run("Read fixed value"));
            assertEquals(1, executed.get());
            assertEquals("second", fixture.agent.run("Read fixed value again"));
            assertEquals(2, executed.get());
        }
    }

    @Test
    void mutationsInvalidateCacheAndDoNotCountAsObservationCycles() throws Exception {
        AtomicInteger state = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        var client = new AgentDelegationTest.ScriptedClient(
                call("change_state", "{}"), call("fixed_value", "{}"),
                call("change_state", "{}"), call("fixed_value", "{}"),
                call("change_state", "{}"), call("fixed_value", "{}"), answer("verified"));
        try (Fixture fixture = fixture(client)) {
            fixture.registry.registerTool(new ToolRegistry.Tool("change_state", "Change local state",
                    new ObjectMapper().readTree("{\"type\":\"object\"}"),
                    args -> Integer.toString(state.incrementAndGet()), ToolRegistry.ToolEffect.LOCAL_CONTEXT));
            register(fixture.registry, "fixed_value", () -> {
                reads.incrementAndGet();
                return Integer.toString(state.get());
            });
            assertEquals("verified", fixture.agent.run("Change and verify state"));
            assertEquals(3, state.get());
            assertEquals(3, reads.get());
        }
    }

    @Test
    void perToolBudgetIncludesCacheHits() throws Exception {
        String key = "devcli.tool.budget.max.per.tool";
        String previous = System.getProperty(key);
        System.setProperty(key, "5");
        AtomicInteger executed = new AtomicInteger();
        var client = new AgentDelegationTest.ScriptedClient(
                call("fixed_value", "{}"), call("fixed_value", "{}"), call("fixed_value", "{}"),
                call("fixed_value", "{}"), call("fixed_value", "{}"), call("fixed_value", "{}"));
        try (Fixture fixture = fixture(client)) {
            register(fixture.registry, "fixed_value", () -> Integer.toString(executed.incrementAndGet()));
            String output = fixture.agent.run("Read repeatedly");
            assertTrue(output.contains("工具调用额度不足"), output);
            assertEquals(1, executed.get());
            assertEquals(6, client.requests.size());
        } finally {
            restore(key, previous);
        }
    }

    @Test
    void defaultBudgetAllowsReadingMoreThanFiveDifferentFiles() throws Exception {
        var responses = new java.util.ArrayList<LlmClient.ChatResponse>();
        for (int i = 0; i < 6; i++) {
            Files.writeString(project.resolve("file" + i + ".txt"), "value " + i);
            responses.add(read("file" + i + ".txt"));
        }
        responses.add(answer("six files read"));
        var client = new AgentDelegationTest.ScriptedClient(responses.toArray(LlmClient.ChatResponse[]::new));
        try (Fixture fixture = fixture(client)) {
            assertEquals("six files read", fixture.agent.run("Read the six files"));
        }
    }

    @Test
    void searchedCandidatesExpireAfterNextRequest() throws Exception {
        var client = new AgentDelegationTest.ScriptedClient(
                call("search_tools", "{\"query\":\"zz_probe\",\"limit\":\"5\"}"),
                call("zz_probe_0", "{}"), answer("done"));
        try (Fixture fixture = fixture(client)) {
            for (int i = 0; i < 20; i++) register(fixture.registry, "lookup_" + i, () -> "value");
            for (int i = 0; i < 5; i++) register(fixture.registry, "zz_probe_" + i, () -> "probe");
            assertEquals("done", fixture.agent.run("lookup_9"));
            assertTrue(client.tools.get(1).stream().anyMatch(tool -> tool.name().equals("zz_probe_0")));
            assertTrue(client.tools.get(2).stream().noneMatch(tool -> tool.name().startsWith("zz_probe_")));
            assertTrue(client.tools.get(2).stream().anyMatch(tool -> tool.name().equals("lookup_9")));
        }
    }

    @Test
    void structuredOutcomesAreAuditedWithoutBeingMarkedSuccessful() throws Exception {
        String key = "devcli.audit.dir";
        String previous = System.getProperty(key);
        Path auditDirectory = project.resolve("audit");
        System.setProperty(key, auditDirectory.toString());
        try {
            for (var output : List.of(
                    com.devcli.tool.ToolOutput.success("ok"),
                    com.devcli.tool.ToolOutput.rejected(com.devcli.tool.ToolErrorCode.POLICY_DENIED, "no"),
                    com.devcli.tool.ToolOutput.error(com.devcli.tool.ToolErrorCode.EXECUTION_FAILED, "failed", false),
                    com.devcli.tool.ToolOutput.timedOut("timeout"),
                    com.devcli.tool.ToolOutput.cancelled("cancelled"))) {
                var client = new AgentDelegationTest.ScriptedClient(call("mcp__fixture__probe", "{}"), answer("done"));
                try (Fixture fixture = fixture(client)) {
                    fixture.registry.registerTool(new ToolRegistry.Tool("mcp__fixture__probe", "Read fixture",
                            new ObjectMapper().readTree("{\"type\":\"object\"}"),
                            (ToolRegistry.StructuredToolExecutor) args -> output, ToolRegistry.ToolEffect.READ_ONLY));
                    fixture.agent.run("Read fixture");
                }
            }
            var entries = new com.devcli.policy.AuditLog(auditDirectory).readRecent(5);
            assertEquals(List.of("allow", "deny", "error", "error", "error"),
                    entries.stream().map(com.devcli.policy.AuditLog.AuditEntry::outcome).toList());
            assertEquals("REJECTED/POLICY_DENIED", entries.get(1).reason());
        } finally {
            restore(key, previous);
        }
    }

    @Test
    void childCallsConsumeParentToolBudget() throws Exception {
        String key = "devcli.tool.budget.max.calls";
        String previous = System.getProperty(key);
        System.setProperty(key, "2");
        var client = new AgentDelegationTest.ScriptedClient(call("delegate_task", """
                {"role":"explorer","task":"Inspect directory","deliverable":"Return directory evidence",
                 "task_spec":{"execution_kind":"agent_loop",
                 "inputs":"Project directory","scope":"Read only","done_condition":"Return directory evidence"}}
                """), call("list_dir", "{\"path\":\".\"}"), answer("child evidence"),
                call("list_dir", "{\"path\":\".\"}"));
        try (Fixture fixture = fixture(client)) {
            client.beforeResponse = () -> System.setProperty(key, "1");
            String output = fixture.agent.run("Inspect project");
            assertTrue(output.contains("工具调用额度不足"), output);
            assertEquals(4, client.requests.size());
        } finally {
            restore(key, previous);
        }
    }

    @Test
    void deniedToolReturnsAllowedAlternativesAndDoesNotExecute() throws Exception {
        AtomicInteger executed = new AtomicInteger();
        var client = new AgentDelegationTest.ScriptedClient(call("mutate", "{}"), answer("read instead"));
        try (Fixture fixture = fixture(client)) {
            fixture.registry.registerTool(new ToolRegistry.Tool("mutate", "Mutate project",
                    new ObjectMapper().readTree("{\"type\":\"object\"}"),
                    args -> Integer.toString(executed.incrementAndGet()), ToolRegistry.ToolEffect.PROJECT_MUTATION));
            String result = fixture.registry.runWithToolAccess(ToolRegistry.ToolAccessScope.READ_ONLY,
                    () -> fixture.agent.run("Inspect only"));
            assertEquals("read instead", result);
            assertEquals(0, executed.get());
            assertTrue(client.requests.get(1).stream().anyMatch(message ->
                    "tool".equals(message.role()) && message.content().contains("CAPABILITY_DENIED")
                            && message.content().contains("当前允许工具")));
        }
    }

    @Test
    void stopsConsecutiveFailuresEvenWhenToolNamesChange() throws Exception {
        var client = new AgentDelegationTest.ScriptedClient(
                call("missing_one", "{}"), call("missing_two", "{}"), call("missing_three", "{}"));
        try (Fixture fixture = fixture(client)) {
            String output = fixture.agent.run("Inspect tools");
            assertTrue(output.contains("连续 3 次工具调用失败"), output);
            assertEquals(3, client.requests.size());
            assertTrue(client.requests.get(1).stream().anyMatch(message ->
                    "tool".equals(message.role()) && message.content().contains("UNKNOWN_TOOL")
                            && message.content().contains("search_tools")));
        }
    }

    private static LlmClient.ChatResponse read(String path) {
        return call("read_file", "{\"path\":\"" + path + "\"}");
    }

    private static void register(ToolRegistry registry, String name, java.util.function.Supplier<String> action)
            throws Exception {
        registry.registerTool(new ToolRegistry.Tool(name, "Read " + name,
                new ObjectMapper().readTree("{\"type\":\"object\",\"additionalProperties\":false}"),
                args -> action.get(), ToolRegistry.ToolEffect.READ_ONLY));
    }

    private Fixture fixture(LlmClient client) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(project.toString());
        LongTermMemory memory = new LongTermMemory(project.resolve("memory"));
        MemoryManager manager = new MemoryManager(client, 1000, 128000, memory);
        return new Fixture(registry, memory, new Agent(client, registry, manager));
    }

    private static void restore(String key, String previous) {
        if (previous == null) System.clearProperty(key);
        else System.setProperty(key, previous);
    }

    private record Fixture(ToolRegistry registry, LongTermMemory memory, Agent agent) implements AutoCloseable {
        @Override public void close() {
            agent.close();
            memory.close();
            registry.close();
        }
    }

}
