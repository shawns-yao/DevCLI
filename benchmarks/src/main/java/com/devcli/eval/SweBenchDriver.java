package com.devcli.eval;

import com.devcli.agent.Agent;
import com.devcli.agent.AgentOrchestrator;
import com.devcli.config.DevCliConfig;
import com.devcli.llm.LlmClient;
import com.devcli.llm.LlmClientFactory;
import com.devcli.llm.ModelCapabilityRegistry;
import com.devcli.memory.ConversationHistoryCompactor;
import com.devcli.memory.TokenBudget;
import com.devcli.runtime.AgentSessionRuntime;
import com.devcli.runtime.event.RunEvent;
import com.devcli.runtime.event.RunEventSink;
import com.devcli.tool.ToolRegistry;
import com.devcli.tool.command.DefaultCommandExecutionService;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SWE-bench 无头对照 driver。
 *
 * <p>args：&lt;projectPath&gt; &lt;promptFile&gt; &lt;outFile&gt; &lt;mode: solo|delegate|plan&gt;
     * [raw|compact] [continuationRounds] [continuationPromptDirectory]</p>
 *
 * <p>三模式共用同一闭卷固定工具集（read_file/write_file/edit_file/list_dir/grep_code/
 * execute_command/read_tool_result），
 * 禁网、禁长期记忆、禁建项目/回退/skill/browser，唯一变量是编排方式：</p>
 * <ul>
 *   <li><b>solo</b>：ReAct 且额外移除 delegate_task —— 纯单 Agent；</li>
 *   <li><b>delegate</b>：ReAct 主 Agent 保留 delegate_task —— 按需动态委派；</li>
 *   <li><b>plan</b>：固定 Planner/Worker/Reviewer 流水线（AgentOrchestrator），计划默认自动批准。</li>
 * </ul>
 */
public final class SweBenchDriver {

    private static final PrintStream SILENT =
            new PrintStream(OutputStream.nullOutputStream(), false, StandardCharsets.UTF_8);

    /** 闭卷 benchmark 的精确工具面；不依赖本机是否残留 RAG 索引或后来新增了哪些工具。 */
    static final Set<String> CLOSED_BOOK_TOOLS = Set.of(
            "read_file", "write_file", "edit_file", "list_dir", "grep_code",
            "execute_command", "read_tool_result");

