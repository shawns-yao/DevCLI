package com.devcli.eval;

import com.devcli.agent.Agent;
import com.devcli.config.DevCliConfig;
import com.devcli.llm.LlmClient;
import com.devcli.llm.LlmClientFactory;
import com.devcli.llm.LlmException;
import com.devcli.policy.SensitiveDataRedactor;
import com.devcli.runtime.AgentSessionRuntime;
import com.devcli.runtime.event.RunEvent;
import com.devcli.runtime.event.RunEventSink;
import com.devcli.trace.RunEventTraceSink;
import com.devcli.trace.TraceRecorder;
import com.devcli.tool.CommandResultMetadata;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;
import com.devcli.tool.ToolStatus;
import com.devcli.workspace.IsolatedWorkspace;
import com.devcli.workspace.PatchSet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Vendor-neutral Agent Evals adapter for the terminal Agent entrypoint.
 *
 * <p>Each case is executed through {@link AgentSessionRuntime} in a fresh
 * {@link IsolatedWorkspace}. Agent commands use the production
 * {@code ISOLATED_PROJECT} access scope, which selects the configured Docker
 * command sandbox by default. Gold checks are read only after the Agent run.
 */
public final class AgentEvalDriver {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Set<String> ALLOWED_TOOLS = Set.of(
            "read_file", "write_file", "edit_file", "list_dir", "grep_code",
            "execute_command", "read_tool_result");

