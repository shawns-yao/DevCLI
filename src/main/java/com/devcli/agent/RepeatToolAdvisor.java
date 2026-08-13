package com.devcli.agent;

import com.devcli.tool.ToolInvocationFingerprint;
import com.devcli.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 连续重复工具调用的 advisory 分层提醒。
 *
 * 设计参考 DeepSeek Harness 的 repeat-tool-reminder guard：检测"连续相同工具名 + 相同
 * 规范化参数"的调用，到达阈值（默认 [3, 5, 8]）时向模型注入提醒消息，**不阻断、不改写**
 * 任何工具调用，只做前置提示，让模型先意识到重复再自行换策略；最终死循环兜底仍由
 * {@link AgentBudget} 的停滞检测 / 重复错误熔断负责。
 *
 * 语义约定：
 * <ul>
 *   <li>单条活跃链：当前调用与上一次指纹相同则计数 +1，否则重置为新链。同一轮多个工具
 *       结果按序逐个观察，与 dsh 的 post-execute 计数时机一致。</li>
 *   <li>规范化复用 {@link ToolInvocationFingerprint}（deep key-sort + NFKC + 路径/大小写
 *       归一化），字段顺序差异、Unicode 等价字符、冗余空白不会误判。</li>
 *   <li>成功、失败、被拒绝的工具结果一律计数，挂在工具执行结果返回后。</li>
 *   <li>include / exclude 支持 {@code *} 通配符；命中 exclude 或不命中 include 的工具
 *       透明处理：既不计数也不重置当前链。</li>
 *   <li>每个引擎实例（一次 agent run）持有一个 advisor，天然按 agent 隔离检测链。</li>
 * </ul>
 *
 * 配置（系统属性）：
 * <ul>
 *   <li>{@code devcli.repeat.tool.reminder.enabled}：总开关，默认 true</li>
 *   <li>{@code devcli.repeat.tool.thresholds}：逗号分隔阈值，默认 {@code 3,5,8}</li>
 *   <li>{@code devcli.repeat.tool.arguments.preview.chars}：详细提醒参数预览上限，默认 500</li>
 *   <li>{@code devcli.repeat.tool.include} / {@code devcli.repeat.tool.exclude}：通配符白/黑名单</li>
 * </ul>
 */
public final class RepeatToolAdvisor {

    /**
     * 一次触发的提醒。gentle=true 表示第一个阈值的温和提醒，其余为详细提醒。
     */
    public record Reminder(String toolName, int consecutiveCount, String argumentsPreview, boolean gentle) {
        public String text() {
            if (gentle) {
                return "系统提醒：你正在以完全相同的参数重复调用工具 " + toolName
                        + "（已连续 " + consecutiveCount + " 次）。请先仔细分析上一次工具结果："
                        + "如果任务尚未完成，请更换方法或调整参数，而不是继续重复相同调用。";
            }
            return "系统提醒：检测到重复工具调用：\n"
                    + "- 工具: " + toolName + "\n"
                    + "- 连续调用次数: " + consecutiveCount + "\n"
                    + "- 参数: " + argumentsPreview + "\n\n"
                    + "这些重复调用没有取得进展。请不要再以相同参数调用该工具。"
                    + "请检查最近一次工具结果，选择不同的行动、不同的参数，或在证据已足够时结束任务。";
        }
    }

    private static final List<Integer> DEFAULT_THRESHOLDS = List.of(3, 5, 8);
    private static final int DEFAULT_ARGUMENTS_PREVIEW_CHARS = 500;
    private static final String ENABLED_PROPERTY = "devcli.repeat.tool.reminder.enabled";
    private static final String THRESHOLDS_PROPERTY = "devcli.repeat.tool.thresholds";
    private static final String PREVIEW_CHARS_PROPERTY = "devcli.repeat.tool.arguments.preview.chars";
    private static final String INCLUDE_PROPERTY = "devcli.repeat.tool.include";
    private static final String EXCLUDE_PROPERTY = "devcli.repeat.tool.exclude";

    private final List<Integer> thresholds;
    private final int argumentsPreviewChars;
    private final List<Pattern> includePatterns;
    private final List<Pattern> excludePatterns;
    private final boolean enabled;

    private String currentFingerprint = "";
    private int consecutiveCount;

    public RepeatToolAdvisor(List<Integer> thresholds, int argumentsPreviewChars,
                             List<String> include, List<String> exclude) {
        this(true, thresholds, argumentsPreviewChars, include, exclude);
    }

    private RepeatToolAdvisor(boolean enabled, List<Integer> thresholds, int argumentsPreviewChars,
                              List<String> include, List<String> exclude) {
        this.enabled = enabled;
        this.thresholds = normalizeThresholds(thresholds);
        this.argumentsPreviewChars = argumentsPreviewChars > 0
                ? argumentsPreviewChars : DEFAULT_ARGUMENTS_PREVIEW_CHARS;
        this.includePatterns = compilePatterns(include);
        this.excludePatterns = compilePatterns(exclude);
    }

