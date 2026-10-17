package com.devcli.tool;

import com.devcli.tool.ToolContract.Param;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.devcli.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.devcli.mcp.McpToolDiscoveryEntry;
import com.devcli.util.UntrustedContent;
import com.devcli.mcp.config.McpToolTrustPolicy;
import com.devcli.mcp.protocol.McpToolDescriptor;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static com.devcli.tool.ToolContract.*;

/** 工具目录发布与 MCP 生命周期；定义和执行绑定由同一版本管理。 */
final class McpToolCatalog {
    private final ToolRegistry registry;
    McpToolCatalog(ToolRegistry registry) {
        this.registry = registry;
        this.definitions = new McpDefinitionGuard(mcpTools, registry::onMcpToolsChanged);
    }
    final Map<String, Tool> tools = new ConcurrentHashMap<>();

    final Map<String, McpRegisteredTool> mcpTools = new ConcurrentHashMap<>();
    McpDefinitionGuard definitions;

    final Map<String, ToolSemanticValidator.CustomRule> semanticValidators = new ConcurrentHashMap<>();

    final Set<String> builtInSemanticToolNames = ConcurrentHashMap.newKeySet();

    final Map<String, McpToolTrustPolicy> mcpTrustPolicies = new ConcurrentHashMap<>();

    final Map<String, Long> mcpServerLifecycleVersions = new ConcurrentHashMap<>();

    final Set<String> activatedMcpToolDefinitions = ConcurrentHashMap.newKeySet();

    final AtomicLong toolCatalogVersion = new AtomicLong();

    void registerTool(Tool tool) {
        if (tool == null || tool.name() == null || tool.name().isBlank()) {
            return;
        }
        tools.put(tool.name(), tool);
        builtInSemanticToolNames.remove(tool.name());
        registry.invalidateToolSearchIndex();
    }

