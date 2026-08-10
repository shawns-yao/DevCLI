package com.devcli.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.devcli.config.DevCliConfig;
import com.devcli.llm.LlmClient;
import com.devcli.llm.LlmClientFactory;
import com.devcli.runtime.api.RuntimeApiServer;
import com.devcli.runtime.api.RuntimeEvent;
import com.devcli.runtime.api.RuntimeSessionTurnRunner;
import com.devcli.runtime.api.RuntimeThreadStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RealLlmAgentConcurrencyBenchmarkIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String API_KEY = "concurrency-benchmark-local-key";
    private static final List<Double> PHASES = List.of(0.2, 0.5, 0.8);
    private static final int REPETITIONS = 3;

    @TempDir
    Path tempDir;

    @Test
    void rejectsLateEventsFromCancelledTurnsAcrossFortyFiveRealRuns() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("devcli.benchmark.concurrency"),
                "set -Ddevcli.benchmark.concurrency=true to run real concurrency benchmark");
        LlmClient llm = resolveLlmOrSkip();
        long baselineMillis = Long.getLong("devcli.benchmark.concurrency.baseline.millis", 5_000L);
        long timeoutMillis = Long.getLong("devcli.benchmark.concurrency.timeout.millis", 180_000L);
        String previousMemoryDir = System.getProperty("devcli.memory.dir");
        System.setProperty("devcli.memory.dir", tempDir.resolve("memory").toString());
        List<CaseResult> results = new ArrayList<>();

        try (RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("runtime.db"));
             RuntimeSessionTurnRunner runner = new RuntimeSessionTurnRunner(
                     llm, store, Path.of("").toAbsolutePath().normalize(), 0);
             RuntimeApiServer server = new RuntimeApiServer(store, runner, 0, API_KEY)) {
            server.start();
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            String base = "http://127.0.0.1:" + server.port();
            List<String> tasks = taskPrompts();
            int completed = 0;
            for (int taskIndex = 0; taskIndex < tasks.size(); taskIndex++) {
                for (double phase : PHASES) {
                    for (int repetition = 1; repetition <= REPETITIONS; repetition++) {
                        String threadId = post(http, base + "/v1/threads", "").path("id").asText();
                        String oldTurnId = post(http, base + "/v1/threads/" + threadId + "/turns",
                                JSON.createObjectNode().put("input", tasks.get(taskIndex)).toString())
                                .path("id").asText();
                        Thread.sleep(Math.max(100L, Math.round(baselineMillis * phase)));
                        boolean cancelled = post(http, base + "/v1/threads/" + threadId + "/cancel", "")
                                .path("cancelled").asBoolean(false);
                        String marker = "GENERATION-CONSISTENCY-" + (taskIndex + 1)
                                + "-" + Math.round(phase * 100) + "-" + repetition;
                        String newTurnId = post(http, base + "/v1/threads/" + threadId + "/turns",
                                JSON.createObjectNode().put("input",
                                        "纠偏：忽略旧任务，只回复 " + marker).toString())
                                .path("id").asText();
                        List<RuntimeEvent> events = awaitTerminal(
                                store, threadId, newTurnId, timeoutMillis);
                        CaseResult result = evaluate(taskIndex + 1, phase, repetition,
                                threadId, oldTurnId, newTurnId, marker, cancelled, events);
                        results.add(result);
                        completed++;
                        System.out.printf(Locale.ROOT, "\rConcurrency benchmark %d/%d",
                                completed, tasks.size() * PHASES.size() * REPETITIONS);
                    }
                }
            }
            System.out.println();
        } finally {
            if (previousMemoryDir == null) {
                System.clearProperty("devcli.memory.dir");
            } else {
                System.setProperty("devcli.memory.dir", previousMemoryDir);
            }
        }

        Path report = writeReport(llm, results, baselineMillis);
        long staleOverwriteCount = results.stream().mapToLong(CaseResult::staleEventCount).sum();
        long passed = results.stream().filter(CaseResult::passed).count();
        System.out.printf(Locale.ROOT,
                "Agent concurrency benchmark: passed=%d/%d stale_overwrite_count=%d report=%s%n",
                passed, results.size(), staleOverwriteCount, report);
        assertEquals(45, results.size(), "benchmark must complete all 45 cases");
        assertEquals(0, staleOverwriteCount, "old turn emitted stale events after new generation started");
    }

    private static CaseResult evaluate(int taskType, double phase, int repetition,
                                       String threadId, String oldTurnId, String newTurnId,
                                       String marker, boolean cancelled,
                                       List<RuntimeEvent> events) throws Exception {
        long newStartId = events.stream()
                .filter(event -> "turn.started".equals(event.type()))
                .filter(event -> newTurnId.equals(turnId(event)))
                .mapToLong(RuntimeEvent::id)
                .findFirst().orElse(-1L);
        List<RuntimeEvent> stale = events.stream()
                .filter(event -> newStartId >= 0 && event.id() > newStartId)
                .filter(event -> oldTurnId.equals(turnId(event)))
                .filter(event -> List.of("reasoning.delta", "message.delta", "tool.calls",
                        "tool.result", "turn.completed").contains(event.type()))
                .toList();
        boolean markerObserved = events.stream()
                .filter(event -> newTurnId.equals(turnId(event)))
                .filter(event -> "message.delta".equals(event.type()))
                .anyMatch(event -> event.data().contains(marker));
        return new CaseResult(
                "task-" + taskType + "-phase-" + Math.round(phase * 100) + "-run-" + repetition,
                taskType, phase, repetition, threadId, oldTurnId, newTurnId,
                cancelled, markerObserved, stale.size(),
                markerObserved && stale.isEmpty(),
                stale.stream().map(event -> event.id() + ":" + event.type()).toList());
    }

    private static String turnId(RuntimeEvent event) {
        try {
            return JSON.readTree(event.data()).path("turn_id").asText("");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static List<RuntimeEvent> awaitTerminal(RuntimeThreadStore store,
                                                    String threadId, String turnId,
                                                    long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            List<RuntimeEvent> events = store.events(threadId, 0);
            boolean terminal = events.stream().anyMatch(event ->
                    turnId.equals(turnId(event))
                            && List.of("turn.completed", "turn.failed", "turn.rejected")
                            .contains(event.type()));
            if (terminal) return events;
            Thread.sleep(250L);
        }
        throw new IllegalStateException("turn timed out: " + turnId);
    }

    private static JsonNode post(HttpClient http, String url, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(3))
                .header("Authorization", "Bearer " + API_KEY)
                .header("Content-Type", "application/json");
        builder.POST(body == null || body.isBlank()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        HttpResponse<String> response = http.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(url + " failed: HTTP "
                    + response.statusCode() + " " + response.body());
        }
        return response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
    }

    private Path writeReport(LlmClient llm, List<CaseResult> results,
                             long baselineMillis) throws Exception {
        Path dir = Path.of(System.getProperty("devcli.benchmark.report.dir",
                Path.of("target", "benchmark-reports").toString()));
        Files.createDirectories(dir);
        Path report = dir.resolve("real-llm-agent-concurrency-benchmark.json");
        ObjectNode root = JSON.createObjectNode();
        root.put("created_at", Instant.now().toString());
        root.put("llm_provider", llm.getProviderName());
        root.put("llm_model", llm.getModelName());
        root.put("sample_count", results.size());
        root.put("task_type_count", taskPrompts().size());
        root.put("baseline_millis", baselineMillis);
        root.putPOJO("intervention_phases", PHASES);
        root.put("repetitions_per_combination", REPETITIONS);
        root.put("passed", results.stream().filter(CaseResult::passed).count());
        root.put("stale_overwrite_count", results.stream().mapToLong(CaseResult::staleEventCount).sum());
        ArrayNode cases = root.putArray("cases");
        for (CaseResult result : results) {
            cases.addPOJO(result);
        }
        Files.writeString(report, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                StandardCharsets.UTF_8);
        return report;
    }

    private static List<String> taskPrompts() {
        return List.of(
                "执行一个只读长任务：依次检查项目配置、模型选择、工具注册和会话初始化，至少进行四次工具读取后再总结。",
                "执行一个只读长任务：分析 Planner、Worker、Reviewer 的协作链路，至少读取四个相关实现后再总结。",
                "执行一个只读长任务：分析上下文压缩、语义守卫、检查点和恢复链路，至少进行四次工具读取。",
                "执行一个只读长任务：分析 Runtime API 的线程、turn、事件、取消和持久化流程，至少读取四个实现。",
                "执行一个只读长任务：分析工具参数校验、权限策略、执行结果和错误模型，至少读取四个实现。"
        );
    }

    private static LlmClient resolveLlmOrSkip() {
        DevCliConfig config = DevCliConfig.load();
        String preferred = System.getProperty("devcli.it.concurrency.provider", "openai");
        LlmClient client = LlmClientFactory.create(preferred, config);
        if (client == null) {
            for (String provider : List.of("anthropic", "kimi", "glm", "deepseek", "step")) {
                client = LlmClientFactory.create(provider, config);
                if (client != null) break;
            }
        }
        Assumptions.assumeTrue(client != null, "no real LLM provider configured");
        return client;
    }

    private record CaseResult(String caseId, int taskType, double interventionPhase,
                              int repetition, String threadId, String oldTurnId,
                              String newTurnId, boolean cancelAcknowledged,
                              boolean markerObserved, int staleEventCount,
                              boolean passed, List<String> staleEvents) {
    }
}
