package com.devcli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.devcli.browser.BrowserCheckResult;
import com.devcli.browser.BrowserConnector;
import com.devcli.browser.BrowserGuard;
import com.devcli.config.ConfigResolver;
import com.devcli.context.ContextProfile;
import com.devcli.llm.LlmClient;
import com.devcli.lsp.LspDiagnosticReport;
import com.devcli.lsp.LspManager;
import com.devcli.mcp.config.McpToolTrustPolicy;
import com.devcli.mcp.protocol.McpToolDescriptor;
import com.devcli.rag.VectorStore;
import com.devcli.policy.AuditLog;
import com.devcli.policy.PathGuard;
import com.devcli.policy.TaskGrant;
import com.devcli.snapshot.SnapshotService;
import com.devcli.skill.SkillContextBuffer;
import com.devcli.skill.SkillRegistry;
import com.devcli.tool.provider.BrowserToolProvider;
import com.devcli.tool.provider.FileToolProvider;
import com.devcli.tool.provider.GrepToolProvider;
import com.devcli.tool.provider.MemoryToolProvider;
import com.devcli.tool.provider.ProjectToolProvider;
import com.devcli.tool.provider.ShellToolProvider;
import com.devcli.tool.provider.SkillToolProvider;
import com.devcli.tool.provider.SnapshotToolProvider;
import com.devcli.tool.provider.ToolParameter;
import com.devcli.tool.provider.ToolProvider;
import com.devcli.tool.provider.ToolSearchProvider;
import com.devcli.tool.provider.WebToolProvider;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.devcli.tool.ToolContract.*;

/** 工具公共入口；目录、执行装配和项目访问由各自组件维护。 */
public class ToolRegistry implements AutoCloseable, ToolProvider.ToolContext {
    final ToolProjectAccess fileAccess = new ToolProjectAccess(this);
    final ToolExecutionCore executionCore = new ToolExecutionCore(this);
    final ToolAccessContext access = new ToolAccessContext(this);
    final McpToolCatalog catalog = new McpToolCatalog(this);
    final ToolBatchExecution batch = new ToolBatchExecution(this);
    final ToolPipelineAssembly pipelineAssembly = new ToolPipelineAssembly(this);
    static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    static final ObjectMapper mapper = new ObjectMapper();
    static final int DEFAULT_COMMAND_TIMEOUT_SECONDS = 60;
    static final int DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS = 90;
    // write_file 单次写入字节数上限。LLM 想塞超大内容时通常是误生成（重复粘贴 / hallucinate 大段日志），
    // 5MB 对常规代码生成 / 文档撰写完全够用，超过即拒，避免磁盘灌满与误覆盖。
    static final int MAX_WRITE_FILE_BYTES = 5 * 1024 * 1024;
    static final String PIPELINE_PARSED_ARGUMENTS = "parsedArguments";
    static final String PIPELINE_BROWSER_AUDIT = "browserAuditMetadata";
    final ToolSearchProvider toolSearchProvider = new ToolSearchProvider();
    final AgentContextTools agentContextTools = new AgentContextTools();
    final long commandTimeoutSeconds;
    final long toolBatchTimeoutSeconds;
    /**
     * 单批工具的并发上限。默认 10，与 cc 默认值一致；副作用工具另有工作区级
     * 串行锁，调高该值只影响只读工具的并发宽度。
     */
    final int maxParallelTools = ConfigResolver.intValue(
            "devcli.tool.batch.max.parallel", "DEVCLI_TOOL_BATCH_MAX_PARALLEL", 10, 1, 64);
    com.devcli.tool.environment.ExecutionEnvironment executionEnvironment =
            new com.devcli.tool.environment.LocalExecutionEnvironment();
    String projectPath = System.getProperty("user.dir");
    DelegateRoleCatalog delegateRoles = DelegateRoleCatalog.load(Path.of(projectPath));
    PathGuard pathGuard = new PathGuard(projectPath);
    final AuditLog auditLog = new AuditLog();
    ContextProfile contextProfile = ContextProfile.from(null);
    BrowserGuard browserGuard;
    BrowserConnector browserConnector;
    java.util.function.Consumer<String> memorySaver;
    MemorySaver memorySaveHandler;
    MemoryListHandler memoryListHandler;
    SkillRegistry skillRegistry;
    SkillContextBuffer skillContextBuffer;

