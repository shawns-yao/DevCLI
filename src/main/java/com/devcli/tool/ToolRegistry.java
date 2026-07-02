package com.devcli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.devcli.browser.BrowserAuditMetadata;
import com.devcli.browser.BrowserCheckResult;
import com.devcli.browser.BrowserConnector;
import com.devcli.browser.BrowserGuard;
import com.devcli.context.ContextProfile;
import com.devcli.lsp.LspDiagnosticReport;
import com.devcli.lsp.LspManager;
import com.devcli.mcp.protocol.McpSchemaValidator;
import com.devcli.mcp.protocol.McpToolDescriptor;
import com.devcli.rag.CodeRetriever;
import com.devcli.rag.RagEvidencePayload;
import com.devcli.rag.SearchResultFormatter;
import com.devcli.rag.SymbolInvalidation;
import com.devcli.rag.VectorStore;
import com.devcli.policy.AuditLog;
import com.devcli.policy.PathGuard;
import com.devcli.policy.PolicyException;
import com.devcli.runtime.CancellationContext;
import com.devcli.snapshot.SnapshotService;
import com.devcli.skill.Skill;
import com.devcli.skill.SkillContextBuffer;
import com.devcli.skill.SkillRegistry;
import com.devcli.tool.provider.FileToolProvider;
import com.devcli.tool.provider.MemoryToolProvider;
import com.devcli.tool.provider.ProjectToolProvider;
import com.devcli.tool.provider.ShellToolProvider;
import com.devcli.tool.provider.SnapshotToolProvider;
import com.devcli.tool.provider.ToolParameter;
import com.devcli.tool.provider.ToolProvider;
import com.devcli.tool.provider.ToolSearchProvider;
import com.devcli.tool.provider.WebToolProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 工具注册表 - 管理所有可用工具
 */
public class ToolRegistry implements AutoCloseable, ToolProvider.ToolContext {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int DEFAULT_COMMAND_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS = 90;
    private static final int MAX_PARALLEL_TOOLS = 4;
    // write_file 单次写入字节数上限。LLM 想塞超大内容时通常是误生成（重复粘贴 / hallucinate 大段日志），
    // 5MB 对常规代码生成 / 文档撰写完全够用，超过即拒，避免磁盘灌满与误覆盖。
    private static final int MAX_WRITE_FILE_BYTES = 5 * 1024 * 1024;
    // 需要审计的内置工具（与 ApprovalPolicy 的 DANGEROUS_TOOLS 保持一致）；MCP 工具按前缀动态纳入审计。
    private static final Set<String> AUDIT_TOOLS = Set.of("write_file", "execute_command", "create_project", "revert_turn");
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final Map<String, McpRegisteredTool> mcpTools = new ConcurrentHashMap<>();
    private final Map<String, Long> mcpServerLifecycleVersions = new ConcurrentHashMap<>();
    private final Set<String> activatedMcpToolDefinitions = ConcurrentHashMap.newKeySet();
    private final AtomicLong toolCatalogVersion = new AtomicLong();
    private final ToolSearchProvider toolSearchProvider = new ToolSearchProvider();
    private final long commandTimeoutSeconds;
    private final long toolBatchTimeoutSeconds;
    private String projectPath = System.getProperty("user.dir");
    private PathGuard pathGuard = new PathGuard(projectPath);
    private final AuditLog auditLog = new AuditLog();
    private ContextProfile contextProfile = ContextProfile.from(null);
    private BrowserGuard browserGuard;
    private BrowserConnector browserConnector;
    private java.util.function.Consumer<String> memorySaver;
    private MemorySaver memorySaveHandler;
    private MemoryListHandler memoryListHandler;
    private SkillRegistry skillRegistry;
    private SkillContextBuffer skillContextBuffer;
    private final ThreadLocal<SkillContextBuffer> skillContextBufferOverride = new ThreadLocal<>();
    private final ResourceLeaseManager resourceLeaseManager = new ResourceLeaseManager();
    private final ThreadLocal<String> resourceLeaseStep = new ThreadLocal<>();
    private java.util.function.BiConsumer<String, String[]> writeFileObserver = (p, ba) -> {};
    /** 按 step 归集 write_file 实际写过的文件（key 为 resourceLeaseStep 的 stepId），供 checkpoint 记录产物。 */
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.Set<String>> stepModifiedFiles =
            new java.util.concurrent.ConcurrentHashMap<>();
    private LspManager lspManager = new LspManager(projectPath);
    private SnapshotService snapshotService = SnapshotService.forProject(Path.of(projectPath));
    private boolean customSnapshotService;
    private CodeRetriever cachedCodeRetriever;
    private String cachedCodeRetrieverProjectPath = "";

    public ToolRegistry() {
        this(DEFAULT_COMMAND_TIMEOUT_SECONDS, DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS);
    }

    ToolRegistry(long commandTimeoutSeconds) {
        this(commandTimeoutSeconds, Math.max(commandTimeoutSeconds + 5, DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS));
    }

    ToolRegistry(long commandTimeoutSeconds, long toolBatchTimeoutSeconds) {
        this.commandTimeoutSeconds = commandTimeoutSeconds;
        this.toolBatchTimeoutSeconds = toolBatchTimeoutSeconds;
        // 租约抢占（空闲超时回收他人租约）接入审计链：被回收的慢步骤可事后排查
        resourceLeaseManager.setPreemptionListener((path, evictedStepId, newStepId, heldMs) ->
                auditLog.record(AuditLog.AuditEntry.error(
                        "resource_lease_preempt",
                        "path=" + path + ", evicted=" + evictedStepId + ", next=" + newStepId,
                        "租约空闲超时被回收，空闲 " + heldMs + "ms",
                        heldMs)));
        new FileToolProvider().register(this);
        new ShellToolProvider().register(this);
        new ProjectToolProvider().register(this);
        registerRagTools();
        new WebToolProvider().register(this);
        registerBrowserTools();
        new MemoryToolProvider().register(this);
        registerSkillTools();
        toolSearchProvider.register(this);
        new SnapshotToolProvider().register(this);
    }

