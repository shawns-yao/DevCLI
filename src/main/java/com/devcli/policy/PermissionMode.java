package com.devcli.policy;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 权限模式：用户可见的唯一权限预设。
 *
 * <p>模式声明两件事：<b>能力上限</b>（写工具是否暴露给模型）与<b>询问策略</b>（未命中任何规则时默认怎么办）。
 * 它不声明命令在哪执行——那是沙箱层，由委派、计划任务与步骤执行器在内部按执行上下文施加，
 * 用户不可见，也不该混进模式里。</p>
 *
 * <p>模式不引用 {@code ToolAccessScope}：{@code policy} 不得依赖 {@code tool}（由
 * {@code PackageBoundaryTest} 机械验证），因此模式到能力范围的映射落在 {@code cli} 的接线层。</p>
 *
 * <p>模式改不了的东西：策略硬边界、规则层的 {@code deny}、规则层的显式 {@code ask}。
 * {@link AskPolicy#DENY} 只把「本来会询问」的动作收口为拒绝，不会把 {@code allow} 改写成 {@code deny}。</p>
 */
public enum PermissionMode {

    /** 默认：危险操作逐个询问。 */
    DEFAULT("default", "危险操作逐个询问", Capability.FULL, AskPolicy.ASK),

    /** 只读：不暴露写入与命令工具；需要确认时仍然询问。 */
    PLAN("plan", "只读：不暴露写入与命令工具，需要时仍会询问",
            Capability.READ_ONLY, AskPolicy.ASK),

    /** 自动放行编辑类工具，其余仍走常规路径。 */
    ACCEPT_EDITS("acceptEdits", "自动放行编辑类工具（write_file / edit_file），其余仍询问",
            Capability.FULL, AskPolicy.ASK_EXCEPT_EDITS),

    /** 全部直接放行。策略硬边界与规则层 deny 仍然生效。 */
    BYPASS_PERMISSIONS("bypassPermissions", "全部直接放行，不询问",
            Capability.FULL, AskPolicy.ALLOW),

    /** 不询问；未获授权的动作收口为拒绝。 */
    DONT_ASK("dontAsk", "不询问；未获授权的动作一律拒绝",
            Capability.FULL, AskPolicy.DENY),

    /**
     * 由分类器逐个判断。
     *
     * <p>只接管走完整条求值链、没有任何规则或安全机制要求询问、仅仅因为默认策略才需要询问的动作。
     * 显式 {@code ask} 规则、删除确认与逐次审批类要求不进分类器。</p>
     *
     * <p>分类器不可用、超时或连续失败一律收口为拒绝——{@code auto} 不因为「判断不出来」而放行。</p>
     */
    AUTO("auto", "由分类器逐个判断；分类器不可用时一律拒绝",
            Capability.FULL, AskPolicy.AUTO);

    /** 模式能表达的能力上限。不含沙箱——沙箱由内部执行上下文施加。 */
    public enum Capability {
        FULL,
        READ_ONLY
    }

    /** 规则层未命中时的默认答案。 */
    public enum AskPolicy {
        ASK,
        ALLOW,
        ASK_EXCEPT_EDITS,
        DENY,
        /** 交给分类器；分类器只能返回放行或拒绝，不能返回「继续询问」。 */
        AUTO
    }

    private static final Map<String, PermissionMode> ALIASES = Map.ofEntries(
            Map.entry("default", DEFAULT),
            Map.entry("plan", PLAN),
            Map.entry("readonly", PLAN),
            Map.entry("read-only", PLAN),
            Map.entry("acceptedits", ACCEPT_EDITS),
            Map.entry("accept-edits", ACCEPT_EDITS),
            Map.entry("bypasspermissions", BYPASS_PERMISSIONS),
            Map.entry("bypass-permissions", BYPASS_PERMISSIONS),
            Map.entry("bypass", BYPASS_PERMISSIONS),
            Map.entry("dontask", DONT_ASK),
            Map.entry("dont-ask", DONT_ASK),
            Map.entry("auto", AUTO));

    private final String id;
    private final String description;
    private final Capability capability;
    private final AskPolicy askPolicy;

    PermissionMode(String id, String description, Capability capability, AskPolicy askPolicy) {
        this.id = id;
        this.description = description;
        this.capability = capability;
        this.askPolicy = askPolicy;
    }

    /** 用户书写的模式名，与 CodeBuddy 一致。 */
    public String id() {
        return id;
    }

    /** 单行说明，用于状态提示与帮助文本。 */
    public String description() {
        return description;
    }

    public Capability capability() {
        return capability;
    }

    public AskPolicy askPolicy() {
        return askPolicy;
    }

    /**
     * 模式是否把这次调用直接放行，既不询问也不要求预先授权。
     *
     * <p>「编辑类工具」判定复用 {@link ToolResourceSlot}：资源槽为 {@link ToolResourceSlot#PATH} 的工具
     * （{@code write_file} / {@code edit_file}）。删除类（{@code PATH_LIST}）与项目创建类
     * （{@code PROJECT_NAME}）不算编辑——用户说「接受编辑」时没有同意删文件。</p>
     */
    public boolean autoAllows(String toolName) {
        return switch (askPolicy) {
            case ALLOW -> true;
            case ASK_EXCEPT_EDITS -> ToolResourceSlot.of(toolName) == ToolResourceSlot.PATH;
            case ASK, DENY, AUTO -> false;
        };
    }

    /** 命中 {@link #autoAllows} 时的授权理由，用于审批链的放行来源与审计。 */
    public String autoAllowReason() {
        return switch (askPolicy) {
            case ALLOW -> "模式 " + id + " 直接放行";
            case ASK_EXCEPT_EDITS -> "模式 " + id + " 放行编辑类工具";
            case ASK, DENY, AUTO -> "";
        };
    }

    /** 收口为拒绝时的理由；仅 {@link AskPolicy#DENY} 非空。 */
    public String denyReason() {
        return askPolicy == AskPolicy.DENY
                ? "模式 " + id + " 不询问，未获授权的动作一律拒绝"
                : "";
    }

    /**
     * 解析用户输入或配置值。
     *
     * @throws IllegalArgumentException 未知模式；调用方负责拒绝而不是静默回落到默认值
     */
    public static PermissionMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("权限模式不能为空");
        }
        PermissionMode mode = ALIASES.get(raw.trim().toLowerCase(Locale.ROOT));
        if (mode == null) {
            throw new IllegalArgumentException(
                    "未知权限模式 " + raw.trim() + "，可选：" + String.join(" / ", ids()));
        }
        return mode;
    }

    /** 可书写模式名的有序清单，用于帮助文本与错误提示。 */
    public static List<String> ids() {
        return List.of(DEFAULT.id, PLAN.id, ACCEPT_EDITS.id, BYPASS_PERMISSIONS.id, DONT_ASK.id,
                AUTO.id);
    }

    /** {@code /mode} 的帮助文本。 */
    public static String usage() {
        StringBuilder text = new StringBuilder();
        for (PermissionMode mode : values()) {
            text.append("  ").append(mode.id);
            text.append(" ".repeat(Math.max(1, 18 - mode.id.length())));
            text.append(mode.description).append('\n');
        }
        return text.toString().stripTrailing();
    }
}
