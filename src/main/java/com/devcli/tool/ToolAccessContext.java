package com.devcli.tool;

import com.devcli.policy.TaskGrant;
import com.devcli.skill.SkillContextBuffer;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static com.devcli.tool.ToolContract.*;

/** 执行能力、授权、快照与委派上下文的作用域传播。 */
final class ToolAccessContext {
    private final ToolRegistry registry;
    ToolAccessContext(ToolRegistry registry) { this.registry = registry; }
    final ThreadLocal<SkillContextBuffer> skillContextBufferOverride = new ThreadLocal<>();

    /**
     * 能力范围使用可继承的线程本地变量：编排波次线程池在轮次内新建，必须继承当前轮次的
     * 能力范围，否则 `/readonly` 无法作用到编排路径。长生命周期线程池都在轮次之外创建，
     * 不会残留某一轮的约束。
     */
    final InheritableThreadLocal<ToolAccessScope> toolAccessScope = new InheritableThreadLocal<>();

    final ThreadLocal<Set<String>> allowedToolNames = new ThreadLocal<>();

    final ThreadLocal<List<String>> allowedWriteGlobs = new ThreadLocal<>();

    /**
     * 任务级授权范围；只影响是否还需要人工审批，不改变能力范围与策略边界。
     * 与能力范围一样可继承，保证编排路径不会丢掉用户显式给出的授权。
     */
    final InheritableThreadLocal<GrantScope> taskGrant = new InheritableThreadLocal<>();

    /** fork 创建时父线程生效的能力范围，作为本注册表的权限上限；根注册表为 FULL。 */
    ToolAccessScope scopeCeiling = ToolAccessScope.FULL;

    /** fork 创建时父线程生效的授权范围，供编排与委派路径继承用户已给出的授权。 */
    GrantScope inheritedTaskGrant;

    Path inheritedGrantProjectRoot;

    static final class GrantScope {
        final String id = java.util.UUID.randomUUID().toString();
        final TaskGrant grant;
        final Path projectRoot;
        final GrantScope parent;
        volatile boolean active = true;

        GrantScope(TaskGrant grant, Path projectRoot, GrantScope parent) {
            this.grant = grant;
            this.projectRoot = projectRoot;
            this.parent = parent;
        }

        boolean isActive() {
            return active && (parent == null || parent.isActive());
        }
    }

    final ThreadLocal<DelegateTaskTool.Handler> delegationHandler = new ThreadLocal<>();

    /** 当前工具调用绑定的不可变工具快照；并行调用在线程内显式继承。 */
    final ThreadLocal<ToolSnapshot> activeToolSnapshot = new ThreadLocal<>();

    boolean delegatedChild;

    final ThreadLocal<String> resourceLeaseStep = new ThreadLocal<>();

    static final ThreadLocal<String> toolTaskIdentity = new ThreadLocal<>();

    static <T> T runWithToolTask(String identity, java.util.function.Supplier<T> action) {
        String previous = toolTaskIdentity.get();
        if (identity == null) toolTaskIdentity.remove();
        else toolTaskIdentity.set(identity);
        try {
            return action.get();
        } finally {
            if (previous == null) toolTaskIdentity.remove();
            else toolTaskIdentity.set(previous);
        }
    }

    <T> T runWithToolSnapshot(ToolSnapshot snapshot, Supplier<T> action) {
        ToolSnapshot previous = activeToolSnapshot.get();
        if (snapshot == null) {
            activeToolSnapshot.remove();
        } else {
            activeToolSnapshot.set(snapshot);
        }
        try {
            return action.get();
        } finally {
            if (previous == null) {
                activeToolSnapshot.remove();
            } else {
                activeToolSnapshot.set(previous);
            }
        }
    }

