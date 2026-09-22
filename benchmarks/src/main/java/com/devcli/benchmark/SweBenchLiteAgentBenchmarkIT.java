package com.devcli.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.devcli.agent.Agent;
import com.devcli.agent.AgentBenchmarkTestSupport;
import com.devcli.agent.AgentOrchestrator;
import com.devcli.config.DevCliConfig;
import com.devcli.llm.LlmClient;
import com.devcli.llm.LlmClientFactory;
import com.devcli.memory.LongTermMemory;
import com.devcli.memory.MemoryManager;
import com.devcli.render.PlainRenderer;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SweBenchLiteAgentBenchmarkIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "read_file", "write_file", "list_dir", "execute_command", "grep_code"
    );

    @Test
    void generatesSweBenchLitePredictionsForOfficialHarness() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("devcli.benchmark.swebench"),
                "set -Ddevcli.benchmark.swebench=true to run real SWE-bench agent generation");
        LlmClient llm = LlmClientFactory.createFromConfig(DevCliConfig.load());
        Assumptions.assumeTrue(llm != null, "no configured LLM client");

        Path projectRoot = Path.of("").toAbsolutePath().normalize();
        int limit = Math.min(5,
                Math.max(1, Integer.getInteger("devcli.benchmark.swebench.limit", 1)));
        String mode = System.getProperty("devcli.benchmark.swebench.mode", "single")
                .trim().toLowerCase(Locale.ROOT);
        if (!Set.of("single", "team").contains(mode)) {
            throw new IllegalArgumentException("devcli.benchmark.swebench.mode must be single or team");
        }
        Path sample = projectRoot.resolve("Data/raw/public-benchmarks/swebench-lite/sample-5.json");
        Assumptions.assumeTrue(Files.isRegularFile(sample), "download SWE-bench Lite sample JSON first");
        List<PublicBenchmarkDatasets.SweBenchCase> cases =
                PublicBenchmarkDatasets.parseSweBenchRows(JSON.readTree(sample.toFile()), limit);
        Assumptions.assumeTrue(!cases.isEmpty(), "no SWE-bench cases loaded");

        String runId = "devcli-" + mode + "-" + System.currentTimeMillis();
        Path runRoot = projectRoot.resolve("target/swebench-agent/" + runId);
        Files.createDirectories(runRoot);
        List<SweBenchOfficialHarness.Prediction> predictions = new ArrayList<>();
        ObjectNode report = JSON.createObjectNode();
        report.put("schema_version", 1);
        report.put("generated_at", Instant.now().toString());
        report.put("provider", llm.getProviderName());
        report.put("model", llm.getModelName());
        report.put("mode", mode);
        report.put("run_id", runId);
        ArrayNode results = report.putArray("results");

        for (PublicBenchmarkDatasets.SweBenchCase benchmarkCase : cases) {
            Path workspace = runRoot.resolve(benchmarkCase.instanceId()).resolve("workspace");
            CommandResult clone = prepareRepository(benchmarkCase, workspace);
            ObjectNode item = results.addObject();
            item.put("instance_id", benchmarkCase.instanceId());
            item.put("repo", benchmarkCase.repo());
            item.put("base_commit", benchmarkCase.baseCommit());
            item.put("clone_exit_code", clone.exitCode());
            item.put("clone_output", preview(clone.output(), 2000));
            if (clone.exitCode() != 0) {
                item.put("error", "repository preparation failed");
                continue;
            }

            long started = System.currentTimeMillis();
            String output = runAgent(llm, benchmarkCase, workspace, mode);
            CommandResult intentToAdd = command(workspace, Duration.ofMinutes(2),
                    "git", "add", "-N", ".");
            CommandResult diff = command(workspace, Duration.ofMinutes(2),
                    "git", "diff", "--binary", "--no-ext-diff");
            String patch = diff.exitCode() == 0 ? diff.output() : "";
            predictions.add(new SweBenchOfficialHarness.Prediction(
                    benchmarkCase.instanceId(), "devcli/" + llm.getModelName() + "/" + mode, patch));
            item.put("elapsed_ms", System.currentTimeMillis() - started);
            item.put("agent_output", preview(output, 4000));
            item.put("git_add_intent_exit_code", intentToAdd.exitCode());
            item.put("diff_exit_code", diff.exitCode());
            item.put("patch_chars", patch.length());
            item.put("prediction_ready", !patch.isBlank());
        }

        Path reportDir = projectRoot.resolve("target/benchmark-reports/public/swebench");
        Files.createDirectories(reportDir);
        Path predictionsFile = reportDir.resolve("predictions-" + runId + ".jsonl");
        SweBenchOfficialHarness.writePredictions(predictionsFile, predictions);
        report.put("prediction_count", predictions.size());
        report.put("non_empty_patch_count", predictions.stream()
                .filter(prediction -> !prediction.model_patch().isBlank()).count());
        report.put("predictions_file", projectRoot.relativize(predictionsFile).toString().replace('\\', '/'));
        if (Boolean.getBoolean("devcli.benchmark.swebench.evaluate") && !predictions.isEmpty()) {
            Path officialReportDir = reportDir.resolve("official-" + runId);
            Path harnessRunDir = runRoot.resolve("official-harness");
            Files.createDirectories(officialReportDir);
            Files.createDirectories(harnessRunDir);
            List<String> command = SweBenchOfficialHarness.dockerEvaluationCommand(
                    System.getProperty("devcli.benchmark.swebench.image",
                            "devcli/swebench-harness:f7bbbb2"),
                    predictionsFile, officialReportDir, harnessRunDir, runId,
                    predictions.stream().map(SweBenchOfficialHarness.Prediction::instance_id).toList(),
                    Integer.getInteger("devcli.benchmark.swebench.workers", 1));
            CommandResult evaluation = command(
                    projectRoot, Duration.ofHours(2), command.toArray(String[]::new));
            report.put("official_harness_exit_code", evaluation.exitCode());
            report.put("official_harness_output", preview(evaluation.output(), 8000));
            report.put("official_report_dir",
                    projectRoot.relativize(officialReportDir).toString().replace('\\', '/'));
        }

        Path reportFile = reportDir.resolve("generation-" + runId + ".json");
        JSON.writerWithDefaultPrettyPrinter().writeValue(reportFile.toFile(), report);

        assertTrue(Files.isRegularFile(predictionsFile));
        assertTrue(Files.isRegularFile(reportFile));
    }

    private static String runAgent(LlmClient llm, PublicBenchmarkDatasets.SweBenchCase benchmarkCase,
                                   Path workspace, String mode) throws Exception {
        String prompt = """
                Fix the real repository issue below. Work only inside the current repository.
                Inspect the existing implementation before editing. Do not modify or delete existing tests.
                Make the smallest production-code change that addresses the issue, then run focused tests when feasible.
                Do not return a patch without applying it to the workspace.

                Repository: %s
                Base commit: %s
                Instance: %s

                Problem statement:
                %s

                Hints:
                %s
                """.formatted(benchmarkCase.repo(), benchmarkCase.baseCommit(),
                benchmarkCase.instanceId(), benchmarkCase.problemStatement(), benchmarkCase.hintsText());
        BenchmarkToolRegistry registry = new BenchmarkToolRegistry(ALLOWED_TOOLS);
        registry.setProjectPath(workspace.toString());
        try {
            if ("team".equals(mode)) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                try (NoOpMemoryManager memory = new NoOpMemoryManager(workspace.resolve(".memory"));
                     PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
                    AgentOrchestrator orchestrator = new AgentOrchestrator(llm, registry, memory, out);
                    AgentBenchmarkTestSupport.configureControlledBenchmark(orchestrator);
                    return buffer.toString(StandardCharsets.UTF_8) + "\n" + orchestrator.run(prompt);
                }
            }
            try (Agent agent = new Agent(llm, registry)) {
                agent.setRenderer(new PlainRenderer());
                return agent.run(prompt, LlmClient.ToolChoice.required("read_file"));
            }
        } finally {
            registry.close();
        }
    }

    private static CommandResult prepareRepository(PublicBenchmarkDatasets.SweBenchCase benchmarkCase,
                                                   Path workspace) throws Exception {
        Files.createDirectories(workspace.getParent());
        CommandResult clone = command(workspace.getParent(), Duration.ofMinutes(10),
                "git", "clone", "--filter=blob:none", "--no-checkout",
                "https://github.com/" + benchmarkCase.repo() + ".git",
                workspace.getFileName().toString());
        if (clone.exitCode() != 0) {
            return clone;
        }
        CommandResult checkout = command(workspace, Duration.ofMinutes(5),
                "git", "checkout", "--detach", benchmarkCase.baseCommit());
        if (checkout.exitCode() != 0) {
            return checkout;
        }
        command(workspace, Duration.ofMinutes(1), "git", "status", "--short");
        return checkout;
    }

    private static CommandResult command(Path directory, Duration timeout, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(Math.max(1, timeout.toSeconds()), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
            return new CommandResult(124, "command timed out: " + String.join(" ", command));
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new CommandResult(process.exitValue(), output);
    }

    private static String preview(String value, int limit) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...(truncated)";
    }

    private record CommandResult(int exitCode, String output) {
    }

    private static final class NoOpMemoryManager extends MemoryManager {
        private NoOpMemoryManager(Path storageDir) {
            super(null, 32768, 200000, new LongTermMemory(storageDir.toFile()));
        }
    }

    private static final class BenchmarkToolRegistry extends ToolRegistry {
        private final Set<String> allowedTools;

        private BenchmarkToolRegistry(Set<String> allowedTools) {
            this.allowedTools = Set.copyOf(allowedTools);
        }

        @Override
        public List<LlmClient.Tool> getToolDefinitions() {
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