    public static RepeatToolAdvisor fromSystemProperties() {
        if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"))) {
            return disabled();
        }
        List<Integer> thresholds = parseThresholds(System.getProperty(THRESHOLDS_PROPERTY));
        int previewChars = readPositiveInt(PREVIEW_CHARS_PROPERTY, DEFAULT_ARGUMENTS_PREVIEW_CHARS);
        List<String> include = splitPatterns(System.getProperty(INCLUDE_PROPERTY));
        List<String> exclude = splitPatterns(System.getProperty(EXCLUDE_PROPERTY));
        return new RepeatToolAdvisor(thresholds, previewChars, include, exclude);
    }

    /** 完全禁用的实例：不计数、不提醒、不参与停滞兜底协调。 */
    public static RepeatToolAdvisor disabled() {
        return new RepeatToolAdvisor(false, DEFAULT_THRESHOLDS, DEFAULT_ARGUMENTS_PREVIEW_CHARS,
                List.of(), List.of());
    }

    /**
     * 观察一次工具执行结果并返回是否需要注入提醒；未触发时返回 null。
     * 该观察同时维护连续重复链，供 {@link #suspendsStagnationExit()} 判断停滞兜底是否暂缓。
     */
    public Reminder observeAndMaybeRemind(ToolRegistry.ToolExecutionResult result) {
        if (!enabled || result == null || result.name() == null || result.name().isBlank()) {
            return null;
        }
        String toolName = result.name();
        if (!tracked(toolName)) {
            return null;
        }
        String fingerprint = ToolInvocationFingerprint.of(toolName, result.argumentsJson());
        if (fingerprint.equals(currentFingerprint)) {
            consecutiveCount++;
        } else {
            currentFingerprint = fingerprint;
            consecutiveCount = 1;
        }
        int thresholdIndex = thresholds.indexOf(consecutiveCount);
        if (thresholdIndex < 0) {
            return null;
        }
        String preview = ToolInvocationFingerprint.canonicalArguments(result.argumentsJson());
        return new Reminder(toolName, consecutiveCount,
                truncate(preview, argumentsPreviewChars), thresholdIndex == 0);
    }

    /**
     * 当前是否仍处于连续重复链上且未超过最大提醒阈值。
     * 为 true 时调用方应暂缓停滞检测退出，把自我纠正机会留给 advisory 提醒；
     * 超过最大阈值后返回 false，由停滞检测作为最终兜底熔断。
     */
    public boolean suspendsStagnationExit() {
        return enabled && consecutiveCount > 1 && consecutiveCount <= maxThreshold();
    }

    public int maxThreshold() {
        return thresholds.get(thresholds.size() - 1);
    }

    /** 当前连续重复次数，供诊断与测试。 */
    public int consecutiveCount() {
        return consecutiveCount;
    }

    private boolean tracked(String toolName) {
        if (!excludePatterns.isEmpty() && matchesAny(excludePatterns, toolName)) {
            return false;
        }
        return includePatterns.isEmpty() || matchesAny(includePatterns, toolName);
    }

    private static boolean matchesAny(List<Pattern> patterns, String value) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(value).matches()) {
                return true;
            }
        }
        return false;
    }

    private static List<Integer> normalizeThresholds(List<Integer> thresholds) {
        if (thresholds == null || thresholds.isEmpty()) {
            return DEFAULT_THRESHOLDS;
        }
        List<Integer> distinct = new ArrayList<>(thresholds.stream()
                .filter(v -> v != null && v >= 2)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList());
        return distinct.isEmpty() ? DEFAULT_THRESHOLDS : distinct;
    }

    private static List<Integer> parseThresholds(String raw) {
        List<Integer> values = new ArrayList<>();
        if (raw != null && !raw.isBlank()) {
            for (String part : raw.split(",")) {
                try {
                    values.add(Integer.parseInt(part.trim()));
                } catch (NumberFormatException ignored) {
                    // 跳过非法片段，其余阈值继续生效
                }
            }
        }
        return normalizeThresholds(values);
    }

    private static List<String> splitPatterns(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> patterns = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                patterns.add(trimmed);
            }
        }
        return patterns;
    }

    private static int readPositiveInt(String property, int defaultValue) {
        String raw = System.getProperty(property);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 通配符 {@code *} 编译为锚定正则，其余元字符按字面匹配。 */
    private static List<Pattern> compilePatterns(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return List.of();
        }
        List<Pattern> compiled = new ArrayList<>();
        for (String pattern : patterns) {
            String escaped = pattern.replaceAll("([|\\\\{}\\[\\]()^$+?.])", "\\\\$1");
            compiled.add(Pattern.compile("^" + escaped.replace("*", ".*") + "$"));
        }
        return compiled;
    }

    private static String truncate(String value, int maxChars) {
        if (value == null) {
            return value;
        }
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= maxChars) {
            return value;
        }
        int end = value.offsetByCodePoints(0, maxChars);
        return value.substring(0, end) + "...(+"
                + (codePoints - maxChars) + " chars)";
    }
}
