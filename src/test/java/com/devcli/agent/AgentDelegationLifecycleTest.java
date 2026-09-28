package com.devcli.agent;

import com.devcli.hitl.ApprovalRequest;
import com.devcli.hitl.ApprovalResult;
import com.devcli.hitl.HitlHandler;
import com.devcli.hitl.HitlToolRegistry;
import com.devcli.llm.OpenAiClient;
import com.devcli.memory.LongTermMemory;
import com.devcli.memory.MemoryManager;
import com.devcli.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** 定向测试：Agent 入口 → HTTP 模型协议 → 委派 → 实际工具/隔离工作区 → 结果归并。 */
class AgentDelegationLifecycleTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;
    private String previousDirectory;
    private String previousSandbox;
    private String previousTimeout;

    @BeforeEach void configure() {
        previousDirectory = System.getProperty("devcli.delegation.dir");
        previousSandbox = System.getProperty("devcli.command.sandbox.mode");
        previousTimeout = System.getProperty("devcli.delegate.timeout.seconds");
        System.setProperty("devcli.delegation.dir", temp.resolve("records").toString());
    }

    @AfterEach void restore() {
        restoreProperty("devcli.delegation.dir", previousDirectory);
        restoreProperty("devcli.command.sandbox.mode", previousSandbox);
        restoreProperty("devcli.delegate.timeout.seconds", previousTimeout);
    }

    @Test void reportRemainsAvailableInLaterTurns() throws Exception {
        Path project = Files.createDirectory(temp.resolve("project"));
        try (Fixture fixture = new Fixture(); ToolRegistry registry = new ToolRegistry()) {
            fixture.parent(request -> fixture.delegate(brief("planner", "first task", List.of())));
            fixture.parent(request -> fixture.answer("first parent result"));
            fixture.parent(request -> {
                ObjectNode requestArgs = brief("planner", "second task", List.of());
                requestArgs.put("upstream_report_id", fixture.reportId());
                return fixture.delegate(requestArgs);
            });
            fixture.parent(request -> fixture.answer("second parent result"));
            fixture.child(request -> fixture.answer("original bounded findings"));
            fixture.child(request -> fixture.answer("consumer result"));
            try (Agent agent = agent(fixture, registry, project)) {
                agent.run("private parent first instruction");
                agent.run("Continue using the earlier report");
                String result = agent.conversationHistorySnapshot().getLast().content();
                assertTrue(result.contains("second parent result"), "result=" + result + "; report=" + fixture.lastReport);
                assertEquals(2, fixture.childRequests.size());
                assertTrue(fixture.childRequests.get(1).toString().contains("original bounded findings"));
                assertFalse(fixture.childRequests.getFirst().toString().contains("private parent first instruction"));
                assertEquals("UNTRUSTED", fixture.lastReport.path("report_security").path("content_trust").asText());
            }
        }
    }

    @Test void mixedBatchPreservesFileWriteAndReadOrder() throws Exception {
        Path project = Files.createDirectory(temp.resolve("project"));
        Files.writeString(project.resolve("state.txt"), "original");
        try (Fixture fixture = new Fixture(); ToolRegistry registry = new NoIndexRegistry()) {
            registry.setWriteFileObserver((path, contents) -> {
                if ("first".equals(contents[1])) {
                    try { Thread.sleep(200); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            });
            fixture.parent(request -> fixture.batch(
                    "write_file", JSON.valueToTree(Map.of("path", "state.txt", "content", "first")),
                    "read_file", JSON.valueToTree(Map.of("path", "state.txt")),
                    "write_file", JSON.valueToTree(Map.of("path", "state.txt", "content", "second")),
                    "read_file", JSON.valueToTree(Map.of("path", "state.txt"))));
            fixture.parent(request -> fixture.answer("verified order"));
            try (Agent agent = agent(fixture, registry, project)) {
                agent.run("Update state.txt and verify each write");
                List<JsonNode> results = toolMessages(fixture.parentRequests.getLast());
                assertEquals(4, results.size());
                assertTrue(results.get(1).path("content").asText().contains("first"), results.toString());
                assertTrue(results.get(3).path("content").asText().contains("second"), results.toString());
                assertEquals("second", Files.readString(project.resolve("state.txt")));
                assertEquals(List.of("call-1", "call-2", "call-3", "call-4"), results.stream()
                        .map(result -> result.path("tool_call_id").asText()).toList());
            }
        }
    }

    @Test void consecutiveReadsRemainParallel() throws Exception {
        Path project = Files.createDirectory(temp.resolve("project"));
        CyclicBarrier ready = new CyclicBarrier(2);
        try (Fixture fixture = new Fixture(); ToolRegistry registry = new NoIndexRegistry()) {
            for (String name : List.of("parallel_left", "parallel_right")) {
                registry.registerTool(new ToolRegistry.Tool(name, "Read " + name,
                    JSON.readTree("{\"type\":\"object\"}"), args -> {
                    try { ready.await(3, TimeUnit.SECONDS); }
                    catch (Exception e) { throw new IllegalStateException(e); }
                    return "completed " + name;
                }, ToolRegistry.ToolEffect.READ_ONLY));
            }
            fixture.parent(request -> fixture.batch("parallel_left", JSON.createObjectNode(),
                    "parallel_right", JSON.createObjectNode()));
            fixture.parent(request -> fixture.answer("parallel reads finished"));
            try (Agent agent = agent(fixture, registry, project)) {
                agent.run("Use parallel_left and parallel_right");
                List<JsonNode> results = toolMessages(fixture.parentRequests.getLast());
                assertTrue(results.get(0).path("content").asText().contains("completed parallel_left"));
                assertTrue(results.get(1).path("content").asText().contains("completed parallel_right"));
            }
        }
    }

    @Test void localContextChangeCompletesBeforeFollowingRead() throws Exception {
        Path project = Files.createDirectory(temp.resolve("project"));
        AtomicInteger revision = new AtomicInteger();
        try (Fixture fixture = new Fixture(); ToolRegistry registry = new NoIndexRegistry()) {
            registry.registerTool(new ToolRegistry.Tool("update_revision", "Update revision",
                    JSON.readTree("{\"type\":\"object\"}"), args -> {
                try { Thread.sleep(200); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return Integer.toString(revision.incrementAndGet());
            }, ToolRegistry.ToolEffect.LOCAL_CONTEXT));
            registry.registerTool(new ToolRegistry.Tool("read_revision", "Read revision",
                    JSON.readTree("{\"type\":\"object\"}"), args -> Integer.toString(revision.get()),
                    ToolRegistry.ToolEffect.READ_ONLY));
            fixture.parent(request -> fixture.batch("update_revision", JSON.createObjectNode(),
                    "read_revision", JSON.createObjectNode()));
            fixture.parent(request -> fixture.answer("revision verified"));
            try (Agent agent = agent(fixture, registry, project)) {
                agent.run("Use update_revision then read_revision");
                assertEquals("1", toolMessages(fixture.parentRequests.getLast()).get(1).path("content").asText());
            }
        }
    }

    @Test void parallelDelegationFinishesBeforeParentReadsMergedPatch() throws Exception {
        Path project = Files.createDirectory(temp.resolve("project"));
        CyclicBarrier ready = new CyclicBarrier(2);
        try (Fixture fixture = new Fixture(); ToolRegistry registry = new NoIndexRegistry()) {
            fixture.parent(request -> fixture.batch(
                    "delegate_task", brief("worker", "Write first.txt", List.of("first.txt")),
                    "delegate_task", brief("planner", "Inspect project structure", List.of()),
                    "read_file", JSON.valueToTree(Map.of("path", "first.txt"))));
            fixture.parent(request -> fixture.answer("merged result verified"));
            Function<JsonNode, MockResponse> child = request -> {
                try { ready.await(3, TimeUnit.SECONDS); }
                catch (Exception e) { throw new IllegalStateException(e); }
                return request.toString().contains("Write first.txt")
                        ? fixture.tool("write_file", JSON.valueToTree(Map.of("path", "first.txt", "content", "child result")))
                        : fixture.answer("structure inspected");
            };
            fixture.child(child);
            fixture.child(child);
            fixture.child(request -> fixture.answer("worker finished"));
            try (Agent agent = agent(fixture, registry, project)) {
                agent.run("Delegate the write and planning, then read first.txt");
                assertEquals("child result", Files.readString(project.resolve("first.txt")));
                List<JsonNode> results = toolMessages(fixture.parentRequests.getLast());
                assertEquals(3, results.size());
                assertTrue(results.get(2).path("content").asText().contains("child result"), results.toString());
                assertEquals("APPLIED", JSON.readTree(results.get(0).path("content").asText()).path("patch_status").asText());
            }
        }
    }

    @Test void cancelledBatchPreventsLaterWrite() throws Exception {
        Path project = Files.createDirectory(temp.resolve("project"));
        CountDownLatch started = new CountDownLatch(1);
        try (Fixture fixture = new Fixture(); ToolRegistry registry = new NoIndexRegistry()) {
            registry.registerTool(new ToolRegistry.Tool("wait_probe", "Wait for observation",
                    JSON.readTree("{\"type\":\"object\"}"), args -> {
                started.countDown();
                try { Thread.sleep(2_000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return "wait ended";
            }, ToolRegistry.ToolEffect.READ_ONLY));
            fixture.parent(request -> fixture.batch("wait_probe", JSON.createObjectNode(),
                    "write_file", JSON.valueToTree(Map.of("path", "late.txt", "content", "must not be written"))));
            try (Agent agent = agent(fixture, registry, project)) {
                var events = new CopyOnWriteArrayList<com.devcli.event.RunEvent>();
                agent.setRunEventSink(events::add);
                var cancellation = java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try { assertTrue(started.await(3, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { throw new IllegalStateException(e); }
                    agent.abort();
                });
                try (var run = com.devcli.concurrent.CancellationContext.startRunContext(project)) {
                    agent.run("Wait, then write late.txt");
                }
                cancellation.get(3, TimeUnit.SECONDS);
                assertFalse(Files.exists(project.resolve("late.txt")));
                var results = events.stream().filter(com.devcli.event.RunEvent.ToolResults.class::isInstance)
                        .map(com.devcli.event.RunEvent.ToolResults.class::cast).findFirst().orElseThrow().results();
                assertEquals(2, results.size());
                assertTrue(results.stream().allMatch(result -> "CANCELLED".equals(result.status())), results.toString());
                assertEquals(1, fixture.parentRequests.size());
            }
        }
    }

    @Test void parentDiscoveryIsNotInheritedByDelegatedPlanner() throws Exception {
        Path project = Files.createDirectory(temp.resolve("project"));
        try (Fixture fixture = new Fixture(); ToolRegistry registry = new NoIndexRegistry()) {
            for (int i = 0; i < 20; i++) {
                registry.registerTool(new ToolRegistry.Tool("lookup_" + i, "Read lookup_" + i,
                        JSON.readTree("{\"type\":\"object\"}"), args -> "value", ToolRegistry.ToolEffect.READ_ONLY));
            }
            registry.registerTool(new ToolRegistry.Tool("zz_journal", "Read journal",
                    JSON.readTree("{\"type\":\"object\"}"), args -> "journal", ToolRegistry.ToolEffect.READ_ONLY));
            fixture.parent(request -> fixture.tool("search_tools", JSON.valueToTree(Map.of("query", "zz_journal"))));
            fixture.parent(request -> fixture.delegate(brief("planner", "Inspect lookup_9", List.of())));
            fixture.parent(request -> fixture.answer("delegation checked"));
            fixture.child(request -> fixture.answer("planning complete"));
            try (Agent agent = agent(fixture, registry, project)) {
                agent.run("lookup_9");
                assertTrue(toolNames(fixture.parentRequests.getLast()).contains("zz_journal"));
                assertEquals(1, fixture.childRequests.size());
                var childNames = toolNames(fixture.childRequests.getFirst());
                assertFalse(childNames.contains("zz_journal"), childNames.toString());
                assertTrue(childNames.contains("read_file"), childNames.toString());
                assertTrue(java.util.Collections.disjoint(childNames,
                        List.of("delegate_task", "delegate_control", "save_memory", "list_memory", "write_file")));
            }
        }
    }

    @Test void failedWorkerPatchAndHistorySurviveNewAgentInstance() throws Exception {
        Path project = Files.createDirectory(temp.resolve("project"));
        try (Fixture fixture = new Fixture(); ToolRegistry registry = new ToolRegistry()) {
            fixture.parent(request -> {
                ObjectNode args = brief("worker", "write draft", List.of("draft.txt"));
                args.putObject("budget").put("max_iterations", 1);
                return fixture.delegate(args);
            });
            fixture.parent(request -> fixture.answer("first attempt stopped"));
            fixture.child(request -> fixture.tool("write_file", JSON.valueToTree(Map.of(
                    "path", "draft.txt", "content", "retained draft"))));
            try (Agent first = agent(fixture, registry, project)) {
                first.run("Create the draft");
                assertFalse(Files.exists(project.resolve("draft.txt")));
                assertTrue(fixture.lastReport.path("resume_available").asBoolean(), fixture.lastReport.toString());
                assertEquals(1, fixture.lastReport.path("pending_patch_files").asInt());
            }
            String childId = fixture.reportId();
            fixture.parent(request -> {
                ObjectNode args = brief("worker", "verify the saved draft and finish", List.of("draft.txt"));
                args.put("resume_report_id", childId);
                return fixture.delegate(args);
            });
            fixture.parent(request -> fixture.answer("resumed parent result"));
            fixture.child(request -> fixture.tool("read_file", JSON.valueToTree(Map.of("path", "draft.txt"))));
            fixture.child(request -> fixture.answer("draft verified"));
            try (Agent second = agent(fixture, registry, project)) {
                second.run("Continue the previous child");
                String result = second.conversationHistorySnapshot().getLast().content();
                assertTrue(result.contains("resumed parent result"), "result=" + result + "; report=" + fixture.lastReport);
                assertEquals("retained draft", Files.readString(project.resolve("draft.txt")));
                assertEquals(childId, fixture.reportId());
                assertEquals("APPLIED", fixture.lastReport.path("patch_status").asText());
                assertEquals(0, fixture.lastReport.path("pending_patch_files").asInt());
                assertTrue(fixture.childRequests.get(1).toString().contains("write draft"));
            }
        }
    }

    @Test void changedBaselineRejectsRecoveryBeforeCallingChildModel() throws Exception {
        Path project = Files.createDirectory(temp.resolve("project"));
        Files.writeString(project.resolve("draft.txt"), "original");
        try (Fixture fixture = new Fixture(); ToolRegistry registry = new ToolRegistry()) {
            fixture.parent(request -> {
                ObjectNode args = brief("worker", "prepare edit", List.of("draft.txt"));
                args.putObject("budget").put("max_iterations", 1);
                return fixture.delegate(args);
            });
            fixture.parent(request -> fixture.answer("draft retained"));
            fixture.child(request -> fixture.tool("write_file", JSON.valueToTree(Map.of(
                    "path", "draft.txt", "content", "child edit"))));
            try (Agent agent = agent(fixture, registry, project)) {
                agent.run("Prepare an edit");
                String childId = fixture.reportId();
                Files.writeString(project.resolve("draft.txt"), "new user edit");
                fixture.parent(request -> {
                    ObjectNode args = brief("worker", "continue edit", List.of("draft.txt"));
                    args.put("resume_report_id", childId);
                    return fixture.delegate(args);
                });
                fixture.parent(request -> fixture.answer("conflict handled"));
                agent.run("Resume the edit");
                assertEquals("new user edit", Files.readString(project.resolve("draft.txt")));
                assertEquals(1, fixture.childRequests.size());
                assertTrue(fixture.lastReport.path("summary").asText().contains("基线冲突"), fixture.lastReport.toString());
            }
        }
    }

    @Test void resumeCannotExpandWriteScope() throws Exception {
        Path project = Files.createDirectory(temp.resolve("project"));
        try (Fixture fixture = new Fixture(); ToolRegistry registry = new ToolRegistry()) {
            fixture.parent(request -> fixture.delegate(brief("worker", "finish original", List.of("draft.txt"))));
            fixture.parent(request -> fixture.answer("first result"));
            fixture.child(request -> fixture.answer("no changes needed"));
            try (Agent agent = agent(fixture, registry, project)) {
                agent.run("Complete the original task");
                String childId = fixture.reportId();
                fixture.parent(request -> {
                    ObjectNode args = brief("worker", "expand task", List.of("draft.txt", "other.txt"));
                    args.put("resume_report_id", childId);
                    return fixture.delegate(args);
                });
                fixture.parent(request -> {
                    assertTrue(request.toString().contains("续接不能更换角色或扩大"));
                    return fixture.answer("scope refused");
                });
                agent.run("Continue the child");
                assertEquals(1, fixture.childRequests.size());
                assertFalse(Files.exists(project.resolve("other.txt")));
            }
        }
    }

    @Test void resumedFailuresDoNotBecomeSuccessfulEvidence() throws Exception {
        Path project = Files.createDirectory(temp.resolve("project"));
        try (Fixture fixture = new Fixture(); ToolRegistry registry = new ToolRegistry()) {
            fixture.parent(request -> {
                ObjectNode args = brief("explorer", "inspect missing file", List.of());
                args.putObject("budget").put("max_iterations", 1);
                return fixture.delegate(args);
            });
            fixture.parent(request -> fixture.answer("failure retained"));
            fixture.child(request -> fixture.tool("read_file", JSON.valueToTree(Map.of("path", "missing.txt"))));
            try (Agent agent = agent(fixture, registry, project)) {
                agent.run("Inspect the missing file");
                String childId = fixture.reportId();
                assertEquals("NONE", fixture.lastReport.path("knowledge_outcome").asText());
                fixture.parent(request -> {
                    ObjectNode args = brief("explorer", "report the unresolved inspection", List.of());
                    args.put("resume_report_id", childId);
                    return fixture.delegate(args);
                });
                fixture.parent(request -> fixture.answer("unresolved inspection reported"));
                fixture.child(request -> fixture.answer("No successful observation is available"));
                agent.run("Continue the inspection report");
                assertEquals(2, fixture.childRequests.size());
                assertEquals("done", fixture.lastReport.path("status").asText());
                assertEquals("NONE", fixture.lastReport.path("knowledge_outcome").asText(), fixture.lastReport.toString());
                assertEquals(1, fixture.lastReport.path("dead_ends").size());
            }
        }
    }

    @Test void backgroundChildCanBeWaitedAcrossTurnsWithoutBlockingParent() throws Exception {
        Path project = Files.createDirectory(temp.resolve("project"));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        try (Fixture fixture = new Fixture(); ToolRegistry registry = new ToolRegistry()) {
            fixture.parent(request -> {
                ObjectNode args = brief("explorer", "background inspection", List.of());
                args.put("run_in_background", true);
                return fixture.delegate(args);
            });
            fixture.parent(request -> fixture.answer("parent is available"));
            fixture.child(request -> {
                started.countDown();
                try { assertTrue(finish.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return fixture.answer("background findings");
            });
            try (Agent agent = agent(fixture, registry, project)) {
                agent.run("Inspect in the background");
                assertTrue(agent.conversationHistorySnapshot().getLast().content().contains("parent is available"));
                assertTrue(started.await(3, TimeUnit.SECONDS));
                assertEquals("running", fixture.lastReport.path("status").asText());
                assertTrue(fixture.parentRequests.getFirst().path("tools").toString().contains("delegate_control"));
                finish.countDown();
                fixture.parent(request -> fixture.control("wait"));
                fixture.parent(request -> fixture.answer("background report collected"));
                agent.run("Wait for the child report");
                assertEquals("done", fixture.lastReport.path("status").asText(), fixture.lastReport.toString());
                assertEquals("background findings", fixture.lastReport.path("summary").asText());
                assertTrue(fixture.parentRequests.getLast().toString().contains("后台委派任务已结束"));
            } finally { finish.countDown(); }
        }
    }

    @Test void backgroundCancellationPropagatesToActualHttpRequest() throws Exception {
        Path project = Files.createDirectory(temp.resolve("project"));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        try (Fixture fixture = new Fixture(); ToolRegistry registry = new ToolRegistry()) {
            fixture.parent(request -> {
                ObjectNode args = brief("explorer", "cancel this background inspection", List.of());
                args.put("run_in_background", true);
                return fixture.delegate(args);
            });
            fixture.parent(request -> fixture.answer("parent continues"));
            fixture.child(request -> {
                started.countDown();
                try { finish.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return fixture.answer("late answer must not complete child");
            });
            try (Agent agent = agent(fixture, registry, project)) {
                agent.run("Start a cancellable child");
                assertTrue(started.await(3, TimeUnit.SECONDS));
                fixture.parent(request -> fixture.control("cancel"));
                fixture.parent(request -> fixture.control("wait"));
                fixture.parent(request -> fixture.answer("cancelled result collected"));
                agent.run("Cancel the child");
                assertEquals("cancelled", fixture.lastReport.path("status").asText(), fixture.lastReport.toString());
                assertEquals("CANCELLED", fixture.lastReport.path("error_code").asText());
            } finally { finish.countDown(); }
        }
    }

    @Test void backgroundWorkerCannotOverwriteParentEdit() throws Exception {
        Path project = Files.createDirectory(temp.resolve("project"));
        Files.writeString(project.resolve("draft.txt"), "original");
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        try (Fixture fixture = new Fixture(); ToolRegistry registry = new ToolRegistry()) {
            fixture.parent(request -> {
                ObjectNode args = brief("worker", "edit draft in background", List.of("draft.txt"));
                args.put("run_in_background", true);
                return fixture.delegate(args);
            });
            fixture.parent(request -> fixture.answer("parent is available"));
            fixture.child(request -> fixture.tool("write_file", JSON.valueToTree(Map.of(
                    "path", "draft.txt", "content", "child edit"))));
            fixture.child(request -> {
                ready.countDown();
                try { finish.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return fixture.answer("draft finished");
            });
            try (Agent agent = agent(fixture, registry, project)) {
                agent.run("Start a background edit");
                assertTrue(ready.await(3, TimeUnit.SECONDS));
                fixture.parent(request -> fixture.tool("write_file", JSON.valueToTree(Map.of(
                        "path", "draft.txt", "content", "parent edit"))));
                fixture.parent(request -> {
                    finish.countDown();
                    return fixture.control("wait");
                });
                fixture.parent(request -> fixture.answer("conflict collected"));
                agent.run("Apply my newer draft and collect the background result");
                assertEquals("parent edit", Files.readString(project.resolve("draft.txt")));
                assertEquals("failed", fixture.lastReport.path("status").asText(), fixture.lastReport.toString());
                assertEquals("NOT_APPLIED", fixture.lastReport.path("patch_status").asText());
                assertEquals(1, fixture.lastReport.path("pending_patch_files").asInt());
            } finally { finish.countDown(); }
        }
    }

    @Test void backgroundDeadlineCancelsActualHttpRequest() throws Exception {
        System.setProperty("devcli.delegate.timeout.seconds", "1");
        Path project = Files.createDirectory(temp.resolve("project"));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        try (Fixture fixture = new Fixture(); ToolRegistry registry = new ToolRegistry()) {
            fixture.parent(request -> {
                ObjectNode args = brief("explorer", "time limited inspection", List.of());
                args.put("run_in_background", true);
                return fixture.delegate(args);
            });
            fixture.parent(request -> fixture.answer("parent continues"));
            fixture.child(request -> {
                started.countDown();
                try { finish.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return fixture.answer("late answer must not complete child");
            });
            try (Agent agent = agent(fixture, registry, project)) {
                agent.run("Start a child with a deadline");
                assertTrue(started.await(3, TimeUnit.SECONDS));
                fixture.parent(request -> fixture.control("wait"));
                fixture.parent(request -> fixture.answer("timeout collected"));
                agent.run("Collect the result");
                assertEquals("timed_out", fixture.lastReport.path("status").asText(), fixture.lastReport.toString());
                assertEquals("TIMEOUT", fixture.lastReport.path("error_code").asText());
            } finally { finish.countDown(); }
        }
    }

    @Test void reportCannotBeReadFromAnotherProject() throws Exception {
        Path project = Files.createDirectory(temp.resolve("project"));
        Path otherProject = Files.createDirectory(temp.resolve("other-project"));
        try (Fixture fixture = new Fixture(); ToolRegistry registry = new ToolRegistry()) {
            fixture.parent(request -> fixture.delegate(brief("planner", "private project report", List.of())));
            fixture.parent(request -> fixture.answer("original result"));
            fixture.child(request -> fixture.answer("project scoped findings"));
            try (Agent agent = agent(fixture, registry, project)) {
                agent.run("Create a project report");
            }
            fixture.parent(request -> fixture.control("status"));
            fixture.parent(request -> fixture.answer("unknown report handled"));
            try (Agent other = agent(fixture, registry, otherProject)) {
                other.run("Query a report from another project");
                assertTrue(fixture.parentRequests.getLast().path("messages").toString().contains("委派报告不存在"));
                assertEquals(1, fixture.childRequests.size());
            }
        }
    }

    @Test void javaPatchMustPassRealCompilationBeforeMerge() throws Exception {
        verifyJavaPatch("public class Hello { public static int value() { return 7; } }", true);
    }

    @Test void brokenJavaPatchIsRetainedWithoutMerge() throws Exception {
        verifyJavaPatch("public class Hello { broken syntax }", false);
    }

    private void verifyJavaPatch(String code, boolean expectedApplied) throws Exception {
        System.setProperty("devcli.command.sandbox.mode", "HOST_WARN");
        Path project = Files.createDirectory(temp.resolve("project"));
        AtomicInteger approvals = new AtomicInteger();
        HitlHandler handler = new HitlHandler() {
            @Override public ApprovalResult requestApproval(ApprovalRequest request) {
                approvals.incrementAndGet();
                return ApprovalResult.approve();
            }
            @Override public boolean isEnabled() { return true; }
            @Override public void setEnabled(boolean enabled) { }
        };
        try (Fixture fixture = new Fixture(); ToolRegistry registry = new HitlToolRegistry(handler)) {
            fixture.parent(request -> fixture.delegate(brief("worker", "implement Hello", List.of("src/main/java/Hello.java"))));
            fixture.parent(request -> fixture.answer("parent checked result"));
            fixture.child(request -> fixture.tool("write_file", JSON.valueToTree(Map.of(
                    "path", "src/main/java/Hello.java", "content", code))));
            fixture.child(request -> fixture.answer("implementation finished"));
            try (Agent agent = agent(fixture, registry, project)) {
                agent.run("Implement and verify Hello");
                assertEquals(expectedApplied, Files.exists(project.resolve("src/main/java/Hello.java")));
                assertEquals(expectedApplied ? "PASSED" : "FAILED", fixture.lastReport.path("hard_check").asText(),
                        fixture.lastReport.toString());
                assertEquals(expectedApplied ? "APPLIED" : "NOT_APPLIED", fixture.lastReport.path("patch_status").asText());
                assertTrue(approvals.get() >= 2, "文件写入与主机编译均须单次批准");
                if (!expectedApplied) assertEquals(1, fixture.lastReport.path("pending_patch_files").asInt());
            }
        }
    }

    private Agent agent(Fixture fixture, ToolRegistry registry, Path project) {
        registry.setProjectPath(project.toString());
        OpenAiClient client = new OpenAiClient("local-fixture", "delegation-fixture", fixture.server.url("/v1").toString());
        MemoryManager memory = new MemoryManager(client, 1000, 128000,
                new LongTermMemory(temp.resolve("memory")));
        return new Agent(client, registry, memory);
    }

    private static List<JsonNode> toolMessages(JsonNode request) {
        return java.util.stream.StreamSupport.stream(request.path("messages").spliterator(), false)
                .filter(message -> "tool".equals(message.path("role").asText())).toList();
    }

    private static List<String> toolNames(JsonNode request) {
        return java.util.stream.StreamSupport.stream(request.path("tools").spliterator(), false)
                .map(tool -> tool.path("function").path("name").asText()).toList();
    }

    private static final class NoIndexRegistry extends ToolRegistry {
        NoIndexRegistry() { super(); }
        NoIndexRegistry(com.devcli.tool.ResourceLeaseMaintenance maintenance) { super(maintenance); }
        @Override public void markRagIndexDirty(java.util.Collection<String> paths) { }
        @Override protected ToolRegistry createProjectForkRegistry(com.devcli.tool.ResourceLeaseMaintenance maintenance) {
            return new NoIndexRegistry(maintenance);
        }
    }

    private static ObjectNode brief(String role, String task, List<String> paths) {
        ObjectNode args = JSON.createObjectNode().put("role", role).put("task", task).put("deliverable", "Verified task result");
        args.putObject("task_spec").put("execution_kind", "agent_loop").put("inputs", "Provided task and project files")
                .put("scope", "Only declared resources").put("done_condition", "Return result and evidence");
        if (!paths.isEmpty()) args.set("allowed_write_paths", JSON.valueToTree(paths));
        return args;
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) System.clearProperty(name); else System.setProperty(name, value);
    }

    private static final class Fixture implements AutoCloseable {
        final MockWebServer server = new MockWebServer();
        final List<JsonNode> childRequests = new CopyOnWriteArrayList<>();
        final List<JsonNode> parentRequests = new CopyOnWriteArrayList<>();
        final ArrayDeque<Function<JsonNode, MockResponse>> parent = new ArrayDeque<>();
        final ArrayDeque<Function<JsonNode, MockResponse>> child = new ArrayDeque<>();
        final AtomicInteger calls = new AtomicInteger();
        volatile JsonNode lastReport = JSON.createObjectNode();

        Fixture() throws Exception {
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    try {
                        JsonNode body = JSON.readTree(request.getBody().readUtf8());
                        boolean delegated = body.path("messages").get(0).path("content").asText()
                                .contains("你是受主 Agent 委派的子 Agent。");
                        Function<JsonNode, MockResponse> response;
                        synchronized (Fixture.this) {
                            (delegated ? childRequests : parentRequests).add(body);
                            if (!delegated) captureReport(body);
                            response = (delegated ? child : parent).pollFirst();
                        }
                        if (response == null) return answer("unexpected request");
                        return response.apply(body);
                    } catch (Exception e) {
                        return new MockResponse().setResponseCode(400).setBody(e.toString());
                    }
                }
            });
            server.start();
        }

        synchronized void parent(Function<JsonNode, MockResponse> response) { parent.addLast(response); }
        synchronized void child(Function<JsonNode, MockResponse> response) { child.addLast(response); }
        String reportId() { return lastReport.path("report_id").asText(); }
        MockResponse delegate(ObjectNode args) { return tool("delegate_task", args); }
        MockResponse control(String action) {
            return tool("delegate_control", JSON.createObjectNode().put("action", action)
                    .put("report_id", reportId()).put("wait_seconds", 5));
        }
        MockResponse answer(String content) {
            return stream(JSON.createObjectNode().put("role", "assistant").put("content", content));
        }
        MockResponse tool(String name, JsonNode args) {
            ObjectNode delta = JSON.createObjectNode().put("role", "assistant");
            delta.putArray("tool_calls").addObject().put("index", 0).put("id", "call-" + calls.incrementAndGet())
                    .put("type", "function").putObject("function").put("name", name).put("arguments", args.toString());
            return stream(delta);
        }
        MockResponse batch(Object... entries) {
            ObjectNode delta = JSON.createObjectNode().put("role", "assistant");
            var batch = delta.putArray("tool_calls");
            for (int index = 0; index < entries.length / 2; index++) {
                batch.addObject().put("index", index).put("id", "call-" + calls.incrementAndGet())
                        .put("type", "function").putObject("function")
                        .put("name", (String) entries[index * 2]).put("arguments", entries[index * 2 + 1].toString());
            }
            return stream(delta);
        }
        MockResponse stream(ObjectNode delta) {
            ObjectNode response = JSON.createObjectNode();
            response.putArray("choices").addObject().put("index", 0).set("delta", delta);
            response.putObject("usage").put("prompt_tokens", 20).put("completion_tokens", 10);
            return new MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("data: " + response + "\n\ndata: [DONE]\n\n");
        }
        void captureReport(JsonNode request) {
            for (JsonNode message : request.path("messages")) {
                if (!"tool".equals(message.path("role").asText())) continue;
                String content = message.path("content").asText();
                int start = content.indexOf('{');
                if (start < 0) continue;
                try {
                    JsonNode report = JSON.readTree(content.substring(start));
                    if (report.has("report_id")) lastReport = report;
                } catch (Exception ignored) { }
            }
        }
        @Override public void close() throws Exception { server.close(); }
    }
}
