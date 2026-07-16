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
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SagaCollaborationBenchmarkIT {
    static final Path CONTRACT_TEMPLATE = Path.of("src", "test", "resources", "benchmark", "saga-contracts",
            "src", "main", "java", "bench", "saga", "contracts", "SagaContracts.java");
    static final Path CONTRACT_TARGET = Path.of("src", "main", "java", "bench", "saga", "contracts",
            "SagaContracts.java");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ALLOWED_TOOLS = Set.of("read_file", "write_file", "list_dir");

    @Test
    @DisplayName("single Agent and Planner/Worker/Reviewer implement the same Saga system")
    void compareSingleAndMultiAgentOnSaga() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("devcli.benchmark.saga"),
                "set -Ddevcli.benchmark.saga=true to run the real LLM Saga benchmark");
        LlmClient llm = LlmClientFactory.createFromConfig(DevCliConfig.load());
        Assumptions.assumeTrue(llm != null, "no configured LLM client");
        Assumptions.assumeTrue(ToolProvider.getSystemJavaCompiler() != null,
                "JDK compiler is required for hidden validation");

        Path root = Path.of("target", "agent-benchmark", "saga-run-" + System.currentTimeMillis())
                .toAbsolutePath().normalize();
        Files.createDirectories(root);
        RunResult single = runSingle(llm, root.resolve("single"));
        RunResult team = runTeam(llm, root.resolve("team"));
        Path report = writeReport(root, llm, single, team);
        System.out.println("Saga collaboration benchmark report: " + report);
        System.out.println(Files.readString(report));
        assertTrue(Files.exists(report));
    }

    @Test
    void contractTemplateShouldCompile(@TempDir Path workspace) throws Exception {
        prepareWorkspace(workspace);
        Compilation result = compile(workspace);
        assertTrue(result.success(), String.join("\n", result.diagnostics()));
    }

    @Test
    void missingImplementationShouldFailAllChecks(@TempDir Path workspace) {
        SagaBenchmarkValidator.Evaluation result = evaluate(workspace);
        assertEquals(SagaBenchmarkValidator.CHECK_TOTAL, result.total());
        assertEquals(0, result.passed());
        assertEquals(SagaBenchmarkValidator.CHECK_TOTAL, result.failures().size());
    }

    @Test
    void contractModificationShouldBeDetected(@TempDir Path workspace) throws Exception {
        prepareWorkspace(workspace);
        Path contract = workspace.resolve(CONTRACT_TARGET);
        Files.writeString(contract, Files.readString(contract) + System.lineSeparator(), StandardCharsets.UTF_8);
        SagaBenchmarkValidator.Evaluation result = evaluate(workspace);
        assertTrue(result.failures().stream()
                .anyMatch(value -> value.startsWith("architecture: contract integrity:")));
    }

    private static RunResult runSingle(LlmClient llm, Path workspace) throws Exception {
        prepareWorkspace(workspace);
        ControlledBenchmarkToolRegistry registry = registryFor(workspace);
        long started = System.nanoTime();
        String output;
        try (Agent agent = new Agent(llm, registry)) {
            agent.setRenderer(new PlainRenderer());
            output = agent.run(prompt("single-agent"), LlmClient.ToolChoice.required("read_file"));
        } catch (Exception e) {
            output = "LLM run failed: " + e.getMessage();
        }
        return finish("single-agent", workspace, started, output);
    }

    private static RunResult runTeam(LlmClient llm, Path workspace) throws Exception {
        prepareWorkspace(workspace);
        ControlledBenchmarkToolRegistry registry = registryFor(workspace);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        long started = System.nanoTime();
        String output;
        try (PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
             NoOpMemoryManager memory = new NoOpMemoryManager(workspace.resolve(".memory"))) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(llm, registry, memory, out);
            AgentBenchmarkTestSupport.configureControlledBenchmark(orchestrator);
            output = orchestrator.run(prompt("planner-worker-reviewer"));
        } catch (Exception e) {
            output = "LLM run failed: " + e.getMessage();
        }
        return finish("planner-worker-reviewer", workspace, started,
                buffer.toString(StandardCharsets.UTF_8) + System.lineSeparator() + output);
    }

    private static RunResult finish(String mode, Path workspace, long started, String output) {
        try {
            Files.writeString(workspace.resolve("benchmark-output-" + mode + ".txt"), output, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Benchmark diagnostics must not affect scoring.
        }
        return new RunResult(mode, workspace, elapsedMs(started), !output.contains("LLM run failed"), output,
                evaluate(workspace));
    }

    private static String prompt(String mode) {
        return """
                Implement a production-style Java order fulfillment Saga in the current project root.
                Mode: %s.

                First read src/main/java/bench/saga/contracts/SagaContracts.java. It is a read-only public contract.
                Do not modify, replace, move, or duplicate it.

                Create these implementations in separate package directories:
                - bench.saga.inventory.InMemoryInventoryService
                - bench.saga.payment.InMemoryPaymentService
                - bench.saga.shipping.InMemoryShippingService
                - bench.saga.notification.InMemoryNotificationService
                - bench.saga.audit.InMemoryAuditLog
                - bench.saga.fulfillment.DefaultFulfillmentOrchestrator

                Every service implementation must implement its matching SagaContracts interface and expose both a
                public no-argument constructor and a public constructor accepting SagaContracts.FailureSwitch.
                DefaultFulfillmentOrchestrator must expose a public constructor accepting InventoryService,
                PaymentService, ShippingService, NotificationService, and AuditLog in that order.

                Behavioral requirements:
                - Keep all state thread-safe and in memory.
                - Inventory reserve, payment authorize, shipment creation, compensation, and notifications are
                  idempotent for an order.
                - fulfill is idempotent by idempotencyKey and safe when the same request runs concurrently.
                - Execute reserve -> authorize -> create shipment -> success notification.
                - On failure, compensate completed steps in strict reverse order, send one failure notification,
                  and return FAILED. A success-notification failure also cancels shipment, refunds payment, and
                  releases inventory.
                - Call FailureSwitch.before with the matching Operation immediately before each side effect.
                - Audit state names in order. Success must include NEW, INVENTORY_RESERVED, PAYMENT_AUTHORIZED,
                  SHIPMENT_CREATED, COMPLETED.
                - Do not add dependencies, tests, build files, command execution, or files outside
                  src/main/java/bench/saga/.

                For planner-worker-reviewer mode, split inventory, payment, shipping, notification, and audit into
                independent parallel implementation steps. Make fulfillment the final integration step depending on
                those five modules. Each worker owns only its package directory. Review every requirement.
                """.formatted(mode);
    }

    private static SagaBenchmarkValidator.Evaluation evaluate(Path workspace) {
        Compilation compilation;
        try {
            compilation = compile(workspace);
        } catch (Exception e) {
            return SagaBenchmarkValidator.failedAll("compilation unavailable: " + message(e));
        }
        if (!compilation.success()) {
            return SagaBenchmarkValidator.failedAll("compilation failed: " + String.join(" | ", compilation.diagnostics()));
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[]{compilation.classes().toUri().toURL()},
                SagaCollaborationBenchmarkIT.class.getClassLoader())) {
            return new SagaBenchmarkValidator(workspace, loader, CONTRACT_TEMPLATE, CONTRACT_TARGET).evaluate();
        } catch (Exception e) {
            return SagaBenchmarkValidator.failedAll("validator unavailable: " + message(e));
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
        if (compiler == null) return new Compilation(false, classes, List.of("JDK compiler unavailable"));
        Path sourceRoot = workspace.resolve("src/main/java");
        if (!Files.isDirectory(sourceRoot)) return new Compilation(false, classes, List.of("source root missing"));
        List<Path> sources;
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            sources = stream.filter(path -> path.toString().endsWith(".java")).toList();
        }
        if (sources.isEmpty()) return new Compilation(false, classes, List.of("Java sources missing"));
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            boolean success = Boolean.TRUE.equals(compiler.getTask(null, manager, diagnostics,
                    List.of("--release", "17", "-encoding", "UTF-8", "-d", classes.toString()), null,
                    manager.getJavaFileObjectsFromPaths(sources)).call());
            return new Compilation(success, classes, diagnostics.getDiagnostics().stream()
                    .map(value -> value.getKind() + " " + value.getMessage(null)).toList());
        }
    }

    private static Path writeReport(Path root, LlmClient llm, RunResult single, RunResult team) throws IOException {
        ObjectNode report = JSON.createObjectNode();
        report.put("benchmark", "saga-collaboration");
        report.put("generated_at", Instant.now().toString());
        report.put("provider", llm.getProviderName());
        report.put("model", llm.getModelName());
        report.put("hidden_check_total", SagaBenchmarkValidator.CHECK_TOTAL);
        report.set("single_agent", toJson(single));
        report.set("planner_worker_reviewer", toJson(team));
        ObjectNode comparison = report.putObject("comparison");
        comparison.put("completion_rate_delta", round(team.evaluation().completionRate()
                - single.evaluation().completionRate()));
        comparison.put("elapsed_ms_delta", team.elapsedMs() - single.elapsedMs());
        Path path = root.resolve("saga-collaboration-benchmark.json");
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
        node.put("output_preview", preview(result.output()));
        return node;
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

    private record Compilation(boolean success, Path classes, List<String> diagnostics) {
    }

    private record RunResult(String mode, Path workspace, long elapsedMs, boolean llmRunCompleted,
                             String output, SagaBenchmarkValidator.Evaluation evaluation) {
    }

    private static final class NoOpMemoryManager extends MemoryManager {
        private NoOpMemoryManager(Path storageDir) {
            super(null, 32768, 200000, new LongTermMemory(storageDir.toFile()));
        }
    }
}
