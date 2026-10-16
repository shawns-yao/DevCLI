package com.devcli.policy;

import java.util.List;

/**
 * 用户规则层求值：在策略硬边界之后、模式基线策略之前，判定一次工具调用。
 *
 * <p>只覆盖规则层三步，不包含模式、沙箱、非交互兜底与分类器——那些阶段需要会话上下文，
 * 由 {@code HitlToolRegistry} 的审批链持有。本类保持纯函数：只读工具名、资源值与规则集。</p>
 *
 * <p>所有模式的判定次序固定为 {@code hard_deny} → {@code soft_deny} → {@code allow}：</p>
 * <ol>
 *   <li>{@code hard_deny} 绝对优先，命中的第一条即返回 {@link Outcome#HARD_DENY}，
 *       任何授权与允许例外都不能覆盖它；</li>
 *   <li>{@code soft_deny} 命中即返回 {@link Outcome#SOFT_DENY}，交由人工确认；</li>
 *   <li>{@code allow} 命中即返回 {@link Outcome#ALLOW}；</li>
 *   <li>四类都未命中返回 {@link Outcome#UNDECIDED}，交回调用方。</li>
 * </ol>
 *
 * <p>auto 忽略过宽的命令 allow；硬拒绝和软拒绝规则不受影响。Shell 复合语义不能由命令前缀授权。</p>
 *
 * <p>策略硬边界（路径越界、命令黑名单、非法 URL scheme）不在本类内，必须由调用方在此之前
 * 完成——本类的 {@link Outcome#ALLOW} 只在硬边界已经通过的前提下才意味着可以放行。</p>
 */
public final class PermissionEvaluator {

    /** 规则层的四值结果：前三值终止判定，{@link #UNDECIDED} 交回调用方。 */
    public enum Outcome {
        ALLOW,
        SOFT_DENY,
        HARD_DENY,
        UNDECIDED
    }

    public record Result(Outcome outcome, String reason) {
    }

    /** 规则层未命中，判定权交回下游模式基线策略。 */
    public static final Result UNDECIDED = new Result(Outcome.UNDECIDED, "");

    private PermissionEvaluator() {
    }

    /**
     * @param toolName       被调用的工具名
     * @param resourceValues 按资源槽归一化后的资源值；{@code null} 表示无法解析，
     *                       此时带 specifier 的规则一律不命中，判定退回人工审批
     * @param rules          用户规则全集；null 或空表示没有规则
     */
    public static Result evaluate(String toolName, List<String> resourceValues, PermissionRuleSet rules) {
        return evaluate(toolName, resourceValues, rules, false);
    }

    /** auto 忽略过宽的命令 allow；拒绝规则始终保留。 */
    public static Result evaluate(String toolName, List<String> resourceValues, PermissionRuleSet rules,
                                  boolean auto) {
        PermissionRuleSet effective = rules == null ? PermissionRuleSet.EMPTY : rules;
        if (effective.isEmpty()) {
            return UNDECIDED;
        }
        ToolResourceSlot slot = ToolResourceSlot.of(toolName);

        for (PermissionRule rule : effective.hardDeny()) {
            if (rule.matchesTool(toolName) && rule.triggersOn(slot, resourceValues)) {
                return new Result(Outcome.HARD_DENY, "命中硬阻止规则 " + rule.raw());
            }
        }
        for (PermissionRule rule : effective.softDeny()) {
            if (rule.matchesTool(toolName) && rule.triggersOn(slot, resourceValues)) {
                return new Result(Outcome.SOFT_DENY, "命中软阻止规则 " + rule.raw());
            }
        }
        for (PermissionRule rule : effective.allow()) {
            if (auto && ToolResourceSlot.of(toolName) == ToolResourceSlot.COMMAND
                    && !CommandPermissionRules.isNarrow(rule)) continue;
            if (slot == ToolResourceSlot.COMMAND && resourceValues != null
                    && !CommandPermissionRules.isSimpleCommand(resourceValues.get(0))) continue;
            if (rule.matchesTool(toolName) && rule.allows(slot, resourceValues)) {
                return new Result(Outcome.ALLOW, "命中放行规则 " + rule.raw());
            }
        }
        return UNDECIDED;
    }
}