    /**
     * 当前线程临时覆盖 load_skill 写入目标。
     *
     * Multi-Agent 并行执行时多个 SubAgent 共享同一个 ToolRegistry，但每个 SubAgent
     * 需要把 load_skill 结果写回自己的 SkillContextBuffer。ThreadLocal 只包住本次
     * 工具执行，避免不同并行 worker 互相 drain / push 同一个 buffer。
     */
    void runWithSkillContextBuffer(SkillContextBuffer buffer, Runnable action) {
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

    SkillContextBuffer activeSkillContextBuffer() {
        SkillContextBuffer override = skillContextBufferOverride.get();
        return override == null ? registry.skillContextBuffer : override;
    }

    /**
     * 在收窄后的能力范围内执行；嵌套调用只能继续收窄，不能放宽。
     *
     * <p>{@code scope} 为 null 表示不收窄（保留当前范围），不会重置为 FULL。</p>
     */
    <T> T runWithToolAccess(ToolAccessScope scope, java.util.function.Supplier<T> action) {
        if (action == null) {
            return null;
        }
        ToolAccessScope previous = toolAccessScope.get();
        toolAccessScope.set(scope == null
                ? currentToolAccessScope()
                : currentToolAccessScope().narrow(scope));
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

    /** 当前生效的能力范围：线程内收窄结果，且永不超过本注册表的 {@link #scopeCeiling}。 */
    ToolAccessScope currentToolAccessScope() {
        ToolAccessScope current = toolAccessScope.get();
        return current == null ? scopeCeiling : current;
    }

    <T> T runWithDelegation(DelegateTaskTool.Handler handler, java.util.function.Supplier<T> action) {
        DelegateTaskTool.Handler previous = delegationHandler.get();
        if (handler == null) delegationHandler.remove();
        else delegationHandler.set(handler);
        try {
            return action.get();
        } finally {
            if (previous == null) delegationHandler.remove();
            else delegationHandler.set(previous);
        }
    }

    ToolOutput executeDelegation(Map<String, String> arguments, ToolExecutionContext context) {
        DelegateTaskTool.Handler handler = delegationHandler.get();
        return handler == null
                ? ToolOutput.rejected(ToolErrorCode.CAPABILITY_DENIED,
                        "只有主 Agent 的活动运行可以委派；子 Agent 不允许递归委派")
                : handler.execute(arguments, context);
    }

    ToolOutput executeDelegationControl(Map<String, String> arguments, ToolExecutionContext context) {
        DelegateTaskTool.Handler handler = delegationHandler.get();
        return handler == null || currentToolAccessScope() != ToolAccessScope.FULL
                ? ToolOutput.rejected(ToolErrorCode.CAPABILITY_DENIED, "只有主 Agent 可以控制委派任务")
                : handler.control(arguments, context);
    }

    static boolean isDelegationTool(String name) {
        return DelegateTaskTool.NAME.equals(name) || "delegate_control".equals(name);
    }

    /** 子 Agent 只能接收父 Agent 显式传递的记忆，不能遍历父会话的记忆或继续派生。 */
    void restrictForDelegation() {
        delegatedChild = true;
    }

    boolean restrictedInDelegation(String toolName) {
        return delegatedChild && (isDelegationTool(toolName) || "list_memory".equals(toolName)
                || "read_memory".equals(toolName) || "update_plan".equals(toolName));
    }

    <T> T runWithResourceLease(String stepId, java.util.function.Supplier<T> action) {
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

    /** 委派 brief 的工具白名单只能收窄权限，不能扩大 ToolAccessScope。 */
    <T> T runWithAllowedTools(Set<String> names, java.util.function.Supplier<T> action) {
        Set<String> previous = allowedToolNames.get();
        if (names == null || names.isEmpty()) allowedToolNames.remove();
        else allowedToolNames.set(Set.copyOf(names));
        try {
            return action == null ? null : action.get();
        } finally {
            if (previous == null) allowedToolNames.remove();
            else allowedToolNames.set(previous);
        }
    }

    /**
     * 设置本次任务的授权范围。
     *
     * <p>授权只影响「是否还需要人工审批」，不改变能力范围与策略边界。显式传入空授权会
     * 撤销继承来的授权（fork 默认继承父线程授权，需要能主动收回）；传 null 等价于空授权。
     * 与能力范围一样是线程本地的，并行工具批次会显式继承。</p>
     */
    <T> T runWithTaskGrant(TaskGrant grant, java.util.function.Supplier<T> action) {
        GrantScope scope = new GrantScope(grant == null ? TaskGrant.NONE : grant,
                Path.of(registry.projectPath).toAbsolutePath().normalize(), currentGrantScope());
        try {
            return runWithGrantScope(scope, action);
        } finally {
            // Forks and child threads retain this same revocable scope, not a permission copy.
            scope.active = false;
        }
    }

    <T> T runWithGrantScope(GrantScope scope, java.util.function.Supplier<T> action) {
        GrantScope previous = taskGrant.get();
        if (scope == null) taskGrant.remove();
        else taskGrant.set(scope);
        try {
            return action == null ? null : action.get();
        } finally {
            if (previous == null) {
                taskGrant.remove();
            } else {
                taskGrant.set(previous);
            }
        }
    }

    /** 当前任务的授权范围：线程内显式设置优先，否则继承 fork 创建时的父授权。 */
    TaskGrant currentTaskGrant() {
        GrantScope scope = currentGrantScope();
        Path currentRoot = Path.of(registry.projectPath).toAbsolutePath().normalize();
        return scope != null && scope.isActive()
                && (scope == inheritedTaskGrant
                ? currentRoot.equals(inheritedGrantProjectRoot) : scope.projectRoot.equals(currentRoot))
                ? scope.grant : TaskGrant.NONE;
    }

    GrantScope currentGrantScope() {
        GrantScope current = taskGrant.get();
        return current == null ? inheritedTaskGrant : current;
    }

    /** 委派 Worker 的写路径白名单，使用项目相对路径 glob。 */
    <T> T runWithAllowedWritePaths(List<String> globs, java.util.function.Supplier<T> action) {
        List<String> previous = allowedWriteGlobs.get();
        if (globs == null || globs.isEmpty()) allowedWriteGlobs.remove();
        else allowedWriteGlobs.set(List.copyOf(globs));
        try {
            return action == null ? null : action.get();
        } finally {
            if (previous == null) allowedWriteGlobs.remove();
            else allowedWriteGlobs.set(previous);
        }
    }

    /**
     * 为隔离工作区创建项目级工具注册表。内置 Provider 重新绑定到新根目录，
     * MCP 描述与调用器、策略配置和记忆处理器沿用父注册表。
     */
    ToolRegistry forkForProject(Path projectRoot) {
        Path root = Objects.requireNonNull(projectRoot, "projectRoot")
                .toAbsolutePath().normalize();
        ToolRegistry fork = registry.createProjectForkRegistry(registry.resourceLeaseMaintenance);
        // fork 的权限只能等于或小于创建它的线程：内层隔离工作区不得放宽外层只读约束，
        // 也不应丢失用户已显式给出的任务级授权。
        fork.access.scopeCeiling = registry.currentToolAccessScope();
        fork.access.inheritedTaskGrant = registry.currentTaskGrant().isEmpty() ? null : registry.access.currentGrantScope();
        fork.bindContextVersionLedger(registry.contextVersionLedger, registry.contextProjectRoot);
        fork.setProjectPath(root.toString());
        fork.delegateRoles = registry.delegateRoles;
        fork.registerTool(DelegateTaskTool.definition(fork));
        fork.access.inheritedGrantProjectRoot = root;
        fork.contextProfile = registry.contextProfile;
        fork.access.delegatedChild = registry.access.delegatedChild;
        fork.synchronousWorkerGate = registry.synchronousWorkerGate;
        fork.browserGuard = registry.browserGuard;
        fork.browserConnector = registry.browserConnector;
        fork.memorySaver = registry.memorySaver;
        fork.memorySaveHandler = registry.memorySaveHandler;
        fork.memoryListHandler = registry.memoryListHandler;
        fork.agentContextTools.copyHandlersFrom(registry.agentContextTools);
        fork.skillRegistry = registry.skillRegistry;
        fork.skillContextBuffer = registry.skillContextBuffer == null ? null : registry.skillContextBuffer.copy();
        fork.commandExecutionService = registry.commandExecutionService;
        fork.catalog.mcpTrustPolicies.putAll(registry.catalog.mcpTrustPolicies);
        fork.catalog.semanticValidators.putAll(registry.catalog.semanticValidators);
        registry.catalog.mcpTools.values().forEach(registered ->
                fork.registerContextualMcpToolOutput(
                        registered.descriptor(), registered.invoker()));
        fork.catalog.mcpServerLifecycleVersions.putAll(registry.catalog.mcpServerLifecycleVersions);
        fork.catalog.activatedMcpToolDefinitions.addAll(registry.catalog.activatedMcpToolDefinitions);
        return fork;
    }
}