    public static void main(String[] args) throws Exception {
        if (args.length < 4 || args.length > 7) {
            System.err.println("usage: SweBenchDriver <project> <promptFile> <outFile> <solo|delegate|plan> [raw|compact] [continuationRounds] [continuationPromptDirectory]");
            System.exit(2);
        }
        Path project = Path.of(args[0]).toAbsolutePath().normalize();
        String prompt = Files.readString(Path.of(args[1]), StandardCharsets.UTF_8);
        Path outFile = Path.of(args[2]);
        // 在任何 Agent / MemoryManager 初始化之前隔离存储，不能只关闭后续召回。
        System.setProperty("devcli.memory.dir",
                outFile.toAbsolutePath().normalize().getParent().resolve("memory").toString());
        String mode = args[3].trim().toLowerCase(Locale.ROOT);
        String contextMode = args.length >= 5 ? normalizeContextMode(args[4]) : "compact";
        int continuationRounds = args.length >= 6 ? normalizeContinuationRounds(args[5]) : 1;
        List<String> continuationPrompts = args.length == 7
                ? loadContinuationPrompts(Path.of(args[6]), continuationRounds)
                : List.of();
        String conversationProtocol = System.getProperty("devcli.benchmark.conversation.protocol", "continue");
        if ("original-task-qa".equals(conversationProtocol)) {
            if (!"solo".equals(mode) || !"compact".equals(contextMode)
                    || continuationRounds < 2 || continuationRounds > 64 || args.length == 7) {
                throw new IllegalArgumentException("original-task-qa requires solo compact, 2..64 rounds, no prompt overrides");
            }
            continuationPrompts = qaContinuationPrompts();
        } else if (!"continue".equals(conversationProtocol)) {
            throw new IllegalArgumentException("Unknown conversation protocol: " + conversationProtocol);
        }

        DevCliConfig config = DevCliConfig.load();
        LlmClient client = LlmClientFactory.createFromConfig(config);
        if (client == null) {
            throw new IllegalStateException("no LLM client: check .env");
        }
        registerBenchmarkContextWindow(client);
        if ("original-task-qa".equals(conversationProtocol)
                && (!"openai".equals(client.getProviderName()) || !isSupportedQaModel(client.getModelName())
                || com.devcli.context.ContextProfile.from(client).compressionTriggerTokens() != 64000)) {
            throw new IllegalArgumentException("original-task-qa requires Luna/Terra and an effective 64000-token threshold");
        }

        long started = System.currentTimeMillis();
        System.err.println("[driver] mode=" + mode + " contextMode=" + contextMode
                + " continuationRounds=" + continuationRounds
                + " provider=" + client.getProviderName()
                + " model=" + client.getModelName()
                + " modelContextWindow=" + client.maxContextWindow() + " env={"
                + environmentFingerprint(System.getProperties(), System.getenv()) + "}"
                + " memoryScope=isolated toolScope=ISOLATED_PROJECT");
        UsageCollector usage = new UsageCollector(outFile.toAbsolutePath().getParent().resolve("conversation.jsonl"));
        String previousCompaction = System.getProperty(ConversationHistoryCompactor.COMPACTION_ENABLED_PROPERTY);
        System.setProperty(ConversationHistoryCompactor.COMPACTION_ENABLED_PROPERTY,
                Boolean.toString("compact".equals(contextMode)));
        String out;
        try {
            out = switch (mode) {
                case "solo" -> runReact(client, project, prompt, true, usage, continuationRounds, continuationPrompts);
                case "delegate" -> runReact(client, project, prompt, false, usage, continuationRounds, continuationPrompts);
                case "plan" -> runPlan(config, client, project, prompt, usage, continuationRounds, continuationPrompts);
                default -> throw new IllegalArgumentException("mode must be solo|delegate|plan, got " + mode);
            };
        } finally {
            if (previousCompaction == null) {
                System.clearProperty(ConversationHistoryCompactor.COMPACTION_ENABLED_PROPERTY);
            } else {
                System.setProperty(ConversationHistoryCompactor.COMPACTION_ENABLED_PROPERTY, previousCompaction);
            }
        }
        long elapsed = System.currentTimeMillis() - started;
        out = out == null ? "" : out;
        Files.writeString(outFile, out, StandardCharsets.UTF_8);
        System.err.println("[driver] done mode=" + mode + " contextMode=" + contextMode
                + " continuationRounds=" + continuationRounds + " wallMs=" + elapsed
                + " outChars=" + out.length() + " out=" + outFile);
        UsageSnapshot totals = usage.snapshot();
        System.err.println("[driver] usage inputTokens=" + totals.inputTokens()
                + " outputTokens=" + totals.outputTokens()
                + " cachedInputTokens=" + totals.cachedInputTokens()
                + " estimatedCostCny=" + totals.estimatedCostCny());
        System.out.println(out);
    }

