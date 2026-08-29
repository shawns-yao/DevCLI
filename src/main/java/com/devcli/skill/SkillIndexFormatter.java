package com.devcli.skill;

import com.devcli.memory.MemoryEntry;

import java.util.List;

/**
 * 把启用 skill 渲染成 system prompt 索引段。
 *
 * 预算约束：
 * - 单条 description ≤ 500 codepoint
 * - Skill 数量不设固定上限，按任务相关排序后在 Token 预算内尽量纳入
 * - 默认预算用于兼容旧调用；生产调用由 ContextProfile 按模型窗口给出
 *
 * 注入位置：每个 Agent / SubAgent 的 system prompt 末尾，独立段。
 */
public final class SkillIndexFormatter {

    public static final int MAX_DESCRIPTION_CODEPOINTS = 500;
    public static final int DEFAULT_INDEX_TOKENS = 1_024;

    private SkillIndexFormatter() {
    }

    public static String format(List<Skill> enabled) {
        return format(enabled, DEFAULT_INDEX_TOKENS);
    }

    public static String format(List<Skill> enabled, int tokenBudget) {
        return formatWithMetrics(enabled, tokenBudget).text();
    }

    public static FormatResult formatWithMetrics(List<Skill> enabled, int tokenBudget) {
        if (enabled == null || enabled.isEmpty()) {
            return new FormatResult("", 0, 0, 0);
        }

        int safeBudget = Math.max(64, tokenBudget);

        StringBuilder sb = new StringBuilder();
        sb.append("## 可用 Skills（按需调用 load_skill 加载完整指引）\n\n");

        String footer = safeBudget >= 128
                ? "\n判断准则：任务匹配时调用 load_skill(name)；正文进入下一轮会话历史。上下文压缩后若摘要不足，可以再次调用 load_skill，并用 page 继续读取。\n"
                : "\n任务匹配时调用 load_skill(name)，需要时可再次加载。\n";
        int included = 0;
        for (Skill skill : enabled) {
            String desc = truncateByCodepoint(
                    skill.description().replaceAll("\\s+", " ").trim(), MAX_DESCRIPTION_CODEPOINTS);
            String line = "- **" + skill.name() + "**：" + desc + '\n';
            if (MemoryEntry.estimateTokens(sb + line + footer) > safeBudget) {
                continue;
            }
            sb.append(line);
            included++;
        }
        if (included == 0) {
            Skill first = enabled.getFirst();
            String minimal = "## 可用 Skills\n- **" + first.name() + "**\n" + footer;
            String text = truncateToTokenBudget(minimal, safeBudget);
            return new FormatResult(text, 1, Math.max(0, enabled.size() - 1),
                    MemoryEntry.estimateTokens(text));
        }
        sb.append(footer);
        String text = sb.toString();
        return new FormatResult(text, included, Math.max(0, enabled.size() - included),
                MemoryEntry.estimateTokens(text));
    }

    private static String truncateToTokenBudget(String value, int tokenBudget) {
        if (MemoryEntry.estimateTokens(value) <= tokenBudget) return value;
        int low = 0;
        int high = value.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (MemoryEntry.estimateTokens(value.substring(0, mid)) <= tokenBudget) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return value.substring(0, low);
    }

    static String truncateByCodepoint(String s, int limit) {
        if (s == null) return "";
        if (s.codePointCount(0, s.length()) <= limit) return s;
        StringBuilder sb = new StringBuilder();
        int count = 0;
        int i = 0;
        while (i < s.length() && count < limit) {
            int cp = s.codePointAt(i);
            sb.appendCodePoint(cp);
            i += Character.charCount(cp);
            count++;
        }
        return sb.toString() + "...";
    }

    public record FormatResult(String text, int includedCount, int omittedCount, int estimatedTokens) {
    }
}
