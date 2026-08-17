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
import com.devcli.mcp.config.McpToolTrustPolicy;
import com.devcli.mcp.protocol.McpSchemaValidator;
import com.devcli.mcp.protocol.McpToolDescriptor;
import com.devcli.rag.VectorStore;
import com.devcli.policy.AuditLog;
import com.devcli.policy.PathGuard;
import com.devcli.policy.PolicyException;
import com.devcli.runtime.CancellationContext;
import com.devcli.runtime.CancellationReason;
import com.devcli.runtime.CancellationToken;
import com.devcli.snapshot.SnapshotService;
import com.devcli.skill.SkillContextBuffer;
import com.devcli.skill.SkillRegistry;
import com.devcli.tool.command.CommandExecutionService;
import com.devcli.tool.command.DefaultCommandExecutionService;
import com.devcli.tool.provider.BrowserToolProvider;
import com.devcli.tool.provider.FileToolProvider;
import com.devcli.tool.provider.GrepToolProvider;
import com.devcli.tool.provider.MemoryToolProvider;
import com.devcli.tool.provider.ProjectToolProvider;
import com.devcli.tool.provider.RagToolProvider;
import com.devcli.tool.provider.ShellToolProvider;
import com.devcli.tool.provider.SkillToolProvider;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * 工具注册表 - 管理所有可用工具
 */
public class ToolRegistry implements AutoCloseable, ToolProvider.ToolContext {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int DEFAULT_COMMAND_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS = 90;
    private static final int MAX_PARALLEL_TOOLS = 4;
    private static final long COOPERATIVE_CANCEL_GRACE_MILLIS = 250;
    private static final long TERMINATION_CONFIRM_TIMEOUT_MILLIS = 1_000;
    // write_file 单次写入字节数上限。LLM 想塞超大内容时通常是误生成（重复粘贴 / hallucinate 大段日志），
    // 5MB 对常规代码生成 / 文档撰写完全够用，超过即拒，避免磁盘灌满与误覆盖。
    private static final int MAX_WRITE_FILE_BYTES = 5 * 1024 * 1024;
    // 需要审计的内置工具（与 ApprovalPolicy 的 DANGEROUS_TOOLS 保持一致）；MCP 工具按前缀动态纳入审计。
    private static final Set<String> AUDIT_TOOLS = Set.of("write_file", "execute_command", "create_project", "revert_turn");
    private static final String PIPELINE_PARSED_ARGUMENTS = "parsedArguments";
    private static final String PIPELINE_BROWSER_AUDIT = "browserAuditMetadata";
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final Map<String, McpRegisteredTool> mcpTools = new ConcurrentHashMap<>();
    private final Map<String, McpToolTrustPolicy> mcpTrustPolicies = new ConcurrentHashMap<>();
    private final Map<String, Long> mcpServerLifecycleVersions = new ConcurrentHashMap<>();
    private final Set<String> activatedMcpToolDefinitions = ConcurrentHashMap.newKeySet();
    private final AtomicLong toolCatalogVersion = new AtomicLong();
    private final RagToolProvider ragToolProvider = new RagToolProvider();
    private final ToolSearchProvider toolSearchProvider = new ToolSearchProvider();
    private final long commandTimeoutSeconds;
    private final long toolBatchTimeoutSeconds;
    private CommandExecutionService commandExecutionService =
            new DefaultCommandExecutionService();
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
    private final ThreadLocal<ToolAccessScope> toolAccessScope = new ThreadLocal<>();
    private final ResourceLeaseManager resourceLeaseManager = new ResourceLeaseManager();
    /**
     * 过期写入屏障：租约只在步骤执行期内防并发写，跨步骤的 read-modify-write 版本过期由它兜。
     * 只对非空步骤 id 生效，单 Agent 路径不启用。
     */
    private final com.devcli.workspace.StaleWriteBarrier staleWriteBarrier =
            new com.devcli.workspace.StaleWriteBarrier();
    private final ResourceLeaseMaintenance resourceLeaseMaintenance;
    private final ResourceLeaseMaintenance.Registration resourceLeaseMaintenanceRegistration;
    private final ThreadLocal<String> resourceLeaseStep = new ThreadLocal<>();
    private final ToolExecutionPipeline executionPipeline = new ToolExecutionPipeline(this::executeResolvedTool);
    private final ToolResultCache toolResultCache = new ToolResultCache();
    private java.util.function.BiConsumer<String, String[]> writeFileObserver = (p, ba) -> {};
    /** 按 step 归集 write_file 实际写过的文件（key 为 resourceLeaseStep 的 stepId），供 checkpoint 记录产物。 */
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.Set<String>> stepModifiedFiles =
            new java.util.concurrent.ConcurrentHashMap<>();
    private LspManager lspManager = new LspManager(projectPath);
    private SnapshotService snapshotService = SnapshotService.forProject(Path.of(projectPath));
    private boolean customSnapshotService;
    private boolean closed;

    public ToolRegistry() {
        this(DEFAULT_COMMAND_TIMEOUT_SECONDS, DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS,
                new ResourceLeaseMaintenance());
    }

    protected ToolRegistry(ResourceLeaseMaintenance maintenance) {
        this(DEFAULT_COMMAND_TIMEOUT_SECONDS, DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS, maintenance);
    }

    ToolRegistry(long commandTimeoutSeconds) {
        this(commandTimeoutSeconds,
                Math.max(commandTimeoutSeconds + 5, DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS),
                new ResourceLeaseMaintenance());
    }

    ToolRegistry(long commandTimeoutSeconds, long toolBatchTimeoutSeconds) {
        this(commandTimeoutSeconds, toolBatchTimeoutSeconds, new ResourceLeaseMaintenance());
    }