    SynchronousWorkerGate synchronousWorkerGate = new SynchronousWorkerGate();
    final ResourceLeaseManager resourceLeaseManager = new ResourceLeaseManager();
    /**
     * 过期写入屏障：租约只在步骤执行期内防并发写，跨步骤的 read-modify-write 版本过期由它兜。
     * 只对非空步骤 id 生效，单 Agent 路径不启用。
     */
    com.devcli.workspace.ContextVersionLedger contextVersionLedger =
            new com.devcli.workspace.ContextVersionLedger();
    Path contextProjectRoot = Path.of(projectPath).toAbsolutePath().normalize();
    boolean sharedContextVersionLedger;
    final ResourceLeaseMaintenance resourceLeaseMaintenance;
    final ResourceLeaseMaintenance.Registration resourceLeaseMaintenanceRegistration;
    final ToolExecutionPipeline executionPipeline = new ToolExecutionPipeline(executionCore::executeResolvedTool);
    protected static final String PIPELINE_APPROVAL_SOURCE = "approval.source";
    protected static final String PIPELINE_APPROVAL_ID = "approval.id";
    final ToolResultCache toolResultCache = new ToolResultCache();

    public static <T> T runWithToolTask(String identity, java.util.function.Supplier<T> action) {
        return ToolAccessContext.runWithToolTask(identity, action);
    }

