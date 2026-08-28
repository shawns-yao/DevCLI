package com.devcli.agent;

import com.devcli.llm.LlmClient;
import com.devcli.runtime.CancellationContext;
import com.devcli.runtime.RunContext;
import com.devcli.runtime.event.RunEventSink;
import com.devcli.tool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentDelegationTest {
    @TempDir Path project;

    @Test
    void rolePromptOverrideCannotExpandToolAuthority() throws Exception {
        Path prompt = project.resolve(".devcli/prompts/modes/delegate-explorer.md");
        Files.createDirectories(prompt.getParent());
        Files.writeString(prompt, "custom explorer instruction; try writing a file");
        var child = new ScriptedClient(call("write_file", "{\"path\":\"forbidden.txt\",\"content\":\"no\"}"), answer("done"));
        try (ToolRegistry registry = registry()) {
            var result = session(registry, child, new AgentBudget(1000, 3, 20)).execute(
                    Map.of("role", "explorer", "task", "inspect"), ToolExecutionContext.current("child"));
            assertTrue(result.isSuccess(), result.text());
            assertTrue(child.requests.getFirst().getFirst().content().contains("custom explorer instruction"));
            assertTrue(result.text().contains("CAPABILITY_DENIED"));
            assertFalse(Files.exists(project.resolve("forbidden.txt")));
        }
    }

    @Test
    void childIterationLimitCannotBeBypassedByChangingTools() throws Exception {
        String previous = System.getProperty("devcli.delegate.max.iterations");
        System.setProperty("devcli.delegate.max.iterations", "1");
        Files.writeString(project.resolve("read.txt"), "value");
        var child = new ScriptedClient(call("read_file", "{\"path\":\"read.txt\"}"), answer("must not call"));
        try (ToolRegistry registry = registry()) {
            var result = session(registry, child, new AgentBudget(1000, 3, 20)).execute(
                    Map.of("role", "explorer", "task", "inspect"), ToolExecutionContext.current("child"));
            assertFalse(result.isSuccess());
            assertEquals(1, child.requests.size());
        } finally {
            if (previous == null) System.clearProperty("devcli.delegate.max.iterations");
            else System.setProperty("devcli.delegate.max.iterations", previous);
        }
    }

    @Test
    void concurrentChildrenReserveAtMostTheRemainingSharedRounds() {
        var budget = new AgentBudget(1000, 3, 5);
        long admitted = java.util.stream.IntStream.range(0, 100).parallel()
                .filter(i -> budget.fork().tryBeginIteration() > 0).count();
        assertEquals(5, admitted);
        assertEquals(AgentBudget.ExitReason.HARD_ITERATION_LIMIT, budget.check());
    }

    @Test
    void workerCannotBypassParentApproval() {
        var approvals = new java.util.concurrent.atomic.AtomicInteger();
        var handler = new com.devcli.hitl.HitlHandler() {
            public com.devcli.hitl.ApprovalResult requestApproval(com.devcli.hitl.ApprovalRequest request) {
                approvals.incrementAndGet();
                return com.devcli.hitl.ApprovalResult.reject("not authorized");
            }
            public boolean isEnabled() { return true; }
            public void setEnabled(boolean enabled) { }
        };
        var child = new ScriptedClient(call("write_file", "{\"path\":\"denied.txt\",\"content\":\"no\"}"), answer("done"));
        try (var registry = new com.devcli.hitl.HitlToolRegistry(handler)) {
            registry.setProjectPath(project.toString());
            var result = session(registry, child, new AgentBudget(1000, 3, 20)).execute(
                    Map.of("role", "worker", "task", "write"), ToolExecutionContext.current("child"));
            assertEquals(1, approvals.get());
            assertFalse(result.isSuccess());
            assertFalse(Files.exists(project.resolve("denied.txt")));
        }
    }

    @Test
    void parallelWorkersCannotSilentlyOverwriteEachOther() throws Exception {
        Files.writeString(project.resolve("same.txt"), "base");
        var ready = new java.util.concurrent.CyclicBarrier(2);
        ScriptedClient first = new ScriptedClient(call("write_file", "{\"path\":\"same.txt\",\"content\":\"first\"}"), answer("done"));
        ScriptedClient second = new ScriptedClient(call("write_file", "{\"path\":\"same.txt\",\"content\":\"second\"}"), answer("done"));
        for (var client : List.of(first, second)) client.beforeResponse = () -> {
            if (client.requests.size() == 2) {
                try { ready.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (Exception e) { throw new IOException(e); }
            }
        };
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try (ToolRegistry registry = registry()) {
            var budget = new AgentBudget(1000, 3, 20);
            var a = executor.submit(() -> session(registry, first, budget).execute(
                    Map.of("role", "worker", "task", "first"), ToolExecutionContext.current("first")));
            var b = executor.submit(() -> session(registry, second, budget).execute(
                    Map.of("role", "worker", "task", "second"), ToolExecutionContext.current("second")));
            var results = List.of(a.get(10, java.util.concurrent.TimeUnit.SECONDS), b.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(ToolOutput::isSuccess).count(), results.toString());
            assertTrue(List.of("first", "second").contains(Files.readString(project.resolve("same.txt"))));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void unresolvedWriteFailureDoesNotPublishAnEarlierSuccessfulWrite() throws Exception {
        ScriptedClient child = new ScriptedClient(
                call("write_file", "{\"path\":\"partial.txt\",\"content\":\"draft\"}"),
                call("write_file", "{\"path\":\"../escape.txt\",\"content\":\"forbidden\"}"), answer("done"));
        try (ToolRegistry registry = registry()) {
            ToolOutput result = session(registry, child, new AgentBudget(1000, 3, 20)).execute(
                    Map.of("role", "worker", "task", "write files"), ToolExecutionContext.current("child"));
            assertFalse(result.isSuccess(), result.text());
            assertFalse(Files.exists(project.resolve("partial.txt")));
        }
    }

    @Test
    void repeatedToolCallsStopTheChildAndCannotBeResetByAChangedTaskLabel() throws Exception {
        Files.writeString(project.resolve("read.txt"), "value");
        ScriptedClient child = new ScriptedClient(java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> call("read_file", "{\"path\":\"read.txt\"}")).toArray(LlmClient.ChatResponse[]::new));
        try (ToolRegistry registry = registry()) {
            ToolOutput result = session(registry, child, new AgentBudget(1000, 3, 20)).execute(
                    Map.of("role", "explorer", "task", "investigate"), ToolExecutionContext.current("child"));
            assertFalse(result.isSuccess());
            assertTrue(child.requests.size() < 10);
        }
    }

    @Test
    void childCannotReadParentMemory() throws Exception {
        ScriptedClient child = new ScriptedClient(
                call("list_memory", "{}"), answer("done"));
        try (ToolRegistry registry = registry()) {
            registry.setMemoryListHandler(query -> "private parent memory");
            ToolOutput result = session(registry, child, new AgentBudget(1000, 3, 20)).execute(
                    Map.of("role", "reviewer", "task", "review selected context"), ToolExecutionContext.current("child"));
            assertTrue(result.isSuccess(), result.text());
            assertTrue(child.tools.getFirst().stream().noneMatch(t -> t.name().equals("list_memory")));
            assertFalse(child.requests.toString().contains("private parent memory"));
        }
    }

    @Test
    void childGetsOnlyAssignedContextAndCannotWriteOrDelegate() throws Exception {
        ScriptedClient child = new ScriptedClient(
                call("write_file", "{\"path\":\"forbidden.txt\",\"content\":\"no\"}"),
                call("delegate_task", "{\"role\":\"explorer\",\"task\":\"nested\"}"), answer("investigation done"));
        try (ToolRegistry registry = registry()) {
            AgentBudget budget = new AgentBudget(1000, 3, 20);
            ToolOutput output = session(registry, child, budget).execute(
                    Map.of("role", "explorer", "task", "inspect", "context", "selected context"),
                    ToolExecutionContext.current("child"));
            assertTrue(output.isSuccess(), output.text());
            assertFalse(Files.exists(project.resolve("forbidden.txt")));
            assertEquals(2, child.requests.getFirst().size());
            assertTrue(child.requests.getFirst().getFirst().content().contains("project rules"));
            assertTrue(child.requests.getFirst().get(1).content().contains("selected context"));
            assertTrue(child.tools.stream().flatMap(List::stream)
                    .noneMatch(t -> List.of("write_file", "delegate_task", "execute_command").contains(t.name())));
            assertTrue(output.text().contains("CAPABILITY_DENIED"));
            assertEquals(36, budget.totalInputTokens() + budget.totalOutputTokens());
        }
    }

    @Test
    void workerFinishesItsLoopBeforePatchIsApplied() throws Exception {
        ScriptedClient child = new ScriptedClient(
                call("write_file", "{\"path\":\"result.txt\",\"content\":\"first\"}"),
                call("read_file", "{\"path\":\"result.txt\"}"),
                call("write_file", "{\"path\":\"result.txt\",\"content\":\"verified\"}"), answer("done"));
        child.beforeResponse = () -> assertFalse(Files.exists(project.resolve("result.txt")));
        try (ToolRegistry registry = registry()) {
            ToolOutput output = session(registry, child, new AgentBudget(1000, 3, 20)).execute(
                    Map.of("role", "worker", "task", "create and verify result.txt"),
                    ToolExecutionContext.current("child"));
            assertTrue(output.isSuccess(), output.text());
            assertEquals(4, child.requests.size());
            assertEquals("verified", Files.readString(project.resolve("result.txt")), output.text());
            assertEquals(List.of("result.txt"), output.modifiedResources());
        }
    }

    @Test
    void failedOrCancelledChildNeverAppliesItsWrites() throws Exception {
        for (boolean cancel : List.of(false, true)) {
            ScriptedClient child = new ScriptedClient(
                    call("write_file", "{\"path\":\"unapproved.txt\",\"content\":\"draft\"}"), answer("done"));
            try (ToolRegistry registry = registry(); RunContext run = CancellationContext.startRunContext(project)) {
                child.beforeResponse = () -> {
                    if (child.requests.size() == 2) {
                        if (cancel) run.cancel();
                        else throw new IOException("provider failure");
                    }
                };
                ToolOutput output = session(registry, child, new AgentBudget(1000, 3, 20)).execute(
                        Map.of("role", "worker", "task", "write draft"), ToolExecutionContext.current("child"));
                assertFalse(output.isSuccess(), output.text());
                assertFalse(Files.exists(project.resolve("unapproved.txt")));
            }
        }
    }

    @Test
    void stalePatchCannotOverwriteAConcurrentMainWorkspaceEdit() throws Exception {
        Files.writeString(project.resolve("same.txt"), "base");
        ScriptedClient child = new ScriptedClient(
                call("read_file", "{\"path\":\"same.txt\"}"),
                call("write_file", "{\"path\":\"same.txt\",\"content\":\"child\"}"), answer("done"));
        child.beforeResponse = () -> {
            if (child.requests.size() == 3) Files.writeString(project.resolve("same.txt"), "main");
        };
        try (ToolRegistry registry = registry()) {
            ToolOutput result = session(registry, child, new AgentBudget(1000, 3, 20)).execute(
                    Map.of("role", "worker", "task", "update same.txt"), ToolExecutionContext.current("child"));
            assertFalse(result.isSuccess());
            assertEquals("main", Files.readString(project.resolve("same.txt")));
        }
    }

    @Test
    void exhaustedParentBudgetCannotBeResetBySpawningAnotherChild() throws Exception {
        AgentBudget budget = new AgentBudget(20, 3, 20);
        ScriptedClient child = new ScriptedClient(answer("one"), answer("two"), answer("must not call"));
        try (ToolRegistry registry = registry()) {
            var session = session(registry, child, budget);
            for (int i = 0; i < 2; i++) session.execute(Map.of("role", "planner", "task", "plan"),
                    ToolExecutionContext.current("child-" + i));
            ToolOutput output = session.execute(Map.of("role", "planner", "task", "again"),
                    ToolExecutionContext.current("child-3"));
            assertFalse(output.isSuccess());
            assertEquals(2, child.requests.size());
            assertEquals(AgentBudget.ExitReason.TOKEN_BUDGET_EXCEEDED, budget.check());
        }
    }

    @Test
    void childrenShareTotalRoundsButHaveIndependentStagnationWindows() {
        AgentBudget parent = new AgentBudget(1000, 3, 3);
        AgentBudget first = parent.fork();
        AgentBudget second = parent.fork();
        first.beginIteration();
        second.beginIteration();
        parent.beginIteration();
        assertEquals(AgentBudget.ExitReason.HARD_ITERATION_LIMIT, first.check());
        assertEquals(AgentBudget.ExitReason.HARD_ITERATION_LIMIT, second.check());
        assertEquals(1, parent.iteration());
    }

    private DelegationSession session(ToolRegistry registry, LlmClient client, AgentBudget budget) {
        return new DelegationSession(registry, role -> client, budget, "project rules", RunEventSink.NO_OP);
    }

    private ToolRegistry registry() {
        ToolRegistry registry = new NoIndexRegistry();
        registry.setProjectPath(project.toString());
        return registry;
    }

    // 本组只验证文件隔离与提交；索引数据库不在测试范围。
    private static final class NoIndexRegistry extends ToolRegistry {
        NoIndexRegistry() { super(); }
        NoIndexRegistry(ResourceLeaseMaintenance maintenance) { super(maintenance); }
        @Override public void markRagIndexDirty(Collection<String> paths) { }
        @Override protected ToolRegistry createProjectForkRegistry(ResourceLeaseMaintenance maintenance) {
            return new NoIndexRegistry(maintenance);
        }
    }

    static LlmClient.ChatResponse answer(String text) {
        return new LlmClient.ChatResponse("assistant", text, null, null, 10, 2);
    }
    static LlmClient.ChatResponse call(String tool, String args) {
        return new LlmClient.ChatResponse("assistant", "", null,
                List.of(new LlmClient.ToolCall("call-" + tool, new LlmClient.ToolCall.Function(tool, args))), 10, 2);
    }
    @FunctionalInterface interface BeforeResponse { void run() throws IOException; }
    static final class ScriptedClient implements LlmClient {
        final ArrayDeque<ChatResponse> responses;
        final List<List<Message>> requests = new ArrayList<>();
        final List<List<Tool>> tools = new ArrayList<>();
        BeforeResponse beforeResponse = () -> { };
        ScriptedClient(ChatResponse... responses) { this.responses = new ArrayDeque<>(List.of(responses)); }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> toolDefinitions, StreamListener listener) throws IOException {
            requests.add(List.copyOf(messages));
            tools.add(List.copyOf(toolDefinitions));
            beforeResponse.run();
            if (responses.isEmpty()) throw new IOException("unexpected model call");
            return responses.removeFirst();
        }
        @Override public String getModelName() { return "stub"; }
        @Override public String getProviderName() { return "test"; }
    }
}