    /**
     * 设置代码检索的项目路径
     */
    public void setProjectPath(String projectPath) {
        closeCachedCodeRetriever();
        this.projectPath = projectPath;
        this.pathGuard = new PathGuard(projectPath);
        this.lspManager.setProjectPath(projectPath);
        // 把 projectPath 同步给 ToolExecutionResult，让 ToolResultSizeManager 落盘时使用正确的根目录
        ToolExecutionResult.setActiveProjectPath(projectPath);
        if (!customSnapshotService) {
            this.snapshotService.close();
            this.snapshotService = SnapshotService.forProject(Path.of(projectPath));
        }
    }

    private synchronized CodeRetriever getCodeRetriever() throws Exception {
        String normalizedProjectPath = Path.of(projectPath).toAbsolutePath().normalize().toString();
        if (cachedCodeRetriever == null || !normalizedProjectPath.equals(cachedCodeRetrieverProjectPath)) {
            closeCachedCodeRetriever();
            cachedCodeRetriever = new CodeRetriever(normalizedProjectPath);
            cachedCodeRetrieverProjectPath = normalizedProjectPath;
        }
        return cachedCodeRetriever;
    }

    private synchronized void closeCachedCodeRetriever() {
        if (cachedCodeRetriever == null) {
            cachedCodeRetrieverProjectPath = "";
            return;
        }
        try {
            cachedCodeRetriever.close();
        } catch (Exception ignored) {
        } finally {
            cachedCodeRetriever = null;
            cachedCodeRetrieverProjectPath = "";
        }
    }

    @Override
    public void close() {
        closeCachedCodeRetriever();
        snapshotService.close();
    }

    /**
     * 获取代码检索的项目路径
     */
    public String getProjectPath() {
        return projectPath;
    }

    public void setContextProfile(ContextProfile contextProfile) {
        if (contextProfile != null) {
            this.contextProfile = contextProfile;
        }
    }

    public ContextProfile getContextProfile() {
        return contextProfile;
    }

    public void setBrowserGuard(BrowserGuard browserGuard) {
        this.browserGuard = browserGuard;
    }

    protected BrowserGuard getBrowserGuard() {
        return browserGuard;
    }

    public void setBrowserConnector(BrowserConnector browserConnector) {
        this.browserConnector = browserConnector;
    }

    public void setMemorySaver(java.util.function.Consumer<String> memorySaver) {
        this.memorySaver = memorySaver;
    }

    public void setMemorySaveHandler(MemorySaver memorySaveHandler) {
        this.memorySaveHandler = memorySaveHandler;
    }

    public void setMemoryListHandler(MemoryListHandler memoryListHandler) {
        this.memoryListHandler = memoryListHandler;
    }

    public void setSkillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    public SkillRegistry getSkillRegistry() {
        return skillRegistry;
    }

    public void setSkillContextBuffer(SkillContextBuffer skillContextBuffer) {
        this.skillContextBuffer = skillContextBuffer;
    }

    public SkillContextBuffer getSkillContextBuffer() {
        return skillContextBuffer;
    }

    /**
     * 当前线程临时覆盖 load_skill 写入目标。
     *
     * Multi-Agent 并行执行时多个 SubAgent 共享同一个 ToolRegistry，但每个 SubAgent
     * 需要把 load_skill 结果写回自己的 SkillContextBuffer。ThreadLocal 只包住本次
     * 工具执行，避免不同并行 worker 互相 drain / push 同一个 buffer。
     */
    public void runWithSkillContextBuffer(SkillContextBuffer buffer, Runnable action) {
        if (action == null) {
            return;
        }
        SkillContextBuffer previous = skillContextBufferOverride.get();
        if (buffer == null) {
            skillContextBufferOverride.remove();
        } else {
            skillContextBufferOverride.set(buffer);
        }
        try {
            action.run();
        } finally {
            if (previous == null) {
                skillContextBufferOverride.remove();
            } else {
                skillContextBufferOverride.set(previous);
            }
        }
    }

    private SkillContextBuffer activeSkillContextBuffer() {
        SkillContextBuffer override = skillContextBufferOverride.get();
        return override == null ? skillContextBuffer : override;
    }

    public <T> T runWithResourceLease(String stepId, java.util.function.Supplier<T> action) {
        if (action == null) {
            return null;
        }
        String previous = resourceLeaseStep.get();
        if (stepId == null || stepId.isBlank()) {
            resourceLeaseStep.remove();
        } else {
            resourceLeaseStep.set(stepId);
        }
        try {
            return action.get();
        } finally {
            if (previous == null) {
                resourceLeaseStep.remove();
            } else {
                resourceLeaseStep.set(previous);
            }
        }
    }

    public void releaseResourceLeases(String stepId) {
        resourceLeaseManager.releaseStep(stepId);
    }

    public void clearResourceLeases() {
        resourceLeaseManager.clear();
    }

    /** 清理超时租约（orchestration 启动时调用，回收上一轮崩溃残留）。 */
    public int pruneExpiredLeases() {
        return resourceLeaseManager.pruneExpiredLeases();
    }

