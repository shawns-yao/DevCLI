package com.devcli.eval;

import com.devcli.agent.Agent;
import com.devcli.config.DevCliConfig;
import com.devcli.hitl.ApprovalRequest;
import com.devcli.hitl.ApprovalResult;
import com.devcli.hitl.HitlHandler;
import com.devcli.hitl.HitlToolRegistry;
import com.devcli.hitl.TerminalHitlHandler;
import com.devcli.llm.LlmClient;
import com.devcli.llm.OpenAiClient;
import com.devcli.mcp.McpServer;
import com.devcli.mcp.McpServerManager;
import com.devcli.mcp.McpServerStatus;
import com.devcli.mcp.config.McpConfigLoader;
import com.devcli.policy.AuditLog;
import com.devcli.runtime.AgentSessionRuntime;
import com.devcli.runtime.event.RunEvent;
import com.devcli.runtime.event.RunEventSink;
import com.devcli.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/** AgentDojo v1.2.2 真实链路 driver：Luna Agent → MCP → ToolExecutionPipeline → 官方 evaluator。 */
public final class AgentDojoDriver {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SERVER = "agentdojo";
    private static final Set<String> CONTROL_TOOLS = Set.of("agentdojo_metadata", "agentdojo_finalize");

    private AgentDojoDriver() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 9 && args.length != 10) {
            System.err.println("usage: AgentDojoDriver <python> <bridge.py> <harnessRoot> <outDir> "
                    + "<baseline|treatment> <suite> <userTask> <injectionTask> <attack> [auto-approve|terminal]");
            System.exit(2);
        }
        Path python = Path.of(args[0]).toAbsolutePath().normalize();
        Path bridge = Path.of(args[1]).toAbsolutePath().normalize();
        Path harnessRoot = Path.of(args[2]).toAbsolutePath().normalize();
        Path outDir = Path.of(args[3]).toAbsolutePath().normalize();
        String mode = normalizeMode(args[4]);
        String suite = args[5];
        String userTask = args[6];
        String injectionTask = args[7];
        String attack = args[8];
        String approvalPolicy = approvalPolicy(mode, args.length == 10 ? args[9] : "");
        prepareOutputDirectory(outDir);
        Path projectDir = outDir.resolve("workspace");
        Files.createDirectories(projectDir);
        System.setProperty("devcli.memory.dir", outDir.resolve("memory").toString());
        System.setProperty("devcli.audit.dir", outDir.resolve("audit").toString());

        long started = System.currentTimeMillis();
        ObjectNode result = MAPPER.createObjectNode();
        result.put("benchmark", "AgentDojo");
        result.put("benchmark_revision", "089ed468cf3ed0322acc66b0211f26d9d90dbf60");
        result.put("benchmark_version", "v1.2.2");
        result.put("mode", mode);
        result.put("suite", suite);
        result.put("user_task_id", userTask);
        result.put("injection_task_id", injectionTask);
        result.put("attack", attack);
        result.put("harness_root", harnessRoot.toString());
        result.put("run_id", java.util.UUID.randomUUID().toString());
        result.put("started_at", java.time.Instant.now().toString());
        result.put("approval_policy", approvalPolicy);
        result.put("comparison_scope", "HITL_only_shared_production_pipeline");
        result.put("security_improvement_claim_eligible", false);
        result.put("token_budget", System.getProperty("devcli.react.token.budget", "model_default"));
        result.put("max_iterations", System.getProperty("devcli.react.hard.max.iterations", "100"));

        CountingHitlHandler hitl = new CountingHitlHandler("terminal".equals(approvalPolicy));
        UsageCollector usage = new UsageCollector();
        try (ToolRegistry registry = "treatment".equals(mode)
                ? new HitlToolRegistry(hitl) : new ToolRegistry()) {
            registry.setProjectPath(projectDir.toString());
            Path config = writeMcpConfig(outDir, python, bridge, harnessRoot, suite, userTask, injectionTask, attack);
            McpConfigLoader loader = new McpConfigLoader(
                    outDir.resolve("missing-user-mcp.json"), config, projectDir);
            try (McpServerManager manager = new McpServerManager(registry, projectDir, loader)) {
                manager.loadConfiguredServers();
                manager.startAll(System.err, Duration.ofSeconds(30));
                McpServer server = manager.server(SERVER);
                if (server == null || server.status() != McpServerStatus.READY || server.client() == null) {
                    throw new IllegalStateException("AgentDojo MCP 未就绪: "
                            + (server == null ? "missing" : server.status() + " " + server.errorMessage()));
                }

                JsonNode metadata = parseToolJson(server.client().callTool("agentdojo_metadata", "{}"));
                result.set("metadata", metadata);
                Set<String> allowedTools = new LinkedHashSet<>();
                server.tools().stream()
                        .filter(tool -> !CONTROL_TOOLS.contains(tool.name()))
                        .forEach(tool -> allowedTools.add(tool.namespacedName()));
                retainBenchmarkTools(registry, allowedTools);
                result.put("registered_tool_count", allowedTools.size());
                result.put("recovery_tool_enabled", registry.hasTool("read_tool_result"));
                result.put("discovery_tool_enabled", registry.hasTool("search_tools"));
                result.set("allowed_tools", MAPPER.valueToTree(allowedTools));
                result.put("mcp_transport", server.transportName());

                LlmClient client = lunaClient();
                result.put("provider", client.getProviderName());
                result.put("actual_model", client.getModelName());
                result.put("model_is_luna", "gpt-5.6-luna".equals(client.getModelName()));
                if (!"gpt-5.6-luna".equals(client.getModelName())) {
                    throw new IllegalStateException("AgentDojo 正式运行必须使用 gpt-5.6-luna，实际为 "
                            + client.getModelName());
                }

                String output;
                try (Agent agent = new Agent(client, registry);
                     AgentSessionRuntime session = new AgentSessionRuntime(
                             agent, registry, projectDir, false, false)) {
                    agent.setRunEventSink(usage);
                    output = registry.runWithToolAccess(ToolRegistry.ToolAccessScope.FULL,
                            () -> session.runInCurrentContext(metadata.path("prompt").asText()).output());
                    result.set("final_visible_tools", MAPPER.valueToTree(
                            registry.getToolDefinitions().stream().map(LlmClient.Tool::name).toList()));
                }
                Files.writeString(outDir.resolve("model-output.txt"), output, StandardCharsets.UTF_8);
                boolean externalFailure = usage.externalFailure() || isExternalFailureText(output);
                if (externalFailure) {
                    result.put("valid_sample", false);
                    result.put("external_failure", true);
                    result.put("official_evaluation_skipped", true);
                    result.put("error", usage.failureReason().isBlank()
                            ? "LLM external failure" : usage.failureReason());
                } else {
                    ObjectNode finalizeArgs = MAPPER.createObjectNode().put("model_output", output);
                    JsonNode official = parseToolJson(server.client().callTool(
                            "agentdojo_finalize", MAPPER.writeValueAsString(finalizeArgs)));
                    result.set("official", official);
                    result.put("valid_sample", true);
                    result.put("external_failure", false);
                }
                result.put("approval_count", hitl.approvals.sum());
                List<AuditLog.AuditEntry> audit = registry.getAuditLog().readRecent(10_000);
                result.put("audit_count", audit.size());
                result.put("audit_allow_count", audit.stream()
                        .filter(entry -> AuditLog.OUTCOME_ALLOW.equals(entry.outcome())).count());
                result.put("audit_deny_count", audit.stream()
                        .filter(entry -> AuditLog.OUTCOME_DENY.equals(entry.outcome())).count());
            }
        } catch (Exception error) {
            result.put("valid_sample", false);
            result.put("external_failure", isExternalFailure(error));
            result.put("error_type", error.getClass().getName());
            result.put("error", safeMessage(error));
            throw error;
        } finally {
            UsageSnapshot totals = usage.snapshot();
            result.put("input_tokens", totals.inputTokens());
            result.put("output_tokens", totals.outputTokens());
            result.put("cached_input_tokens", totals.cachedInputTokens());
            result.put("estimated_cost_cny", totals.estimatedCostCny());
            result.put("wall_ms", System.currentTimeMillis() - started);
            result.set("tool_events", usage.toolEvents);
            result.set("approval_events", hitl.events);
            Files.writeString(outDir.resolve("result.json"),
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
    }

    static void prepareOutputDirectory(Path outDir) throws java.io.IOException {
        Files.createDirectories(outDir.toAbsolutePath().getParent());
        Files.createDirectory(outDir);
    }

    static void retainBenchmarkTools(ToolRegistry registry, Set<String> allowedTools) {
        allowedTools.add("read_tool_result");
        allowedTools.add("search_tools");
        CONTROL_TOOLS.forEach(name -> registry.unregisterMcpTool("mcp__" + SERVER + "__" + name));
        registry.retainTools(allowedTools);
    }

    static String approvalPolicy(String mode, String policy) {
        if ("baseline".equals(mode) && policy.isBlank()) return "none";
        if ("treatment".equals(mode) && Set.of("auto-approve", "terminal").contains(policy)) return policy;
        throw new IllegalArgumentException("baseline uses no HITL; treatment requires explicit auto-approve or terminal");
    }

    static String normalizeMode(String raw) {
        String mode = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("baseline", "treatment").contains(mode)) {
            throw new IllegalArgumentException("mode must be baseline|treatment, got " + raw);
        }
        return mode;
    }

    private static Path writeMcpConfig(Path outDir, Path python, Path bridge, Path harnessRoot, String suite,
                                       String userTask, String injectionTask, String attack) throws Exception {
        ObjectNode server = MAPPER.createObjectNode();
        server.put("command", python.toString());
        server.putObject("env").put("PYTHONPATH", harnessRoot.resolve("src").toString())
                .put("PYTHONDONTWRITEBYTECODE", "1");
        var commandArgs = server.putArray("args");
        commandArgs.add(bridge.toString());
        commandArgs.add("--suite").add(suite);
        commandArgs.add("--version").add("v1.2.2");
        commandArgs.add("--user-task").add(userTask);
        commandArgs.add("--injection-task").add(injectionTask);
        commandArgs.add("--attack").add(attack);
        server.put("trustReadOnlyAnnotations", true);
        ObjectNode root = MAPPER.createObjectNode();
        root.putObject("mcpServers").set(SERVER, server);
        Path config = outDir.resolve("mcp.json");
        Files.writeString(config, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                StandardCharsets.UTF_8);
        return config;
    }

    private static LlmClient lunaClient() {
        DevCliConfig config = DevCliConfig.load();
        String key = config.getApiKey("openai");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY 未配置");
        }
        return new OpenAiClient(key, config.getModel("openai"), config.getBaseUrl("openai"));
    }

    private static JsonNode parseToolJson(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    private static boolean isExternalFailure(Exception error) {
        return isExternalFailureText(safeMessage(error));
    }

    private static boolean isExternalFailureText(String value) {
        String message = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return message.contains("503") || message.contains("model_not_found")
                || message.contains("timeout") || message.contains("timed out")
                || message.contains("connection") || message.contains("network")
                || message.contains("mcp 未就绪") || message.contains("upstream_stream_break")
                || message.contains("upstream stream ended prematurely")
                || message.contains("safe to retry");
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message;
    }

    private static final class CountingHitlHandler implements HitlHandler {
        private final LongAdder approvals = new LongAdder();
        private final TerminalHitlHandler terminal;
        private final com.fasterxml.jackson.databind.node.ArrayNode events = MAPPER.createArrayNode();
        private volatile boolean enabled = true;

        CountingHitlHandler(boolean interactive) {
            terminal = interactive ? new TerminalHitlHandler(true) : null;
        }

        @Override
        public synchronized ApprovalResult requestApproval(ApprovalRequest request) {
            approvals.increment();
            long start = System.nanoTime();
            ApprovalResult decision = terminal == null ? ApprovalResult.approve() : terminal.requestApproval(request);
            events.addObject().put("tool", request.toolName()).put("arguments", request.arguments())
                    .put("decision", decision.decision().name()).put("reason", decision.reason())
                    .put("elapsed_ms", (System.nanoTime() - start) / 1_000_000);
            return decision;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    private record UsageSnapshot(long inputTokens, long outputTokens, long cachedInputTokens,
                                 double estimatedCostCny) {
    }

    private static final class UsageCollector implements RunEventSink {
        private final com.fasterxml.jackson.databind.node.ArrayNode toolEvents = MAPPER.createArrayNode();
        private final LongAdder inputTokens = new LongAdder();
        private final LongAdder outputTokens = new LongAdder();
        private final LongAdder cachedInputTokens = new LongAdder();
        private final DoubleAdder estimatedCostCny = new DoubleAdder();
        private final AtomicBoolean externalFailure = new AtomicBoolean();
        private final AtomicReference<String> failureReason = new AtomicReference<>("");

        @Override
        public synchronized void emit(RunEvent event) {
            if (event instanceof RunEvent.ModelUsage modelUsage) {
                inputTokens.add(modelUsage.inputTokens());
                outputTokens.add(modelUsage.outputTokens());
                cachedInputTokens.add(modelUsage.cachedInputTokens());
                estimatedCostCny.add(modelUsage.estimatedCostCny());
            } else if (event instanceof RunEvent.FailureGuidance failure
                    && isExternalFailureText(failure.reason())) {
                externalFailure.set(true);
                failureReason.set(failure.reason());
            } else if (event instanceof RunEvent.ToolResults results) {
                for (var tool : results.results()) {
                    toolEvents.addObject().put("id", tool.id()).put("tool", tool.name())
                            .put("arguments", tool.argumentsJson()).put("status", tool.status())
                            .put("error_code", tool.errorCode()).put("elapsed_ms", tool.elapsedMillis());
                }
            }
        }

        boolean externalFailure() {
            return externalFailure.get();
        }

        String failureReason() {
            return failureReason.get();
        }

        UsageSnapshot snapshot() {
            return new UsageSnapshot(inputTokens.sum(), outputTokens.sum(),
                    cachedInputTokens.sum(), estimatedCostCny.sum());
        }
    }
}
