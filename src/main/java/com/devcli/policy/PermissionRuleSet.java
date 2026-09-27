package com.devcli.policy;

import java.util.List;

/**
 * 一次求值要用的规则全集，四类：{@code hard_deny} / {@code soft_deny} / {@code allow} / {@code environment}。
 *
 * <p>四类的分工对应参照实现的四个用户规则类别，语义不重叠：</p>
 * <ul>
 *   <li>{@code hard_deny} —— 安全边界，<b>无条件</b>拒绝。用户意图不能清除，允许例外也不能覆盖。
 *       对应 {@link PermissionEvaluator.Outcome#HARD_DENY}。</li>
 *   <li>{@code soft_deny} —— 用户声明的边界。<b>明确且具体的用户意图可以清除它</b>，但只有分类器
 *       有资格判断意图，所以在 {@code auto} 模式下它不短路，而是作为分类器输入参与同一次判定；
 *       其余模式没有分类器可用，退回「强制人工审批」。对应
 *       {@link PermissionEvaluator.Outcome#SOFT_DENY}。</li>
 *   <li>{@code allow} —— 允许例外。仅在软阻止上生效，且同样在 {@code auto} 模式下交给分类器
 *       判断（参照实现规定例外是强制的，但有两个例外情形：伪装成例外的可疑动作、用户明确设下的
 *       边界——两者都要分类器才判得出来）。</li>
 *   <li>{@code environment} —— 用户环境的事实描述（哪些域名可信、哪些资源是共享的）。
 *       它<b>不产生任何判定</b>，只作为上下文进分类器提示词，供其余三类规则与内置判据引用。</li>
 * </ul>
 *
 * <p>非自动模式的求值顺序见 {@link PermissionEvaluator}：
 * {@code hard_deny} → {@code soft_deny} → {@code allow}。</p>
 *
 * <p>不可变值对象。规则集为空是合法状态，表示「没有用户规则」，此时求值一律返回未决，
 * 由下游的模式基线策略接管——这也是关闭规则层的方式。</p>
 */
public record PermissionRuleSet(List<PermissionRule> hardDeny,
                                List<PermissionRule> softDeny,
                                List<PermissionRule> allow,
                                List<String> environment) {

    /** 没有用户规则。 */
    public static final PermissionRuleSet EMPTY =
            new PermissionRuleSet(List.of(), List.of(), List.of(), List.of());

    public PermissionRuleSet {
        hardDeny = hardDeny == null ? List.of() : List.copyOf(hardDeny);
        softDeny = softDeny == null ? List.of() : List.copyOf(softDeny);
        allow = allow == null ? List.of() : List.copyOf(allow);
        environment = environment == null ? List.of() : environment.stream()
                .filter(entry -> entry != null && !entry.isBlank())
                .map(String::trim)
                .toList();
    }

    /**
     * 从原始规则文本解析四类规则。
     *
     * <p>{@code environment} 是自由文本而不是 {@code Tool(specifier)} 形态，因此不做规则语法校验，
     * 只做去空白与丢弃空条目。</p>
     *
     * @throws IllegalArgumentException 任一条规则非法；调用方负责拒绝启动而不是部分接受
     */
    public static PermissionRuleSet parse(List<String> hardDeny, List<String> softDeny,
                                          List<String> allow, List<String> environment) {
        return new PermissionRuleSet(
                PermissionRule.parseAll(hardDeny),
                PermissionRule.parseAll(softDeny),
                PermissionRule.parseAll(allow),
                environment);
    }

    public boolean isEmpty() {
        return hardDeny.isEmpty() && softDeny.isEmpty() && allow.isEmpty() && environment.isEmpty();
    }

    /** 状态栏与授权状态视图用的单行摘要；无规则时返回空串，避免常驻占位。 */
    public String compactSummary() {
        if (isEmpty()) {
            return "";
        }
        StringBuilder summary = new StringBuilder();
        appendCount(summary, "hard_deny", hardDeny.size());
        appendCount(summary, "soft_deny", softDeny.size());
        appendCount(summary, "allow", allow.size());
        appendCount(summary, "environment", environment.size());
        return summary.toString();
    }

    private static void appendCount(StringBuilder target, String label, int count) {
        if (count == 0) {
            return;
        }
        if (!target.isEmpty()) {
            target.append(" · ");
        }
        target.append(label).append(' ').append(count);
    }

    /**
     * 命中所给工具的放行规则。
     *
     * <p>供调用方检查「这条规则到底会不会生效」：主机执行的命令必须单次人工确认，
     * 因此 {@code allow execute_command(...)} 在主机后端上不参与判定，加载期据此提示用户。</p>
     */
    public List<PermissionRule> allowRulesForTool(String toolName) {
        return allow.stream().filter(rule -> rule.matchesTool(toolName)).toList();
    }

}
