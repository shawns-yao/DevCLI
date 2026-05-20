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
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCollaborationBenchmarkIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int HIDDEN_TEST_TOTAL = 10;
    private static final int DEFAULT_MAX_LLM_ATTEMPTS = 3;
    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "read_file", "write_file", "list_dir", "execute_command"
    );

    @Test
    @DisplayName("single Agent and Planner/Worker/Reviewer run the same nested CLI task suite")
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

        List<TaskComparison> comparisons = new ArrayList<>();
        for (BenchmarkTask task : selectedBenchmarkTasks()) {
            Path taskRoot = root.resolve(task.id());
            Files.createDirectories(taskRoot);
            RunResult single = runSingleWithRetries(llm, task, taskRoot.resolve("single"));
            RunResult team = runTeamWithRetries(llm, task, taskRoot.resolve("team"));
            comparisons.add(new TaskComparison(task, single, team));
        }

        Path report = writeSuiteReport(root, llm, comparisons);
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

    private static RunResult runSingleWithRetries(LlmClient llm, BenchmarkTask task, Path workspace) throws Exception {
        return runWithRetries("single-agent", task, workspace,
                attemptWorkspace -> runSingleAttempt(llm, task, attemptWorkspace));
    }

    private static RunResult runTeamWithRetries(LlmClient llm, BenchmarkTask task, Path workspace) throws Exception {
        return runWithRetries("planner-worker-reviewer", task, workspace,
                attemptWorkspace -> runTeamAttempt(llm, task, attemptWorkspace));
    }

    private static RunResult runWithRetries(String mode, BenchmarkTask task, Path workspace,
                                            BenchmarkRun run) throws Exception {
        int maxAttempts = Math.max(1, Integer.getInteger("paicli.benchmark.llm.maxAttempts", DEFAULT_MAX_LLM_ATTEMPTS));
        RunResult last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Path attemptWorkspace = attempt == 1
                    ? workspace
                    : workspace.resolveSibling(workspace.getFileName() + "-attempt-" + attempt);
            RunResult result = run.execute(attemptWorkspace).withAttempt(attempt);
            last = result;
            if (result.llmRunCompleted()) {
                return result;
            }
            System.out.println("Benchmark LLM failure, retrying: task=" + task.id()
                    + ", mode=" + mode + ", attempt=" + attempt + "/" + maxAttempts);
        }
        return last == null
                ? new RunResult(mode, workspace, 0, false, 0, "LLM run failed before start", evaluate(workspace, task))
                : last;
    }

    private static RunResult runSingleAttempt(LlmClient llm, BenchmarkTask task, Path workspace) throws Exception {
        Files.createDirectories(workspace);
        LimitedToolRegistry registry = registryFor(workspace);
        long started = System.nanoTime();
        String output;
        try (Agent agent = new Agent(llm, registry)) {
            agent.setRenderer(new PlainRenderer());
            output = agent.run(promptFor("single-agent", task));
        } catch (Exception e) {
            return result("single-agent", task, workspace, started, false, "LLM run failed: " + e.getMessage());
        }
        return result("single-agent", task, workspace, started, !isLlmFailure(output), output);
    }

    private static RunResult runTeamAttempt(LlmClient llm, BenchmarkTask task, Path workspace) throws Exception {
        Files.createDirectories(workspace);
        LimitedToolRegistry registry = registryFor(workspace);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        long started = System.nanoTime();
        String output;
        try (NoOpMemoryManager memory = new NoOpMemoryManager(workspace.resolve(".memory"))) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(llm, registry, memory, out);
            output = orchestrator.run(promptFor("planner-worker-reviewer", task));
        } catch (Exception e) {
            return result("planner-worker-reviewer", task, workspace, started, false,
                    buffer.toString(StandardCharsets.UTF_8) + "\nLLM run failed: " + e.getMessage());
        }
        String combined = buffer.toString(StandardCharsets.UTF_8) + "\n" + output;
        return result("planner-worker-reviewer", task, workspace, started, !isLlmFailure(combined), combined);
    }

    private static RunResult result(String mode, BenchmarkTask task, Path workspace,
                                    long started, boolean completed, String output) {
        return new RunResult(mode, workspace, elapsedMs(started), completed, 1, output, evaluate(workspace, task));
    }

    private static LimitedToolRegistry registryFor(Path workspace) {
        LimitedToolRegistry registry = new LimitedToolRegistry(ALLOWED_TOOLS);
        registry.setProjectPath(workspace.toString());
        return registry;
    }

    private static List<BenchmarkTask> benchmarkTasks() {
        return List.of(logOpsTask(), salesOpsTask(), incidentOpsTask());
    }

    private static List<BenchmarkTask> selectedBenchmarkTasks() {
        String selected = System.getProperty("paicli.benchmark.tasks", "").trim();
        if (selected.isBlank()) {
            return benchmarkTasks();
        }
        Set<String> ids = Set.copyOf(Arrays.stream(selected.split(","))
                .map(String::trim)
                .filter(id -> !id.isBlank())
                .toList());
        return benchmarkTasks().stream()
                .filter(task -> ids.contains(task.id()))
                .toList();
    }

    private static BenchmarkTask logOpsTask() {
        return new BenchmarkTask("logops", "implement nested log operations CLI", """
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

                Testable behavior:
                - expose a public static Java method returning String and accepting
                  (String[] args, String terminalInput, java.nio.file.Path dataDir,
                  java.nio.file.Path exportDir). Class name and method name are not fixed.

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

                Suggested concerns: parsing, storage, query, statistics, cleanup, table formatting, export, CLI dispatch.

                Planner/Worker/Reviewer mode should plan module dependencies first, then implement, then review
                command coverage, defaults, interactive input, table output, export, and clean behavior.
                """);
    }

    private static BenchmarkTask salesOpsTask() {
        return new BenchmarkTask("salesops", "implement nested sales operations CLI", """
                Implement a bounded Java benchmark task. Do not edit files outside the current project root.
                Mode: ${mode}.

                Build a sales analytics operations CLI under src/main/java/bench/salesops/.

                Required CLI:
                - level 1 command: sales
                - level 2 subcommands: order, revenue, clean
                - level 3 commands:
                  * sales order query --status <PAID|REFUNDED|PENDING>
                  * sales order daily --date <yyyy-MM-dd>
                  * sales revenue region
                  * sales clean export --before <yyyy-MM-dd>

                Testable behavior:
                - expose a public static Java method returning String and accepting
                  (String[] args, String terminalInput, java.nio.file.Path dataDir,
                  java.nio.file.Path exportDir). Class name and method name are not fixed.

                Order format:
                yyyy-MM-dd,status,region,amount,orderId
                Example: 2026-05-19,PAID,east,120.50,O-1001

                Behavior:
                - support default values: missing status defaults to PAID when terminalInput is blank;
                  missing --date defaults to 2026-05-19; missing --before defaults to 2026-01-01.
                - support interactive terminal input: when --status is missing and terminalInput contains a nonblank
                  line, use that line as the status.
                - return table-formatted output with a header and pipe separators.
                - export filtered order rows to exportDir/orders.csv whenever order query or order daily runs.
                - revenue region aggregates PAID amount by region.
                - clean export deletes only files named export-<yyyy-MM-dd>.csv older than --before.
                - invalid command or argument returns a clear error string, not an exception.

                Suggested concerns: parsing, CSV loading, filtering, aggregation, cleanup, table formatting, export, CLI dispatch.

                Planner/Worker/Reviewer mode should plan module dependencies first, then implement, then review
                command coverage, defaults, interactive input, table output, export, and clean behavior.
                """);
    }

    private static BenchmarkTask incidentOpsTask() {
        return new BenchmarkTask("incidentops", "implement nested incident operations CLI", """
                Implement a bounded Java benchmark task. Do not edit files outside the current project root.
                Mode: ${mode}.

                Build an incident response operations CLI under src/main/java/bench/incidentops/.

                Required CLI:
                - level 1 command: incident
                - level 2 subcommands: ticket, stat, archive
                - level 3 commands:
                  * incident ticket query --severity <P1|P2|P3>
                  * incident ticket service --name <service>
                  * incident stat severity
                  * incident archive resolved --before <yyyy-MM-dd>

                Testable behavior:
                - expose a public static Java method returning String and accepting
                  (String[] args, String terminalInput, java.nio.file.Path dataDir,
                  java.nio.file.Path exportDir). Class name and method name are not fixed.

                Incident format:
                yyyy-MM-dd'T'HH:mm:ss severity service status message
                Example: 2026-05-19T10:15:00 P1 auth OPEN login outage

                Behavior:
                - support default values: missing severity defaults to P1 when terminalInput is blank;
                  missing --before defaults to 2026-01-01.
                - support interactive terminal input: when --severity is missing and terminalInput contains a nonblank
                  line, use that line as the severity.
                - return table-formatted output with a header and pipe separators.
                - export P1 incidents to exportDir/p1-incidents.log whenever ticket query P1 or stat severity runs.
                - stat severity aggregates incident count by severity.
                - archive resolved deletes only files named incident-<yyyy-MM-dd>.log older than --before.
                - invalid command or argument returns a clear error string, not an exception.

                Suggested concerns: parsing, storage, query, statistics, archive cleanup, table formatting, export, CLI dispatch.

                Planner/Worker/Reviewer mode should plan module dependencies first, then implement, then review
                command coverage, defaults, interactive input, table output, export, and archive behavior.
                """);
    }

    private static String promptFor(String mode, BenchmarkTask task) {
        return task.prompt().replace("${mode}", mode);
    }

    private static Evaluation evaluate(Path workspace, BenchmarkTask task) {
        List<String> hiddenFailures = new ArrayList<>();
        try {
            hiddenFailures.addAll(switch (task.id()) {
                case "logops" -> runLogOpsHiddenChecks(workspace);
                case "salesops" -> runSalesOpsHiddenChecks(workspace);
                case "incidentops" -> runIncidentOpsHiddenChecks(workspace);
                default -> fatalFunctionalFailures("unknown benchmark task: " + task.id());
            });
        } catch (Exception e) {
            hiddenFailures.addAll(fatalFunctionalFailures("hidden validation crashed: " + e.getMessage()));
        }
        return new Evaluation(hiddenFailures.size(),
                uniqueBugCount(hiddenFailures), List.copyOf(hiddenFailures));
    }

    private static List<String> runLogOpsHiddenChecks(Path workspace) throws Exception {
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
            CliEntrypoint run = discoverEntrypoint(classes, loader, "log query level --level ERROR", "", logDir, exportDir);
            hiddenCheck(failures, "time query", () -> {
                String out = run.invoke("log query time --from 2026-05-19T00:00:00 --to 2026-05-19T23:59:59", "", logDir, exportDir);
                checkContains(failures, out, "|", "time query table");
                checkContains(failures, out, "2026-05-19T10:15:00", "time query includes in-range");
                checkNotContains(failures, out, "2026-05-18T09:00:00", "time query excludes old");
            });
            hiddenCheck(failures, "level query default", () -> {
                String out = run.invoke("log query level", "", logDir, exportDir);
                checkContains(failures, out, "ERROR", "default level is ERROR");
                checkNotContains(failures, out, "WARN billing", "default level excludes WARN");
            });
            hiddenCheck(failures, "level query interactive", () -> {
                String out = run.invoke("log query level", "WARN\n", logDir, exportDir);
                checkContains(failures, out, "WARN", "interactive level uses input");
                checkNotContains(failures, out, "ERROR auth", "interactive level excludes ERROR");
            });
            hiddenCheck(failures, "stat error", () -> {
                String out = run.invoke("log stat error", "", logDir, exportDir);
                checkContains(failures, out, "auth", "stat includes auth");
                checkContains(failures, out, "billing", "stat includes billing");
                checkContains(failures, out, "1", "stat counts each service");
            });
            hiddenCheck(failures, "error export", () -> {
                run.invoke("log stat error", "", logDir, exportDir);
                String exported = Files.readString(exportDir.resolve("errors.log"));
                checkContains(failures, exported, "ERROR auth", "export auth error");
                checkContains(failures, exported, "ERROR billing", "export billing error");
                checkNotContains(failures, exported, "WARN", "export excludes warn");
            });
            hiddenCheck(failures, "clean shard explicit", () -> {
                run.invoke("log clean shard --before 2026-01-15", "", logDir, exportDir);
                check(failures, false, Files.exists(logDir.resolve("shard-2025-12-31.log")), "old shard deleted");
                check(failures, true, Files.exists(logDir.resolve("shard-2026-02-01.log")), "new shard kept");
            });
            hiddenCheck(failures, "invalid command", () -> {
                String out = run.invoke("log query unknown", "", logDir, exportDir).toLowerCase(Locale.ROOT);
                checkContains(failures, out, "error", "invalid command has error");
            });
            hiddenCheck(failures, "root command required", () -> {
                String out = run.invoke("query level --level ERROR", "", logDir, exportDir).toLowerCase(Locale.ROOT);
                checkContains(failures, out, "log", "missing root command mentions log");
            });
            hiddenCheck(failures, "table header", () -> {
                String out = run.invoke("log query level --level ERROR", "", logDir, exportDir);
                checkContains(failures, out.toUpperCase(Locale.ROOT), "TIME", "table has time header");
                checkContains(failures, out.toUpperCase(Locale.ROOT), "LEVEL", "table has level header");
            });
            hiddenCheck(failures, "clean default", () -> {
                Files.writeString(logDir.resolve("shard-2025-11-01.log"), "old\n", StandardCharsets.UTF_8);
                run.invoke("log clean shard", "", logDir, exportDir);
                check(failures, false, Files.exists(logDir.resolve("shard-2025-11-01.log")), "default before deletes before 2026-01-01");
            });
        }
        return failures;
    }

    private static List<String> runSalesOpsHiddenChecks(Path workspace) throws Exception {
        CompiledWorkspace compiled = compileWorkspace(workspace);
        if (!compiled.failures().isEmpty()) return compiled.failures();

        Path dataDir = workspace.resolve("hidden-sales");
        Path exportDir = workspace.resolve("hidden-export");
        Files.createDirectories(dataDir);
        Files.createDirectories(exportDir);
        Files.writeString(dataDir.resolve("orders.csv"), """
                2026-05-18,PAID,east,80.00,O-1000
                2026-05-19,PAID,east,120.50,O-1001
                2026-05-19,REFUNDED,west,30.00,O-1002
                2026-05-19,PENDING,north,45.00,O-1003
                2026-05-20,PAID,west,210.00,O-1004
                """, StandardCharsets.UTF_8);
        Files.writeString(dataDir.resolve("export-2025-12-31.csv"), "old\n", StandardCharsets.UTF_8);
        Files.writeString(dataDir.resolve("export-2026-02-01.csv"), "new\n", StandardCharsets.UTF_8);

        List<String> failures = new ArrayList<>();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{compiled.classes().toUri().toURL()})) {
            CliEntrypoint run = discoverEntrypoint(compiled.classes(), loader,
                    "sales order query --status PAID", "", dataDir, exportDir);
            hiddenCheck(failures, "sales status default", () -> {
                String out = run.invoke("sales order query", "", dataDir, exportDir);
                checkContains(failures, out, "PAID", "default status is PAID");
                checkNotContains(failures, out, "REFUNDED", "default status excludes refunded");
            });
            hiddenCheck(failures, "sales status interactive", () -> {
                String out = run.invoke("sales order query", "REFUNDED\n", dataDir, exportDir);
                checkContains(failures, out, "REFUNDED", "interactive status uses input");
                checkNotContains(failures, out, "PENDING", "interactive status excludes pending");
            });
            hiddenCheck(failures, "sales daily default", () -> {
                String out = run.invoke("sales order daily", "", dataDir, exportDir);
                checkContains(failures, out, "2026-05-19", "default date is 2026-05-19");
                checkNotContains(failures, out, "2026-05-18", "default date excludes other days");
            });
            hiddenCheck(failures, "sales revenue region", () -> {
                String out = run.invoke("sales revenue region", "", dataDir, exportDir);
                checkContains(failures, out, "east", "revenue includes east");
                checkContains(failures, out, "west", "revenue includes west");
                checkContains(failures, out, "200", "revenue sums east paid amount");
            });
            hiddenCheck(failures, "sales export", () -> {
                run.invoke("sales order query --status PAID", "", dataDir, exportDir);
                String exported = Files.readString(exportDir.resolve("orders.csv"));
                checkContains(failures, exported, "O-1001", "export includes paid order");
                checkNotContains(failures, exported, "O-1002", "export excludes refunded");
            });
            hiddenCheck(failures, "sales clean explicit", () -> {
                run.invoke("sales clean export --before 2026-01-15", "", dataDir, exportDir);
                check(failures, false, Files.exists(dataDir.resolve("export-2025-12-31.csv")), "old export deleted");
                check(failures, true, Files.exists(dataDir.resolve("export-2026-02-01.csv")), "new export kept");
            });
            hiddenCheck(failures, "sales invalid command", () -> {
                String out = run.invoke("sales order unknown", "", dataDir, exportDir).toLowerCase(Locale.ROOT);
                checkContains(failures, out, "error", "invalid sales command has error");
            });
            hiddenCheck(failures, "sales root command required", () -> {
                String out = run.invoke("order query --status PAID", "", dataDir, exportDir).toLowerCase(Locale.ROOT);
                checkContains(failures, out, "sales", "missing sales root mentions sales");
            });
            hiddenCheck(failures, "sales table header", () -> {
                String out = run.invoke("sales order query --status PAID", "", dataDir, exportDir);
                checkContains(failures, out, "|", "sales table has pipe");
                checkContains(failures, out.toUpperCase(Locale.ROOT), "STATUS", "sales table has status header");
            });
            hiddenCheck(failures, "sales clean default", () -> {
                Files.writeString(dataDir.resolve("export-2025-11-01.csv"), "old\n", StandardCharsets.UTF_8);
                run.invoke("sales clean export", "", dataDir, exportDir);
                check(failures, false, Files.exists(dataDir.resolve("export-2025-11-01.csv")),
                        "default before deletes exports before 2026-01-01");
            });
        }
        return failures;
    }

    private static List<String> runIncidentOpsHiddenChecks(Path workspace) throws Exception {
        CompiledWorkspace compiled = compileWorkspace(workspace);
        if (!compiled.failures().isEmpty()) return compiled.failures();

        Path dataDir = workspace.resolve("hidden-incidents");
        Path exportDir = workspace.resolve("hidden-export");
        Files.createDirectories(dataDir);
        Files.createDirectories(exportDir);
        Files.writeString(dataDir.resolve("incidents.log"), """
                2026-05-18T09:00:00 P3 billing RESOLVED old payment issue
                2026-05-19T10:15:00 P1 auth OPEN login outage
                2026-05-19T11:00:00 P2 billing OPEN checkout slow
                2026-05-19T12:00:00 P1 search RESOLVED indexing down
                2026-05-20T08:00:00 P3 auth OPEN minor alert
                """, StandardCharsets.UTF_8);
        Files.writeString(dataDir.resolve("incident-2025-12-31.log"), "old\n", StandardCharsets.UTF_8);
        Files.writeString(dataDir.resolve("incident-2026-02-01.log"), "new\n", StandardCharsets.UTF_8);

        List<String> failures = new ArrayList<>();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{compiled.classes().toUri().toURL()})) {
            CliEntrypoint run = discoverEntrypoint(compiled.classes(), loader,
                    "incident ticket query --severity P1", "", dataDir, exportDir);
            hiddenCheck(failures, "incident severity default", () -> {
                String out = run.invoke("incident ticket query", "", dataDir, exportDir);
                checkContains(failures, out, "P1", "default severity is P1");
                checkNotContains(failures, out, "P2 billing", "default severity excludes P2");
            });
            hiddenCheck(failures, "incident severity interactive", () -> {
                String out = run.invoke("incident ticket query", "P2\n", dataDir, exportDir);
                checkContains(failures, out, "P2", "interactive severity uses input");
                checkNotContains(failures, out, "P1 auth", "interactive severity excludes P1");
            });
            hiddenCheck(failures, "incident service query", () -> {
                String out = run.invoke("incident ticket service --name auth", "", dataDir, exportDir);
                checkContains(failures, out, "auth", "service query includes auth");
                checkNotContains(failures, out, "billing", "service query excludes billing");
            });
            hiddenCheck(failures, "incident stat severity", () -> {
                String out = run.invoke("incident stat severity", "", dataDir, exportDir);
                checkContains(failures, out, "P1", "stat includes P1");
                checkContains(failures, out, "P2", "stat includes P2");
                checkContains(failures, out, "2", "stat counts P1");
            });
            hiddenCheck(failures, "incident p1 export", () -> {
                run.invoke("incident stat severity", "", dataDir, exportDir);
                String exported = Files.readString(exportDir.resolve("p1-incidents.log"));
                checkContains(failures, exported, "P1 auth", "export includes P1 auth");
                checkContains(failures, exported, "P1 search", "export includes P1 search");
                checkNotContains(failures, exported, "P2", "export excludes P2");
            });
            hiddenCheck(failures, "incident archive explicit", () -> {
                run.invoke("incident archive resolved --before 2026-01-15", "", dataDir, exportDir);
                check(failures, false, Files.exists(dataDir.resolve("incident-2025-12-31.log")),
                        "old incident archive deleted");
                check(failures, true, Files.exists(dataDir.resolve("incident-2026-02-01.log")),
                        "new incident archive kept");
            });
            hiddenCheck(failures, "incident invalid command", () -> {
                String out = run.invoke("incident ticket unknown", "", dataDir, exportDir).toLowerCase(Locale.ROOT);
                checkContains(failures, out, "error", "invalid incident command has error");
            });
            hiddenCheck(failures, "incident root command required", () -> {
                String out = run.invoke("ticket query --severity P1", "", dataDir, exportDir).toLowerCase(Locale.ROOT);
                checkContains(failures, out, "incident", "missing incident root mentions incident");
            });
            hiddenCheck(failures, "incident table header", () -> {
                String out = run.invoke("incident ticket query --severity P1", "", dataDir, exportDir);
                checkContains(failures, out, "|", "incident table has pipe");
                checkContains(failures, out.toUpperCase(Locale.ROOT), "SEVERITY", "incident table has severity header");
            });
            hiddenCheck(failures, "incident archive default", () -> {
                Files.writeString(dataDir.resolve("incident-2025-11-01.log"), "old\n", StandardCharsets.UTF_8);
                run.invoke("incident archive resolved", "", dataDir, exportDir);
                check(failures, false, Files.exists(dataDir.resolve("incident-2025-11-01.log")),
                        "default before deletes incidents before 2026-01-01");
            });
        }
        return failures;
    }

    private static CompiledWorkspace compileWorkspace(Path workspace) throws Exception {
        Path sourceRoot = workspace.resolve("src/main/java");
        if (!Files.exists(sourceRoot)) return new CompiledWorkspace(workspace.resolve("classes"),
                fatalFunctionalFailures("compile failed: no Java source files"));
        List<Path> sources;
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            sources = stream.filter(path -> path.toString().endsWith(".java")).toList();
        }
        if (sources.isEmpty()) return new CompiledWorkspace(workspace.resolve("classes"),
                fatalFunctionalFailures("compile failed: no Java source files"));

        Path classes = workspace.resolve("classes");
        Files.createDirectories(classes);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            Boolean ok = compiler.getTask(null, manager, diagnostics,
                    List.of("-encoding", "UTF-8", "-d", classes.toString()), null,
                    manager.getJavaFileObjectsFromFiles(sources.stream().map(Path::toFile).toList())).call();
            if (!Boolean.TRUE.equals(ok)) {
                return new CompiledWorkspace(classes,
                        fatalFunctionalFailures("compile failed: " + summarizeDiagnostics(diagnostics)));
            }
        }
        return new CompiledWorkspace(classes, List.of());
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

    private static CliEntrypoint discoverEntrypoint(Path classes, URLClassLoader loader, String probeCommand,
                                                   String input, Path dataDir, Path exportDir) throws Exception {
        List<Method> candidates = new ArrayList<>();
        for (String className : compiledClassNames(classes)) {
            Class<?> type = Class.forName(className, true, loader);
            for (Method method : type.getMethods()) {
                if (isCliEntrypoint(method)) {
                    candidates.add(method);
                }
            }
        }
        if (candidates.isEmpty()) {
            throw new IllegalStateException("no public static String CLI entrypoint accepting String[], String, Path, Path");
        }
        for (Method method : candidates) {
            CliEntrypoint entrypoint = new CliEntrypoint(method);
            try {
                String out = entrypoint.invoke(probeCommand, input, dataDir, exportDir);
                if (out != null && !out.isBlank()) {
                    return entrypoint;
                }
            } catch (Exception ignored) {
                // Try the next candidate; hidden checks validate behavior after discovery.
            }
        }
        return new CliEntrypoint(candidates.get(0));
    }

    private static List<String> compiledClassNames(Path classes) throws Exception {
        try (Stream<Path> stream = Files.walk(classes)) {
            return stream.filter(path -> path.toString().endsWith(".class"))
                    .filter(path -> !path.getFileName().toString().contains("$"))
                    .map(classes::relativize)
                    .map(Path::toString)
                    .map(name -> name.substring(0, name.length() - ".class".length())
                            .replace('\\', '.')
                            .replace('/', '.'))
                    .toList();
        }
    }

    private static boolean isCliEntrypoint(Method method) {
        Class<?>[] parameters = method.getParameterTypes();
        return java.lang.reflect.Modifier.isPublic(method.getModifiers())
                && java.lang.reflect.Modifier.isStatic(method.getModifiers())
                && method.getReturnType().equals(String.class)
                && parameters.length == 4
                && parameters[0].equals(String[].class)
                && parameters[1].equals(String.class)
                && parameters[2].equals(Path.class)
                && parameters[3].equals(Path.class);
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

    private record CliEntrypoint(Method method) {
        String invoke(String command, String input, Path dataDir, Path exportDir) throws Exception {
            return invokeRun(method, command, input, dataDir, exportDir);
        }
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
        return new RunResult(mode, workspace, elapsedMs, llmRunCompleted, 1,
                "re-evaluated existing generated files", evaluate(workspace, logOpsTask()));
    }

    private static Path writeSuiteReport(Path root, LlmClient llm, List<TaskComparison> comparisons) throws Exception {
        ObjectNode report = JSON.createObjectNode();
        report.put("created_at", Instant.now().toString());
        report.put("provider", llm.getProviderName());
        report.put("model", llm.getModelName());
        report.put("sample_size_per_mode", comparisons.size());
        report.put("completion_metric", "hidden behavior checks passed / hidden behavior checks total");
        report.put("hidden_failure_metric", "hidden validation failures / hidden validation checks total");
        report.put("unique_bug_metric", "deduplicated functional failure categories / hidden validation checks total");
        report.put("note", "task suite run once per task per mode; report averages across distinct task specs");

        com.fasterxml.jackson.databind.node.ArrayNode tasks = report.putArray("tasks");
        for (TaskComparison comparison : comparisons) {
            ObjectNode taskNode = tasks.addObject();
            taskNode.put("task_id", comparison.task().id());
            taskNode.put("benchmark_task", comparison.task().name());
            taskNode.set("single_agent", toJson(comparison.single()));
            taskNode.set("planner_worker_reviewer", toJson(comparison.team()));
            taskNode.set("comparison", comparisonJson(comparison.single(), comparison.team()));
        }

        ObjectNode aggregate = report.putObject("aggregate");
        aggregate.set("single_agent", aggregateJson(comparisons, true));
        aggregate.set("planner_worker_reviewer", aggregateJson(comparisons, false));
        aggregate.set("comparison", aggregateComparisonJson(comparisons));
        List<TaskComparison> completedPairs = comparisons.stream()
                .filter(comparison -> comparison.single().llmRunCompleted()
                        && comparison.team().llmRunCompleted())
                .toList();
        aggregate.set("completed_pairs_comparison", aggregateComparisonJson(completedPairs));

        Path file = root.resolve("agent-collaboration-benchmark.json");
        Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
        return file;
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

        ObjectNode comparison = comparisonJson(single, team);
        comparison.put("note", note);
        report.set("comparison", comparison);

        Path file = root.resolve(note.startsWith("corrected") ? "agent-collaboration-benchmark.corrected.json"
                : "agent-collaboration-benchmark.json");
        Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
        return file;
    }

    private static ObjectNode comparisonJson(RunResult single, RunResult team) {
        ObjectNode comparison = JSON.createObjectNode();
        comparison.put("completion_rate_delta_pct_points",
                roundPctPoints(team.evaluation.completionRate() - single.evaluation.completionRate()));
        comparison.put("execution_time_reduction_pct", reduction(single.elapsedMs, team.elapsedMs));
        comparison.put("hidden_failure_rate_reduction_pct",
                reduction(single.evaluation.hiddenFailureRate(), team.evaluation.hiddenFailureRate()));
        comparison.put("unique_bug_rate_reduction_pct",
                reduction(single.evaluation.uniqueBugRate(), team.evaluation.uniqueBugRate()));
        return comparison;
    }

    private static ObjectNode aggregateJson(List<TaskComparison> comparisons, boolean single) {
        ObjectNode node = JSON.createObjectNode();
        List<RunResult> runs = comparisons.stream()
                .map(comparison -> single ? comparison.single() : comparison.team())
                .toList();
        node.put("avg_completion_rate", average(runs.stream().mapToDouble(run -> run.evaluation.completionRate()).toArray()));
        node.put("avg_hidden_failure_rate", average(runs.stream().mapToDouble(run -> run.evaluation.hiddenFailureRate()).toArray()));
        node.put("avg_unique_bug_rate", average(runs.stream().mapToDouble(run -> run.evaluation.uniqueBugRate()).toArray()));
        node.put("avg_elapsed_ms", Math.round(average(runs.stream().mapToDouble(run -> run.elapsedMs).toArray())));
        node.put("llm_completed_count", runs.stream().filter(RunResult::llmRunCompleted).count());
        node.put("task_count", runs.size());
        return node;
    }

    private static ObjectNode aggregateComparisonJson(List<TaskComparison> comparisons) {
        double singleCompletion = average(comparisons.stream()
                .mapToDouble(comparison -> comparison.single().evaluation.completionRate()).toArray());
        double teamCompletion = average(comparisons.stream()
                .mapToDouble(comparison -> comparison.team().evaluation.completionRate()).toArray());
        double singleFailure = average(comparisons.stream()
                .mapToDouble(comparison -> comparison.single().evaluation.hiddenFailureRate()).toArray());
        double teamFailure = average(comparisons.stream()
                .mapToDouble(comparison -> comparison.team().evaluation.hiddenFailureRate()).toArray());
        double singleUniqueBug = average(comparisons.stream()
                .mapToDouble(comparison -> comparison.single().evaluation.uniqueBugRate()).toArray());
        double teamUniqueBug = average(comparisons.stream()
                .mapToDouble(comparison -> comparison.team().evaluation.uniqueBugRate()).toArray());

        ObjectNode node = JSON.createObjectNode();
        node.put("task_count", comparisons.size());
        node.put("avg_completion_rate_delta_pct_points", roundPctPoints(teamCompletion - singleCompletion));
        node.put("avg_hidden_failure_rate_reduction_pct", reduction(singleFailure, teamFailure));
        node.put("avg_unique_bug_rate_reduction_pct", reduction(singleUniqueBug, teamUniqueBug));
        node.put("note", "execution time intentionally excluded from quality claim");
        return node;
    }

    private static double average(double[] values) {
        if (values.length == 0) return 0.0;
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        return Math.round((sum / values.length) * 10_000.0) / 10_000.0;
    }

    private static ObjectNode toJson(RunResult run) {
        ObjectNode node = JSON.createObjectNode();
        node.put("mode", run.mode);
        node.put("workspace", run.workspace.toString());
        node.put("elapsed_ms", run.elapsedMs);
        node.put("llm_run_completed", run.llmRunCompleted);
        node.put("attempts", run.attempts);
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

    private record BenchmarkTask(String id, String name, String prompt) {
    }

    private record TaskComparison(BenchmarkTask task, RunResult single, RunResult team) {
    }

    private record CompiledWorkspace(Path classes, List<String> failures) {
    }

    private record RunResult(String mode, Path workspace, long elapsedMs, boolean llmRunCompleted,
                             int attempts, String output, Evaluation evaluation) {
        RunResult withAttempt(int attempt) {
            return new RunResult(mode, workspace, elapsedMs, llmRunCompleted, attempt, output, evaluation);
        }
    }

    @FunctionalInterface
    private interface BenchmarkRun {
        RunResult execute(Path workspace) throws Exception;
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
