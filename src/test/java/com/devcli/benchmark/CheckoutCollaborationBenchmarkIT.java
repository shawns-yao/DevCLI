package com.devcli.benchmark;

import com.devcli.agent.Agent;
import com.devcli.agent.AgentBenchmarkTestSupport;
import com.devcli.agent.AgentOrchestrator;
import com.devcli.agent.ControlledBenchmarkToolRegistry;
import com.devcli.config.DevCliConfig;
import com.devcli.llm.LlmClient;
import com.devcli.llm.LlmClientFactory;
import com.devcli.memory.LongTermMemory;
import com.devcli.memory.MemoryManager;
import com.devcli.render.PlainRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckoutCollaborationBenchmarkIT {
    static final Path CONTRACT_TEMPLATE = Path.of("src", "test", "resources", "benchmark", "checkout-contracts",
            "src", "main", "java", "bench", "checkout", "contracts", "CheckoutContracts.java");
    static final Path CONTRACT_TARGET = Path.of("src", "main", "java", "bench", "checkout", "contracts",
            "CheckoutContracts.java");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ALLOWED_TOOLS = Set.of("read_file", "write_file", "list_dir");
    private static final String TASK_PROMPT = """
            Implement a production-style multi-tenant Java checkout Saga in the current project root.

            First read src/main/java/bench/checkout/contracts/CheckoutContracts.java. It is a read-only public
            contract. Do not modify, replace, move, or duplicate it.

            Create these implementations:
            - bench.checkout.access.TenantAccessPolicy
            - bench.checkout.inventory.InMemoryInventoryService
            - bench.checkout.payment.InMemoryPaymentService
            - bench.checkout.shipping.InMemoryShippingService
            - bench.checkout.notification.InMemoryNotificationOutbox
            - bench.checkout.audit.InMemoryAuditTrail
            - bench.checkout.orchestration.DefaultCheckoutOrchestrator

            Every implementation except DefaultCheckoutOrchestrator must expose both a public no-argument
            constructor and a public constructor accepting CheckoutContracts.FailureSwitch. The orchestrator must
            expose a public constructor accepting AccessPolicy, InventoryService, PaymentService, ShippingService,
            NotificationOutbox, and AuditTrail in that order.

            Implement every semantic stated by the contract, including input validation, tenant authorization and
            tenant isolation, idempotency, thread safety, failure injection immediately before each side effect,
            strict reverse-order compensation, audit ordering, and exactly-once notification behavior. Do not add
            dependencies, tests, build files, command execution, or files outside src/main/java/bench/checkout/.
            """;

    @Test
    @DisplayName("single Agent and Planner/Worker/Reviewer implement the same multi-tenant checkout Saga")
    void compareSingleAndMultiAgentOnCheckoutSaga() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("devcli.benchmark.checkout"),
                "set -Ddevcli.benchmark.checkout=true to run the real LLM checkout benchmark");
        DevCliConfig config = DevCliConfig.load();
        String provider = System.getProperty("devcli.it.checkout.provider", "deepseek");
        LlmClient source = LlmClientFactory.create(provider, config);
        Assumptions.assumeTrue(source != null, "no configured LLM client");
        Assumptions.assumeTrue(ToolProvider.getSystemJavaCompiler() != null,
                "JDK compiler is required for hidden validation");

        Path root = Path.of("target", "agent-benchmark", "checkout-run-" + System.currentTimeMillis())
                .toAbsolutePath().normalize();
        PairedBenchmarkRunner<RunResult, RunResult> runner = new PairedBenchmarkRunner<>(
                PairedBenchmarkRunner.configuredMaxAttempts(),
                (attempt, attemptRoot) -> {
                    RunResult single = runSingle(new CountingLlmClient(source), attemptRoot.resolve("single"));
                    RunResult team = runTeam(new CountingLlmClient(source), attemptRoot.resolve("team"));
                    return new PairedBenchmarkRunner.Attempt<>(single, team,
                            single.llmRunCompleted(), team.llmRunCompleted());
                });
        PairedBenchmarkRunner.Result<RunResult, RunResult> paired = runner.run(root);
        Path report = writeReport(root, source, paired);
        System.out.println("Checkout collaboration benchmark report: " + report);
        System.out.println(Files.readString(report));
        assertTrue(paired.valid(), "no complete single/Plan pair; report=" + report);
    }

    @Test
    @DisplayName("single Agent supplement uses the same checkout task and hidden validator")
    void runSingleAgentCheckoutSupplement() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("devcli.benchmark.checkout.singleOnly"),
                "set -Ddevcli.benchmark.checkout.singleOnly=true to run the single-Agent supplement");
        DevCliConfig config = DevCliConfig.load();
        String provider = System.getProperty("devcli.it.checkout.provider", "deepseek");
        LlmClient source = LlmClientFactory.create(provider, config);
        Assumptions.assumeTrue(source != null, "no configured LLM client");
        Assumptions.assumeTrue(ToolProvider.getSystemJavaCompiler() != null,
                "JDK compiler is required for hidden validation");

        Path root = Path.of("target", "agent-benchmark", "checkout-single-run-" + System.currentTimeMillis())
                .toAbsolutePath().normalize();
        Files.createDirectories(root);
        RunResult single = runSingle(new CountingLlmClient(source), root.resolve("single"));
        Path report = writeSingleReport(root, source, single);
        System.out.println("Checkout single-Agent supplement report: " + report);
        System.out.println(Files.readString(report));
        assertTrue(single.llmRunCompleted(), "single Agent LLM run was incomplete; report=" + report);
    }

    @Test
    void contractTemplateShouldCompile(@TempDir Path workspace) throws Exception {
        prepareWorkspace(workspace);
        Compilation result = compile(workspace);
        assertTrue(result.success(), String.join("\n", result.diagnostics()));
    }

    @Test
    void missingImplementationShouldFailAllChecks(@TempDir Path workspace) {
        CheckoutBenchmarkValidator.Evaluation result = evaluate(workspace);
        assertEquals(CheckoutBenchmarkValidator.CHECK_TOTAL, result.total());
        assertEquals(0, result.passed());
        assertEquals(CheckoutBenchmarkValidator.CHECK_TOTAL, result.failures().size());
    }

    @Test
    void contractModificationShouldBeDetected(@TempDir Path workspace) throws Exception {
        prepareWorkspace(workspace);
        Path contract = workspace.resolve(CONTRACT_TARGET);
        Files.writeString(contract, Files.readString(contract) + System.lineSeparator(), StandardCharsets.UTF_8);
        CheckoutBenchmarkValidator.Evaluation result = evaluate(workspace);
        assertTrue(result.failures().stream()
                .anyMatch(value -> value.startsWith("architecture: contract integrity:")));
    }

    private static RunResult runSingle(CountingLlmClient llm, Path workspace) throws Exception {
        prepareWorkspace(workspace);
        ControlledBenchmarkToolRegistry registry = registryFor(workspace);
        long started = System.nanoTime();
        String output;
        try (Agent agent = new Agent(llm, registry)) {
            agent.setRenderer(new PlainRenderer());
            output = agent.run(TASK_PROMPT);
        } catch (Exception error) {
            output = "LLM run failed: " + message(error);
        }
        return finish("single-agent", workspace, started, output, llm.metrics());
    }

    private static RunResult runTeam(CountingLlmClient llm, Path workspace) throws Exception {
        prepareWorkspace(workspace);
        ControlledBenchmarkToolRegistry registry = registryFor(workspace);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        long started = System.nanoTime();
        String output;
        try (PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
             NoOpMemoryManager memory = new NoOpMemoryManager(workspace.resolve(".memory"))) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(llm, registry, memory, out);
            AgentBenchmarkTestSupport.configureControlledBenchmark(orchestrator);
            output = orchestrator.run(TASK_PROMPT);
        } catch (Exception error) {
            output = "LLM run failed: " + message(error);
        }
        return finish("planner-worker-reviewer", workspace, started,
                buffer.toString(StandardCharsets.UTF_8) + System.lineSeparator() + output, llm.metrics());
    }

    private static RunResult finish(String mode, Path workspace, long started, String output, LlmMetrics metrics) {
        try {
            Files.writeString(workspace.resolve("benchmark-output-" + mode + ".txt"), output, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Diagnostic persistence must not affect scoring.
        }
        return new RunResult(mode, workspace, elapsedMs(started), isValidLlmRun(output), output,
                evaluate(workspace), metrics, unexpectedJavaSources(workspace), retrySignals(output));
    }

    private static boolean isValidLlmRun(String output) {
        if (output == null || output.isBlank()) {
            return false;
        }
        return !output.contains("LLM run failed")
                && !output.contains("调用 LLM 失败")
                && !output.contains("规划阶段失败")
                && !output.contains("[code=NETWORK")
                && !output.contains("[code=TIMEOUT")
                && !output.contains("[code=RATE_LIMITED")
                && !output.contains("[code=AUTHENTICATION")
                && !output.contains("[code=OVERLOADED");
    }

    private static CheckoutBenchmarkValidator.Evaluation evaluate(Path workspace) {
        Compilation compilation;
        try {
            compilation = compile(workspace);
        } catch (Exception error) {
            return CheckoutBenchmarkValidator.failedAll("compilation unavailable: " + message(error));
        }
        if (!compilation.success()) {
            return CheckoutBenchmarkValidator.failedAll("compilation failed: "
                    + String.join(" | ", compilation.diagnostics()));
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[]{compilation.classes().toUri().toURL()},
                CheckoutCollaborationBenchmarkIT.class.getClassLoader())) {
            return new CheckoutBenchmarkValidator(workspace, loader, CONTRACT_TEMPLATE, CONTRACT_TARGET).evaluate();
        } catch (Exception error) {
            return CheckoutBenchmarkValidator.failedAll("validator unavailable: " + message(error));
        }
    }

    static void prepareWorkspace(Path workspace) throws IOException {
        Path target = workspace.resolve(CONTRACT_TARGET);
        Files.createDirectories(target.getParent());
        Files.copy(CONTRACT_TEMPLATE, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Compilation compile(Path workspace) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Path classes = workspace.resolve(".benchmark-classes");
        Files.createDirectories(classes);
        if (compiler == null) {
            return new Compilation(false, classes, List.of("JDK compiler unavailable"));
        }
        Path sourceRoot = workspace.resolve("src/main/java");
        if (!Files.isDirectory(sourceRoot)) {
            return new Compilation(false, classes, List.of("source root missing"));
        }
        List<Path> sources;
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            sources = stream.filter(path -> path.toString().endsWith(".java")).toList();
        }
        if (sources.isEmpty()) {
            return new Compilation(false, classes, List.of("Java sources missing"));
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            boolean success = Boolean.TRUE.equals(compiler.getTask(null, manager, diagnostics,
                    List.of("--release", "17", "-encoding", "UTF-8", "-d", classes.toString()), null,
                    manager.getJavaFileObjectsFromPaths(sources)).call());
            return new Compilation(success, classes, diagnostics.getDiagnostics().stream()
                    .map(value -> value.getKind() + " " + value.getMessage(null)).toList());
        }
    }

    private static List<String> unexpectedJavaSources(Path workspace) {
        Path sourceRoot = workspace.resolve("src/main/java");
        if (!Files.isDirectory(sourceRoot)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            return paths.filter(path -> path.toString().endsWith(".java"))
                    .map(sourceRoot::relativize)
                    .map(path -> path.toString().replace('\\', '/'))
                    .filter(path -> !path.startsWith("bench/checkout/"))
                    .toList();
        } catch (IOException ignored) {
            return List.of("source scan unavailable");
        }
    }

    private static int retrySignals(String output) {
        if (output == null || output.isBlank()) {
            return 0;
        }
        return countOccurrences(output.toLowerCase(), "retry") + countOccurrences(output, "重做")
                + countOccurrences(output, "修复轮");
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int offset = 0; (offset = text.indexOf(needle, offset)) >= 0; offset += needle.length()) {
            count++;
        }
        return count;
    }

    private static Path writeReport(Path root, LlmClient llm,
                                    PairedBenchmarkRunner.Result<RunResult, RunResult> paired) throws IOException {
        ObjectNode report = JSON.createObjectNode();
        report.put("benchmark", "checkout-collaboration");
        report.put("generated_at", Instant.now().toString());
        report.put("provider", llm.getProviderName());
        report.put("model", llm.getModelName());
        report.put("hidden_check_total", CheckoutBenchmarkValidator.CHECK_TOTAL);
        report.putPOJO("tool_policy", ALLOWED_TOOLS.stream().sorted().toList());
        report.put("same_task_prompt_sha256", sha256(TASK_PROMPT));
        report.put("same_initial_workspace", true);
        report.put("same_hidden_validator", true);
        report.put("max_attempts", paired.maxAttempts());
        report.put("attempt_count", paired.attempts().size());
        report.put("valid_paired_run", paired.valid());
        report.put("selected_attempt", paired.validPair().map(PairedBenchmarkRunner.AttemptRecord::number).orElse(0));
        PairedBenchmarkRunner.AttemptRecord<RunResult, RunResult> selected = paired.validPair()
                .orElse(paired.attempts().isEmpty() ? null : paired.attempts().get(paired.attempts().size() - 1));
        if (selected == null || selected.outcome() == null) {
            report.putNull("single_agent");
            report.putNull("planner_worker_reviewer");
        } else {
            putRunResult(report, "single_agent", selected.outcome().single());
            putRunResult(report, "planner_worker_reviewer", selected.outcome().plannerWorkerReviewer());
        }
        ArrayNode attempts = report.putArray("attempts");
        for (PairedBenchmarkRunner.AttemptRecord<RunResult, RunResult> attempt : paired.attempts()) {
            ObjectNode attemptNode = attempts.addObject();
            attemptNode.put("attempt", attempt.number());
            attemptNode.put("workspace", attempt.workspace().toString());
            attemptNode.put("complete_pair", attempt.complete());
            if (attempt.failure() != null) {
                attemptNode.put("failure", attempt.failure());
            }
            if (attempt.outcome() == null) {
                attemptNode.putNull("single_agent");
                attemptNode.putNull("planner_worker_reviewer");
            } else {
                putRunResult(attemptNode, "single_agent", attempt.outcome().single());
                putRunResult(attemptNode, "planner_worker_reviewer",
                        attempt.outcome().plannerWorkerReviewer());
            }
        }
        RunResult single = selected == null || selected.outcome() == null
                ? null : selected.outcome().single();
        RunResult team = selected == null || selected.outcome() == null
                ? null : selected.outcome().plannerWorkerReviewer();
        ObjectNode comparison = report.putObject("comparison");
        if (single == null || team == null || !paired.valid()) {
            comparison.putNull("completion_rate_delta");
            comparison.putNull("elapsed_ms_delta");
            comparison.putNull("team_elapsed_ratio");
            comparison.putNull("llm_call_delta");
            comparison.putNull("reported_token_delta");
        } else {
            comparison.put("completion_rate_delta", round(team.evaluation().completionRate()
                    - single.evaluation().completionRate()));
            comparison.put("elapsed_ms_delta", team.elapsedMs() - single.elapsedMs());
            comparison.put("team_elapsed_ratio", single.elapsedMs() == 0 ? 0.0
                    : round((double) team.elapsedMs() / single.elapsedMs()));
            comparison.put("llm_call_delta", team.metrics().calls() - single.metrics().calls());
            comparison.put("reported_token_delta", team.metrics().totalTokens() - single.metrics().totalTokens());
        }
        comparison.put("valid_paired_run", paired.valid());
        Path path = root.resolve("checkout-collaboration-benchmark.json");
        JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), report);
        return path;
    }

    private static Path writeSingleReport(Path root, LlmClient llm, RunResult single) throws IOException {
        ObjectNode report = JSON.createObjectNode();
        report.put("benchmark", "checkout-single-agent-supplement");
        report.put("generated_at", Instant.now().toString());
        report.put("provider", llm.getProviderName());
        report.put("model", llm.getModelName());
        report.put("hidden_check_total", CheckoutBenchmarkValidator.CHECK_TOTAL);
        report.putPOJO("tool_policy", ALLOWED_TOOLS.stream().sorted().toList());
        report.put("task_prompt_sha256", sha256(TASK_PROMPT));
        report.put("same_initial_workspace_rule", true);
        report.put("same_hidden_validator", true);
        report.put("named_initial_tool_choice", false);
        report.put("cross_run_supplement", true);
        report.set("single_agent", toJson(single));
        Path path = root.resolve("checkout-single-agent-supplement.json");
        JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), report);
        return path;
    }

    private static ObjectNode toJson(RunResult result) {
        ObjectNode node = JSON.createObjectNode();
        node.put("mode", result.mode());
        node.put("workspace", result.workspace().toString());
        node.put("elapsed_ms", result.elapsedMs());
        node.put("llm_run_completed", result.llmRunCompleted());
        node.put("checks_passed", result.evaluation().passed());
        node.put("checks_total", result.evaluation().total());
        node.put("completion_rate", round(result.evaluation().completionRate()));
        node.putPOJO("failures", result.evaluation().failures());
        node.putPOJO("unexpected_java_sources", result.unexpectedJavaSources());
        node.put("agent_retry_signals", result.retrySignals());
        node.set("llm_metrics", result.metrics().toJson());
        node.put("output_preview", preview(result.output()));
        return node;
    }

    private static void putRunResult(ObjectNode target, String field, RunResult result) {
        if (result == null) {
            target.putNull(field);
        } else {
            target.set(field, toJson(result));
        }
    }

    private static ControlledBenchmarkToolRegistry registryFor(Path workspace) {
        ControlledBenchmarkToolRegistry registry = new ControlledBenchmarkToolRegistry(ALLOWED_TOOLS);
        registry.setProjectPath(workspace.toString());
        return registry;
    }

    private static String message(Throwable throwable) {
        String text = throwable.getMessage();
        return throwable.getClass().getSimpleName() + (text == null || text.isBlank() ? "" : " " + text);
    }

    private static String preview(String text) {
        String compact = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return compact.length() <= 800 ? compact : compact.substring(0, 800);
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private record Compilation(boolean success, Path classes, List<String> diagnostics) {
    }

    private record RunResult(String mode, Path workspace, long elapsedMs, boolean llmRunCompleted,
                             String output, CheckoutBenchmarkValidator.Evaluation evaluation, LlmMetrics metrics,
                             List<String> unexpectedJavaSources, int retrySignals) {
    }

    private record LlmMetrics(long calls, long failedCalls, long inputTokens, long outputTokens, long cachedTokens) {
        private long totalTokens() {
            return inputTokens + outputTokens;
        }

        private ObjectNode toJson() {
            ObjectNode node = JSON.createObjectNode();
            node.put("calls", calls);
            node.put("failed_calls", failedCalls);
            node.put("input_tokens", inputTokens);
            node.put("output_tokens", outputTokens);
            node.put("cached_input_tokens", cachedTokens);
            node.put("total_reported_tokens", totalTokens());
            node.put("token_usage_reported", totalTokens() > 0 || cachedTokens > 0);
            node.put("cost", "not reported by provider");
            return node;
        }
    }

    private static final class CountingLlmClient implements LlmClient {
        private final LlmClient delegate;
        private final LongAdder calls = new LongAdder();
        private final LongAdder failedCalls = new LongAdder();
        private final LongAdder inputTokens = new LongAdder();
        private final LongAdder outputTokens = new LongAdder();
        private final LongAdder cachedTokens = new LongAdder();

        private CountingLlmClient(LlmClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return observe(() -> delegate.chat(messages, tools));
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            return observe(() -> delegate.chat(messages, tools, listener));
        }

        @Override
        public String getModelName() {
            return delegate.getModelName();
        }

        @Override
        public String getProviderName() {
            return delegate.getProviderName();
        }

        private ChatResponse observe(ThrowingCall call) throws IOException {
            calls.increment();
            try {
                ChatResponse response = call.invoke();
                inputTokens.add(response.inputTokens());
                outputTokens.add(response.outputTokens());
                cachedTokens.add(response.cachedInputTokens());
                return response;
            } catch (IOException error) {
                failedCalls.increment();
                throw error;
            }
        }

        private LlmMetrics metrics() {
            return new LlmMetrics(calls.sum(), failedCalls.sum(), inputTokens.sum(), outputTokens.sum(),
                    cachedTokens.sum());
        }

        @FunctionalInterface
        private interface ThrowingCall {
            ChatResponse invoke() throws IOException;
        }
    }

    private static final class NoOpMemoryManager extends MemoryManager {
        private NoOpMemoryManager(Path storageDir) {
            super(null, 32768, 200000, new LongTermMemory(storageDir.toFile()));
        }
    }
}
