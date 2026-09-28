package com.devcli.tool;

import com.devcli.tool.ToolRegistry.Destructiveness;
import com.devcli.tool.ToolRegistry.Idempotency;
import com.devcli.tool.ToolRegistry.ToolEffect;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 内置工具的副作用、契约、审批与审计策略唯一来源。
 *
 * <p>契约不是从工具名推断出来的：每个内置工具在此显式声明底层物理影响，
 * 执行管线只读这份声明。{@link #policyOrDefault} 只服务于未声明的第三方/测试工具，
 * 按资源域给保守默认，不代表内置工具可以省略声明。</p>
 */
public final class BuiltInToolPolicy {
    public record Policy(ToolEffect effect,
                         boolean requiresApproval,
                         boolean audited,
                         Destructiveness destructiveness,
                         Idempotency idempotency,
                         boolean cacheable) {
    }

    private static final Map<String, Policy> POLICIES = policies();

    private BuiltInToolPolicy() {
    }

    public static Optional<Policy> find(String toolName) {
        return Optional.ofNullable(POLICIES.get(toolName == null ? "" : toolName));
    }

    /**
     * 返回声明契约；未声明的工具按传入 effect 取保守默认值。
     *
     * <p>只读/本地上下文默认视为无破坏且幂等，只读工具默认可短期缓存，
     * 与历史行为保持一致；依赖外部可变状态的只读工具必须显式声明
     * {@code cacheable=false}，会并发改状态的只读工具必须显式声明非幂等。</p>
     */
    public static Policy policyOrDefault(String toolName, ToolEffect effect) {
        Policy declared = POLICIES.get(toolName == null ? "" : toolName);
        return declared != null ? declared : defaultPolicy(effect);
    }

    public static ToolEffect effectOrDefault(String toolName) {
        return find(toolName).map(Policy::effect).orElse(ToolEffect.EXTERNAL_MUTATION);
    }

    public static Destructiveness destructivenessOrDefault(String toolName, ToolEffect effect) {
        return policyOrDefault(toolName, effect).destructiveness();
    }

    public static Idempotency idempotencyOrDefault(String toolName, ToolEffect effect) {
        return policyOrDefault(toolName, effect).idempotency();
    }

    public static boolean cacheableOrDefault(String toolName, ToolEffect effect) {
        return policyOrDefault(toolName, effect).cacheable();
    }

    public static boolean requiresApproval(String toolName) {
        return find(toolName).map(Policy::requiresApproval).orElse(false);
    }

    public static boolean audited(String toolName) {
        return find(toolName).map(Policy::audited).orElse(false);
    }

    public static Set<String> approvalRequiredTools() {
        return POLICIES.entrySet().stream()
                .filter(entry -> entry.getValue().requiresApproval())
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** 未声明契约的工具按资源域取保守默认，不假设它没有状态影响。 */
    public static Policy defaultPolicy(ToolEffect effect) {
        ToolEffect effective = effect == null ? ToolEffect.EXTERNAL_MUTATION : effect;
        boolean readOnlyLike = effective == ToolEffect.READ_ONLY || effective == ToolEffect.LOCAL_CONTEXT;
        return new Policy(
                effective,
                false,
                false,
                readOnlyLike ? Destructiveness.NONE : Destructiveness.STRUCTURAL,
                readOnlyLike ? Idempotency.IDEMPOTENT : Idempotency.NON_IDEMPOTENT,
                effective == ToolEffect.READ_ONLY);
    }

    private static Map<String, Policy> policies() {
        Map<String, Policy> values = new LinkedHashMap<>();
        // 只读且不依赖外部可变状态的工具可以短期缓存，也允许同批并行。
        register(values, ToolEffect.READ_ONLY, false, false,
                Destructiveness.NONE, Idempotency.IDEMPOTENT, true,
                "read_tool_result", "search_tools");
        // 只读但仍依赖外部可变状态（文件、网络、记忆、浏览器态）：不缓存，不假设并发安全。
        register(values, ToolEffect.READ_ONLY, false, false,
                Destructiveness.NONE, Idempotency.IDEMPOTENT, false,
                "read_file", "list_dir", "search_code", "grep_code",
                "web_search", "list_memory", "browser_status");
        // web_fetch 会把请求送到外部站点，是需要出口授权的只读工具：默认逐次确认，
        // 命中任务级域名授权时才自动放行；审计保留每次放行与拒绝记录。
        register(values, ToolEffect.READ_ONLY, true, true,
                Destructiveness.NONE, Idempotency.IDEMPOTENT, false,
                "web_fetch");
        register(values, ToolEffect.LOCAL_CONTEXT, false, false,
                Destructiveness.NONE, Idempotency.IDEMPOTENT, false,
                "load_skill", "delegate_control");
        register(values, ToolEffect.PROJECT_MUTATION, true, true,
                Destructiveness.STRUCTURAL, Idempotency.IDEMPOTENT, false,
                "write_file");
        register(values, ToolEffect.PROJECT_MUTATION, true, true,
                Destructiveness.STRUCTURAL, Idempotency.NON_IDEMPOTENT, false,
                "edit_file", "create_project", "revert_turn");
        // apply_patch 一个调用可跨文件增删改与改名，破坏面大于单文件写入，按非幂等声明。
        register(values, ToolEffect.PROJECT_MUTATION, true, true,
                Destructiveness.STRUCTURAL, Idempotency.NON_IDEMPOTENT, false,
                "apply_patch", "delete_files");
        register(values, ToolEffect.HOST_PROCESS, true, true,
                Destructiveness.STRUCTURAL, Idempotency.NON_IDEMPOTENT, false,
                "execute_command");
        // 浏览器连接只在会话内流转状态，不改业务数据。
        register(values, ToolEffect.EXTERNAL_MUTATION, false, false,
                Destructiveness.BENIGN, Idempotency.NON_IDEMPOTENT, false,
                "browser_connect", "browser_disconnect");
        register(values, ToolEffect.EXTERNAL_MUTATION, false, false,
                Destructiveness.STRUCTURAL, Idempotency.NON_IDEMPOTENT, false,
                "delegate_task");
        // 写入确认在 CONTENT_REVIEW 阶段执行，不进入可复用或自动批准分支。
        register(values, ToolEffect.EXTERNAL_MUTATION, false, true,
                Destructiveness.STRUCTURAL, Idempotency.NON_IDEMPOTENT, false,
                "save_memory");
        return Map.copyOf(values);
    }

    private static void register(Map<String, Policy> target,
                                 ToolEffect effect,
                                 boolean approval,
                                 boolean audited,
                                 Destructiveness destructiveness,
                                 Idempotency idempotency,
                                 boolean cacheable,
                                 String... names) {
        Policy policy = new Policy(effect, approval, audited, destructiveness, idempotency, cacheable);
        for (String name : names) {
            target.put(name, policy);
        }
    }
}