    /**
     * 取出并清除指定 step 在本次执行中通过 write_file 实际修改过的文件列表。
     * 供 AgentOrchestrator 在步骤终态写 checkpoint 时归集产物；step 未写过文件时返回空列表。
     */
    public java.util.List<String> consumeStepModifiedFiles(String stepId) {
        if (stepId == null || stepId.isBlank()) {
            return java.util.List.of();
        }
        java.util.Set<String> files = stepModifiedFiles.remove(stepId);
        return files == null ? java.util.List.of() : java.util.List.copyOf(files);
    }

    @Override
    public void registerTool(Tool tool) {
        if (tool == null || tool.name() == null || tool.name().isBlank()) {
            return;
        }
        tools.put(tool.name(), tool);
        invalidateToolSearchIndex();
    }

    @Override
    public Path resolveSafePath(String path) { return pathGuard.resolveSafe(path); }
    @Override
    public int maxWriteFileBytes() { return MAX_WRITE_FILE_BYTES; }
    @Override
    public String currentResourceLeaseStep() { return resourceLeaseStep.get(); }
    @Override
    public void acquireWriteLease(String stepId, Path path) { resourceLeaseManager.acquireWrite(stepId, path); }
    @Override
    public boolean isWriteLeaseValid(String stepId, Path path) { return resourceLeaseManager.isLeaseValid(stepId, path); }

    @Override
    public void recordFileWrite(String displayPath, Path safePath, String before, String content, String stepId) {
        if (stepId != null && !stepId.isBlank()) {
            stepModifiedFiles
                    .computeIfAbsent(stepId, k -> java.util.concurrent.ConcurrentHashMap.newKeySet())
                    .add(safePath.toString());
        }
        try {
            writeFileObserver.accept(displayPath, new String[]{before, content});
        } catch (Exception ignored) {
            // observer 失败不能影响 write_file 主路径
        }
        runPostEditLspHook(displayPath, safePath);
    }

    @Override
    public String projectPath() { return projectPath; }
    @Override
    public long commandTimeoutSeconds() { return commandTimeoutSeconds; }
    @Override
    public java.util.function.Consumer<String> memorySaver() { return memorySaver; }
    @Override
    public MemorySaver memorySaveHandler() { return memorySaveHandler; }
    @Override
    public MemoryListHandler memoryListHandler() { return memoryListHandler; }
    @Override
    public SnapshotService snapshotService() { return snapshotService; }
    @Override
    public List<Tool> searchableTools() { return List.copyOf(tools.values()); }
    @Override
    public boolean isMcpTool(String toolName) { return mcpTools.containsKey(toolName); }
    @Override
    public boolean activateToolDefinition(String toolName) { return activateMcpToolDefinition(toolName); }
    @Override
    public long toolCatalogVersion() { return toolCatalogVersion.get(); }

    /**
     * 注册 write_file 写入观察者：参数 (path, [before, after])，
     * before == null 表示新建文件或读不出原文。
     * 用于把 write_file 接到行内 diff 渲染等只读副作用里；
     * 观察者抛异常不影响 write_file 主路径。
     */
    public void setWriteFileObserver(java.util.function.BiConsumer<String, String[]> observer) {
        this.writeFileObserver = observer == null ? (p, ba) -> {} : observer;
    }

    public void setLspManager(LspManager lspManager) {
        this.lspManager = lspManager == null ? new LspManager(projectPath) : lspManager;
        this.lspManager.setProjectPath(projectPath);
    }

    public LspDiagnosticReport flushPendingLspDiagnostics() {
        return lspManager == null ? LspDiagnosticReport.EMPTY : lspManager.flushPendingDiagnostics();
    }

    public SnapshotService getSnapshotService() {
        return snapshotService;
    }

    public void setSnapshotService(SnapshotService snapshotService) {
        this.snapshotService = snapshotService == null ? SnapshotService.forProject(Path.of(projectPath)) : snapshotService;
        this.customSnapshotService = snapshotService != null;
    }

    /**
     * 注册 RAG 检索工具
     */
    private void registerRagTools() {
        tools.put("search_code", new Tool(
                "search_code",
                "检索代码库。mode 可选：auto/general/call_chain/definition/error_trace/config；调用链场景可用 graph_depth 0-3 控制图谱扩展。",
                createParameters(
                        new Param("query", "string", "自然语言查询描述，例如'用户登录的实现'", true),
                        new Param("top_k", "integer", "返回结果数量（默认 5，上限 30）", false),
                        new Param("mode", "string", "检索意图，可选 auto/general/call_chain/definition/error_trace/config；非法值自动降级", false),
                        new Param("graph_depth", "integer", "调用链图谱扩展深度，范围 0-3；非调用链模式会自动收窄", false)
                ),
                args -> {
                    String query = args.get("query");
                    int topK = 5;
                    try {
                        if (args.containsKey("top_k")) {
                            topK = Integer.parseInt(args.get("top_k"));
                        }
                    } catch (NumberFormatException ignored) {
                    }
                    topK = Math.max(1, Math.min(topK, 30));
                    Integer graphDepth = null;
                    try {
                        if (args.containsKey("graph_depth")) {
                            graphDepth = Integer.parseInt(args.get("graph_depth"));
                        }
                    } catch (NumberFormatException ignored) {
                    }

                    try {
                        CodeRetriever retriever = getCodeRetriever();
                        synchronized (retriever) {
                            var stats = retriever.getStats();
                            if (stats.chunkCount() == 0) {
                                return "代码库尚未索引，请先使用 /index 命令索引当前项目。";
                            }

                            List<VectorStore.SearchResult> results = retriever.search(query, topK, args.get("mode"), graphDepth);
                            if (results.isEmpty()) {
                                results = retriever.search(query, topK, "general", 1);
                            }
                            List<SymbolInvalidation> invalidations =
                                    retriever.relevantInvalidations(query, Math.min(topK, 10));
                            String invalidationFacts = SearchResultFormatter.formatInvalidations(invalidations);
                            if (results.isEmpty()) {
                                if (!invalidationFacts.isBlank()) {
                                    return RagEvidencePayload.appendTo(invalidationFacts, query, results, invalidations);
                                }
                                return "未找到与查询相关的代码。";
                            }

                            String formatted = SearchResultFormatter.formatForTool(query, results);
                            if (!invalidationFacts.isBlank()) {
                                formatted = formatted + "\n\n" + invalidationFacts;
                            }
                            if (retriever.lastSemanticDegraded()) {
                                formatted = "（注意：语义检索服务不可用，本次已降级为关键词+结构化检索，结果可能不完整）\n\n"
                                        + formatted;
                            }
                            return RagEvidencePayload.appendTo(formatted, query, results, invalidations);
                        }
                    } catch (Exception e) {
                        closeCachedCodeRetriever();
                        return "代码检索失败: " + e.getMessage();
                    }
                }
        ));
    }