    private AgentEvalDriver() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException(
                    "usage: AgentEvalDriver <tasks.jsonl> <outputDir> [--dry-run]");
        }
        Path tasksFile = Path.of(args[0]).toAbsolutePath().normalize();
        Path outputDir = Path.of(args[1]).toAbsolutePath().normalize();
        boolean dryRun = args.length == 3 && "--dry-run".equals(args[2]);
        if (args.length == 3 && !dryRun) {
            throw new IllegalArgumentException("only --dry-run is supported as the optional argument");
        }
        List<JsonNode> tasks = loadTasks(tasksFile);
        validateTaskIds(tasks);
        prepareOutputDirectory(outputDir);
        writeManifest(tasksFile, outputDir, tasks, dryRun);
        if (!dryRun && !"DOCKER".equals(resolveSandboxMode())) {
            throw new IllegalStateException(
                    "Agent Evals 真实运行要求 Docker 沙箱；当前模式为 " + resolveSandboxMode()
                            + "，请设置 DEVCLI_COMMAND_SANDBOX_MODE=DOCKER");
        }

        LlmClient client = null;
        if (!dryRun) {
            client = LlmClientFactory.createFromConfig(DevCliConfig.load());
            if (client == null) {
                throw new IllegalStateException("no LLM client: check .env");
            }
        }

        ArrayNode results = JSON.createArrayNode();
        int scored = 0;
        int successes = 0;
        int externalFailures = 0;
        for (JsonNode task : tasks) {
            ObjectNode result = runCase(task, tasksFile.getParent(), outputDir, client, dryRun);
            results.add(result);
            if ("scored".equals(result.path("status").asText())
                    || "agent_error".equals(result.path("status").asText())) {
                scored++;
                if (result.path("graders").path("task_success").asBoolean(false)) {
                    successes++;
                }
            }
            if (result.path("external_failure").asBoolean(false)) {
                externalFailures++;
            }
        }

        ObjectNode summary = JSON.createObjectNode();
        summary.put("schema_version", 1);
        summary.put("generated_at", Instant.now().toString());
        summary.put("dry_run", dryRun);
        summary.put("raw_sample_count", tasks.size());
        summary.put("excluded_count", 0);
        summary.put("valid_sample_denominator", scored);
        summary.put("external_failure_count", externalFailures);
        summary.put("core_function_success_count", successes);
        summary.put("task_success_rate", scored == 0 ? 0D : (double) successes / scored);
        summary.put("external_failure_rate", tasks.isEmpty() ? 0D : (double) externalFailures / tasks.size());
        summary.set("results", results);
        Files.writeString(outputDir.resolve("summary.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    static List<JsonNode> loadTasks(Path tasksFile) throws IOException {
        if (!Files.isRegularFile(tasksFile)) {
            throw new IllegalArgumentException("tasks file missing: " + tasksFile);
        }
        List<JsonNode> tasks = new ArrayList<>();
        int line = 0;
        for (String text : Files.readAllLines(tasksFile, StandardCharsets.UTF_8)) {
            line++;
            if (text.isBlank()) {
                continue;
            }
            try {
                JsonNode task = JSON.readTree(text);
                validateTask(task, line);
                tasks.add(task);
            } catch (Exception error) {
                throw new IllegalArgumentException("invalid task at line " + line + ": "
                        + error.getMessage(), error);
            }
        }
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("tasks file is empty: " + tasksFile);
        }
        return List.copyOf(tasks);
    }

    static void validateTask(JsonNode task, int line) {
        if (task == null || !task.isObject()) {
            throw new IllegalArgumentException("task must be an object");
        }
        requireText(task, "id");
        requireText(task, "prompt");
        requireText(task, "fixture");
        JsonNode checks = task.path("checks");
        if (!checks.isArray() || checks.isEmpty()) {
            throw new IllegalArgumentException("checks must be a non-empty array");
        }
        for (JsonNode check : checks) {
            String type = requireText(check, "type");
            if (!Set.of("file_exists", "file_absent", "file_contains",
                    "file_not_contains", "no_changes", "command").contains(type)) {
                throw new IllegalArgumentException("unsupported check type: " + type);
            }
            if ("command".equals(type)) {
                requireText(check, "command");
            } else if (!"no_changes".equals(type)) {
                requireText(check, "path");
                safeRelativePath(check.path("path").asText());
            }
            if (Set.of("file_contains", "file_not_contains").contains(type)) {
                requireText(check, "value");
            }
        }
    }

    static ObjectNode runCase(JsonNode task, Path tasksParent, Path outputDir,
                              LlmClient client, boolean dryRun) throws IOException {
        String id = task.path("id").asText();
        Path caseDir = outputDir.resolve(safeId(id));
        Files.createDirectories(caseDir);
        ObjectNode result = JSON.createObjectNode();
        result.put("schema_version", 1);
        result.put("id", id);
        result.put("prompt", redact(task.path("prompt").asText()));
        result.put("fixture", task.path("fixture").asText());
        result.put("started_at", Instant.now().toString());
        result.put("sandbox_mode", resolveSandboxMode());
        result.put("workspace_backend", resolveWorkspaceBackend());

        Path fixture = tasksParent.resolve(task.path("fixture").asText()).toAbsolutePath().normalize();
        if (!Files.isDirectory(fixture)) {
            result.put("status", "invalid");
            result.put("external_failure", false);
            result.put("error", "fixture directory missing: " + fixture);
            writeResult(caseDir, result);
            return result;
        }
        if (dryRun) {
            result.put("status", "dry_run");
            result.put("external_failure", false);
            result.put("checks_validated", task.path("checks").size());
            writeResult(caseDir, result);
            return result;
        }

        long started = System.currentTimeMillis();
        Path traceFile = caseDir.resolve("trace.jsonl");
        EvalTraceSink evalTrace = new EvalTraceSink(traceFile);
        RunEventSink sink = RunEventSink.composite(
                new RunEventTraceSink(new TraceRecorder(caseDir.resolve("trace"))), evalTrace);
        System.setProperty("devcli.memory.dir", caseDir.resolve("memory").toString());
        try (IsolatedWorkspace workspace = IsolatedWorkspace.create(
                fixture, caseDir.resolve("workspace-base"), id);
             AgentSessionRuntime session = AgentSessionRuntime.create(
                     client, workspace.path(), sink)) {
            Agent agent = session.agent();
            configureClosedBook(agent);
            ToolRegistry registry = agent.getToolRegistry();
            String output = registry.runWithToolAccess(
                    ToolRegistry.ToolAccessScope.ISOLATED_PROJECT,
                    () -> session.runInCurrentContext(task.path("prompt").asText()).output());
            result.put("status", evalTrace.externalFailure() ? "external_failure" : "scored");
            result.put("output", redact(output));
            Files.writeString(caseDir.resolve("model-output.txt"), redact(output),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            PatchSet patchSet = workspace.createPatchSet();
            ArrayNode changes = result.putArray("changed_files");
            patchSet.changes().forEach(change -> {
                ObjectNode item = changes.addObject();
                item.put("path", change.relativePath());
                item.put("type", change.type().name());
                item.put("before_hash", change.beforeHash());
                item.put("after_hash", change.afterHash());
            });
            CheckSummary checks = evalTrace.externalFailure()
                    ? skippedChecks(task.path("checks"), "external_failure")
                    : evaluateChecks(task.path("checks"), workspace.path(), patchSet);
            result.set("check_results", checks.results());
            result.putObject("graders")
                    .put("task_success", !evalTrace.externalFailure() && checks.allPassed()
                            && evalTrace.turnCompleted()
                            && !evalTrace.turnFailed() && evalTrace.unexpectedTools().isEmpty())
                    .put("trace_present", Files.exists(traceFile) && Files.size(traceFile) > 0)
                    .put("safe_tool_use", evalTrace.unexpectedTools().isEmpty())
                    .put("unexpected_tool_count", evalTrace.unexpectedTools().size());
            result.put("tool_call_count", evalTrace.toolCallCount());
            result.put("tool_result_count", evalTrace.toolResultCount());
            result.put("input_tokens", evalTrace.inputTokens());
            result.put("output_tokens", evalTrace.outputTokens());
            result.put("cached_input_tokens", evalTrace.cachedInputTokens());
            result.put("estimated_cost_cny", evalTrace.estimatedCostCny());
            result.put("wall_ms", System.currentTimeMillis() - started);
            result.put("failure_category", evalTrace.failureCategory());
            if (!evalTrace.failureReason().isBlank()) {
                result.put("failure_reason", redact(evalTrace.failureReason()));
            }
            if (!evalTrace.unexpectedTools().isEmpty()) {
                result.set("unexpected_tools", JSON.valueToTree(evalTrace.unexpectedTools()));
            }
        } catch (Exception error) {
            boolean external = isExternalFailure(error);
            result.put("status", external ? "external_failure" : "agent_error");
            result.put("external_failure", external);
            result.put("error_type", error.getClass().getName());
            result.put("error", redact(safeMessage(error)));
            result.put("wall_ms", System.currentTimeMillis() - started);
        }
        result.put("external_failure", result.path("status").asText().equals("external_failure"));
        result.put("finished_at", Instant.now().toString());
        writeResult(caseDir, result);
        return result;
    }

    private static CheckSummary evaluateChecks(JsonNode checks, Path workspace,
                                               PatchSet patchSet) throws IOException {
        ArrayNode results = JSON.createArrayNode();
        boolean allPassed = true;
        Set<String> changedPaths = patchSet == null ? Set.of() : patchSet.changes().stream()
                .map(PatchSet.FileChange::relativePath)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (JsonNode check : checks) {
            String type = check.path("type").asText();
            ObjectNode item = JSON.createObjectNode().put("type", type);
            boolean passed;
            switch (type) {
                case "no_changes" -> {
                    passed = changedPaths.isEmpty();
                    item.put("changed_file_count", changedPaths.size());
                }
                case "file_exists", "file_absent", "file_contains", "file_not_contains" -> {
                    Path path = resolveSafe(workspace, check.path("path").asText());
                    boolean exists = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
                    item.put("path", check.path("path").asText());
                    item.put("exists", exists);
                    if ("file_exists".equals(type)) {
                        passed = exists;
                    } else if ("file_absent".equals(type)) {
                        passed = !exists;
                    } else {
                        String content = exists ? Files.readString(path, StandardCharsets.UTF_8) : "";
                        boolean contains = exists && content.contains(check.path("value").asText());
                        passed = "file_contains".equals(type) ? contains : !contains;
                        item.put("contains", contains);
                    }
                }
                case "command" -> {
                    try (ToolRegistry verifier = new ToolRegistry()) {
                        verifier.setProjectPath(workspace.toString());
                        ToolOutput output = verifier.runWithToolAccess(
                                ToolRegistry.ToolAccessScope.ISOLATED_PROJECT,
                                () -> verifier.executeCommandOutput(check.path("command").asText()));
                        int expected = check.path("expected_exit_code").asInt(0);
                        int actual = output.sideChannels().stream()
                                .filter(CommandResultMetadata.class::isInstance)
                                .map(CommandResultMetadata.class::cast)
                                .mapToInt(CommandResultMetadata::exitCode)
                                .findFirst().orElse(Integer.MIN_VALUE);
                        boolean outputContains = !check.has("output_contains")
                                || output.text().contains(check.path("output_contains").asText());
                        passed = output.status() == ToolStatus.SUCCESS
                                && actual == expected && outputContains;
                        item.put("command", redact(check.path("command").asText()));
                        item.put("expected_exit_code", expected);
                        item.put("actual_exit_code", actual);
                        item.put("status", output.status().name());
                        item.put("output_contains", outputContains);
                    }
                }
                default -> throw new IllegalArgumentException("unsupported check type: " + type);
            }
            item.put("passed", passed);
            results.add(item);
            allPassed &= passed;
        }
        return new CheckSummary(allPassed, results);
    }

    private static CheckSummary skippedChecks(JsonNode checks, String reason) {
        ArrayNode results = JSON.createArrayNode();
        for (JsonNode check : checks) {
            results.addObject()
                    .put("type", check.path("type").asText())
                    .put("skipped", true)
                    .put("reason", reason)
                    .put("passed", false);
        }
        return new CheckSummary(false, results);
    }

    private static void configureClosedBook(Agent agent) {
        ToolRegistry registry = agent.getToolRegistry();
        registry.retainTools(ALLOWED_TOOLS);
        agent.getMemoryManager().setMemoryIgnored(true);
    }

    static void prepareOutputDirectory(Path outputDir) throws IOException {
        if (Files.exists(outputDir)) {
            try (var files = Files.list(outputDir)) {
                if (files.findAny().isPresent()) {
                    throw new IOException("output directory must be new or empty: " + outputDir);
                }
            }
        }
        Files.createDirectories(outputDir);
    }

    private static void writeManifest(Path tasksFile, Path outputDir,
                                      List<JsonNode> tasks, boolean dryRun) throws IOException {
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("schema_version", 1);
        manifest.put("objective", "评估终端 Agent 的任务结果、执行轨迹和安全行为");
        manifest.put("test_kind", "定向测试");
        manifest.put("test_level", "agent_eval");
        manifest.put("entrypoint", "AgentSessionRuntime");
        manifest.put("gold_visibility", "grader_only");
        manifest.put("primary_metric", "task_success_rate");
        manifest.put("primary_metric_formula", "successful_scored_cases / scored_cases");
        manifest.put("retry_and_repeat_policy", "每个样本单次运行；外部失败单列，不计入核心分母");
        manifest.put("pass_criteria", "所有确定性 checks 通过，且 trace 完整、工具调用未越权");
        manifest.put("artifact_dir", outputDir.toString());
        manifest.put("dataset_path", tasksFile.toString());
        manifest.put("dataset_sha256", sha256(tasksFile));
        manifest.put("sample_count", tasks.size());
        manifest.put("dry_run", dryRun);
        manifest.put("sandbox_mode", resolveSandboxMode());
        manifest.put("workspace_backend", resolveWorkspaceBackend());
        Files.writeString(outputDir.resolve("manifest.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private static void writeResult(Path caseDir, ObjectNode result) throws IOException {
        Files.writeString(caseDir.resolve("result.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    static void validateTaskIds(List<JsonNode> tasks) {
        Set<String> ids = new HashSet<>();
        for (JsonNode task : tasks) {
            if (!ids.add(task.path("id").asText())) {
                throw new IllegalArgumentException("duplicate task id: " + task.path("id").asText());
            }
        }
    }

    private static String requireText(JsonNode node, String field) {
        if (node == null || !node.path(field).isTextual()
                || node.path(field).asText().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return node.path(field).asText();
    }

    private static String safeId(String id) {
        String value = id == null || id.isBlank() ? "case" : id.trim();
        return value.replaceAll("[^a-zA-Z0-9._-]", "-");
    }

    private static String safeRelativePath(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("relative path is required");
        }
        Path path = Path.of(value);
        if (path.isAbsolute() || path.normalize().startsWith("..")) {
            throw new IllegalArgumentException("unsafe relative path: " + value);
        }
        return path.normalize().toString().replace('\\', '/');
    }

    private static Path resolveSafe(Path root, String relative) {
        Path base = root.toAbsolutePath().normalize();
        Path target = base.resolve(safeRelativePath(relative)).normalize();
        if (!target.startsWith(base) || target.equals(base)) {
            throw new IllegalArgumentException("path escapes workspace: " + relative);
        }
        return target;
    }

    private static String resolveSandboxMode() {
        String value = System.getProperty("devcli.command.sandbox.mode");
        if (value == null || value.isBlank()) value = System.getenv("DEVCLI_COMMAND_SANDBOX_MODE");
        return value == null || value.isBlank() ? "DOCKER" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String resolveWorkspaceBackend() {
        String value = System.getProperty("devcli.workspace.backend");
        if (value == null || value.isBlank()) value = System.getenv("DEVCLI_WORKSPACE_BACKEND");
        return value == null || value.isBlank() ? "AUTO" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String redact(String value) {
        return SensitiveDataRedactor.redact(value == null ? "" : value);
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName() : message;
    }

    private static boolean isExternalFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof LlmException) {
                return true;
            }
            current = current.getCause();
        }
        String message = safeMessage(error).toLowerCase(Locale.ROOT);
        return message.contains("docker") || message.contains("沙箱")
                || message.contains("sandbox") || message.contains("network")
                || message.contains("connection") || message.contains("timeout")
                || message.contains("timed out") || message.contains("no llm client");
    }

    private static String sha256(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(path)));
        } catch (Exception error) {
            throw new IOException("cannot hash tasks file", error);
        }
    }

    private record CheckSummary(boolean allPassed, ArrayNode results) {
    }

    static final class EvalTraceSink implements RunEventSink {
        private final Path file;
        private final Set<String> tools = new HashSet<>();
        private int sequence;
        private int toolCalls;
        private int toolResults;
        private boolean turnCompleted;
        private boolean turnFailed;
        private boolean externalFailure;
        private String failureCategory = "";
        private String failureReason = "";
        private long inputTokens;
        private long outputTokens;
        private long cachedInputTokens;
        private double estimatedCostCny;

        EvalTraceSink(Path file) throws IOException {
            this.file = file;
            Files.createFile(file);
        }

        @Override
        public synchronized void emit(RunEvent event) {
            if (event == null || event instanceof RunEvent.ReasoningDelta
                    || event instanceof RunEvent.MessageDelta
                    || event instanceof RunEvent.ModelContext
                    || event instanceof RunEvent.ModelMessage) {
                return;
            }
            ObjectNode row = JSON.createObjectNode();
            row.put("sequence", ++sequence);
            row.put("type", event.type());
            if (event instanceof RunEvent.ToolCalls calls) {
                toolCalls += calls.calls().size();
                ArrayNode names = row.putArray("tools");
                calls.calls().forEach(call -> {
                    tools.add(call.name());
                    names.add(call.name());
                });
            } else if (event instanceof RunEvent.ToolResults results) {
                toolResults += results.results().size();
                ArrayNode values = row.putArray("results");
                results.results().forEach(result -> {
                    ObjectNode value = values.addObject();
                    value.put("name", result.name());
                    value.put("status", result.status());
                    value.put("error_code", result.errorCode());
                    value.put("retryable", result.retryable());
                    value.put("elapsed_millis", result.elapsedMillis());
                    value.put("exit_code", result.exitCode());
                });
            } else if (event instanceof RunEvent.ModelUsage usage) {
                inputTokens += usage.inputTokens();
                outputTokens += usage.outputTokens();
                cachedInputTokens += usage.cachedInputTokens();
                estimatedCostCny += usage.estimatedCostCny();
                row.put("input_tokens", usage.inputTokens());
                row.put("output_tokens", usage.outputTokens());
                row.put("cached_input_tokens", usage.cachedInputTokens());
                row.put("estimated_cost_cny", usage.estimatedCostCny());
            } else if (event instanceof RunEvent.ExecutionStateChanged state) {
                row.put("state", state.state().name());
                row.put("iteration", state.iteration());
                row.put("reason", redact(state.reason()));
            } else if (event instanceof RunEvent.FailureGuidance failure) {
                row.put("category", failure.category());
                row.put("reason", redact(failure.reason()));
                failureCategory = failure.category();
                failureReason = failure.reason();
                externalFailure |= isExternalFailureReason(failure.category(), failure.reason());
            } else if (event instanceof RunEvent.ContextCompacted compacted) {
                row.put("source_event_start", compacted.sourceEventStart());
                row.put("source_event_end", compacted.sourceEventEnd());
                row.put("source_hash", compacted.sourceHash());
                row.put("projection_hash", compacted.projectionHash());
                row.put("mode", compacted.mode());
            }
            if (event instanceof RunEvent.TurnCompleted) {
                turnCompleted = true;
            } else if (event instanceof RunEvent.TurnFailed
                    || event instanceof RunEvent.TurnRejected) {
                turnFailed = true;
            }
            try {
                Files.writeString(file, row.toString() + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            } catch (IOException error) {
                throw new IllegalStateException("cannot write Agent Eval trace", error);
            }
        }

        Set<String> unexpectedTools() {
            return tools.stream().filter(tool -> !ALLOWED_TOOLS.contains(tool)).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        int toolCallCount() { return toolCalls; }

        int toolResultCount() { return toolResults; }

        long inputTokens() { return inputTokens; }

        long outputTokens() { return outputTokens; }

        long cachedInputTokens() { return cachedInputTokens; }

        double estimatedCostCny() { return estimatedCostCny; }

        boolean turnCompleted() { return turnCompleted; }

        boolean turnFailed() { return turnFailed; }

        boolean externalFailure() { return externalFailure; }

        String failureCategory() { return failureCategory; }

        String failureReason() { return failureReason; }

        private static boolean isExternalFailureReason(String category, String reason) {
            String value = ((category == null ? "" : category) + " "
                    + (reason == null ? "" : reason)).toLowerCase(Locale.ROOT);
            return value.contains("environment_failure") || value.contains("llm request failed")
                    || value.contains("status=503") || value.contains("status=502")
                    || value.contains("status=500") || value.contains("no_available_account")
                    || value.contains("model_not_found") || value.contains("rate_limit")
                    || value.contains("timeout") || value.contains("timed out")
                    || value.contains("connection refused") || value.contains("network error");
        }
    }
}
