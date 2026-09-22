package com.devcli.policy;

import java.util.List;

/**
 * 用户规则层求值：在策略硬边界之后、模式基线策略之前，判定一次工具调用。
 *
 * <p>只覆盖规则层三步，不包含模式、沙箱、非交互兜底与分类器——那些阶段需要会话上下文，
 * 由 {@code HitlToolRegistry} 的审批链持有。本类保持纯函数：只读工具名、资源值与规则集。</p>
 *
 * <p>判定次序固定为 {@code deny} → {@code allow} → {@code ask}，命中即短路：</p>
 * <ol>
 *   <li>{@code deny} 绝对优先，命中的第一条即返回 {@link Outcome#DENY}，
 *       任何授权与放行规则都不能覆盖它；</li>
 *   <li>{@code allow} 命中即返回 {@link Outcome#ALLOW}；</li>
 *   <li>{@code ask} 命中即返回 {@link Outcome#ASK}；</li>
 *   <li>三组都未命中返回 {@link Outcome#UNDECIDED}，交回调用方。</li>
 * </ol>
 *
 * <p>这个次序与 WorkBuddy 的求值链一致（其阶段 1 deny、阶段 2 可信 allow、阶段 4 ask）。
 * 代价是真实的：一条宽泛的 {@code allow} 会静默吞掉更窄的 {@code ask}，例如
 * {@code allow write_file(src/**)} 配 {@code ask write_file(src/secret/**)} 时后者永不生效。
 * 这个坑由 {@link PermissionRuleSet#shadowedAskRules()} 在加载期提示，不靠改次序解决——
 * 曾尝试用「specifier 字面量长度」当具体度来比较两者，但那个度量会判反
 * （{@code **&#47;secret&#47;**} 的字面量前缀是空串，语义上却比 {@code src&#47;**} 更窄），
 * 在安全判定里不可接受。真正的解法是存储层的信任分层，让 {@code ask} 按规则来源而非 glob 形状取胜。</p>
 *
 * <p>策略硬边界（路径越界、命令黑名单、非法 URL scheme）不在本类内，必须由调用方在此之前
 * 完成——本类的 {@link Outcome#ALLOW} 只在硬边界已经通过的前提下才意味着可以放行。</p>
 */
public final class PermissionEvaluator {

    /** 规则层的四值结果：前三值终止判定，{@link #UNDECIDED} 交回调用方。 */
    public enum Outcome {
        ALLOW,
        ASK,
        DENY,
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
        PermissionRuleSet effective = rules == null ? PermissionRuleSet.EMPTY : rules;
        if (effective.isEmpty()) {
            return UNDECIDED;
        }
        ToolResourceSlot slot = ToolResourceSlot.of(toolName);

        for (PermissionRule rule : effective.deny()) {
            if (rule.matchesTool(toolName) && rule.triggersOn(slot, resourceValues)) {
                return new Result(Outcome.DENY, "命中拒绝规则 " + rule.raw());
            }
        }
        for (PermissionRule rule : effective.allow()) {
            if (rule.matchesTool(toolName) && rule.allows(slot, resourceValues)) {
                return new Result(Outcome.ALLOW, "命中放行规则 " + rule.raw());
            }
        }
        for (PermissionRule rule : effective.ask()) {
            if (rule.matchesTool(toolName) && rule.triggersOn(slot, resourceValues)) {
                return new Result(Outcome.ASK, "命中询问规则 " + rule.raw());
            }
        }
        return UNDECIDED;
    }
}