    static String normalizeContextMode(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("raw", "compact").contains(normalized)) {
            throw new IllegalArgumentException("context mode must be raw|compact, got " + value);
        }
        return normalized;
    }

    static int normalizeContinuationRounds(String value) {
        try {
            int rounds = Integer.parseInt(value == null ? "" : value.trim());
            if (rounds < 1 || rounds > 64) {
                throw new IllegalArgumentException("continuation rounds must be within 1..64, got " + value);
            }
            return rounds;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("continuation rounds must be within 1..64, got " + value, e);
        }
    }

    private static List<String> loadContinuationPrompts(Path directory, int continuationRounds) throws Exception {
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("continuation prompt directory missing: " + directory);
        }
        List<Path> files;
        try (var stream = Files.list(directory)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".txt"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        if (files.size() != continuationRounds - 1) {
            throw new IllegalArgumentException("expected " + (continuationRounds - 1)
                    + " continuation prompts, got " + files.size());
        }
        List<String> prompts = new ArrayList<>(files.size());
        for (Path file : files) prompts.add(Files.readString(file, StandardCharsets.UTF_8));
        return List.copyOf(prompts);
    }

    private static void registerBenchmarkContextWindow(LlmClient client) {
        int window = Integer.getInteger("devcli.benchmark.model.context.window", 0);
        if (window <= 0) return;
        ModelCapabilityRegistry.Capabilities current = client.capabilities();
        ModelCapabilityRegistry.register(new ModelCapabilityRegistry.Capabilities(
                client.getProviderName(), client.getModelName(), window, current.maxOutputTokens(),
                current.promptCaching(), current.promptCacheMode(), current.toolCalls(),
                current.vision(), current.reasoning()));
        System.err.println("[driver] benchmarkModelContextWindow=" + window);
    }

    /** solo/delegate 走统一 ReAct 会话；solo 额外移除 delegate_task 得到纯单 Agent。 */
    static String runReact(LlmClient client, Path project, String prompt, boolean solo,
                           UsageCollector eventSink, int continuationRounds,
                           List<String> continuationPrompts) {
        try (AgentSessionRuntime session = AgentSessionRuntime.create(
                client, null, project, eventSink)) {
            ToolRegistry registry = session.agent().getToolRegistry();
            configureClosedBook(session.agent(), !solo);
            return registry.runWithToolAccess(ToolRegistry.ToolAccessScope.ISOLATED_PROJECT,
                    () -> runRounds(session, prompt, continuationRounds, eventSink, continuationPrompts));
        }
    }

    /** plan 走固定 Planner/Worker/Reviewer 流水线，复用同一 registry 与闭卷白名单。 */
    private static String runPlan(DevCliConfig config, LlmClient client, Path project, String prompt,
                                  RunEventSink eventSink, int continuationRounds,
                                  List<String> continuationPrompts) {
        try (ToolRegistry registry = new ToolRegistry()) {
            registry.setProjectPath(project.toString());
            try (Agent agent = new Agent(client, registry)) {
                configureClosedBook(agent, false);
                LlmClient reviewer = LlmClientFactory.createTeamReviewer(config, client);
                AgentOrchestrator orchestrator = new AgentOrchestrator(
                        client, reviewer, registry, agent.getMemoryManager(), SILENT);
                orchestrator.setPlanReviewHandler(SweBenchDriver::headlessPlanReviewDecision);
                orchestrator.setAdditionalEventSink(eventSink);
                String output = orchestrator.run(prompt);
                for (int round = 2; round <= continuationRounds; round++) {
                    output = orchestrator.run(continuationPrompt(round, continuationPrompts));
                }
                return output;
            }
        }
    }

    private static String runRounds(AgentSessionRuntime session, String prompt, int continuationRounds,
                                    UsageCollector usage, List<String> continuationPrompts) {
        String sessionId = java.util.UUID.randomUUID().toString();
        String output = "";
        for (int round = 1; round <= continuationRounds; round++) {
            if (usage.externalFailure.get()) break;
            if (round > 1) System.err.println("[driver] continuation round=" + round);
            String question = round == 1 ? prompt : continuationPrompt(round, continuationPrompts);
            output = session.runInCurrentContext(question).output();
            emitRoundContextMetrics(session.agent(), round);
            usage.recordTurn(sessionId, question, output, contextWindowSnapshot(session.agent(), round));
        }
        return output;
    }

    static List<String> qaContinuationPrompts() {
        List<String> prompts = new ArrayList<>(63);
        String prefix = "Continue the same SWE-bench issue. Use the current conversation and workspace evidence, "
                + "preserve the original requirements, constraints, failures, decisions, and verified results. "
                + "Do not introduce another issue, do not restart, and do not run unrelated full suites. ";
        List<String> discovery = List.of("state confirmed facts and unknowns", "trace the next relevant code path",
                "identify one missing piece of evidence", "check one concrete edge case",
                "compare the current choice with an alternative", "verify unchanged behavior",
                "report actual results and avoid unsupported claims");
        for (int i = 0; i < 10; i++) {
            prompts.add(prefix + "Focus on diagnosis step " + (i + 1) + ": "
                    + discovery.get(i % discovery.size()) + ". If evidence is missing, say so and use only "
                    + "the project tools needed to obtain it.");
        }
        List<String> execution = List.of(
                "The context may have been compacted. Reconstruct the original task and inspect the current workspace before acting.",
                "Implement the smallest production-code change that fixes the original issue; do not stop at analysis or documentation.",
                "Add or update a focused regression test for the reported behavior, without changing unrelated tests.",
                "Run the focused test for the original issue and inspect the actual failure output.",
                "If the focused test fails, correct the implementation and rerun only the relevant test.",
                "Verify non-regression constraints and confirm the patch is based on the original issue, not a new hypothesis.",
                "Review the complete diff and remove unrelated edits; keep the fix minimal and production-ready.",
                "Run the smallest additional validation needed to support the original acceptance criteria.",
                "Confirm which requirements, root cause, evidence, decisions, files, and test results are represented in the final patch.",
                "Finalize the original SWE-bench patch and report the actual implementation and test result; do not claim unrun tests.");
        for (String action : execution) {
            prompts.add(prefix + action);
        }
        for (int i = prompts.size(); i < 63; i++) {
            prompts.add(prefix + "Continue the original task with one evidence-backed implementation or validation step. "
                    + "If the patch is complete, inspect it and report actual results without rerunning unrelated suites. "
                    + "Sequence " + i + ".");
        }
        return List.copyOf(prompts);
    }

    static boolean isSupportedQaModel(String model) {
        return "gpt-5.6-luna".equals(model) || "gpt-5.6-terra".equals(model);
    }

    static ContextWindowSnapshot contextWindowSnapshot(Agent agent, int round) {
        int historyTokens = TokenBudget.estimateMessagesTokens(agent.getConversationHistory());
        int toolDefinitionTokens = TokenBudget.estimateToolDefinitionsTokens(
                agent.getToolRegistry().getToolDefinitions());
        int triggerTokens = agent.getMemoryManager().getContextProfile()
                .historyTriggerTokens(toolDefinitionTokens);
        return new ContextWindowSnapshot(round, historyTokens, triggerTokens);
    }

    private static void emitRoundContextMetrics(Agent agent, int round) {
        ContextWindowSnapshot snapshot = contextWindowSnapshot(agent, round);
        System.err.println("[driver] context round=" + snapshot.round()
                + " historyTokens=" + snapshot.historyTokens()
                + " triggerTokens=" + snapshot.triggerTokens());
    }

    private static String continuationPrompt(int round, List<String> continuationPrompts) {
        if (!continuationPrompts.isEmpty()) return continuationPrompts.get(round - 2);
        return "继续当前 SWE-bench 任务（第 " + round + " 轮）。不要重新开始，也不要改变需求；"
                + "检查当前工作区和已有修改，补充尚未完成的实现，运行或补充相关测试，并在证据足够时给出最终结论。";
    }

    static AgentOrchestrator.TeamPlanReviewDecision headlessPlanReviewDecision(
            AgentOrchestrator.TeamPlanReviewRequest request) {
        // 无头 benchmark 只自动确认执行；HUMAN 标准在 Reviewer 协议中仍保持 pending_human。
        return AgentOrchestrator.TeamPlanReviewDecision.execute();
    }

    record UsageSnapshot(long inputTokens, long outputTokens, long cachedInputTokens,
                         double estimatedCostCny) {
    }

    record ContextWindowSnapshot(int round, int historyTokens, int triggerTokens) {
    }

    static final class UsageCollector implements RunEventSink {
        private final Path transcript;
        private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
                new com.fasterxml.jackson.databind.ObjectMapper();
        private final LongAdder inputTokens = new LongAdder();
        private final LongAdder outputTokens = new LongAdder();
        private final LongAdder cachedInputTokens = new LongAdder();
        private final DoubleAdder estimatedCostCny = new DoubleAdder();
        private final AtomicBoolean externalFailure = new AtomicBoolean();

        UsageCollector() {
            transcript = null;
        }

        UsageCollector(Path transcript) throws java.io.IOException {
            this.transcript = Files.createFile(transcript);
        }

        void recordTurn(String sessionId, String question, String answer, ContextWindowSnapshot context) {
            if (transcript == null) return;
            var row = JSON.createObjectNode();
            row.put("session_id", sessionId).put("round", context.round())
                    .put("question", question).put("answer", answer)
                    .put("history_tokens", context.historyTokens()).put("trigger_tokens", context.triggerTokens())
                    .put("input_tokens_cumulative", inputTokens.sum())
                    .put("output_tokens_cumulative", outputTokens.sum())
                    .put("external_failure", externalFailure.get());
            try {
                Files.writeString(transcript, JSON.writeValueAsString(row) + "\n", StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (java.io.IOException error) {
                throw new java.io.UncheckedIOException("Cannot preserve conversation evidence", error);
            }
        }

        @Override
        public void emit(RunEvent event) {
            if (event instanceof RunEvent.ModelUsage usage) {
                inputTokens.add(usage.inputTokens());
                outputTokens.add(usage.outputTokens());
                cachedInputTokens.add(usage.cachedInputTokens());
                estimatedCostCny.add(usage.estimatedCostCny());
            } else if (event instanceof RunEvent.FailureGuidance failure) {
                String reason = failure.reason();
                var code = java.util.regex.Pattern.compile("\\bcode=([A-Z_]+)").matcher(reason);
                String failureCode = code.find() ? code.group(1) : "UNKNOWN";
                if (reason.contains("model_not_found")) failureCode = "MODEL_UNAVAILABLE";
                boolean external = Set.of("AUTHENTICATION", "RATE_LIMITED", "OVERLOADED",
                        "TIMEOUT", "NETWORK", "SERVER_ERROR", "MODEL_UNAVAILABLE")
                        .contains(failureCode);
                if (external) externalFailure.set(true);
                System.err.println("[driver] failure code=" + failureCode + " external=" + external);
            } else if (event instanceof RunEvent.ToolCalls calls) {
                calls.calls().stream().filter(call -> "delegate_task".equals(call.name()))
                        .forEach(call -> System.err.println("[driver] delegation-call"));
            }
        }

        UsageSnapshot snapshot() {
            return new UsageSnapshot(inputTokens.sum(), outputTokens.sum(),
                    cachedInputTokens.sum(), estimatedCostCny.sum());
        }
    }

    static void configureClosedBook(Agent agent, boolean allowDelegation) {
        ToolRegistry registry = agent.getToolRegistry();
        Set<String> allowed = new java.util.HashSet<>(CLOSED_BOOK_TOOLS);
        if (allowDelegation) {
            allowed.add("delegate_task");
        }
        registry.retainTools(allowed);
        agent.getMemoryManager().setMemoryIgnored(true);
    }

    static String resolveSandboxMode(Properties properties, Map<String, String> environment) {
        String configured = properties == null ? null : properties.getProperty(
                DefaultCommandExecutionService.SANDBOX_MODE_PROPERTY);
        if ((configured == null || configured.isBlank()) && environment != null) {
            configured = environment.get(DefaultCommandExecutionService.SANDBOX_MODE_ENV);
        }
        String normalized = configured == null || configured.isBlank()
                ? "DOCKER"
                : configured.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (!Set.of("DOCKER", "HOST_WARN").contains(normalized)) {
            throw new IllegalArgumentException(
                    "sandbox mode must be DOCKER|HOST_WARN: " + configured);
        }
        return normalized;
    }

    static String environmentFingerprint(Properties properties,
                                         Map<String, String> environment) {
        Properties safeProperties = properties == null ? new Properties() : properties;
        Map<String, String> safeEnvironment = environment == null ? Map.of() : environment;
        String javaVersion = firstNonBlank(safeProperties.getProperty("java.version"), null,
                "unknown");
        String httpProtocol = firstNonBlank(
                safeProperties.getProperty("devcli.llm.http.protocol"),
                safeEnvironment.get("DEVCLI_LLM_HTTP_PROTOCOL"), "AUTO")
                .toUpperCase(Locale.ROOT).replace('-', '_').replace('.', '_');
        if ("HTTP11".equals(httpProtocol)) {
            httpProtocol = "HTTP_1_1";
        }
        String mavenRepository = firstNonBlank(
                safeProperties.getProperty(
                        DefaultCommandExecutionService.SANDBOX_MAVEN_REPOSITORY_PROPERTY),
                safeEnvironment.get(
                        DefaultCommandExecutionService.SANDBOX_MAVEN_REPOSITORY_ENV), "");
        return "java=" + javaVersion
                + " sandbox=" + resolveSandboxMode(safeProperties, safeEnvironment)
                + " http=" + httpProtocol
                + " mavenRepo=" + (mavenRepository.isBlank() ? "DEFAULT" : "EXPLICIT");
    }

    private static String firstNonBlank(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return fallback;
    }
}
