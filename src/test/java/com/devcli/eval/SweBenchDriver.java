package com.devcli.eval;

import com.devcli.agent.Agent;
import com.devcli.agent.AgentOrchestrator;
import com.devcli.config.DevCliConfig;
import com.devcli.llm.LlmClient;
import com.devcli.llm.LlmClientFactory;
import com.devcli.runtime.AgentSessionRuntime;
import com.devcli.runtime.event.RunEventSink;
import com.devcli.tool.ToolRegistry;
import com.devcli.tool.command.DefaultCommandExecutionService;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * SWE-bench 无头对照 driver。
 *
 * <p>args：&lt;projectPath&gt; &lt;promptFile&gt; &lt;outFile&gt; &lt;mode: solo|delegate|plan&gt;</p>
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
        if (args.length < 4) {
            System.err.println("usage: SweBenchDriver <project> <promptFile> <outFile> <solo|delegate|plan>");
            System.exit(2);
        }
        Path project = Path.of(args[0]).toAbsolutePath().normalize();
        String prompt = Files.readString(Path.of(args[1]), StandardCharsets.UTF_8);
        Path outFile = Path.of(args[2]);
        String mode = args[3].trim().toLowerCase(Locale.ROOT);

        DevCliConfig config = DevCliConfig.load();
        LlmClient client = LlmClientFactory.createFromConfig(config);
        if (client == null) {
            throw new IllegalStateException("no LLM client: check .env");
        }

        long started = System.currentTimeMillis();
        System.err.println("[driver] mode=" + mode + " provider=" + client.getProviderName()
                + " model=" + client.getModelName() + " env={"
                + environmentFingerprint(System.getProperties(), System.getenv()) + "}"
                + " project=" + project);
        String out = switch (mode) {
            case "solo" -> runReact(client, project, prompt, true);
            case "delegate" -> runReact(client, project, prompt, false);
            case "plan" -> runPlan(config, client, project, prompt);
            default -> throw new IllegalArgumentException("mode must be solo|delegate|plan, got " + mode);
        };
        long elapsed = System.currentTimeMillis() - started;
        out = out == null ? "" : out;
        Files.writeString(outFile, out, StandardCharsets.UTF_8);
        System.err.println("[driver] done mode=" + mode + " wallMs=" + elapsed
                + " outChars=" + out.length() + " out=" + outFile);
        System.out.println(out);
    }

    /** solo/delegate 走统一 ReAct 会话；solo 额外移除 delegate_task 得到纯单 Agent。 */
    private static String runReact(LlmClient client, Path project, String prompt, boolean solo) {
        try (AgentSessionRuntime session = AgentSessionRuntime.create(
                client, null, project, RunEventSink.NO_OP)) {
            ToolRegistry registry = session.agent().getToolRegistry();
            configureClosedBook(session.agent(), !solo);
            return session.runBlocking(prompt).output();
        }
    }

    /** plan 走固定 Planner/Worker/Reviewer 流水线，复用同一 registry 与闭卷白名单。 */
    private static String runPlan(DevCliConfig config, LlmClient client, Path project, String prompt) {
        try (ToolRegistry registry = new ToolRegistry()) {
            registry.setProjectPath(project.toString());
            try (Agent agent = new Agent(client, registry)) {
                configureClosedBook(agent, false);
                LlmClient reviewer = LlmClientFactory.createTeamReviewer(config, client);
                AgentOrchestrator orchestrator = new AgentOrchestrator(
                        client, reviewer, registry, agent.getMemoryManager(), SILENT);
                return orchestrator.run(prompt);
            }
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