    public static <T> T runWithResultEvents(com.devcli.event.RunEventSink sink,
                                          java.util.function.Supplier<T> action) {
        return ToolAccessContext.runWithResultEvents(sink, action);
    }
    java.util.function.BiConsumer<String, String[]> writeFileObserver = (p, ba) -> {};
    /** 按 step 归集 write_file 实际写过的文件（key 为 resourceLeaseStep 的 stepId），供 checkpoint 记录产物。 */
    final java.util.concurrent.ConcurrentHashMap<String, java.util.Set<String>> stepModifiedFiles =
            new java.util.concurrent.ConcurrentHashMap<>();
    LspManager lspManager = new LspManager(projectPath);
    SnapshotService snapshotService = SnapshotService.forProject(Path.of(projectPath));
    boolean customSnapshotService;
    boolean closed;

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
        if (commandTimeoutSeconds <= 0) {
            throw new IllegalArgumentException(
                    "commandTimeoutSeconds 必须为正整数: " + commandTimeoutSeconds);
        }
        if (toolBatchTimeoutSeconds <= 0) {
            throw new IllegalArgumentException(
                    "toolBatchTimeoutSeconds 必须为正整数: " + toolBatchTimeoutSeconds);
        }
        this.commandTimeoutSeconds = commandTimeoutSeconds;
        this.toolBatchTimeoutSeconds = toolBatchTimeoutSeconds;
        this.resourceLeaseMaintenance = Objects.requireNonNull(maintenance, "maintenance");
        this.resourceLeaseMaintenanceRegistration = maintenance.attach(resourceLeaseManager);
        pipelineAssembly.configureExecutionPipeline();
        // 租约抢占（空闲超时回收他人租约）接入审计链：被回收的慢步骤可事后排查
        resourceLeaseManager.setPreemptionListener((path, evictedStepId, newStepId, heldMs) ->
                auditLog.record(AuditLog.AuditEntry.error(
                        "resource_lease_preempt",
                        "path=" + path + ", evicted=" + evictedStepId + ", next=" + newStepId,
                        "租约空闲超时被回收，空闲 " + heldMs + "ms",
                        heldMs)));
        new FileToolProvider().register(this);
        new com.devcli.tool.provider.ImageToolProvider().register(this);
        new GrepToolProvider().register(this);
        new com.devcli.tool.provider.GlobToolProvider().register(this);
        new ShellToolProvider().register(this);
        new ProjectToolProvider().register(this);
        new WebToolProvider().register(this);
        new BrowserToolProvider().register(this);
        new MemoryToolProvider().register(this);
        new SkillToolProvider().register(this);
        toolSearchProvider.register(this);
        McpToolDispatcher.register(this);
        agentContextTools.register(this);
        new SnapshotToolProvider().register(this);
        registerTool(DelegateTaskTool.definition(this));
        registerTool(DelegateTaskTool.controlDefinition(this));
        catalog.builtInSemanticToolNames.addAll(catalog.tools.keySet());
    }

    public void setProjectPath(String projectPath) {
        fileAccess.setProjectPath(projectPath);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        try {
            snapshotService.close();
        } catch (RuntimeException e) {
            failure = e;
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
        if (!sharedContextVersionLedger) {
            contextVersionLedger.clear();
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

    public DelegateRoleCatalog delegateRoles() { return delegateRoles; }

    /** 返回工具声明的副作用等级；未知工具按外部副作用保守处理。 */
    public ToolEffect toolEffect(String name) {
        Tool tool = activeTool(name);
        return tool == null ? ToolEffect.EXTERNAL_MUTATION : tool.effect();
    }

    /** 返回工具声明的破坏性等级；未知工具按实质性外部变更保守处理。 */
    public Destructiveness toolDestructiveness(String name) {
        Tool tool = activeTool(name);
        return tool == null
                ? Destructiveness.STRUCTURAL
                : tool.destructiveness();
    }

    /** 返回工具声明的幂等性；未知工具按不可重试保守处理。 */
    public Idempotency toolIdempotency(String name) {
        Tool tool = activeTool(name);
        return tool == null
                ? Idempotency.NON_IDEMPOTENT
                : tool.idempotency();
    }

    /** 返回工具是否声明可进入短期结果缓存；未知工具不缓存。 */
    public boolean toolCacheable(String name) {
        Tool tool = activeTool(name);
        return tool != null && tool.cacheable();
    }

    public ToolPresentation toolPresentation(String name) {
        Tool tool = activeTool(name);
        return tool == null ? ToolPresentation.generic(name) : tool.presentation();
    }

    Tool activeTool(String name) {
        ToolSnapshot snapshot = access.activeToolSnapshot.get();
        if (snapshot != null) {
            ToolBinding binding = snapshot.binding(name);
            return binding == null ? null : binding.tool();
        }
        return catalog.tools.get(name);
    }

    McpRegisteredTool activeMcpTool(String name) {
        ToolSnapshot snapshot = access.activeToolSnapshot.get();
        if (snapshot != null) {
            ToolBinding binding = snapshot.binding(name);
            return binding == null ? null : binding.mcpTool();
        }
        return catalog.mcpTools.get(name);
    }

    boolean isSnapshotCurrent(ToolSnapshot snapshot) {
        return snapshot != null
                && snapshot.owner == this
                && snapshot.catalogVersion == catalog.toolCatalogVersion.get();
    }

    public ToolRegistry forkForProject(Path projectRoot) {
        return access.forkForProject(projectRoot);
    }

    protected ToolRegistry createProjectForkRegistry(ResourceLeaseMaintenance maintenance) {
        return new ToolRegistry(commandTimeoutSeconds, toolBatchTimeoutSeconds, maintenance);
    }

    /** 同步 worker 在进入模型与工作区之前取得写入权，结束或失败时交还主 Agent。 */
    public SynchronousWorkerGate.Lease tryEnterSynchronousWorker() {
        return synchronousWorkerGate.tryEnterWorker();
    }

    public void setAgentContextHandlers(Function<Map<String, String>, ToolOutput> planUpdater,
                                       Function<Map<String, String>, ToolOutput> memoryReader) {
        agentContextTools.bind(planUpdater, memoryReader);
    }

    void bindContextVersionLedger(
            com.devcli.workspace.ContextVersionLedger ledger, Path logicalProjectRoot) {
        this.contextVersionLedger = Objects.requireNonNull(ledger, "ledger");
        this.contextProjectRoot = Objects.requireNonNull(logicalProjectRoot, "logicalProjectRoot")
                .toAbsolutePath().normalize();
        this.sharedContextVersionLedger = true;
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

    @Override
    public ContextProfile contextProfile() {
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

    public void setExecutionEnvironment(com.devcli.tool.environment.ExecutionEnvironment environment) {
        this.executionEnvironment = environment;
    }

    @Override
    public com.devcli.tool.environment.ExecutionEnvironment executionEnvironment() { return executionEnvironment; }

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

    public void runWithSkillContextBuffer(SkillContextBuffer buffer, Runnable action) {
        access.runWithSkillContextBuffer(buffer, action);
    }

    @Override
    public SkillContextBuffer activeSkillContextBuffer() {
        return access.activeSkillContextBuffer();
    }

    public <T> T runWithToolAccess(ToolAccessScope scope, java.util.function.Supplier<T> action) {
        return access.runWithToolAccess(scope, action);
    }

    public ToolAccessScope currentToolAccessScope() {
        return access.currentToolAccessScope();
    }

    public <T> T runWithDelegation(DelegateTaskTool.Handler handler, java.util.function.Supplier<T> action) {
        return access.runWithDelegation(handler, action);
    }

    public void restrictForDelegation() {
        access.restrictForDelegation();
    }

    public <T> T runWithResourceLease(String stepId, java.util.function.Supplier<T> action) {
        return access.runWithResourceLease(stepId, action);
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
    public synchronized void registerTool(Tool tool) {
        catalog.registerTool(tool);
    }

    /**
     * 评测 / 受限运行入口：按名移除一个已注册工具（如闭卷 benchmark 禁用 web_search / save_memory）。
     * 只影响当前 ToolRegistry 实例，默认产品路径不调用。
     */
    public synchronized void removeTool(String toolName) {
        catalog.removeTool(toolName);
    }

    /** 为 MCP 或业务扩展工具注册客户端侧语义校验；Schema 通过后才会调用该规则。 */
    public void registerSemanticValidator(String toolName, ToolSemanticValidator.CustomRule validator) {
        catalog.registerSemanticValidator(toolName, validator);
    }

    public void removeSemanticValidator(String toolName) {
        catalog.removeSemanticValidator(toolName);
    }

    public <T> T runWithAllowedTools(Set<String> names, java.util.function.Supplier<T> action) {
        return access.runWithAllowedTools(names, action);
    }

    public <T> T runWithTaskGrant(TaskGrant grant, java.util.function.Supplier<T> action) {
        return access.runWithTaskGrant(grant, action);
    }

    public TaskGrant currentTaskGrant() {
        return access.currentTaskGrant();
    }

    /** 供审批阶段读取已通过参数校验的参数对象。 */
    protected JsonNode parsedArgumentsOf(ToolExecutionPipeline.Context context) {
        return context.attribute(PIPELINE_PARSED_ARGUMENTS, JsonNode.class);
    }

    /** 项目相对路径键；路径越界或无法解析时返回 null（调用方按人工审批处理）。 */
    public String projectRelativePathOrNull(String path) {
        return fileAccess.projectRelativePathOrNull(path);
    }

    public <T> T runWithAllowedWritePaths(List<String> globs, java.util.function.Supplier<T> action) {
        return access.runWithAllowedWritePaths(globs, action);
    }

    @Override
    public boolean isWritePathAllowed(String path) {
        return fileAccess.isWritePathAllowed(path);
    }

    /** 评测 / 受限运行入口：只保留显式允许的工具，包含当前未激活或暂时不可见的工具。 */
    public synchronized void retainTools(Set<String> retainedToolNames) {
        Set<String> allowed = retainedToolNames == null ? Set.of() : Set.copyOf(retainedToolNames);
        Set<String> retained = new HashSet<>(allowed);
        if (allowed.stream().anyMatch(catalog.mcpTools::containsKey)) {
            retained.add(McpToolDispatcher.NAME);
            retained.add("search_tools");
        }
        boolean changed = catalog.tools.keySet().removeIf(name -> !retained.contains(name));
        if (changed) {
            catalog.mcpTools.keySet().removeIf(name -> !allowed.contains(name));
            catalog.activatedMcpToolDefinitions.removeIf(name -> !allowed.contains(name));
            invalidateToolSearchIndex();
        }
    }

    @Override
    public Path resolveSafePath(String path) {
        return fileAccess.resolveSafePath(path);
    }

    /**
     * 写入类工具的路径解析：根围栏之后再过受保护路径策略。
     *
     * <p>与 HITL 开关无关，也不可被审批放行：关闭人工审批不会让凭据、私钥或 `.git` 变成可写。</p>
     */
    @Override
    public Path resolveSafeWritePath(String path) {
        return fileAccess.resolveSafeWritePath(path);
    }

    @Override
    public int maxWriteFileBytes() {
        return fileAccess.maxWriteFileBytes();
    }
    @Override
    public String currentResourceLeaseStep() {
        return fileAccess.currentResourceLeaseStep();
    }
    @Override
    public void acquireWriteLease(String stepId, Path path) {
        fileAccess.acquireWriteLease(stepId, path);
    }
    @Override
    public boolean isWriteLeaseValid(String stepId, Path path) {
        return fileAccess.isWriteLeaseValid(stepId, path);
    }

    @Override
    public void recordFileRead(Path safePath, String content, String stepId) {
        fileAccess.recordFileRead(safePath, content, stepId);
    }

    @Override
    public void recordCodeEvidence(Path safePath, String chunkType, String symbolName,
                                   String symbolVersion, String sourceContent) {
        fileAccess.recordCodeEvidence(safePath, chunkType, symbolName, symbolVersion, sourceContent);
    }

    public void forgetStaleWriteScope(String stepId) {
        fileAccess.forgetStaleWriteScope(stepId);
    }

    @Override
    public String staleWriteReason(String stepId, Path safePath, String currentContent) {
        return fileAccess.staleWriteReason(stepId, safePath, currentContent);
    }

    @Override
    public com.devcli.workspace.WriteGateResult validateWrite(
            String stepId, Path safePath, String currentContent) {
        return fileAccess.validateWrite(stepId, safePath, currentContent);
    }

    @Override
    public void recordFileWrite(String displayPath, Path safePath, String before, String content, String stepId) {
        fileAccess.recordFileWrite(displayPath, safePath, before, content, stepId);
    }

    @Override
    public String projectPath() { return projectPath; }

    public com.devcli.workspace.ContextVersionLedger contextVersionLedger() {
        return contextVersionLedger;
    }

    public Path contextProjectRoot() {
        return contextProjectRoot;
    }

    /** 项目内容成功落盘后发布索引失效；索引不可用不回滚已经完成的文件写入。 */
    public void markRagIndexDirty(Collection<String> relativePaths) {
        fileAccess.markRagIndexDirty(relativePaths);
    }

    /** 隔离工作区补丁归并到主项目后清理主 Registry 的只读结果缓存。 */
    public void invalidateToolResultCache() {
        fileAccess.invalidateToolResultCache();
    }

    public String contextResourceKey(Path path) {
        return fileAccess.contextResourceKey(path);
    }

    public java.util.Map<String, String> refreshStaleContext(String stepId) {
        return fileAccess.refreshStaleContext(stepId);
    }
    @Override
    public long commandTimeoutSeconds() { return commandTimeoutSeconds; }
    @Override
    public String executeCommand(String command) {
        return executeCommandOutput(command).text();
    }
    public ToolOutput executeCommandOutput(String command) {
        return executeCommandOutput(command, ToolExecutionContext.current(""));
    }

    public ToolOutput executeCommandOutput(String command, ToolExecutionContext executionContext) {
        return executeCommandOutput(command, commandTimeoutSeconds, executionContext);
    }

    public ToolOutput executeCommandOutput(String command, long timeoutSeconds, ToolExecutionContext executionContext) {
        String arguments = mapper.createObjectNode().put("command", command).put("timeout", timeoutSeconds).toString();
        return executionPipeline.execute("execute_command", arguments, executionContext.invocationId(), executionContext);
    }

    @Override
    public ToolOutput runApprovedCommand(String command, long timeoutSeconds, ToolExecutionContext executionContext) {
        return executionCore.executeCommandOutput(command, timeoutSeconds, executionContext);
    }

    protected final boolean commandExecutesOnHost() {
        return executionEnvironment.commands().executesOnHost(
                currentToolAccessScope() == ToolAccessScope.ISOLATED_PROJECT);
    }

    /** 所有入口共用审批阶段；基础注册表没有主机审批通道。 */
    protected ToolOutput reviewToolApproval(ToolExecutionPipeline.Context context, ToolExecutionPipeline.Chain chain) {
        if (toolEffect(context.name()) == ToolEffect.HOST_PROCESS && commandExecutesOnHost()) {
            return ToolOutput.rejected(ToolErrorCode.HITL_REJECTED, "主机命令没有审批处理器，未启动进程");
        }
        return chain.proceed(context);
    }

    protected final ToolOutput validateCommandRequest(ToolExecutionPipeline.Context context) {
        return executionCore.validateCommandRequest(context);
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
        return catalog.tools.values().stream()
                .filter(tool -> !ToolAccessContext.isDelegationTool(tool.name()) || access.delegationHandler.get() != null)
                .filter(tool -> tool.permitsAccess(scope, access.delegatedChild))
                .filter(tool -> allowsToolName(access.allowedToolNames.get(), tool.name()))
                .filter(tool -> validateSkillToolAllowed(tool.name()) == null)
                .toList();
    }
    @Override
    public boolean isMcpTool(String toolName) { return catalog.mcpTools.containsKey(toolName); }
    @Override
    public boolean activateToolDefinition(String toolName) { return activateMcpToolDefinition(toolName); }
    @Override
    public long toolCatalogVersion() {
        return catalog.toolCatalogVersion();
    }

    /**
     * 注册 write_file 写入观察者：参数 (path, [before, after])，
     * before == null 表示新建文件或读不出原文。
     * 用于把 write_file 接到行内 diff 渲染等只读副作用里；
     * 观察者抛异常不影响 write_file 主路径。
     */
    public void setWriteFileObserver(java.util.function.BiConsumer<String, String[]> observer) {
        fileAccess.setWriteFileObserver(observer);
    }

    public void setLspManager(LspManager lspManager) {
        fileAccess.setLspManager(lspManager);
    }

    public LspDiagnosticReport flushPendingLspDiagnostics() {
        return fileAccess.flushPendingLspDiagnostics();
    }

    public SnapshotService getSnapshotService() {
        return fileAccess.getSnapshotService();
    }

    public void setSnapshotService(SnapshotService snapshotService) {
        fileAccess.setSnapshotService(snapshotService);
    }

    long toolSearchIndexBuildCount() {
        return toolSearchProvider.buildCount();
    }

    void invalidateToolSearchIndex() {
        catalog.toolCatalogVersion.incrementAndGet();
        toolResultCache.clear();
    }

    /**
     * 创建参数定义
     */
    @Override
    public JsonNode createToolParameters(ToolParameter... params) {
        Param[] converted = Arrays.stream(params == null ? new ToolParameter[0] : params)
                .map(param -> new Param(param.name(), param.type(), param.description(), param.required(),
                        param.enumValues() == null ? List.of() : param.enumValues(), param.allowEmpty()))
                .toArray(Param[]::new);
        return createParameters(converted);
    }

    JsonNode createParameters(Param... params) {
        return catalog.createParameters(params);
    }

    public synchronized ToolSnapshot snapshotForCurrentAccess() {
        return catalog.snapshotForCurrentAccess();
    }

    /**
     * 获取所有工具定义（用于 LLM）。调用方若还要执行本轮工具，应优先使用
     * {@link #snapshotForCurrentAccess()}，以便复用同一份执行绑定。
     */
    public List<LlmClient.Tool> getToolDefinitions() {
        return snapshotForCurrentAccess().definitions();
    }

    public boolean permitsDelegatedTool(String name, ToolAccessScope scope) {
        Tool tool = activeTool(name);
        return tool != null && tool.permitsAccess(scope, true);
    }

    boolean isToolDefinitionVisible(String toolName) {
        if (ToolAccessContext.isDelegationTool(toolName)) return access.delegationHandler.get() != null;
        return !catalog.mcpTools.containsKey(toolName);
    }

    /** 动态工具只改变消息中的发现结果，不改变发送给模型的 catalog.tools 数组。 */
    ToolOutput dispatchMcpTool(String name, String arguments,
                              ToolExecutionContext context) {
        Tool target = activeTool(name);
        if (target == null || activeMcpTool(name) == null
                || !catalog.activatedMcpToolDefinitions.contains(name)) {
            return ToolOutput.rejected(ToolErrorCode.INVALID_ARGUMENTS,
                    "请先调用 search_tools 发现目标 MCP 工具并读取完整 schema", true);
        }
        return executionPipeline.execute(name, arguments, context.invocationId() + "/mcp", context);
    }

    boolean activateMcpToolDefinition(String toolName) {
        if (toolName != null && catalog.mcpTools.containsKey(toolName)) {
            return catalog.activatedMcpToolDefinitions.add(toolName);
        }
        return false;
    }

    public void setMcpToolTrustPolicy(String serverName, McpToolTrustPolicy policy) {
        catalog.setMcpToolTrustPolicy(serverName, policy);
    }

    public void removeMcpToolTrustPolicy(String serverName) {
        catalog.removeMcpToolTrustPolicy(serverName);
    }

    /**
     * 注册一个 MCP 工具到 ToolRegistry。
     *
     * @param descriptor 工具描述（含 namespacedName 如 mcp__filesystem__read_file）
     * @param invoker    工具执行器：输入 JSON 参数字符串，输出给 LLM 看的字符串结果。
     *                   typically lambda 在内部调用 McpClient.callTool 并处理异常 → 字符串。
     */
    public synchronized void registerMcpTool(McpToolDescriptor descriptor, Function<String, String> invoker) {
        catalog.registerMcpTool(descriptor, invoker);
    }

    public synchronized void registerMcpToolOutput(McpToolDescriptor descriptor, Function<String, ToolOutput> invoker) {
        catalog.registerMcpToolOutput(descriptor, invoker);
    }

    public synchronized void registerContextualMcpToolOutput(
            McpToolDescriptor descriptor, McpToolInvoker invoker) {
        catalog.registerContextualMcpToolOutput(descriptor, invoker);
    }

    public synchronized void unregisterMcpTool(String toolName) {
        catalog.unregisterMcpTool(toolName);
    }

    protected synchronized boolean mcpToolRequiresPerCallApproval(String toolName) {
        return catalog.mcpToolRequiresPerCallApproval(toolName);
    }

    protected synchronized String mcpToolApprovalNotice(String toolName) {
        return catalog.mcpToolApprovalNotice(toolName);
    }

    public synchronized String mcpToolSnapshot() {
        return catalog.mcpToolSnapshot();
    }

    public synchronized void setMcpServerLifecycleVersion(String serverName, long lifecycleVersion) {
        catalog.setMcpServerLifecycleVersion(serverName, lifecycleVersion);
    }

    public String currentRagIndexEpochSnapshot() {
        try (VectorStore store = new VectorStore(projectPath)) {
            return store.currentIndexEpoch();
        } catch (Exception e) {
            return "none";
        }
    }

    public synchronized void replaceMcpToolsForServer(String serverName, List<McpToolDescriptor> newTools,
                                                      Function<McpToolDescriptor, Function<String, String>> invokerFactory) {
        catalog.replaceMcpToolsForServer(serverName, newTools, invokerFactory);
    }

    public synchronized void replaceMcpToolOutputsForServer(String serverName, List<McpToolDescriptor> newTools,
                                                            Function<McpToolDescriptor, Function<String, ToolOutput>> invokerFactory) {
        catalog.replaceMcpToolOutputsForServer(serverName, newTools, invokerFactory);
    }

    public synchronized void replaceMcpToolOutputsForServer(String serverName, List<McpToolDescriptor> newTools,
                                                            long lifecycleVersion,
                                                            Function<McpToolDescriptor, Function<String, ToolOutput>> invokerFactory) {
        catalog.replaceMcpToolOutputsForServer(serverName, newTools, lifecycleVersion, invokerFactory);
    }

    public synchronized void replaceContextualMcpToolOutputsForServer(
            String serverName,
            List<McpToolDescriptor> newTools,
            long lifecycleVersion,
            Function<McpToolDescriptor, McpToolInvoker> invokerFactory) {
        catalog.replaceContextualMcpToolOutputsForServer(serverName, newTools, lifecycleVersion, invokerFactory);
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
        ToolResultSizeManager.resetTurnBudget();
        return executionPipeline.execute(name, argumentsJson, null).text();
    }

    protected com.devcli.policy.SensitiveContentPolicy.Decision reviewSensitiveContent(
            String tool, com.devcli.policy.SensitiveContentPolicy.Inspection inspection,
            String purpose, String target, boolean redactionAllowed) {
        return executionCore.reviewSensitiveContent(tool, inspection, purpose, target, redactionAllowed);
    }

    protected ToolOutput reviewMemorySave(ToolExecutionPipeline.Context context) {
        return executionCore.reviewMemorySave(context);
    }

    protected void recordContentDecision(String tool, String types, String purpose, String target,
                                         String decision) {
        executionCore.recordContentDecision(tool, types, purpose, target, decision);
    }

    @Override
    public String reviewFileContent(String content) {
        return executionCore.reviewFileContent(content);
    }

    @Override
    public String reviewFileContent(com.devcli.policy.FileContentReview review) {
        return executionCore.reviewFileContent(review);
    }

    public ToolOutput executeToolOutput(String name, String argumentsJson) {
        ToolResultSizeManager.resetTurnBudget();
        if (isLegacyExecuteToolOverride()) {
            return ToolOutput.text(executeTool(name, argumentsJson));
        }
        return executionPipeline.execute(name, argumentsJson, null);
    }

    /** 兼容扩展类的原有受保护入口，实际执行统一进入中间件管线。 */
    protected ToolOutput doExecuteTool(String name, String argumentsJson) {
        ToolResultSizeManager.resetTurnBudget();
        return executionPipeline.execute(name, argumentsJson, null);
    }

    protected final void registerExecutionMiddleware(ToolExecutionPipeline.Stage stage,
                                                     ToolExecutionPipeline.Middleware middleware) {
        executionPipeline.register(stage, middleware);
    }

    ToolOutput governToolOutput(String name, String invocationId, ToolOutput output) {
        return governToolOutput(name, invocationId, output, 0L);
    }

    ToolOutput governToolOutput(String name, String invocationId, ToolOutput output,
                                        long elapsedMillis) {
        ToolOutput normalized = output == null ? ToolOutput.success("") : output;
        return ToolResultSizeManager.processOutput(name, invocationId, normalized, elapsedMillis);
    }

    protected final void recordToolAudit(AuditLog.AuditEntry entry, ToolExecutionPipeline.Context context) {
        AuditLog.ExecutionIdentity identity =
                context.attribute("audit.identity", AuditLog.ExecutionIdentity.class);
        auditLog.record(entry.withIdentity(identity == null ? executionCore.toolAuditIdentity(context) : identity,
                context.attribute(PIPELINE_APPROVAL_SOURCE, String.class)));
    }

    boolean allowsToolName(Set<String> names, String name) {
        return names == null || names.contains(name)
                || (name.equals(McpToolDispatcher.NAME) || name.equals("search_tools"))
                && names.stream().anyMatch(catalog.mcpTools::containsKey);
    }

    protected ToolOutput validateSkillToolAllowed(String name) {
        return executionCore.validateSkillToolAllowed(name);
    }

    protected ToolOutput validateToolArguments(String name, String argumentsJson) {
        return executionCore.validateToolArguments(name, argumentsJson);
    }

    /** 在 Schema 校验后执行参数组合、资源状态和扩展业务规则校验。 */
    protected ToolOutput validateToolSemantics(String name, String argumentsJson) {
        return executionCore.validateToolSemantics(name, argumentsJson);
    }

    ToolOutput executeToolOutput(ToolInvocation invocation,
                                         ToolExecutionContext executionContext,
                                         ToolSnapshot snapshot) {
        if (isLegacyExecuteToolOverride() || isExecuteToolOutputOverride()) {
            ToolOutput output = executeToolOutput(invocation.name(), invocation.argumentsJson());
            return governToolOutput(invocation.name(), invocation.id(),
                    output, executionContext.elapsedMillis());
        }
        return access.runWithToolSnapshot(snapshot, () -> executionPipeline.execute(
                invocation.name(), invocation.argumentsJson(), invocation.id(), executionContext));
    }

    boolean isLegacyExecuteToolOverride() {
        return declaringClassOf("executeTool") != ToolRegistry.class;
    }

    boolean isExecuteToolOutputOverride() {
        return declaringClassOf("executeToolOutput") != ToolRegistry.class;
    }

    Class<?> declaringClassOf(String methodName) {
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

    public boolean requiresApproval(String name) {
        if (name != null && name.startsWith("mcp__")) {
            Tool tool = activeTool(name);
            return tool == null || isMaterialSideEffect(tool.effect());
        }
        Optional<BuiltInToolPolicy.Policy> policy = BuiltInToolPolicy.find(name);
        if (policy.isPresent()) {
            return policy.get().requiresApproval();
        }
        Tool tool = activeTool(name);
        return tool != null && isMaterialSideEffect(tool.effect());
    }

    public boolean requiresAudit(String name) {
        if (name != null && name.startsWith("mcp__")) {
            return true;
        }
        Optional<BuiltInToolPolicy.Policy> policy = BuiltInToolPolicy.find(name);
        if (policy.isPresent()) {
            return policy.get().audited();
        }
        Tool tool = activeTool(name);
        return tool != null && isMaterialSideEffect(tool.effect());
    }

    static boolean isMaterialSideEffect(ToolEffect effect) {
        return effect != ToolEffect.READ_ONLY && effect != ToolEffect.LOCAL_CONTEXT;
    }

    /** 叶子副作用工具需要工作区级串行；delegate_task 是编排工具，其子操作各自加锁，这里不持锁以防跨线程自锁。 */
    boolean isSerializedSideEffect(String toolName) {
        return batch.isSerializedSideEffect(toolName);
    }

    boolean isSerializedSideEffect(String toolName, ToolSnapshot snapshot) {
        return batch.isSerializedSideEffect(toolName, snapshot);
    }

    /**
     * 并行执行同一轮 LLM 返回的多个工具调用。
     * 连续只读调用和连续委派各自并行，其他调用按原顺序执行；结果保持原始顺序。
     */

    public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
        return batch.executeToolsInternal(invocations, null);
    }

    /** 使用指定的模型工具快照执行一批调用；快照失效时失败关闭。 */
    public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations,
                                                   ToolSnapshot snapshot) {
        // 保留旧版扩展点：测试或外部注册表若覆写单参数批量入口，继续由其负责结果编排。
        // 内置路径均使用本方法，因此不会丢失快照绑定。
        if (snapshot != null && batch.hasLegacyExecuteToolsOverride()) {
            return executeTools(invocations);
        }
        return batch.executeToolsInternal(invocations, snapshot);
    }

    long elapsedMillis(long startedAtNanos) {
        return batch.elapsedMillis(startedAtNanos);
    }

    public ToolCancellationCapability toolCancellationCapability(String toolName) {
        Tool tool = toolName == null ? null : activeTool(toolName);
        return tool == null
                ? ToolCancellationCapability.INTERRUPT_ONLY
                : tool.cancellationCapability();
    }

    public boolean hasTool(String name) {
        return activeTool(name) != null;
    }

    // 记录定义
    record Param(String name, String type, String description, boolean required,
                         List<String> enumValues, boolean allowEmpty) {
        Param(String name, String type, String description, boolean required) {
            this(name, type, description, required, List.of(), false);
        }

        Param(String name, String type, String description, boolean required, String... enumValues) {
            this(name, type, description, required,
                    enumValues == null || enumValues.length == 0 ? List.of() : List.of(enumValues), false);
        }
    }

}