    private void registerBrowserTools() {
        tools.put("browser_connect", new Tool(
                "browser_connect",
                "当浏览器页面返回登录页、权限不足或明确需要登录态时，自动连接已允许远程调试的本机 Chrome 并复用其登录态；公开页面不要提前调用。",
                createParameters(),
                args -> browserConnector == null
                        ? "浏览器连接器未初始化，无法自动切换 shared 模式"
                        : browserConnector.connectDefault()
        ));
        tools.put("browser_disconnect", new Tool(
                "browser_disconnect",
                "完成登录态页面访问后，可切回 isolated 浏览器模式。",
                createParameters(),
                args -> browserConnector == null
                        ? "浏览器连接器未初始化，无法切回 isolated 模式"
                        : browserConnector.disconnect()
        ));
        tools.put("browser_status", new Tool(
                "browser_status",
                "查看当前浏览器 MCP 模式、autoConnect 引导和旧式 CDP 端口探活状态。",
                createParameters(),
                args -> browserConnector == null
                        ? "浏览器连接器未初始化，无法查看浏览器状态"
                        : browserConnector.status()
        ));
    }

    private void registerSkillTools() {
        tools.put("load_skill", new Tool(
                "load_skill",
                "Load full SKILL.md instructions for a skill the system has indexed (see the \"可用 Skills\" section in this system prompt). Call this when a skill's description matches the current task. Pass the exact kebab-case skill name. The full body will appear at the start of your next user message under \"## 已加载 Skill：<name>\". Don't reload the same skill twice in one session.",
                createParameters(new Param("name", "string", "the exact kebab-case skill name (e.g. web-access)", true)),
                args -> {
                    String name = args.get("name");
                    if (name == null || name.isBlank()) {
                        return "load_skill 失败: name 不能为空";
                    }
                    if (skillRegistry == null) {
                        return "load_skill 失败: Skill 系统未初始化";
                    }
                    Skill skill = skillRegistry.findSkill(name);
                    if (skill == null) {
                        Skill any = skillRegistry.findAnySkill(name);
                        if (any == null) {
                            return "Skill '" + name + "' 未找到，可用 /skill list 查看可用 skill";
                        }
                        return "Skill '" + name + "' 已被禁用，可用 /skill on " + name + " 启用";
                    }
                    String body = skill.body();
                    int originalLen = body == null ? 0 : body.length();
                    int max = 5 * 1024;
                    String injected = body == null ? "" : body;
                    if (injected.length() > max) {
                        injected = injected.substring(0, max)
                                + "\n\n...(skill body truncated, full content via /skill show " + name + ")";
                    }
                    SkillContextBuffer targetBuffer = activeSkillContextBuffer();
                    if (targetBuffer != null) {
                        targetBuffer.push(name, injected, skill.allowedTools(), skill.context());
                    }
                    skillRegistry.recordUsage(name);
                    String allowedTools = skill.allowedTools().isEmpty()
                            ? ""
                            : "允许工具: " + String.join(", ", skill.allowedTools()) + "。";
                    String context = skill.context() == Skill.Context.FORK
                            ? "context: fork。建议在子任务/fork 上下文中使用，避免污染主上下文。"
                            : "context: inline。";
                    return "已加载 skill '" + name + "' 的完整指引（" + originalLen
                            + " bytes），" + allowedTools
                            + context
                            + "将在下一轮上下文中以 \"## 已加载 Skill：" + name + "\" 段出现。";
                }
        ));
    }

    private void runPostEditLspHook(String displayPath, Path safePath) {
        try {
            if (lspManager != null) {
                lspManager.runPostEditLspHook(displayPath, safePath);
            }
        } catch (Exception ignored) {
            // LSP 诊断是 post-edit 辅助信号，失败不能影响工具主结果。
        }
    }

    public int prefetchToolDefinitionsForInput(String input) {
        return mcpTools.isEmpty() ? 0 : toolSearchProvider.prefetchToolDefinitionsForInput(this, input);
    }

    long toolSearchIndexBuildCount() {
        return toolSearchProvider.buildCount();
    }

    private void invalidateToolSearchIndex() {
        toolCatalogVersion.incrementAndGet();
    }

