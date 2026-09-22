package com.devcli.policy;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次求值要用的规则全集：{@code deny} / {@code ask} / {@code allow} 三组。
 *
 * <p>三组的分工不重叠：{@code deny} 立即拒绝且不可被任何授权覆盖；{@code allow} 立即放行；
 * {@code ask} 强制人工审批。求值顺序见 {@link PermissionEvaluator}。</p>
 *
 * <p>不可变值对象。规则集为空是合法状态，表示「没有用户规则」，此时求值一律返回未决，
 * 由下游的模式基线策略接管——这也是关闭规则层的方式。</p>
 */
public record PermissionRuleSet(List<PermissionRule> deny,
                                List<PermissionRule> ask,
                                List<PermissionRule> allow) {

    /** 没有用户规则。 */
    public static final PermissionRuleSet EMPTY =
            new PermissionRuleSet(List.of(), List.of(), List.of());

    public PermissionRuleSet {
        deny = deny == null ? List.of() : List.copyOf(deny);
        ask = ask == null ? List.of() : List.copyOf(ask);
        allow = allow == null ? List.of() : List.copyOf(allow);
    }

    /**
     * 从原始规则文本解析三组规则。
     *
     * @throws IllegalArgumentException 任一条非法；调用方负责拒绝启动而不是部分接受
     */
    public static PermissionRuleSet parse(List<String> deny, List<String> ask, List<String> allow) {
        return new PermissionRuleSet(
                PermissionRule.parseAll(deny),
                PermissionRule.parseAll(ask),
                PermissionRule.parseAll(allow));
    }

    public boolean isEmpty() {
        return deny.isEmpty() && ask.isEmpty() && allow.isEmpty();
    }

    /** 状态栏与授权状态视图用的单行摘要；无规则时返回空串，避免常驻占位。 */
    public String compactSummary() {
        if (isEmpty()) {
            return "";
        }
        StringBuilder summary = new StringBuilder();
        appendCount(summary, "deny", deny.size());
        appendCount(summary, "ask", ask.size());
        appendCount(summary, "allow", allow.size());
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
     * 加载期告警：找出被更宽的 {@code allow} 规则覆盖、因而永远不会生效的 {@code ask} 规则。
     *
     * <p>{@link PermissionEvaluator} 按固定次序判定，{@code allow} 先于 {@code ask} 短路。因此
     * {@code allow write_file(src/**)} 加上 {@code ask write_file(src/secret/**)} 时，那条 ask 是
     * 死文本——用户以为自己声明了例外，实际没有。这个次序与 WorkBuddy 一致，不打算改；能做的是
     * 让沉默的失效变成显式的提示。</p>
     *
     * <p>检测是启发式的（同工具、或无 specifier、或 allow 的字面量前缀是 ask 的前缀），
     * 只用于提示。规则本身仍然照常求值，本方法不改变任何判定结果。</p>
     *
     * @return 可直接打印的中文提示行；没有可疑规则时返回空清单
     */
    public List<String> shadowedAskRules() {
        if (ask.isEmpty() || allow.isEmpty()) {
            return List.of();
        }
        List<String> warnings = new ArrayList<>();
        for (PermissionRule asked : ask) {
            for (PermissionRule allowed : allow) {
                if (covers(allowed, asked)) {
                    warnings.add("询问规则 " + asked.raw() + " 被放行规则 " + allowed.raw()
                            + " 覆盖，按 deny → allow → ask 的固定次序永远不会生效；"
                            + "要声明例外请改用 deny");
                    break;
                }
            }
        }
        return List.copyOf(warnings);
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

    private static boolean covers(PermissionRule allowed, PermissionRule asked) {
        if (!allowed.matchesTool(asked.tool())) {
            return false;
        }
        if (allowed.specifier() == null) {
            // 裸工具名覆盖该工具的全部调用
            return true;
        }
        if (allowed.specifier().equals(asked.specifier())) {
            // 同一条规则同时出现在 allow 与 ask，allow 先短路
            return true;
        }
        if (asked.specifier() == null) {
            // ask 比 allow 更宽，不可能被完全覆盖
            return false;
        }
        String prefix = allowed.literalPrefix();
        if (prefix.length() == allowed.specifier().length()) {
            // 无通配符的 allow 只覆盖它自己，相等的情形上面已判过
            return false;
        }
        if (prefix.isEmpty()) {
            // 通配符在首位时前缀没有信息量（**/secret/** 比 src/** 更窄），
            // 只有整条规则都是通配符才敢断定它覆盖全部
            return allowed.specifier().chars().allMatch(current -> current == '*');
        }
        return asked.specifier().startsWith(prefix);
    }
}