    ToolRegistry(long commandTimeoutSeconds, long toolBatchTimeoutSeconds,
                 ResourceLeaseMaintenance maintenance) {
        this.commandTimeoutSeconds = commandTimeoutSeconds;
        this.toolBatchTimeoutSeconds = toolBatchTimeoutSeconds;
        this.resourceLeaseMaintenance = Objects.requireNonNull(maintenance, "maintenance");
        this.resourceLeaseMaintenanceRegistration = maintenance.attach(resourceLeaseManager);
        configureExecutionPipeline();
        // 租约抢占（空闲超时回收他人租约）接入审计链：被回收的慢步骤可事后排查
        resourceLeaseManager.setPreemptionListener((path, evictedStepId, newStepId, heldMs) ->
                auditLog.record(AuditLog.AuditEntry.error(
                        "resource_lease_preempt",
                        "path=" + path + ", evicted=" + evictedStepId + ", next=" + newStepId,
                        "租约空闲超时被回收，空闲 " + heldMs + "ms",
                        heldMs)));
        new FileToolProvider().register(this);
        new GrepToolProvider().register(this);
        new ShellToolProvider().register(this);
        new ProjectToolProvider().register(this);
        ragToolProvider.register(this);
        new WebToolProvider().register(this);
        new BrowserToolProvider().register(this);
        new MemoryToolProvider().register(this);
        new SkillToolProvider().register(this);
        toolSearchProvider.register(this);
        new SnapshotToolProvider().register(this);
    }