    /**
     * 创建参数定义
     */
    @Override
    public JsonNode createToolParameters(ToolParameter... params) {
        Param[] converted = Arrays.stream(params == null ? new ToolParameter[0] : params)
                .map(param -> new Param(param.name(), param.type(), param.description(), param.required(),
                        param.enumValues() == null ? List.of() : param.enumValues()))
                .toArray(Param[]::new);
        return createParameters(converted);
    }

    private JsonNode createParameters(Param... params) {
        ObjectNode parameters = mapper.createObjectNode();
        parameters.put("type", "object");
        parameters.put("additionalProperties", false);
        ObjectNode properties = parameters.putObject("properties");
        ArrayNode required = parameters.putArray("required");

        for (Param param : params) {
            ObjectNode prop = properties.putObject(param.name());
            prop.put("type", param.type());
            prop.put("description", param.description());
            if ("string".equals(param.type()) && param.required()) {
                prop.put("minLength", 1);
            }
            if (param.enumValues() != null && !param.enumValues().isEmpty()) {
                ArrayNode enumNode = prop.putArray("enum");
                for (String enumValue : param.enumValues()) {
                    enumNode.add(enumValue);
                }
            }
            if (param.required()) {
                required.add(param.name());
            }
        }

        return parameters;
    }

    /**
     * 获取所有工具定义（用于LLM）
     */
    public List<com.devcli.llm.LlmClient.Tool> getToolDefinitions() {
        return tools.values().stream()
                .filter(tool -> isToolDefinitionVisible(tool.name()))
                .map(t -> new com.devcli.llm.LlmClient.Tool(t.name(), t.description(), t.parameters()))
                .toList();
    }

    private boolean isToolDefinitionVisible(String toolName) {
        return !mcpTools.containsKey(toolName) || activatedMcpToolDefinitions.contains(toolName);
    }

    private boolean activateMcpToolDefinition(String toolName) {
        if (toolName != null && mcpTools.containsKey(toolName)) {
            return activatedMcpToolDefinitions.add(toolName);
        }
        return false;
    }

