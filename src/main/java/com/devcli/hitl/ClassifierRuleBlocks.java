package com.devcli.hitl;

import com.devcli.policy.PermissionRule;
import com.devcli.policy.PermissionRuleSet;

import java.util.List;

/**
 * 把用户规则渲染成分类器提示词里的四段文本。
 *
 * <p>参照实现的分类器提示词是模板：四类用户规则各有自己的占位符，且<b>注入位置本身就携带语义</b>——
 * 硬阻止规则落在「无条件阻止」段、软阻止规则落在「可被用户意图清除」段、允许例外落在「命中必须放行」
 * 段、环境事实落在「定义与事实」段。位置错了，规则的意思就变了：把 {@code allow} 规则拼到软阻止段里，
 * 模型会把它当成一条要拦的规则。所以这里不生成一整块「用户规则」文本，而是按类分段。</p>
 *
 * <p>空类目渲染成一句显式的「未设置」而不是空串。空段在模型看来有歧义——「用户没写规则」和
 * 「这段没填进来」长得一样，后者会让模型以为占位符没被替换而忽略整段。</p>
 *
 * <p>规则文本来自用户自己的配置文件，与提示词同级可信，因此不做中和。但两处结构性处理是必需的：
 * 规则内部的空白折叠成单个空格（一条规则天然是单行，多行会撑破列表结构），以及把占位符同名文本
 * 转义（否则后一次替换会命中前一段注入进来的内容，等于规则文本可以改写提示词结构）。</p>
 */
record ClassifierRuleBlocks(String hardDeny, String softDeny, String allow, String environment) {

    static final String HARD_DENY_SLOT = "<user_hard_deny_rules>";
    static final String SOFT_DENY_SLOT = "<user_soft_deny_rules>";
    static final String ALLOW_SLOT = "<user_allow_rules>";
    static final String ENVIRONMENT_SLOT = "<user_environment>";

    /** 提示词里必须出现的四个占位符；缺任何一个都意味着有一类规则读不到。 */
    static final List<String> SLOTS =
            List.of(HARD_DENY_SLOT, SOFT_DENY_SLOT, ALLOW_SLOT, ENVIRONMENT_SLOT);

    static ClassifierRuleBlocks render(PermissionRuleSet rules) {
        PermissionRuleSet effective = rules == null ? PermissionRuleSet.EMPTY : rules;
        return new ClassifierRuleBlocks(
                renderRules(effective.hardDeny(), "（未设置用户硬阻止规则，只有内置安全边界生效）"),
                renderRules(effective.softDeny(), "（未设置用户软阻止规则）"),
                renderRules(effective.allow(), "（未设置用户允许例外）"),
                renderEnvironment(effective.environment()));
    }

    private static String renderRules(List<PermissionRule> rules, String emptyNotice) {
        if (rules.isEmpty()) {
            return emptyNotice;
        }
        StringBuilder text = new StringBuilder();
        for (PermissionRule rule : rules) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            String normalized = rule.specifier() == null
                    ? rule.tool()
                    : rule.tool() + "(" + rule.specifier() + ")";
            text.append("- ").append(sanitize(normalized));
        }
        return text.toString();
    }

    private static String renderEnvironment(List<String> entries) {
        if (entries.isEmpty()) {
            return "（用户未声明环境事实；无法确认的共享资源一律按共享处理）";
        }
        StringBuilder text = new StringBuilder();
        for (String entry : entries) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append("- ").append(sanitize(entry));
        }
        return text.toString();
    }

    /**
     * 折叠空白并把四个占位符转义。
     *
     * <p>转义用插反斜杠而不是删除原文，与 {@link TrustedIntentContext#neutralize} 同一处置方式：
     * 用户能看出自己的文本被处理过，而不是发现内容凭空少了一段。</p>
     */
    private static String sanitize(String text) {
        String collapsed = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        for (String slot : SLOTS) {
            collapsed = collapsed.replace(slot, "\\" + slot);
        }
        return collapsed;
    }
}