    /**
     * 设置代码检索的项目路径
     */
    public void setProjectPath(String projectPath) {
        ragToolProvider.closeCachedCodeRetriever();
        toolResultCache.clear();
        this.projectPath = projectPath;
        this.pathGuard = new PathGuard(projectPath);
        this.lspManager.setProjectPath(projectPath);
        if (!customSnapshotService) {
            this.snapshotService.close();
            this.snapshotService = SnapshotService.forProject(Path.of(projectPath));
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        try {
            ragToolProvider.close();
        } catch (RuntimeException e) {
            failure = e;
        }
        try {
            snapshotService.close();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        try {
            resourceLeaseMaintenanceRegistration.close();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * 获取代码检索的项目路径
     */
    public String getProjectPath() {
        return projectPath;
    }

    /** 返回工具声明的副作用等级；未知工具按外部副作用保守处理。 */
    public ToolEffect toolEffect(String name) {
        Tool tool = tools.get(name);
        return tool == null ? ToolEffect.EXTERNAL_MUTATION : tool.effect();
    }

    /**
     * 为隔离工作区创建项目级工具注册表。内置 Provider 重新绑定到新根目录，
     * MCP 描述与调用器、策略配置和记忆处理器沿用父注册表。
     */
    public ToolRegistry forkForProject(Path projectRoot) {
        Path root = Objects.requireNonNull(projectRoot, "projectRoot")
                .toAbsolutePath().normalize();
        ToolRegistry fork = createProjectForkRegistry(resourceLeaseMaintenance);
        fork.setProjectPath(root.toString());
        fork.contextProfile = contextProfile;
        fork.browserGuard = browserGuard;
        fork.browserConnector = browserConnector;
        fork.memorySaver = memorySaver;
        fork.memorySaveHandler = memorySaveHandler;
        fork.memoryListHandler = memoryListHandler;
        fork.skillRegistry = skillRegistry;
        fork.skillContextBuffer = skillContextBuffer == null ? null : skillContextBuffer.copy();
        fork.commandExecutionService = commandExecutionService;
        fork.mcpTrustPolicies.putAll(mcpTrustPolicies);
        mcpTools.values().forEach(registered ->
                fork.registerMcpToolOutput(registered.descriptor(), registered.invoker()));
        fork.mcpServerLifecycleVersions.putAll(mcpServerLifecycleVersions);
        fork.activatedMcpToolDefinitions.addAll(activatedMcpToolDefinitions);
        return fork;
    }

    protected ToolRegistry createProjectForkRegistry(ResourceLeaseMaintenance maintenance) {
        return new ToolRegistry(commandTimeoutSeconds, toolBatchTimeoutSeconds, maintenance);
    }

    ResourceLeaseMaintenance resourceLeaseMaintenance() {
        return resourceLeaseMaintenance;
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

    public void setCommandExecutionService(CommandExecutionService commandExecutionService) {
        this.commandExecutionService = Objects.requireNonNull(
                commandExecutionService, "commandExecutionService");
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

    @Override
    public SkillContextBuffer activeSkillContextBuffer() {
        SkillContextBuffer override = skillContextBufferOverride.get();
        return override == null ? skillContextBuffer : override;
    }

    public <T> T runWithToolAccess(ToolAccessScope scope, java.util.function.Supplier<T> action) {
        if (action == null) {
            return null;
        }
        ToolAccessScope previous = toolAccessScope.get();
        toolAccessScope.set(scope == null ? ToolAccessScope.FULL : scope);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                toolAccessScope.remove();
            } else {
                toolAccessScope.set(previous);
            }
        }
    }

    public ToolAccessScope currentToolAccessScope() {
        ToolAccessScope current = toolAccessScope.get();
        return current == null ? ToolAccessScope.FULL : current;
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
    public void recordFileRead(Path safePath, String content, String stepId) {
        staleWriteBarrier.recordRead(stepId, safePath, content);
    }

    /**
     * 步骤真正结束时清理其读取观察，避免长会话无界增长。
     *
     * <p>不能并入 {@link #releaseResourceLeases(String)}：租约释放发生在每次 Worker 调用结束
     * （一个步骤有初次 / 修复 / 重试多次调用），并入会让被拦后的重试失去屏障保护。
     */
    public void forgetStaleWriteScope(String stepId) {
        staleWriteBarrier.forgetScope(stepId);
    }

    @Override
    public String staleWriteReason(String stepId, Path safePath, String currentContent) {
        return staleWriteBarrier.staleReason(stepId, safePath, currentContent);
    }

    @Override
    public void recordFileWrite(String displayPath, Path safePath, String before, String content, String stepId) {
        if (stepId != null && !stepId.isBlank()) {
            stepModifiedFiles
                    .computeIfAbsent(stepId, k -> java.util.concurrent.ConcurrentHashMap.newKeySet())
                    .add(safePath.toString());
        }
        staleWriteBarrier.recordWrite(stepId, safePath, content);
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
    public String executeCommand(String command) {
        return executeCommandOutput(command).text();
    }
    @Override
    public ToolOutput executeCommandOutput(String command) {
        boolean sandboxRequired = currentToolAccessScope() == ToolAccessScope.ISOLATED_PROJECT;
        return commandExecutionService.execute(new CommandExecutionService.Request(
                command, Path.of(projectPath), commandTimeoutSeconds, sandboxRequired)).toToolOutput();
    }
    @Override
    public java.util.function.Consumer<String> memorySaver() { return memorySaver; }
    @Override
    public MemorySaver memorySaveHandler() { return memorySaveHandler; }
    @Override
    public MemoryListHandler memoryListHandler() { return memoryListHandler; }
    @Override
    public BrowserConnector browserConnector() { return browserConnector; }
    @Override
    public SkillRegistry skillRegistry() { return skillRegistry; }
    @Override
    public SnapshotService snapshotService() { return snapshotService; }
    @Override
    public List<Tool> searchableTools() {
        ToolAccessScope scope = currentToolAccessScope();
        return tools.values().stream()
                .filter(tool -> scope.permits(tool.effect()))
                .toList();
    }
    @Override
    public boolean isMcpTool(String toolName) { return mcpTools.containsKey(toolName); }
    @Override
    public boolean activateToolDefinition(String toolName) { return activateMcpToolDefinition(toolName); }
    @Override
    public long toolCatalogVersion() {
        return toolCatalogVersion.get() * ToolAccessScope.values().length
                + currentToolAccessScope().ordinal();
    }

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
        toolResultCache.clear();
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
        ToolAccessScope scope = currentToolAccessScope();
        return tools.values().stream()
                .filter(tool -> isToolDefinitionVisible(tool.name()))
                .filter(tool -> scope.permits(tool.effect()))
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

    public void setMcpToolTrustPolicy(String serverName, McpToolTrustPolicy policy) {
        String normalized = normalizeMcpServerName(serverName);
        mcpTrustPolicies.put(normalized,
                policy == null ? McpToolTrustPolicy.untrusted() : policy);
        invalidateToolSearchIndex();
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
        McpToolTrustPolicy policy = mcpTrustPolicies.getOrDefault(
                normalizeMcpServerName(descriptor.serverName()),
                McpToolTrustPolicy.untrusted());
        if (policy.isDenied(descriptor.name())) {
            unregisterMcpTool(toolName);
            return;
        }
        McpRegisteredTool registered = new McpRegisteredTool(descriptor, invoker);
        mcpTools.put(toolName, registered);
        tools.put(toolName, new Tool(
                toolName,
                mcpDescription(descriptor),
                descriptor.inputSchema(),
                args -> "MCP 工具不应通过 Map<String,String> 入口执行",
                ToolEffect.fromMcp(descriptor, policy)
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
        return executionPipeline.execute(name, argumentsJson, null).text();
    }

    public ToolOutput executeToolOutput(String name, String argumentsJson) {
        if (isLegacyExecuteToolOverride()) {
            return ToolOutput.text(executeTool(name, argumentsJson));
        }
        return executionPipeline.execute(name, argumentsJson, null);
    }

    /** 兼容扩展类的原有受保护入口，实际执行统一进入中间件管线。 */
    protected ToolOutput doExecuteTool(String name, String argumentsJson) {
        return executionPipeline.execute(name, argumentsJson, null);
    }

    protected final void registerExecutionMiddleware(ToolExecutionPipeline.Stage stage,
                                                     ToolExecutionPipeline.Middleware middleware) {
        executionPipeline.register(stage, middleware);
    }

    private void configureExecutionPipeline() {
        executionPipeline.register(ToolExecutionPipeline.Stage.CANCELLATION, (context, chain) ->
                CancellationContext.isCancelled()
                        ? ToolOutput.cancelled("用户取消了此次工具调用")
                        : chain.proceed(context));
        executionPipeline.register(ToolExecutionPipeline.Stage.EXISTENCE, (context, chain) ->
                tools.containsKey(context.name())
                        ? chain.proceed(context)
                        : ToolOutput.error(ToolErrorCode.UNKNOWN_TOOL,
                        unknownToolGuidance(context.name()), true));
        executionPipeline.register(ToolExecutionPipeline.Stage.CAPABILITY, (context, chain) -> {
            Tool tool = tools.get(context.name());
            ToolAccessScope scope = currentToolAccessScope();
            if (tool != null && !scope.permits(tool.effect())) {
                return ToolOutput.rejected(ToolErrorCode.CAPABILITY_DENIED,
                        "工具能力被当前执行范围拒绝: " + context.name()
                                + " (scope=" + scope + ", effect=" + tool.effect() + ")");
            }
            return chain.proceed(context);
        });
        executionPipeline.register(ToolExecutionPipeline.Stage.SKILL_PERMISSION, (context, chain) -> {
            ToolOutput error = validateSkillToolAllowed(context.name());
            return error == null ? chain.proceed(context) : error;
        });
        executionPipeline.register(ToolExecutionPipeline.Stage.ARGUMENT_VALIDATION, (context, chain) -> {
            ToolOutput error = validateToolArguments(context.name(), context.argumentsJson());
            if (error != null) {
                return error;
            }
            context.putAttribute(PIPELINE_PARSED_ARGUMENTS, parseValidatedArguments(context.argumentsJson()));
            return chain.proceed(context);
        });
        executionPipeline.register(ToolExecutionPipeline.Stage.AUDIT, this::executeWithAudit);
        executionPipeline.register(ToolExecutionPipeline.Stage.POLICY, (context, chain) -> {
            if (mcpTools.containsKey(context.name())) {
                BrowserCheckResult browserCheck = checkBrowserTool(
                        context.name(), context.argumentsJson(), false);
                context.putAttribute(PIPELINE_BROWSER_AUDIT, browserCheck.metadata());
                if (browserCheck.blocked()) {
                    throw new PolicyException(browserCheck.reason());
                }
            }
            return chain.proceed(context);
        });
        executionPipeline.register(ToolExecutionPipeline.Stage.RESULT_CACHE, (context, chain) -> {
            Tool tool = tools.get(context.name());
            if (tool == null) return chain.proceed(context);
            if (tool.effect() != ToolEffect.READ_ONLY) {
                toolResultCache.clear();
                return chain.proceed(context);
            }
            String fingerprint = currentToolAccessScope().name() + "|"
                    + ToolInvocationFingerprint.of(context.name(), context.argumentsJson());
            ToolOutput cached = toolResultCache.get(fingerprint);
            if (cached != null) return cached;
            ToolOutput output = chain.proceed(context);
            toolResultCache.put(fingerprint, output);
            return output;
        });
        executionPipeline.register(ToolExecutionPipeline.Stage.RESULT_GOVERNANCE, (context, chain) ->
                governToolOutput(context.name(), context.invocationId(), chain.proceed(context)));
    }

    private ToolOutput governToolOutput(String name, String invocationId, ToolOutput output) {
        ToolOutput normalized = output == null ? ToolOutput.success("") : output;
        if (invocationId == null || invocationId.isBlank()) {
            return normalized;
        }
        String managedText = ToolResultSizeManager.process(
                name, invocationId, projectPath, normalized.hasImageParts(), normalized.text());
        return new ToolOutput(normalized.status(), normalized.errorCode(), normalized.retryable(),
                managedText, normalized.imageParts(), normalized.modifiedResources(), normalized.sideChannels());
    }

    private ToolOutput executeWithAudit(ToolExecutionPipeline.Context context,
                                        ToolExecutionPipeline.Chain chain) {
        boolean audit = shouldAudit(context.name());
        long start = System.nanoTime();
        try {
            ToolOutput output = chain.proceed(context);
            if (audit) {
                auditLog.record(AuditLog.AuditEntry.allow(
                        context.name(), context.argumentsJson(), elapsedMillis(start),
                        context.attribute(PIPELINE_BROWSER_AUDIT, BrowserAuditMetadata.class)));
            }
            return output;
        } catch (ResourceLeaseException e) {
            if (audit) {
                auditLog.record(AuditLog.AuditEntry.denyByPolicy(
                        context.name(), context.argumentsJson(), e.getMessage(), elapsedMillis(start),
                        context.attribute(PIPELINE_BROWSER_AUDIT, BrowserAuditMetadata.class)));
            }
            return ToolOutput.rejected(ToolErrorCode.RESOURCE_CONFLICT,
                    "策略拒绝: " + e.getMessage(), true);
        } catch (PolicyException e) {
            if (audit) {
                auditLog.record(AuditLog.AuditEntry.denyByPolicy(
                        context.name(), context.argumentsJson(), e.getMessage(), elapsedMillis(start),
                        context.attribute(PIPELINE_BROWSER_AUDIT, BrowserAuditMetadata.class)));
            }
            return ToolOutput.rejected(ToolErrorCode.POLICY_DENIED,
                    "策略拒绝: " + e.getMessage());
        } catch (Exception e) {
            if (audit) {
                auditLog.record(AuditLog.AuditEntry.error(
                        context.name(), context.argumentsJson(), e.getMessage(), elapsedMillis(start),
                        context.attribute(PIPELINE_BROWSER_AUDIT, BrowserAuditMetadata.class)));
            }
            return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                    "工具执行失败: " + e.getMessage(), true);
        }
    }

    private ToolOutput executeResolvedTool(ToolExecutionPipeline.Context context) {
        McpRegisteredTool mcpTool = mcpTools.get(context.name());
        if (mcpTool != null) {
            ToolOutput output = mcpTool.invoker().apply(context.argumentsJson());
            if (browserGuard != null) {
                browserGuard.applyAfterExecution(context.name(), context.argumentsJson(), output == null ? "" : output.text());
            }
            return output;
        }

        Tool tool = tools.get(context.name());
        JsonNode parsedArgs = context.attribute(PIPELINE_PARSED_ARGUMENTS, JsonNode.class);
        if (parsedArgs == null) {
            parsedArgs = parseValidatedArguments(context.argumentsJson());
        }
        Map<String, String> argMap = new HashMap<>();
        parsedArgs.fields().forEachRemaining(entry ->
                argMap.put(entry.getKey(), entry.getValue().asText()));
        return tool.executor().executeOutput(argMap);
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
        return ToolOutput.rejected(ToolErrorCode.SKILL_PERMISSION_DENIED,
                "Skill 工具权限拒绝: 当前已加载 Skill 只允许使用 "
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

    private JsonNode parseValidatedArguments(String argumentsJson) {
        try {
            return parseArguments(argumentsJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("工具参数不是合法 JSON: " + e.getOriginalMessage(), e);
        }
    }

    private JsonNode parseArguments(String argumentsJson) throws JsonProcessingException {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return mapper.createObjectNode();
        }
        return mapper.readTree(argumentsJson);
    }

    private ToolOutput validationFailed(String message) {
        return ToolOutput.rejected(ToolErrorCode.INVALID_ARGUMENTS,
                "工具参数校验失败: "
                        + (message == null || message.isBlank() ? "参数不符合工具 schema" : message)
                        + "。请根据工具 JSON Schema 修正参数后重试。",
                true);
    }

    private ToolOutput executeToolOutput(ToolInvocation invocation) {
        if (isLegacyExecuteToolOverride() || isExecuteToolOutputOverride()) {
            return governToolOutput(invocation.name(), invocation.id(),
                    executeToolOutput(invocation.name(), invocation.argumentsJson()));
        }
        return executionPipeline.execute(
                invocation.name(), invocation.argumentsJson(), invocation.id());
    }

    private boolean isLegacyExecuteToolOverride() {
        return declaringClassOf("executeTool") != ToolRegistry.class;
    }

    private boolean isExecuteToolOutputOverride() {
        return declaringClassOf("executeToolOutput") != ToolRegistry.class;
    }

    private Class<?> declaringClassOf(String methodName) {
        try {
            return getClass()
                    .getMethod(methodName, String.class, String.class)
                    .getDeclaringClass();
        } catch (NoSuchMethodException e) {
            return ToolRegistry.class;
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
        int parallelism = Math.min(invocations.size(), MAX_PARALLEL_TOOLS);
        SkillContextBuffer activeSkillBuffer = activeSkillContextBuffer();
        ToolAccessScope activeAccessScope = currentToolAccessScope();
        String activeResourceLeaseStep = resourceLeaseStep.get();
        CancellationToken parentToken = CancellationContext.current();
        if (parentToken == null) {
            parentToken = new CancellationToken();
        }
        CancellationToken executionParent = parentToken;
        ExecutorService executor = Executors.newFixedThreadPool(parallelism, r -> {
            Thread thread = new Thread(r, "devcli-tool-executor");
            thread.setDaemon(true);
            return thread;
        });
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
                Math.min(Math.max(1, invocations.size()), MAX_PARALLEL_TOOLS), r -> {
                    Thread thread = new Thread(r, "devcli-tool-cancellation");
                    thread.setDaemon(true);
                    return thread;
                });
        long batchDeadlineNanos = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(toolBatchTimeoutSeconds);
        List<ToolInvocationControl> controls = invocations.stream()
                .map(invocation -> new ToolInvocationControl(
                        invocation,
                        executionParent.child(),
                        toolCancellationCapability(invocation.name()),
                        toolTimeoutSeconds(invocation.name())))
                .toList();

        try {
            for (ToolInvocationControl control : controls) {
                Future<?> future = executor.submit(() -> runToolInvocation(
                        control, batchDeadlineNanos, scheduler, activeAccessScope,
                        activeResourceLeaseStep, activeSkillBuffer));
                control.attachFuture(future);
            }

            List<ToolExecutionResult> results = new ArrayList<>(controls.size());
            boolean interrupted = false;
            for (ToolInvocationControl control : controls) {
                try {
                    results.add(control.completion().get());
                } catch (InterruptedException e) {
                    interrupted = true;
                    control.cancel(CancellationReason.CALLER_INTERRUPTED, scheduler);
                    break;
                } catch (ExecutionException e) {
                    results.add(ToolExecutionResult.failed(control.invocation(), causeMessage(e)));
                }
            }
            if (interrupted) {
                Thread.interrupted();
                controls.forEach(control -> control.cancel(
                        CancellationReason.CALLER_INTERRUPTED, scheduler));
                results = new ArrayList<>(controls.size());
                for (ToolInvocationControl control : controls) {
                    results.add(control.awaitedResult());
                }
                Thread.currentThread().interrupt();
            }
            return results;
        } finally {
            controls.forEach(ToolInvocationControl::close);
            scheduler.shutdownNow();
            executor.shutdownNow();
        }
    }

    private void runToolInvocation(ToolInvocationControl control, long batchDeadlineNanos,
                                   ScheduledExecutorService scheduler,
                                   ToolAccessScope activeAccessScope,
                                   String activeResourceLeaseStep,
                                   SkillContextBuffer activeSkillBuffer) {
        if (!control.tryStart()) {
            return;
        }
        long startedAt = System.nanoTime();
        control.bindWorker(Thread.currentThread());
        long toolDeadlineNanos = Math.min(batchDeadlineNanos,
                startedAt + TimeUnit.SECONDS.toNanos(control.timeoutSeconds()));
        long remainingNanos = toolDeadlineNanos - startedAt;
        try (CancellationContext.TokenBinding ignored = CancellationContext.bindToken(control.token())) {
            if (remainingNanos <= 0) {
                control.cancel(CancellationReason.BATCH_TIMEOUT, scheduler);
                return;
            }
            CancellationReason timeoutReason = batchDeadlineNanos <= startedAt
                    + TimeUnit.SECONDS.toNanos(control.timeoutSeconds())
                    ? CancellationReason.BATCH_TIMEOUT
                    : CancellationReason.TOOL_TIMEOUT;
            control.scheduleTimeout(scheduler, remainingNanos, timeoutReason);
            if (control.token().isCancelled()) {
                control.complete(control.cancelResult(elapsedMillis(startedAt)));
                return;
            }
            java.util.concurrent.atomic.AtomicReference<ToolOutput> output =
                    new java.util.concurrent.atomic.AtomicReference<>(ToolOutput.text(""));
            runWithToolAccess(activeAccessScope, () ->
                    runWithResourceLease(activeResourceLeaseStep, () -> {
                        runWithSkillContextBuffer(activeSkillBuffer,
                                () -> output.set(executeToolOutput(control.invocation())));
                        return null;
                    }));
            control.complete(control.resultFromOutput(output.get(), elapsedMillis(startedAt)));
        } catch (CancellationException e) {
            control.complete(control.cancelResult(elapsedMillis(startedAt)));
        } catch (Exception e) {
            control.complete(control.token().isCancelled()
                    ? control.cancelResult(elapsedMillis(startedAt))
                    : ToolExecutionResult.failed(control.invocation(), e.getMessage()));
        } finally {
            control.finish();
            Thread.interrupted();
        }
    }

    private enum ToolControlState {
        NEW,
        RUNNING,
        FINISHED
    }

    private static final class ToolInvocationControl {
        private final ToolInvocation invocation;
        private final CancellationToken token;
        private final ToolCancellationCapability capability;
        private final long timeoutSeconds;
        private final CompletableFuture<ToolExecutionResult> completion = new CompletableFuture<>();
        private final AtomicReference<ToolControlState> state =
                new AtomicReference<>(ToolControlState.NEW);
        private final AtomicReference<Thread> worker = new AtomicReference<>();
        private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
        private final CancellationToken.Registration tokenRegistration;
        private volatile Future<?> future;
        private volatile ScheduledFuture<?> timeoutFuture;
        private volatile ScheduledExecutorService scheduler;
        private volatile CancellationReason cancellationReason = CancellationReason.NONE;

        private ToolInvocationControl(ToolInvocation invocation, CancellationToken token,
                                       ToolCancellationCapability capability,
                                       long timeoutSeconds) {
            this.invocation = invocation;
            this.token = token;
            this.capability = capability == null
                    ? ToolCancellationCapability.INTERRUPT_ONLY : capability;
            this.timeoutSeconds = Math.max(1, timeoutSeconds);
            this.tokenRegistration = token.onCancel(() -> onTokenCancelled(token.reason()));
        }

        private ToolInvocation invocation() {
            return invocation;
        }

        private CancellationToken token() {
            return token;
        }

        private long timeoutSeconds() {
            return timeoutSeconds;
        }

        private CompletableFuture<ToolExecutionResult> completion() {
            return completion;
        }

        private boolean tryStart() {
            return state.compareAndSet(ToolControlState.NEW, ToolControlState.RUNNING);
        }

        private void bindWorker(Thread thread) {
            worker.set(thread);
            if (token.isCancelled()) {
                onTokenCancelled(token.reason());
            }
        }

        private void attachFuture(Future<?> future) {
            this.future = future;
            if (state.get() == ToolControlState.FINISHED) {
                future.cancel(false);
            }
        }

        private void scheduleTimeout(ScheduledExecutorService scheduler, long delayNanos,
                                     CancellationReason reason) {
            this.scheduler = scheduler;
            timeoutFuture = scheduler.schedule(() -> cancel(reason, scheduler),
                    Math.max(0, delayNanos), TimeUnit.NANOSECONDS);
        }

        private void cancel(CancellationReason reason, ScheduledExecutorService scheduler) {
            this.scheduler = scheduler;
            cancellationReason = reason == null || reason == CancellationReason.NONE
                    ? CancellationReason.USER_REQUEST : reason;
            token.cancel(cancellationReason);
            onTokenCancelled(cancellationReason);
        }

        private void onTokenCancelled(CancellationReason reason) {
            cancellationReason = reason == null || reason == CancellationReason.NONE
                    ? CancellationReason.USER_REQUEST : reason;
            if (!cancelRequested.compareAndSet(false, true)) {
                return;
            }
            if (state.compareAndSet(ToolControlState.NEW, ToolControlState.FINISHED)) {
                Future<?> queued = future;
                if (queued != null) {
                    queued.cancel(false);
                }
                completion.complete(cancelResult(0));
                return;
            }
            if (state.get() != ToolControlState.RUNNING) {
                return;
            }
            if (capability == ToolCancellationCapability.INTERRUPT_ONLY) {
                interruptWorker();
            } else {
                scheduleEscalation(COOPERATIVE_CANCEL_GRACE_MILLIS);
            }
            scheduleTerminationConfirmation();
        }

        private void scheduleEscalation(long delayMillis) {
            ScheduledExecutorService activeScheduler = scheduler;
            if (activeScheduler == null) {
                interruptWorker();
                return;
            }
            activeScheduler.schedule(this::interruptWorker, delayMillis, TimeUnit.MILLISECONDS);
        }

        private void scheduleTerminationConfirmation() {
            ScheduledExecutorService activeScheduler = scheduler;
            if (activeScheduler == null) {
                return;
            }
            activeScheduler.schedule(() -> {
                if (state.get() == ToolControlState.RUNNING && !completion.isDone()) {
                    completion.complete(ToolExecutionResult.terminationUnconfirmed(
                            invocation, cancellationReason));
                }
            }, TERMINATION_CONFIRM_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }

        private void interruptWorker() {
            Thread activeWorker = worker.get();
            if (activeWorker != null && activeWorker != Thread.currentThread()) {
                activeWorker.interrupt();
            }
        }

        private ToolExecutionResult resultFromOutput(ToolOutput output, long elapsedMillis) {
            if (token.isCancelled()) {
                return cancelResult(elapsedMillis);
            }
            return ToolExecutionResult.completed(invocation, output, elapsedMillis);
        }

        private ToolExecutionResult cancelResult(long elapsedMillis) {
            CancellationReason reason = effectiveCancellationReason();
            return switch (reason) {
                case TOOL_TIMEOUT, BATCH_TIMEOUT ->
                        ToolExecutionResult.timedOut(invocation, timeoutSeconds, reason,
                                elapsedMillis);
                default -> ToolExecutionResult.cancelled(invocation,
                        reason == CancellationReason.PARENT_CANCELLED
                                ? "上游任务取消了此次工具调用"
                                : "用户取消了此次工具调用",
                        elapsedMillis);
            };
        }

        private CancellationReason effectiveCancellationReason() {
            if (cancellationReason != CancellationReason.NONE) {
                return cancellationReason;
            }
            CancellationReason tokenReason = token.reason();
            return tokenReason == CancellationReason.NONE
                    ? CancellationReason.THREAD_INTERRUPTED : tokenReason;
        }

        private void complete(ToolExecutionResult result) {
            completion.complete(result);
        }

        private void finish() {
            if (state.compareAndSet(ToolControlState.RUNNING, ToolControlState.FINISHED)) {
                if (timeoutFuture != null) {
                    timeoutFuture.cancel(false);
                }
                worker.set(null);
                if (!completion.isDone()) {
                    completion.complete(cancelResult(0));
                }
                tokenRegistration.close();
                token.close();
            }
        }

        private ToolExecutionResult awaitedResult() {
            try {
                return completion.get(TERMINATION_CONFIRM_TIMEOUT_MILLIS + 250,
                        TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolExecutionResult.terminationUnconfirmed(
                        invocation, effectiveCancellationReason());
            } catch (ExecutionException | TimeoutException e) {
                return ToolExecutionResult.terminationUnconfirmed(
                        invocation, effectiveCancellationReason());
            }
        }

        private void close() {
            if (timeoutFuture != null) {
                timeoutFuture.cancel(false);
            }
            tokenRegistration.close();
            token.close();
        }
    }

    private long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    private static String causeMessage(ExecutionException e) {
        Throwable cause = e.getCause();
        return cause == null || cause.getMessage() == null ? "未知错误" : cause.getMessage();
    }

    /** 单个工具的执行上限：声明过独立超时用声明值，否则继承批次超时。 */
    private long toolTimeoutSeconds(String toolName) {
        Tool tool = toolName == null ? null : tools.get(toolName);
        if (tool != null && tool.hasOwnTimeout()) {
            return tool.timeoutSeconds();
        }
        return toolBatchTimeoutSeconds;
    }

    public ToolCancellationCapability toolCancellationCapability(String toolName) {
        Tool tool = toolName == null ? null : tools.get(toolName);
        return tool == null
                ? ToolCancellationCapability.INTERRUPT_ONLY
                : tool.cancellationCapability();
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

    public enum ToolEffect {
        READ_ONLY,
        LOCAL_CONTEXT,
        PROJECT_MUTATION,
        HOST_PROCESS,
        EXTERNAL_MUTATION;

        static ToolEffect fromMcp(McpToolDescriptor descriptor,
                                  McpToolTrustPolicy trustPolicy) {
            McpToolTrustPolicy policy = trustPolicy == null
                    ? McpToolTrustPolicy.untrusted()
                    : trustPolicy;
            return policy.isReadOnly(descriptor) ? READ_ONLY : EXTERNAL_MUTATION;
        }

        static ToolEffect builtIn(String name) {
            return switch (name == null ? "" : name) {
                case "read_file", "list_dir", "search_code", "grep_code",
                        "web_search", "web_fetch", "list_memory", "search_tools",
                        "browser_status" -> READ_ONLY;
                case "load_skill" -> LOCAL_CONTEXT;
                case "write_file", "create_project", "revert_turn" -> PROJECT_MUTATION;
                case "browser_connect", "browser_disconnect" -> EXTERNAL_MUTATION;
                case "execute_command" -> HOST_PROCESS;
                case "save_memory" -> EXTERNAL_MUTATION;
                default -> EXTERNAL_MUTATION;
            };
        }
    }

    public enum ToolAccessScope {
        FULL {
            @Override
            boolean permits(ToolEffect effect) {
                return true;
            }
        },
        READ_ONLY {
            @Override
            boolean permits(ToolEffect effect) {
                return effect == ToolEffect.READ_ONLY || effect == ToolEffect.LOCAL_CONTEXT;
            }
        },
        ISOLATED_PROJECT {
            @Override
            boolean permits(ToolEffect effect) {
                return effect != ToolEffect.EXTERNAL_MUTATION;
            }
        };

        abstract boolean permits(ToolEffect effect);
    }

    public enum ToolCancellationCapability {
        COOPERATIVE,
        INTERRUPT_ONLY
    }

    public record Tool(String name, String description, JsonNode parameters,
                       ToolExecutor executor, ToolEffect effect, long timeoutSeconds,
                       ToolCancellationCapability cancellationCapability) {
        public Tool(String name, String description, JsonNode parameters, ToolExecutor executor) {
            this(name, description, parameters, executor, ToolEffect.builtIn(name), -1,
                    ToolCancellationCapability.INTERRUPT_ONLY);
        }

        public Tool(String name, String description, JsonNode parameters, ToolExecutor executor,
                    ToolEffect effect) {
            this(name, description, parameters, executor, effect, -1,
                    ToolCancellationCapability.INTERRUPT_ONLY);
        }

        public Tool(String name, String description, JsonNode parameters, ToolExecutor executor,
                    ToolEffect effect, long timeoutSeconds) {
            this(name, description, parameters, executor, effect, timeoutSeconds,
                    ToolCancellationCapability.INTERRUPT_ONLY);
        }

        public static Tool structured(String name, String description, JsonNode parameters,
                                      StructuredToolExecutor executor) {
            return new Tool(name, description, parameters, executor, ToolEffect.builtIn(name), -1,
                    ToolCancellationCapability.INTERRUPT_ONLY);
        }

        /**
         * 声明独立超时的结构化工具。timeoutSeconds 为单个工具的执行上限（秒），
         * 批量执行时按该 deadline 强制取消；未声明（-1）继承批次超时。
         */
        public static Tool structured(String name, String description, JsonNode parameters,
                                      StructuredToolExecutor executor, long timeoutSeconds) {
            return new Tool(name, description, parameters, executor, ToolEffect.builtIn(name),
                    timeoutSeconds, ToolCancellationCapability.INTERRUPT_ONLY);
        }

        public Tool {
            effect = effect == null ? ToolEffect.EXTERNAL_MUTATION : effect;
            timeoutSeconds = timeoutSeconds <= 0 ? -1 : timeoutSeconds;
            cancellationCapability = cancellationCapability == null
                    ? ToolCancellationCapability.INTERRUPT_ONLY
                    : cancellationCapability;
        }

        /** 是否声明了独立超时（&gt; 0 生效，-1 继承批次超时）。 */
        public boolean hasOwnTimeout() {
            return timeoutSeconds > 0;
        }
    }

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
                                      String result, long elapsedMillis,
                                      ToolStatus status, ToolErrorCode errorCode, boolean retryable,
                                      List<com.devcli.llm.LlmClient.ContentPart> imageParts,
                                      List<ToolSideChannel> sideChannels) {
        public ToolExecutionResult {
            imageParts = imageParts == null ? List.of() : List.copyOf(imageParts);
            sideChannels = sideChannels == null ? List.of() : List.copyOf(sideChannels);
        }

        /** 兼容迁移前的工具执行结果构造方式。 */
        public ToolExecutionResult(String id, String name, String argumentsJson,
                                   String result, long elapsedMillis,
                                   ToolStatus status, ToolErrorCode errorCode, boolean retryable,
                                   List<com.devcli.llm.LlmClient.ContentPart> imageParts) {
            this(id, name, argumentsJson, result, elapsedMillis, status, errorCode,
                    retryable, imageParts, List.of());
        }

        private static ToolExecutionResult completed(ToolInvocation invocation, ToolOutput output,
                                                     long elapsedMillis) {
            String result = output == null ? "" : output.text();
            List<com.devcli.llm.LlmClient.ContentPart> images = output == null ? List.of() : output.imageParts();
            return new ToolExecutionResult(
                    invocation.id(),
                    invocation.name(),
                    invocation.argumentsJson(),
                    result,
                    elapsedMillis,
                    output == null ? ToolStatus.SUCCESS : output.status(),
                    output == null ? ToolErrorCode.NONE : output.errorCode(),
                    output != null && output.retryable(),
                    images,
                    output == null ? List.of() : output.sideChannels());
        }

        public static ToolExecutionResult failed(ToolInvocation invocation, String message) {
            return new ToolExecutionResult(
                    invocation.id(),
                    invocation.name(),
                    invocation.argumentsJson(),
                    "工具执行失败: " + message,
                    0,
                    ToolStatus.ERROR,
                    ToolErrorCode.EXECUTION_FAILED,
                    true,
                    List.of());
        }

        public static ToolExecutionResult cancelled(ToolInvocation invocation, String message) {
            return cancelled(invocation, message, 0);
        }

        public static ToolExecutionResult cancelled(ToolInvocation invocation, String message,
                                                    long elapsedMillis) {
            return new ToolExecutionResult(
                    invocation.id(),
                    invocation.name(),
                    invocation.argumentsJson(),
                    message,
                    elapsedMillis,
                    ToolStatus.CANCELLED,
                    ToolErrorCode.CANCELLED,
                    false,
                    List.of());
        }

        private static ToolExecutionResult timedOut(ToolInvocation invocation, long timeoutSeconds) {
            return timedOut(invocation, timeoutSeconds, CancellationReason.TOOL_TIMEOUT,
                    timeoutSeconds * 1000);
        }

        private static ToolExecutionResult timedOut(ToolInvocation invocation, long timeoutSeconds,
                                                    CancellationReason reason,
                                                    long elapsedMillis) {
            return new ToolExecutionResult(
                    invocation.id(),
                    invocation.name(),
                    invocation.argumentsJson(),
                    "工具执行超时（" + timeoutSeconds + "秒），原因=" + reason,
                    elapsedMillis,
                    ToolStatus.TIMEOUT,
                    ToolErrorCode.TIMEOUT,
                    true,
                    List.of()
            );
        }

        private static ToolExecutionResult terminationUnconfirmed(ToolInvocation invocation,
                                                                   CancellationReason reason) {
            return new ToolExecutionResult(
                    invocation.id(),
                    invocation.name(),
                    invocation.argumentsJson(),
                    "工具已请求取消，但在确认窗口内未能确认执行线程停止，原因=" + reason,
                    0,
                    ToolStatus.TERMINATION_UNCONFIRMED,
                    ToolErrorCode.TERMINATION_UNCONFIRMED,
                    true,
                    List.of());
        }

        public boolean timedOut() {
            return status == ToolStatus.TIMEOUT;
        }

        public boolean hasImageParts() {
            return imageParts != null && !imageParts.isEmpty();
        }
    }

    public interface ToolExecutor {
        String execute(Map<String, String> args);

        default ToolOutput executeOutput(Map<String, String> args) {
            return ToolOutput.success(execute(args));
        }
    }

    @FunctionalInterface
    public interface StructuredToolExecutor extends ToolExecutor {
        ToolOutput executeStructured(Map<String, String> args);

        @Override
        default String execute(Map<String, String> args) {
            return executeStructured(args).text();
        }

        @Override
        default ToolOutput executeOutput(Map<String, String> args) {
            return executeStructured(args);
        }
    }
}
