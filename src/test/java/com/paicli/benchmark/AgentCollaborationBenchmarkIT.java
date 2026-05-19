package com.paicli.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.agent.Agent;
import com.paicli.agent.AgentOrchestrator;
import com.paicli.config.PaiCliConfig;
import com.paicli.llm.LlmClient;
import com.paicli.llm.LlmClientFactory;
import com.paicli.memory.LongTermMemory;
import com.paicli.memory.MemoryManager;
import com.paicli.render.PlainRenderer;
import com.paicli.tool.ToolOutput;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCollaborationBenchmarkIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int HIDDEN_TEST_TOTAL = 10;
    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "read_file", "write_file", "list_dir", "execute_command"
    );

    @Test
    @DisplayName("single Agent and Planner/Worker/Reviewer run the same nested CLI task")
    void compareSingleAgentWithMultiAgentOnOneTask() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("paicli.benchmark.agent"),
                "set -Dpaicli.benchmark.agent=true to run real LLM benchmark");
        LlmClient llm = LlmClientFactory.createFromConfig(PaiCliConfig.load());
        Assumptions.assumeTrue(llm != null, "no configured LLM client");
        Assumptions.assumeTrue(ToolProvider.getSystemJavaCompiler() != null,
                "JDK compiler is required for hidden validation");

        Path root = Path.of("target", "agent-benchmark",
                "run-" + System.currentTimeMillis()).toAbsolutePath().normalize();
        Files.createDirectories(root);

        RunResult single = runSingle(llm, root.resolve("single"));
        RunResult team = runTeam(llm, root.resolve("team"));
        Path report = writeReport(root, llm, single, team);
        System.out.println("Agent collaboration benchmark report: " + report);
        System.out.println(Files.readString(report));
        assertTrue(Files.exists(report), "benchmark report should be written");
    }

    @Test
    @DisplayName("re-evaluate an existing benchmark run without calling LLM")
    void reevaluateExistingRun() throws Exception {
        String runDir = System.getProperty("paicli.benchmark.reevaluate");
        Assumptions.assumeTrue(runDir != null && !runDir.isBlank(),
                "set -Dpaicli.benchmark.reevaluate=<run-dir> to re-score an existing run");
        Path root = Path.of(runDir).toAbsolutePath().normalize();
        ObjectNode previous = (ObjectNode) JSON.readTree(root.resolve("agent-collaboration-benchmark.json").toFile());
        RunResult single = scoredExistingRun("single-agent", root.resolve("single"),
                previous.path("single_agent").path("elapsed_ms").asLong(),
                previous.path("single_agent").path("llm_run_completed").asBoolean(false));
        RunResult team = scoredExistingRun("planner-worker-reviewer", root.resolve("team"),
                previous.path("planner_worker_reviewer").path("elapsed_ms").asLong(),
                previous.path("planner_worker_reviewer").path("llm_run_completed").asBoolean(false));
        Path report = writeReport(root, previous.path("provider").asText("unknown"),
                previous.path("model").asText("unknown"), single, team,
                "corrected re-evaluation of existing generated files; no LLM calls were made");
        System.out.println("Corrected benchmark report: " + report);
        System.out.println(Files.readString(report));
    }

    private static RunResult runSingle(LlmClient llm, Path workspace) throws Exception {
        Files.createDirectories(workspace);
        LimitedToolRegistry registry = registryFor(workspace);
        long started = System.nanoTime();
        String output;
        try (Agent agent = new Agent(llm, registry)) {
            agent.setRenderer(new PlainRenderer());
            output = agent.run(promptFor("single-agent"));
        } catch (Exception e) {
            return result("single-agent", workspace, started, false, "LLM run failed: " + e.getMessage());
        }
        return result("single-agent", workspace, started, !isLlmFailure(output), output);
    }

    private static RunResult runTeam(LlmClient llm, Path workspace) throws Exception {
        Files.createDirectories(workspace);
        LimitedToolRegistry registry = registryFor(workspace);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        long started = System.nanoTime();
        String output;
        try (NoOpMemoryManager memory = new NoOpMemoryManager(workspace.resolve(".memory"))) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(llm, registry, memory, out);
            output = orchestrator.run(promptFor("planner-worker-reviewer"));
        } catch (Exception e) {
            return result("planner-worker-reviewer", workspace, started, false,
                    buffer.toString(StandardCharsets.UTF_8) + "\nLLM run failed: " + e.getMessage());
        }
        String combined = buffer.toString(StandardCharsets.UTF_8) + "\n" + output;
        return result("planner-worker-reviewer", workspace, started, !isLlmFailure(combined), combined);
    }

    private static RunResult result(String mode, Path workspace, long started, boolean completed, String output) {
        return new RunResult(mode, workspace, elapsedMs(started), completed, output, evaluate(workspace));
    }

    private static LimitedToolRegistry registryFor(Path workspace) {
        LimitedToolRegistry registry = new LimitedToolRegistry(ALLOWED_TOOLS);
        registry.setProjectPath(workspace.toString());
        return registry;
    }

    private static String promptFor(String mode) {
        return """
                Implement a bounded Java benchmark task. Do not edit files outside the current project root.
                Mode: ${mode}.

                Build a log analysis operations CLI under src/main/java/bench/logops/.

                Required CLI:
                - level 1 command: log
                - level 2 subcommands: query, stat, clean
                - level 3 commands:
                  * log query time --from <ISO_LOCAL_DATE_TIME> --to <ISO_LOCAL_DATE_TIME>
                  * log query level --level <INFO|WARN|ERROR>
                  * log stat error
                  * log clean shard --before <yyyy-MM-dd>

                Required Java API for hidden tests:
                - public final class LogCli
                - public static String run(String[] args, String terminalInput,
                  java.nio.file.Path logDir, java.nio.file.Path exportDir)

                Log format:
                yyyy-MM-dd'T'HH:mm:ss LEVEL service message
                Example: 2026-05-19T10:15:00 ERROR auth login failed

                Behavior:
                - support default values: missing level defaults to ERROR when terminalInput is blank;
                  missing --before defaults to 2026-01-01.
                - support interactive terminal input: when --level is missing and terminalInput contains a nonblank
                  line, use that line as the level.
                - return table-formatted output with a header and pipe separators.
                - export ERROR logs to exportDir/errors.log whenever query level ERROR or stat error runs.
                - stat error aggregates ERROR count by service.
                - clean shard deletes only files named shard-<yyyy-MM-dd>.log older than --before.
                - invalid command or argument returns a clear error string, not an exception.

                Suggested modules: LogEntry, LogParser, LogStore, LogQueryService, LogStatService,
                LogCleanService, TableFormatter, ExceptionExporter, LogCli.

                Planner/Worker/Reviewer mode should plan module dependencies first, then implement, then review
                command coverage, defaults, interactive input, table output, export, and clean behavior.
                """.replace("${mode}", mode);
    }

    private static Evaluation evaluate(Path workspace) {
        List<String> hiddenFailures = new ArrayList<>();
        try {
            hiddenFailures.addAll(runHiddenChecks(workspace));
        } catch (Exception e) {
            hiddenFailures.addAll(fatalFunctionalFailures("hidden validation crashed: " + e.getMessage()));
        }
        return new Evaluation(hiddenFailures.size(),
                uniqueBugCount(hiddenFailures), List.copyOf(hiddenFailures));
    }

    private static List<String> runHiddenChecks(Path workspace) throws Exception {
        Path sourceRoot = workspace.resolve("src/main/java");
        if (!Files.exists(sourceRoot)) return fatalFunctionalFailures("compile failed: no Java source files");
        List<Path> sources;
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            sources = stream.filter(path -> path.toString().endsWith(".java")).toList();
        }
        if (sources.isEmpty()) return fatalFunctionalFailures("compile failed: no Java source files");

        Path classes = workspace.resolve("classes");
        Files.createDirectories(classes);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            Boolean ok = compiler.getTask(null, manager, diagnostics,
                    List.of("-encoding", "UTF-8", "-d", classes.toString()), null,
                    manager.getJavaFileObjectsFromFiles(sources.stream().map(Path::toFile).toList())).call();
            if (!Boolean.TRUE.equals(ok)) {
                return fatalFunctionalFailures("compile failed: " + summarizeDiagnostics(diagnostics));
            }
        }

        Path logDir = workspace.resolve("hidden-logs");
        Path exportDir = workspace.resolve("hidden-export");
        Files.createDirectories(logDir);
        Files.createDirectories(exportDir);
        Files.writeString(logDir.resolve("app.log"), """
                2026-05-18T09:00:00 INFO auth old login
                2026-05-19T10:15:00 ERROR auth login failed
                2026-05-19T11:00:00 WARN billing slow payment
                2026-05-19T12:00:00 ERROR billing card declined
                2026-05-20T08:00:00 INFO auth ok
                """, StandardCharsets.UTF_8);
        Files.writeString(logDir.resolve("shard-2025-12-31.log"), "old\n", StandardCharsets.UTF_8);
        Files.writeString(logDir.resolve("shard-2026-02-01.log"), "new\n", StandardCharsets.UTF_8);

        List<String> failures = new ArrayList<>();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()})) {
            Class<?> cli = Class.forName("bench.logops.LogCli", true, loader);
            Method run = cli.getMethod("run", String[].class, String.class, Path.class, Path.class);
            if (!Modifier.isStatic(run.getModifiers())) {
                failures.add("LogCli.run is not static");
                return failures;
            }
            hiddenCheck(failures, "time query", () -> {
                String out = invokeRun(run, "log query time --from 2026-05-19T00:00:00 --to 2026-05-19T23:59:59", "", logDir, exportDir);
                checkContains(failures, out, "|", "time query table");
                checkContains(failures, out, "2026-05-19T10:15:00", "time query includes in-range");
                checkNotContains(failures, out, "2026-05-18T09:00:00", "time query excludes old");
            });
            hiddenCheck(failures, "level query default", () -> {
                String out = invokeRun(run, "log query level", "", logDir, exportDir);
                checkContains(failures, out, "ERROR", "default level is ERROR");
                checkNotContains(failures, out, "WARN billing", "default level excludes WARN");
            });
            hiddenCheck(failures, "level query interactive", () -> {
                String out = invokeRun(run, "log query level", "WARN\n", logDir, exportDir);
                checkContains(failures, out, "WARN", "interactive level uses input");
                checkNotContains(failures, out, "ERROR auth", "interactive level excludes ERROR");
            });
            hiddenCheck(failures, "stat error", () -> {
                String out = invokeRun(run, "log stat error", "", logDir, exportDir);
                checkContains(failures, out, "auth", "stat includes auth");
                checkContains(failures, out, "billing", "stat includes billing");
                checkContains(failures, out, "1", "stat counts each service");
            });
            hiddenCheck(failures, "error export", () -> {
                invokeRun(run, "log stat error", "", logDir, exportDir);
                String exported = Files.readString(exportDir.resolve("errors.log"));
                checkContains(failures, exported, "ERROR auth", "export auth error");
                checkContains(failures, exported, "ERROR billing", "export billing error");
                checkNotContains(failures, exported, "WARN", "export excludes warn");
            });
            hiddenCheck(failures, "clean shard explicit", () -> {
                invokeRun(run, "log clean shard --before 2026-01-15", "", logDir, exportDir);
                check(failures, false, Files.exists(logDir.resolve("shard-2025-12-31.log")), "old shard deleted");
                check(failures, true, Files.exists(logDir.resolve("shard-2026-02-01.log")), "new shard kept");
            });
            hiddenCheck(failures, "invalid command", () -> {
                String out = invokeRun(run, "log query unknown", "", logDir, exportDir).toLowerCase(Locale.ROOT);
                checkContains(failures, out, "error", "invalid command has error");
            });
            hiddenCheck(failures, "root command required", () -> {
                String out = invokeRun(run, "query level --level ERROR", "", logDir, exportDir).toLowerCase(Locale.ROOT);
                checkContains(failures, out, "log", "missing root command mentions log");
            });
            hiddenCheck(failures, "table header", () -> {
                String out = invokeRun(run, "log query level --level ERROR", "", logDir, exportDir);
                checkContains(failures, out.toUpperCase(Locale.ROOT), "TIME", "table has time header");
                checkContains(failures, out.toUpperCase(Locale.ROOT), "LEVEL", "table has level header");
            });
            hiddenCheck(failures, "clean default", () -> {
                Files.writeString(logDir.resolve("shard-2025-11-01.log"), "old\n", StandardCharsets.UTF_8);
                invokeRun(run, "log clean shard", "", logDir, exportDir);
                check(failures, false, Files.exists(logDir.resolve("shard-2025-11-01.log")), "default before deletes before 2026-01-01");
            });
        }
        return failures;
    }

    private static List<String> fatalFunctionalFailures(String reason) {
        return List.of(
                "time query unavailable: " + reason,
                "level query default unavailable: " + reason,
                "level query interactive unavailable: " + reason,
                "stat error unavailable: " + reason,
                "error export unavailable: " + reason,
                "clean shard explicit unavailable: " + reason,
                "invalid command unavailable: " + reason,
                "root command required unavailable: " + reason,
                "table header unavailable: " + reason,
                "clean default unavailable: " + reason
        );
    }

    private static String invokeRun(Method run, String command, String input, Path logDir, Path exportDir) throws Exception {
        String[] args = command.split("\\s+");
        Object value = run.invoke(null, args, input, logDir, exportDir);
        return value == null ? "" : value.toString();
    }

    private static void hiddenCheck(List<String> failures, String label, ThrowingRunnable check) {
        int before = failures.size();
        try {
            check.run();
        } catch (Exception e) {
            failures.add(label + " failed: " + rootMessage(e));
        }
        if (failures.size() > before + 1) {
            failures.subList(before + 1, failures.size()).clear();
        }
    }

    private static int uniqueBugCount(List<String> hiddenFailures) {
        return (int) hiddenFailures.stream().map(AgentCollaborationBenchmarkIT::bugCategory).distinct().count();
    }

    private static String bugCategory(String failure) {
        int failedIndex = failure.indexOf(" failed:");
        if (failedIndex > 0) return failure.substring(0, failedIndex);
        int expectedIndex = failure.indexOf(" expected=");
        if (expectedIndex > 0) return failure.substring(0, expectedIndex);
        return failure;
    }

    private static void check(List<String> failures, Object expected, Object actual, String label) {
        if (!Objects.equals(expected, actual)) {
            failures.add(label + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void checkContains(List<String> failures, String text, String token, String label) {
        if (text == null || !text.contains(token)) failures.add(label + " missing " + token);
    }

    private static void checkNotContains(List<String> failures, String text, String token, String label) {
        if (text != null && text.contains(token)) failures.add(label + " unexpectedly contains " + token);
    }

    private static String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static String summarizeDiagnostics(DiagnosticCollector<JavaFileObject> diagnostics) {
        StringBuilder sb = new StringBuilder();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (!sb.isEmpty()) sb.append(" | ");
            sb.append("line ").append(diagnostic.getLineNumber()).append(": ")
                    .append(diagnostic.getMessage(Locale.ROOT));
        }
        return sb.toString();
    }

    private static RunResult scoredExistingRun(String mode, Path workspace, long elapsedMs, boolean llmRunCompleted) {
        return new RunResult(mode, workspace, elapsedMs, llmRunCompleted,
                "re-evaluated existing generated files", evaluate(workspace));
    }

    private static Path writeReport(Path root, LlmClient llm, RunResult single, RunResult team) throws Exception {
        return writeReport(root, llm.getProviderName(), llm.getModelName(), single, team,
                "single task run once per mode; treat as a sample comparison, not a statistically significant benchmark");
    }

    private static Path writeReport(Path root, String provider, String model, RunResult single, RunResult team,
                                    String note) throws Exception {
        ObjectNode report = JSON.createObjectNode();
        report.put("created_at", Instant.now().toString());
        report.put("provider", provider);
        report.put("model", model);
        report.put("sample_size_per_mode", 1);
        report.put("benchmark_task", "implement nested log operations CLI");
        report.put("completion_metric", "hidden behavior checks passed / hidden behavior checks total");
        report.put("hidden_failure_metric", "hidden validation failures / hidden validation checks total");
        report.put("unique_bug_metric", "deduplicated functional failure categories / hidden validation checks total");
        report.set("single_agent", toJson(single));
        report.set("planner_worker_reviewer", toJson(team));

        ObjectNode comparison = report.putObject("comparison");
        comparison.put("completion_rate_delta_pct_points",
                roundPctPoints(team.evaluation.completionRate() - single.evaluation.completionRate()));
        comparison.put("execution_time_reduction_pct", reduction(single.elapsedMs, team.elapsedMs));
        comparison.put("hidden_failure_rate_reduction_pct",
                reduction(single.evaluation.hiddenFailureRate(), team.evaluation.hiddenFailureRate()));
        comparison.put("unique_bug_rate_reduction_pct",
                reduction(single.evaluation.uniqueBugRate(), team.evaluation.uniqueBugRate()));
        comparison.put("note", note);

        Path file = root.resolve(note.startsWith("corrected") ? "agent-collaboration-benchmark.corrected.json"
                : "agent-collaboration-benchmark.json");
        Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
        return file;
    }

    private static ObjectNode toJson(RunResult run) {
        ObjectNode node = JSON.createObjectNode();
        node.put("mode", run.mode);
        node.put("workspace", run.workspace.toString());
        node.put("elapsed_ms", run.elapsedMs);
        node.put("llm_run_completed", run.llmRunCompleted);
        node.put("completion_rate", run.evaluation.completionRate());
        node.put("completion_passed", run.evaluation.completionPassed());
        node.put("completion_total", run.evaluation.completionTotal());
        node.put("hidden_failure_rate", run.evaluation.hiddenFailureRate());
        node.put("hidden_failure_count", run.evaluation.hiddenFailures);
        node.put("hidden_test_total", run.evaluation.hiddenTotal());
        node.put("unique_bug_rate", run.evaluation.uniqueBugRate());
        node.put("unique_bug_count", run.evaluation.uniqueBugCount);
        node.putPOJO("hidden_failures", run.evaluation.hiddenFailureDetails);
        node.put("output_preview", preview(run.output));
        return node;
    }

    private static double roundPctPoints(double value) {
        return Math.round(value * 10_000.0) / 100.0;
    }

    private static double reduction(double baseline, double candidate) {
        if (baseline <= 0) return 0.0;
        return Math.round(((baseline - candidate) / baseline) * 10_000.0) / 100.0;
    }

    private static String preview(String text) {
        if (text == null) return "";
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() <= 800 ? compact : compact.substring(0, 800);
    }

    private static boolean isLlmFailure(String output) {
        if (output == null) return false;
        return output.contains("调用 LLM 失败")
                || output.contains("API请求失败")
                || output.contains("LLM run failed");
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private record RunResult(String mode, Path workspace, long elapsedMs, boolean llmRunCompleted,
                             String output, Evaluation evaluation) {
    }

    private record Evaluation(int hiddenFailures, int uniqueBugCount, List<String> hiddenFailureDetails) {
        double completionRate() {
            int total = completionTotal();
            return total == 0 ? 0.0 : (double) completionPassed() / total;
        }

        int completionPassed() {
            return Math.max(0, HIDDEN_TEST_TOTAL - hiddenFailures);
        }

        int completionTotal() {
            return HIDDEN_TEST_TOTAL;
        }

        double hiddenFailureRate() {
            return HIDDEN_TEST_TOTAL == 0 ? 0.0 : (double) hiddenFailures / HIDDEN_TEST_TOTAL;
        }

        double uniqueBugRate() {
            return HIDDEN_TEST_TOTAL == 0 ? 0.0 : (double) uniqueBugCount / HIDDEN_TEST_TOTAL;
        }

        int hiddenTotal() {
            return HIDDEN_TEST_TOTAL;
        }
    }

    private static final class NoOpMemoryManager extends MemoryManager {
        private NoOpMemoryManager(Path storageDir) {
            super(null, 32768, 200000, new LongTermMemory(storageDir.toFile()));
        }
    }

    private static final class LimitedToolRegistry extends ToolRegistry {
        private final Set<String> allowedTools;

        private LimitedToolRegistry(Set<String> allowedTools) {
            this.allowedTools = Set.copyOf(allowedTools);
        }

        @Override
        public List<com.paicli.llm.LlmClient.Tool> getToolDefinitions() {
            return super.getToolDefinitions().stream()
                    .filter(tool -> allowedTools.contains(tool.name()))
                    .toList();
        }

        @Override
        public ToolOutput executeToolOutput(String name, String argumentsJson) {
            if (!allowedTools.contains(name)) {
                return ToolOutput.text("benchmark policy rejected tool: " + name);
            }
            return super.executeToolOutput(name, argumentsJson);
        }
    }
}