    /**
     * 注册一个 MCP 工具到 ToolRegistry。
     *
     * @param descriptor 工具描述（含 namespacedName 如 mcp__filesystem__read_file）
     * @param invoker    工具执行器：输入 JSON 参数字符串，输出给 LLM 看的字符串结果。
     *                   typically lambda 在内部调用 McpClient.callTool 并处理异常 → 字符串。
     */
    public synchronized void registerMcpTool(McpToolDescriptor descriptor, Function<String, String> invoker) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(invoker, "invoker");
        registerMcpToolOutput(descriptor, args -> ToolOutput.text(invoker.apply(args)));
    }

    public synchronized void registerMcpToolOutput(McpToolDescriptor descriptor, Function<String, ToolOutput> invoker) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(invoker, "invoker");
        String toolName = descriptor.namespacedName();
        McpRegisteredTool registered = new McpRegisteredTool(descriptor, invoker);
        mcpTools.put(toolName, registered);
        tools.put(toolName, new Tool(
                toolName,
                mcpDescription(descriptor),
                descriptor.inputSchema(),
                args -> "MCP 工具不应通过 Map<String,String> 入口执行"
        ));
        invalidateToolSearchIndex();
    }

    public synchronized void unregisterMcpTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return;
        }
        boolean removed = mcpTools.remove(toolName) != null;
        removed = tools.remove(toolName) != null || removed;
        activatedMcpToolDefinitions.remove(toolName);
        if (removed) {
            invalidateToolSearchIndex();
        }
    }

    protected synchronized boolean mcpToolRequiresPerCallApproval(String toolName) {
        McpRegisteredTool registered = mcpTools.get(toolName);
        if (registered == null || registered.descriptor().annotations() == null) {
            return false;
        }
        McpToolDescriptor.Annotations annotations = registered.descriptor().annotations();
        return annotations.destructive() || annotations.openWorld();
    }

    protected synchronized String mcpToolApprovalNotice(String toolName) {
        McpRegisteredTool registered = mcpTools.get(toolName);
        if (registered == null || registered.descriptor().annotations() == null) {
            return null;
        }
        McpToolDescriptor.Annotations annotations = registered.descriptor().annotations();
        List<String> risks = new ArrayList<>();
        if (annotations.destructive()) {
            risks.add("destructive");
        }
        if (annotations.openWorld()) {
            risks.add("openWorld");
        }
        return risks.isEmpty() ? null : "MCP annotations require per-call approval: " + String.join(", ", risks);
    }

    public synchronized String mcpToolSnapshot() {
        if (mcpTools.isEmpty()) {
            return "none";
        }
        Map<String, List<McpRegisteredTool>> toolsByServer = new TreeMap<>();
        for (McpRegisteredTool registered : mcpTools.values()) {
            String serverName = registered.descriptor().serverName();
            String normalizedServer = serverName == null || serverName.isBlank() ? "unknown" : serverName;
            toolsByServer.computeIfAbsent(normalizedServer, ignored -> new ArrayList<>()).add(registered);
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, List<McpRegisteredTool>> entry : toolsByServer.entrySet()) {
            List<McpRegisteredTool> serverTools = entry.getValue().stream()
                    .sorted(Comparator.comparing(tool -> tool.descriptor().name()))
                    .toList();
            long lifecycleVersion = mcpServerLifecycleVersions.getOrDefault(entry.getKey(), 0L);
            parts.add(entry.getKey() + ":" + serverTools.size()
                    + "@" + mcpToolSchemaFingerprint(serverTools)
                    + "#v" + Math.max(0, lifecycleVersion));
        }
        return String.join(", ", parts);
    }

    public synchronized void setMcpServerLifecycleVersion(String serverName, long lifecycleVersion) {
        String normalized = normalizeMcpServerName(serverName);
        if (normalized == null) {
            return;
        }
        mcpServerLifecycleVersions.put(normalized, Math.max(0, lifecycleVersion));
    }

    public String currentRagIndexEpochSnapshot() {
        try (VectorStore store = new VectorStore(projectPath)) {
            return store.currentIndexEpoch();
        } catch (Exception e) {
            return "none";
        }
    }

    private static String mcpToolSchemaFingerprint(List<McpRegisteredTool> serverTools) {
        StringBuilder payload = new StringBuilder();
        for (McpRegisteredTool tool : serverTools) {
            McpToolDescriptor descriptor = tool.descriptor();
            payload.append(descriptor.name()).append('\n')
                    .append(descriptor.namespacedName()).append('\n')
                    .append(canonicalJson(descriptor.inputSchema())).append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(payload.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                hex.append(String.format(Locale.ROOT, "%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(payload.toString().hashCode());
        }
    }

    private static String canonicalJson(JsonNode node) {
        if (node == null) {
            return "";
        }
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            return node.toString();
        }
    }

    public synchronized void replaceMcpToolsForServer(String serverName, List<McpToolDescriptor> newTools,
                                                      Function<McpToolDescriptor, Function<String, String>> invokerFactory) {
        replaceMcpToolOutputsForServer(serverName, newTools,
                descriptor -> args -> ToolOutput.text(invokerFactory.apply(descriptor).apply(args)));
    }

    public synchronized void replaceMcpToolOutputsForServer(String serverName, List<McpToolDescriptor> newTools,
                                                            Function<McpToolDescriptor, Function<String, ToolOutput>> invokerFactory) {
        replaceMcpToolOutputsForServer(serverName, newTools, 0, invokerFactory);
    }

    public synchronized void replaceMcpToolOutputsForServer(String serverName, List<McpToolDescriptor> newTools,
                                                            long lifecycleVersion,
                                                            Function<McpToolDescriptor, Function<String, ToolOutput>> invokerFactory) {
        Objects.requireNonNull(serverName, "serverName");
        Objects.requireNonNull(newTools, "newTools");
        Objects.requireNonNull(invokerFactory, "invokerFactory");
        setMcpServerLifecycleVersion(serverName, lifecycleVersion);
        String prefix = "mcp__" + serverName + "__";
        List<String> existing = mcpTools.keySet().stream()
                .filter(name -> name.startsWith(prefix))
                .toList();
        for (String toolName : existing) {
            mcpTools.remove(toolName);
            tools.remove(toolName);
            activatedMcpToolDefinitions.remove(toolName);
        }
        if (!existing.isEmpty()) {
            invalidateToolSearchIndex();
        }
        for (McpToolDescriptor descriptor : newTools) {
            registerMcpToolOutput(descriptor, invokerFactory.apply(descriptor));
        }
    }

    private static String normalizeMcpServerName(String serverName) {
        if (serverName == null || serverName.isBlank()) {
            return null;
        }
        return serverName.trim();
    }

    /**
     * 执行工具调用
     *
     * 危险工具（write_file / execute_command / create_project）会写一行审计：
     * - 策略拦截（PathGuard / CommandGuard / 文件大小上限）→ deny
     * - 普通异常 → error
     * - 其他情况 → allow（仅表示工具调用真的发生过，工具内部的业务错误仍以返回字符串呈现给 LLM）
     */
    public String executeTool(String name, String argumentsJson) {
        return doExecuteTool(name, argumentsJson).text();
    }

    public ToolOutput executeToolOutput(String name, String argumentsJson) {
        if (isLegacyExecuteToolOverride()) {
            return ToolOutput.text(executeTool(name, argumentsJson));
        }
        return doExecuteTool(name, argumentsJson);
    }

    protected ToolOutput doExecuteTool(String name, String argumentsJson) {
        if (CancellationContext.isCancelled()) {
            return ToolOutput.text("用户取消了此次工具调用");
        }
        Tool tool = tools.get(name);
        if (tool == null) {
            return ToolOutput.text(unknownToolGuidance(name));
        }
        ToolOutput skillPermissionError = validateSkillToolAllowed(name);
        if (skillPermissionError != null) {
            return skillPermissionError;
        }

        boolean shouldAudit = shouldAudit(name);
        long start = System.nanoTime();
        BrowserAuditMetadata auditMetadata = null;

        try {
            ToolOutput validationError = validateToolArguments(name, argumentsJson);
            if (validationError != null) {
                return validationError;
            }
            JsonNode parsedArgs = parseArguments(argumentsJson);

            McpRegisteredTool mcpTool = mcpTools.get(name);
            if (mcpTool != null) {
                BrowserCheckResult browserCheck = checkBrowserTool(name, argumentsJson, false);
                auditMetadata = browserCheck.metadata();
                if (browserCheck.blocked()) {
                    throw new PolicyException(browserCheck.reason());
                }
                ToolOutput output = mcpTool.invoker().apply(argumentsJson);
                if (output == null) {
                    output = ToolOutput.text("");
                }
                if (browserGuard != null) {
                    browserGuard.applyAfterExecution(name, argumentsJson, output.text());
                }
                if (shouldAudit) {
                    auditLog.record(AuditLog.AuditEntry.allow(name, argumentsJson, elapsedMillis(start), auditMetadata));
                }
                return output;
            }

            Map<String, String> argMap = new HashMap<>();
            parsedArgs.fields().forEachRemaining(entry ->
                    argMap.put(entry.getKey(), entry.getValue().asText()));
            String result = tool.executor().execute(argMap);
            if (shouldAudit) {
                auditLog.record(AuditLog.AuditEntry.allow(name, argumentsJson, elapsedMillis(start), auditMetadata));
            }
            return ToolOutput.text(result);
        } catch (PolicyException e) {
            if (shouldAudit) {
                auditLog.record(AuditLog.AuditEntry.denyByPolicy(
                        name, argumentsJson, e.getMessage(), elapsedMillis(start), auditMetadata));
            }
            return ToolOutput.text("🛡️ 策略拒绝: " + e.getMessage());
        } catch (Exception e) {
            if (shouldAudit) {
                auditLog.record(AuditLog.AuditEntry.error(
                        name, argumentsJson, e.getMessage(), elapsedMillis(start), auditMetadata));
            }
            return ToolOutput.text("工具执行失败: " + e.getMessage());
        }
    }

    private static String unknownToolGuidance(String name) {
        String toolName = name == null || name.isBlank() ? "(empty)" : name;
        String query = toolName
                .replace("mcp__", "")
                .replace("__", " ")
                .replace('_', ' ')
                .trim();
        if (query.isBlank()) {
            query = toolName;
        }
        return "未知工具: " + toolName
                + "\n可先调用 search_tools 查找可用工具，例如: {\"query\":\""
                + escapeJson(query) + "\"}";
    }

    private static String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    protected ToolOutput validateSkillToolAllowed(String name) {
        SkillContextBuffer buffer = activeSkillContextBuffer();
        if (buffer == null) {
            return null;
        }
        Set<String> allowedTools = buffer.activeAllowedTools();
        if (allowedTools.isEmpty() || allowedTools.contains(name)) {
            return null;
        }
        return ToolOutput.text("Skill 工具权限拒绝: 当前已加载 Skill 只允许使用 "
                + String.join(", ", allowedTools)
                + "；被拒绝工具: " + name);
    }

    protected ToolOutput validateToolArguments(String name, String argumentsJson) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return null;
        }
        JsonNode parsedArgs;
        try {
            parsedArgs = parseArguments(argumentsJson);
        } catch (JsonProcessingException e) {
            return validationFailed("不是合法 JSON: " + e.getOriginalMessage());
        }
        JsonNode schema = tool.parameters();
        McpRegisteredTool mcpTool = mcpTools.get(name);
        if (mcpTool != null) {
            schema = mcpTool.descriptor().inputSchema();
        }
        McpSchemaValidator.ValidationResult validation = McpSchemaValidator.validate(schema, parsedArgs);
        if (!validation.valid()) {
            return validationFailed(validation.message());
        }
        return null;
    }

    private JsonNode parseArguments(String argumentsJson) throws JsonProcessingException {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return mapper.createObjectNode();
        }
        return mapper.readTree(argumentsJson);
    }

    private ToolOutput validationFailed(String message) {
        return ToolOutput.text("工具参数校验失败: " + (message == null || message.isBlank() ? "参数不符合工具 schema" : message)
                + "。请根据工具 JSON Schema 修正参数后重试。");
    }

    private boolean isLegacyExecuteToolOverride() {
        try {
            return getClass()
                    .getMethod("executeTool", String.class, String.class)
                    .getDeclaringClass() != ToolRegistry.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    protected BrowserCheckResult checkBrowserTool(String name, String argumentsJson, boolean previewOnly) {
        if (browserGuard == null || !BrowserGuard.isChromeTool(name)) {
            return BrowserCheckResult.allow(null);
        }
        return browserGuard.check(name, argumentsJson, !previewOnly);
    }

    public AuditLog getAuditLog() {
        return auditLog;
    }

    /**
     * 并行执行同一轮 LLM 返回的多个工具调用。
     *
     * 结果按传入顺序返回，调用方可以安全地按原 tool_call 顺序回灌消息历史。
     * 如果某个工具超过批次超时仍未返回，会取消任务并返回超时结果；已完成工具不受影响。
     */
    public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
        ToolResultSizeManager.resetTurnBudget();
        if (invocations == null || invocations.isEmpty()) {
            return List.of();
        }
        if (CancellationContext.isCancelled()) {
            return invocations.stream()
                    .map(invocation -> ToolExecutionResult.failed(invocation, "用户取消了此次工具调用"))
                    .toList();
        }
        if (invocations.size() == 1) {
            ToolInvocation invocation = invocations.get(0);
            long startedAt = System.nanoTime();
            ToolOutput output = executeToolOutput(invocation.name(), invocation.argumentsJson());
            return List.of(ToolExecutionResult.completed(invocation, output, elapsedMillis(startedAt)));
        }

        int parallelism = Math.min(invocations.size(), MAX_PARALLEL_TOOLS);
        SkillContextBuffer activeSkillBuffer = activeSkillContextBuffer();
        ExecutorService executor = Executors.newFixedThreadPool(parallelism, r -> {
            Thread thread = new Thread(r, "devcli-tool-executor");
            thread.setDaemon(true);
            return thread;
        });

        try {
            List<Callable<ToolExecutionResult>> tasks = invocations.stream()
                    .<Callable<ToolExecutionResult>>map(invocation -> () -> {
                        if (CancellationContext.isCancelled()) {
                            return ToolExecutionResult.failed(invocation, "用户取消了此次工具调用");
                        }
                        long startedAt = System.nanoTime();
                        java.util.concurrent.atomic.AtomicReference<ToolOutput> output =
                                new java.util.concurrent.atomic.AtomicReference<>(ToolOutput.text(""));
                        runWithSkillContextBuffer(activeSkillBuffer,
                                () -> output.set(executeToolOutput(invocation.name(), invocation.argumentsJson())));
                        return ToolExecutionResult.completed(invocation, output.get(), elapsedMillis(startedAt));
                    })
                    .toList();

            List<Future<ToolExecutionResult>> futures =
                    executor.invokeAll(tasks, toolBatchTimeoutSeconds, TimeUnit.SECONDS);

            List<ToolExecutionResult> results = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                ToolInvocation invocation = invocations.get(i);
                Future<ToolExecutionResult> future = futures.get(i);
                if (future.isCancelled()) {
                    results.add(ToolExecutionResult.timedOut(invocation, toolBatchTimeoutSeconds));
                    continue;
                }

                try {
                    results.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(ToolExecutionResult.failed(invocation, "工具执行被中断"));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    String message = cause == null || cause.getMessage() == null
                            ? "未知错误"
                            : cause.getMessage();
                    results.add(ToolExecutionResult.failed(invocation, message));
                }
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return invocations.stream()
                    .map(invocation -> ToolExecutionResult.failed(invocation, "工具批次执行被中断"))
                    .toList();
        } finally {
            executor.shutdownNow();
        }
    }

    private long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    private static boolean shouldAudit(String name) {
        return AUDIT_TOOLS.contains(name) || (name != null && name.startsWith("mcp__"));
    }

    private static String mcpDescription(McpToolDescriptor descriptor) {
        String base = descriptor.description() == null || descriptor.description().isBlank()
                ? "MCP server 提供的外部工具"
                : descriptor.description();
        String annotations = mcpAnnotationSummary(descriptor.annotations());
        return base + " (MCP server: " + descriptor.serverName() + ", tool: " + descriptor.name()
                + (annotations.isBlank() ? "" : ", annotations: " + annotations)
                + ")";
    }

    private static String mcpAnnotationSummary(McpToolDescriptor.Annotations annotations) {
        if (annotations == null) {
            return "";
        }
        List<String> labels = new ArrayList<>();
        if (annotations.readOnly()) {
            labels.add("readOnly");
        }
        if (annotations.destructive()) {
            labels.add("destructive");
        }
        labels.add(annotations.openWorld() ? "openWorld" : "closedWorld");
        return String.join(", ", labels);
    }

    // 记录定义
    private record Param(String name, String type, String description, boolean required, List<String> enumValues) {
        private Param(String name, String type, String description, boolean required) {
            this(name, type, description, required, List.of());
        }

        private Param(String name, String type, String description, boolean required, String... enumValues) {
            this(name, type, description, required,
                    enumValues == null || enumValues.length == 0 ? List.of() : List.of(enumValues));
        }
    }

    public record Tool(String name, String description, JsonNode parameters, ToolExecutor executor) {}

    private record McpRegisteredTool(McpToolDescriptor descriptor, Function<String, ToolOutput> invoker) {}

    public record MemorySaveResult(boolean stored, String message) {}

    @FunctionalInterface
    public interface MemoryListHandler {
        String list(int limit);
    }

    @FunctionalInterface
    public interface MemorySaver {
        MemorySaveResult save(String fact);
    }

    public record ToolInvocation(String id, String name, String argumentsJson) {}

    public record ToolExecutionResult(String id, String name, String argumentsJson,
                                      String result, long elapsedMillis, boolean timedOut,
                                      List<com.devcli.llm.LlmClient.ContentPart> imageParts) {
        // 当前 ToolRegistry 实例的 projectPath。在 completed(...) 工厂方法里读取。
        // 用 ThreadLocal 也行；这里用线程安全的 volatile 静态字段——所有 ToolRegistry
        // 共享同一个 size manager 配置，简单。
        private static volatile String activeProjectPath = System.getProperty("user.dir");

        static void setActiveProjectPath(String projectPath) {
            if (projectPath != null && !projectPath.isBlank()) {
                activeProjectPath = projectPath;
            }
        }

        private static ToolExecutionResult completed(ToolInvocation invocation, ToolOutput output, long elapsedMillis) {
            String rawResult = output == null ? "" : output.text();
            List<com.devcli.llm.LlmClient.ContentPart> images = output == null ? List.of() : output.imageParts();
            // 工具结果尺寸治理：> 5K 截断，> 50K 落盘 + 预览。
            // 见 ToolResultSizeManager 的白名单（read_file / list_dir 等）。
            String managedResult = ToolResultSizeManager.process(
                    invocation.name(),
                    invocation.id(),
                    activeProjectPath,
                    images != null && !images.isEmpty(),
                    rawResult);
            return new ToolExecutionResult(
                    invocation.id(),
                    invocation.name(),
                    invocation.argumentsJson(),
                    managedResult,
                    elapsedMillis,
                    false,
                    images);
        }

        private static ToolExecutionResult completed(ToolInvocation invocation, String result, long elapsedMillis) {
            return completed(invocation, ToolOutput.text(result), elapsedMillis);
        }

        public static ToolExecutionResult failed(ToolInvocation invocation, String message) {
            return completed(invocation, "工具执行失败: " + message, 0);
        }

        private static ToolExecutionResult timedOut(ToolInvocation invocation, long timeoutSeconds) {
            return new ToolExecutionResult(
                    invocation.id(),
                    invocation.name(),
                    invocation.argumentsJson(),
                    "工具执行超时（" + timeoutSeconds + "秒），已取消",
                    timeoutSeconds * 1000,
                    true,
                    List.of()
            );
        }

        public boolean hasImageParts() {
            return imageParts != null && !imageParts.isEmpty();
        }
    }

    public interface ToolExecutor {
        String execute(Map<String, String> args);
    }
}