    /**
     * 评测 / 受限运行入口：按名移除一个已注册工具（如闭卷 benchmark 禁用 web_search / save_memory）。
     * 只影响当前 ToolRegistry 实例，默认产品路径不调用。
     */
    void removeTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return;
        }
        if (tools.remove(toolName) != null) {
            registry.invalidateToolSearchIndex();
        }
    }

    /** 为 MCP 或业务扩展工具注册客户端侧语义校验；Schema 通过后才会调用该规则。 */
    void registerSemanticValidator(String toolName, ToolSemanticValidator.CustomRule validator) {
        if (toolName == null || toolName.isBlank() || validator == null) {
            return;
        }
        semanticValidators.put(toolName, validator);
    }

    void removeSemanticValidator(String toolName) {
        if (toolName != null && !toolName.isBlank()) {
            semanticValidators.remove(toolName);
        }
    }

    long toolCatalogVersion() {
        return (toolCatalogVersion.get() * ToolAccessScope.values().length
                + registry.currentToolAccessScope().ordinal()) * 4 + (registry.access.delegationHandler.get() == null ? 0 : 1)
                + (registry.access.delegatedChild ? 2 : 0);
    }

    void setMcpToolTrustPolicy(String serverName, McpToolTrustPolicy policy) {
        String normalized = normalizeMcpServerName(serverName);
        McpToolTrustPolicy effective = policy == null ? McpToolTrustPolicy.untrusted() : policy;
        synchronized (registry) {
            McpToolTrustPolicy previous = mcpTrustPolicies.put(normalized, effective);
            if (!effective.equals(previous)) refreshTrustPolicy(normalized);
        }
    }

    void removeMcpToolTrustPolicy(String serverName) {
        String normalized = normalizeMcpServerName(serverName);
        if (normalized == null) {
            return;
        }
        synchronized (registry) {
            if (mcpTrustPolicies.remove(normalized) != null) refreshTrustPolicy(normalized);
        }
    }

    private void refreshTrustPolicy(String serverName) {
        // 重新发布能力合同与绑定，让已有 fork 不能保留被撤销的本地授权。
        List<McpRegisteredTool> existing = mcpTools.values().stream()
                .filter(tool -> serverName.equals(tool.descriptor().serverName())).toList();
        for (McpRegisteredTool registered : existing) {
            registerContextualMcpToolOutput(registered.descriptor(), registered.invoker());
        }
        registry.onMcpToolsChanged(serverName);
        registry.invalidateToolSearchIndex();
    }

    /**
     * 注册一个 MCP 工具到 ToolRegistry。
     *
     * @param descriptor 工具描述（含 namespacedName 如 mcp__filesystem__read_file）
     * @param invoker    工具执行器：输入 JSON 参数字符串，输出给 LLM 看的字符串结果。
     *                   typically lambda 在内部调用 McpClient.callTool 并处理异常 → 字符串。
     */
    void registerMcpTool(McpToolDescriptor descriptor, Function<String, String> invoker) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(invoker, "invoker");
        registerMcpToolOutput(descriptor, args -> ToolOutput.text(invoker.apply(args)));
    }

    void registerMcpToolOutput(McpToolDescriptor descriptor, Function<String, ToolOutput> invoker) {
        Objects.requireNonNull(invoker, "invoker");
        registerContextualMcpToolOutput(descriptor,
                (argumentsJson, ignored) -> invoker.apply(argumentsJson));
    }

    void registerContextualMcpToolOutput(
            McpToolDescriptor descriptor, McpToolInvoker invoker) {
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
        McpRegisteredTool previous = mcpTools.get(toolName);
        if (previous != null && !previous.descriptor().equals(descriptor)) {
            definitions.changed(descriptor.serverName(), descriptor);
        } else if (previous == null) {
            registry.onMcpToolsChanged(descriptor.serverName());
        }
        mcpTools.put(toolName, registered);
        tools.put(toolName, mcpTool(toolName, descriptor, policy));
        registry.invalidateToolSearchIndex();
    }

    void unregisterMcpTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return;
        }
        McpRegisteredTool previous = mcpTools.remove(toolName);
        if (previous != null) definitions.removed(previous.descriptor().serverName(), toolName);
        boolean removed = previous != null;
        removed = tools.remove(toolName) != null || removed;
        activatedMcpToolDefinitions.remove(toolName);
        if (removed) {
            registry.invalidateToolSearchIndex();
        }
    }

    boolean mcpToolRequiresPerCallApproval(String toolName) {
        McpRegisteredTool registered = registry.activeMcpTool(toolName);
        if (registered == null || registered.descriptor().annotations() == null) {
            return false;
        }
        McpToolDescriptor.Annotations annotations = registered.descriptor().annotations();
        return annotations.destructive() || annotations.openWorld();
    }

    String mcpToolApprovalNotice(String toolName) {
        McpRegisteredTool registered = registry.activeMcpTool(toolName);
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

    String definitionNotice(ToolExecutionPipeline.Context context) {
        var descriptor = context.attribute(McpDefinitionGuard.BINDING, McpRegisteredTool.class).descriptor();
        return mcpDescription(descriptor) + "\n参数 schema: " + descriptor.inputSchema();
    }

    String mcpToolSnapshot() {
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

    void setMcpServerLifecycleVersion(String serverName, long lifecycleVersion) {
        String normalized = normalizeMcpServerName(serverName);
        if (normalized == null) {
            return;
        }
        mcpServerLifecycleVersions.put(normalized, Math.max(0, lifecycleVersion));
    }

    static String mcpToolSchemaFingerprint(List<McpRegisteredTool> serverTools) {
        return McpToolDiscoveryEntry.fingerprint(serverTools.stream()
                .map(McpRegisteredTool::descriptor).toList());
    }

    void replaceMcpToolsForServer(String serverName, List<McpToolDescriptor> newTools,
                                                      Function<McpToolDescriptor, Function<String, String>> invokerFactory) {
        replaceMcpToolOutputsForServer(serverName, newTools,
                descriptor -> args -> ToolOutput.text(invokerFactory.apply(descriptor).apply(args)));
    }

    void replaceMcpToolOutputsForServer(String serverName, List<McpToolDescriptor> newTools,
                                                            Function<McpToolDescriptor, Function<String, ToolOutput>> invokerFactory) {
        replaceMcpToolOutputsForServer(serverName, newTools, 0, invokerFactory);
    }

    void replaceMcpToolOutputsForServer(String serverName, List<McpToolDescriptor> newTools,
                                                            long lifecycleVersion,
                                                            Function<McpToolDescriptor, Function<String, ToolOutput>> invokerFactory) {
        Objects.requireNonNull(invokerFactory, "invokerFactory");
        replaceContextualMcpToolOutputsForServer(serverName, newTools, lifecycleVersion,
                descriptor -> {
                    Function<String, ToolOutput> invoker = Objects.requireNonNull(
                            invokerFactory.apply(descriptor), "MCP invoker");
                    return (argumentsJson, ignored) -> invoker.apply(argumentsJson);
                });
    }

    void replaceContextualMcpToolOutputsForServer(
            String serverName,
            List<McpToolDescriptor> newTools,
            long lifecycleVersion,
            Function<McpToolDescriptor, McpToolInvoker> invokerFactory) {
        Objects.requireNonNull(serverName, "serverName");
        Objects.requireNonNull(newTools, "newTools");
        Objects.requireNonNull(invokerFactory, "invokerFactory");
        String prefix = "mcp__" + serverName + "__";
        List<String> existing = mcpTools.keySet().stream()
                .filter(name -> name.startsWith(prefix))
                .toList();

        // 先完成整批构造，任何 descriptor / invoker 失败都不能破坏当前已发布集合。
        Set<String> pendingNames = new HashSet<>();
        List<PendingMcpTool> pending = new ArrayList<>();
        for (McpToolDescriptor descriptor : newTools) {
            Objects.requireNonNull(descriptor, "descriptor");
            String toolName = descriptor.namespacedName();
            if (toolName == null || toolName.isBlank() || !pendingNames.add(toolName)) {
                throw new IllegalArgumentException("MCP 工具批次包含重复或空名称");
            }
            if (tools.containsKey(toolName) && !existing.contains(toolName)) {
                throw new IllegalArgumentException("工具名称已被其他注册项占用: " + toolName);
            }
            McpToolTrustPolicy policy = mcpTrustPolicies.getOrDefault(
                    normalizeMcpServerName(descriptor.serverName()),
                    McpToolTrustPolicy.untrusted());
            if (policy.isDenied(descriptor.name())) {
                continue;
            }
            McpToolInvoker invoker = Objects.requireNonNull(
                    invokerFactory.apply(descriptor), "MCP invoker");
            McpRegisteredTool registered = new McpRegisteredTool(descriptor, invoker);
            Tool tool = mcpTool(toolName, descriptor, policy);
            pending.add(new PendingMcpTool(toolName, registered, tool));
        }

        for (PendingMcpTool item : pending) {
            McpRegisteredTool previous = mcpTools.get(item.name());
            if (previous != null && !previous.descriptor().equals(item.registered().descriptor())) {
                definitions.changed(serverName, item.registered().descriptor());
            } else if (previous == null) {
                registry.onMcpToolsChanged(serverName);
            }
        }
        Set<String> publishedNames = new HashSet<>();
        for (PendingMcpTool item : pending) publishedNames.add(item.name());
        for (String name : existing) {
            if (!publishedNames.contains(name)) definitions.removed(serverName, name);
        }
        setMcpServerLifecycleVersion(serverName, lifecycleVersion);
        for (String toolName : existing) {
            mcpTools.remove(toolName);
            tools.remove(toolName);
            activatedMcpToolDefinitions.remove(toolName);
        }
        if (!existing.isEmpty() || !pending.isEmpty()) {
            registry.invalidateToolSearchIndex();
        }
        for (PendingMcpTool item : pending) {
            mcpTools.put(item.name(), item.registered());
            tools.put(item.name(), item.tool());
        }
        if (!pending.isEmpty()) {
            registry.invalidateToolSearchIndex();
        }
    }

    static String normalizeMcpServerName(String serverName) {
        if (serverName == null || serverName.isBlank()) {
            return null;
        }
        return serverName.trim();
    }

    /**
     * 注册 MCP 工具时使用保守契约：服务端 annotations 不可信，本地只读授权
     * 也只说明访问等级，不能证明没有状态流转。
     *
     * <p>因此只读授权工具声明为 {@link Destructiveness#BENIGN}，
     * 所有 MCP 工具一律 {@link Idempotency#NON_IDEMPOTENT} 且不可缓存。</p>
     */
    private static Tool mcpTool(String toolName, McpToolDescriptor descriptor, McpToolTrustPolicy policy) {
        ToolEffect effect = ToolEffect.fromMcp(descriptor, policy);
        return new Tool(
                toolName,
                mcpDescription(descriptor),
                descriptor.inputSchema(),
                args -> "MCP 工具不应通过 Map<String,String> 入口执行",
                effect,
                -1,
                ToolCancellationCapability.COOPERATIVE,
                ToolPresentation.generic(toolName),
                mcpDestructiveness(effect),
                Idempotency.NON_IDEMPOTENT,
                false);
    }

    /** MCP 工具的破坏性等级：只读授权按良性状态流转处理，其余按实质写入。 */
    static Destructiveness mcpDestructiveness(ToolEffect effect) {
        return effect == ToolEffect.READ_ONLY ? Destructiveness.BENIGN : Destructiveness.STRUCTURAL;
    }

    static String mcpDescription(McpToolDescriptor descriptor) {
        String base = descriptor.description() == null || descriptor.description().isBlank()
                ? "MCP server 提供的外部工具"
                : descriptor.description();
        if (base.codePointCount(0, base.length()) > 2000) {
            base = base.substring(0, base.offsetByCodePoints(0, 2000)) + "…[描述已截断]";
        }
        String annotations = mcpAnnotationSummary(descriptor.annotations());
        return "[第三方提供，" + UntrustedContent.UNTRUSTED + "] " + base
                + " (MCP server: " + descriptor.serverName() + ", tool: " + descriptor.name()
                + (annotations.isBlank() ? "" : ", annotations: " + annotations)
                + ")";
    }

    static String mcpAnnotationSummary(McpToolDescriptor.Annotations annotations) {
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

    JsonNode createParameters(Param... params) {
        ObjectNode parameters = registry.mapper.createObjectNode();
        parameters.put("type", "object");
        parameters.put("additionalProperties", false);
        ObjectNode properties = parameters.putObject("properties");
        ArrayNode required = parameters.putArray("required");

        for (Param param : params) {
            ObjectNode prop = properties.putObject(param.name());
            prop.put("type", param.type());
            prop.put("description", param.description());
            if ("string".equals(param.type()) && param.required() && !param.allowEmpty()) {
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
     * 捕获当前权限范围内的工具定义与可执行绑定。
     *
     * <p>模型请求和随后的一批工具调用应使用同一份快照；注册表在两者之间发生变更时，
     * 执行入口会拒绝该批调用，避免模型看到的 schema 与实际执行器错位。</p>
     */
    ToolSnapshot snapshotForCurrentAccess() {
        ToolAccessScope scope = registry.currentToolAccessScope();
        Set<String> allowlist = registry.access.allowedToolNames.get();
        Map<String, ToolBinding> bindings = new LinkedHashMap<>();
        List<LlmClient.Tool> definitions = new ArrayList<>();
        List<Tool> orderedTools = registry.catalog.tools.values().stream()
                .sorted(Comparator.comparing(Tool::name))
                .toList();
        for (Tool tool : orderedTools) {
            bindings.put(tool.name(), new ToolBinding(tool, registry.catalog.mcpTools.get(tool.name())));
            if (registry.isToolDefinitionVisible(tool.name())
                    && tool.permitsAccess(scope, registry.access.delegatedChild)
                    && registry.allowsToolName(allowlist, tool.name())) {
                definitions.add(new LlmClient.Tool(tool.name(), tool.description(), tool.parameters()));
            }
        }
        return new ToolSnapshot(registry, registry.catalog.toolCatalogVersion.get(), definitions, bindings);
    }
}
