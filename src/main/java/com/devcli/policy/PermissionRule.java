package com.devcli.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一条权限规则：{@code Tool} 或 {@code Tool(specifier)}。
 *
 * <p>规则表达的是用户意图，不是资源清单：{@code Edit(src/**)} 说的是「这一类改动放行」，
 * 而 {@link TaskGrant} 的资源维度说的是「这些资源可用」。两者不并存——资源维度授权是
 * 规则语法的一个特例，统一到本类后只保留一套匹配语义。</p>
 *
 * <p>三条硬约束：</p>
 * <ul>
 *   <li>specifier 只对已声明资源槽的工具有效。给没有资源槽的工具（如 MCP 工具）写
 *       specifier 会在解析时直接拒绝，不留一条永远不匹配的规则。</li>
 *   <li>工具名可用 DevCLI 原生名（{@code write_file}）或 WorkBuddy 风格别名
 *       （{@code Edit}），别名在解析时归一到原生名。</li>
 *   <li>命令类规则按 {@code &&} / {@code ||} / {@code ;} / {@code |} 拆分后逐段判定。
 *       拆分不做引号感知，因此可能把带引号的片段切错——两个方向都只会更严格，不会放宽。</li>
 * </ul>
 */
public record PermissionRule(String tool, String specifier, String raw) {

    private static final Pattern CALL_FORM = Pattern.compile("^([A-Za-z0-9_*]+)\\s*\\((.*)\\)$");
    private static final Pattern COMMAND_SEPARATOR = Pattern.compile("&&|\\|\\||;|\\|");
    private static final Pattern REDIRECTION = Pattern.compile("[<>]");
    private static final String ANY_TOOL = "*";
    private static final String COMMAND_PREFIX_SUFFIX = ":*";
    private static final String DOMAIN_PREFIX = "domain:";

    /** WorkBuddy 风格别名 → DevCLI 原生工具名；原生名本身就是合法写法。 */
    private static final Map<String, String> ALIASES = Map.of(
            "edit", "edit_file",
            "write", "write_file",
            "read", "read_file",
            "bash", "execute_command",
            "webfetch", "web_fetch",
            "websearch", "web_search");

    public PermissionRule {
        if (tool == null || tool.isBlank()) {
            throw new IllegalArgumentException("权限规则缺少工具名: " + raw);
        }
        specifier = specifier == null || specifier.isBlank() ? null : specifier.trim();
    }

    /**
     * 解析一条规则文本。
     *
     * @throws IllegalArgumentException 工具名为空、括号内为空、通配符位置非法、
     *                                  或给没有资源槽的工具写了 specifier
     */
    public static PermissionRule parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("权限规则不能为空");
        }
        String text = raw.trim();
        Matcher call = CALL_FORM.matcher(text);
        if (!call.matches()) {
            return new PermissionRule(normalizeTool(text, text), null, text);
        }
        String tool = normalizeTool(call.group(1), text);
        String specifier = call.group(2).trim();
        if (specifier.isEmpty()) {
            throw new IllegalArgumentException("权限规则括号内不能为空: " + text);
        }
        ToolResourceSlot slot = ToolResourceSlot.of(tool);
        if (!slot.specifiable()) {
            throw new IllegalArgumentException("工具 " + tool + " 没有声明参数级资源槽，"
                    + "规则只能写成 " + tool + "（不带括号）: " + text);
        }
        requireValidSpecifier(slot, specifier, text);
        return new PermissionRule(tool, specifier, text);
    }

    /** 批量解析；任一条非法立即抛出，不做部分接受。 */
    public static List<PermissionRule> parseAll(List<String> rawRules) {
        List<PermissionRule> rules = new ArrayList<>();
        for (String raw : rawRules == null ? List.<String>of() : rawRules) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            rules.add(parse(raw));
        }
        return List.copyOf(rules);
    }

    /** 工具名是否命中：{@code *} 匹配全部，尾随 {@code *} 是前缀匹配，其余忽略大小写相等。 */
    public boolean matchesTool(String toolName) {
        String name = toolName == null ? "" : toolName.trim();
        if (ANY_TOOL.equals(tool)) {
            return true;
        }
        if (tool.endsWith(ANY_TOOL)) {
            String prefix = tool.substring(0, tool.length() - 1);
            return name.regionMatches(true, 0, prefix, 0, prefix.length());
        }
        return tool.equalsIgnoreCase(name);
    }

    /**
     * hard_deny / soft_deny 语义：任一子命令或任一资源命中即算命中。
     *
     * @param values 由 {@code ApprovalGate} 按资源槽归一化后的资源值；
     *               {@code null} 表示无法解析，此时带 specifier 的规则一律不命中
     */
    public boolean triggersOn(ToolResourceSlot slot, List<String> values) {
        if (specifier == null) {
            return true;
        }
        if (values == null || values.isEmpty()) {
            return false;
        }
        return switch (slot) {
            case PATH, PROJECT_NAME -> matchesGlob(values.get(0));
            case PATH_LIST -> values.stream().anyMatch(this::matchesGlob);
            case COMMAND -> segmentsOf(values.get(0)).stream()
                    .anyMatch(segment -> matchesCommand(segment, false));
            case HOST -> matchesHost(values.get(0));
            case NONE -> false;
        };
    }

    /**
     * allow 例外语义：全部子命令与全部资源都必须命中。
     *
     * <p>命令含重定向（{@code >} / {@code <}）时通配符不生效，只接受精确匹配：重定向能改写的
     * 目标不在命令文本的资源槽里，通配放行会顺带授权一个未声明的写入目标。</p>
     */
    public boolean allows(ToolResourceSlot slot, List<String> values) {
        if (specifier == null) {
            return true;
        }
        if (values == null || values.isEmpty()) {
            return false;
        }
        return switch (slot) {
            case PATH, PROJECT_NAME -> matchesGlob(values.get(0));
            case PATH_LIST -> values.stream().allMatch(this::matchesGlob);
            case COMMAND -> {
                List<String> segments = segmentsOf(values.get(0));
                boolean strict = REDIRECTION.matcher(values.get(0)).find();
                yield !segments.isEmpty()
                        && segments.stream().allMatch(segment -> matchesCommand(segment, strict));
            }
            case HOST -> matchesHost(values.get(0));
            case NONE -> false;
        };
    }

    private boolean matchesGlob(String value) {
        return value != null && !value.isBlank() && WriteGlobSet.matches(List.of(specifier), value);
    }

    private boolean matchesCommand(String segment, boolean strictWildcards) {
        String command = segment == null ? "" : segment.trim().replaceAll("\\s+", " ");
        if (command.isEmpty()) {
            return false;
        }
        String pattern = specifier.replaceAll("\\s+", " ");
        boolean prefixForm = pattern.endsWith(COMMAND_PREFIX_SUFFIX) || pattern.endsWith(" *");
        if (!pattern.contains(ANY_TOOL) && !prefixForm) {
            return command.equals(pattern);
        }
        if (strictWildcards) {
            // 重定向能改写命令文本之外的写入目标，含重定向时任何通配形态都不生效
            return false;
        }
        if (prefixForm) {
            int cut = pattern.endsWith(COMMAND_PREFIX_SUFFIX)
                    ? COMMAND_PREFIX_SUFFIX.length()
                    : 2;
            String prefix = pattern.substring(0, pattern.length() - cut).trim();
            return !prefix.isEmpty()
                    && (command.equals(prefix) || command.startsWith(prefix + " "));
        }
        return globMatches(pattern, command);
    }

    private boolean matchesHost(String value) {
        String host = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            return false;
        }
        String domain = specifier.trim().toLowerCase(Locale.ROOT);
        if (domain.startsWith(DOMAIN_PREFIX)) {
            domain = domain.substring(DOMAIN_PREFIX.length()).trim();
        }
        if (domain.isEmpty()) {
            return false;
        }
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static List<String> segmentsOf(String command) {
        List<String> segments = new ArrayList<>();
        for (String part : COMMAND_SEPARATOR.split(command == null ? "" : command)) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                segments.add(trimmed);
            }
        }
        return segments;
    }

    private static boolean globMatches(String pattern, String text) {
        StringBuilder regex = new StringBuilder("^");
        int index = 0;
        while (index < pattern.length()) {
            char current = pattern.charAt(index);
            if (current == '*') {
                regex.append(".*");
                index++;
            } else if (current == '?') {
                regex.append('.');
                index++;
            } else {
                int start = index;
                while (index < pattern.length()
                        && pattern.charAt(index) != '*' && pattern.charAt(index) != '?') {
                    index++;
                }
                regex.append(Pattern.quote(pattern.substring(start, index)));
            }
        }
        return Pattern.compile(regex.append('$').toString()).matcher(text).matches();
    }

    private static String normalizeTool(String name, String raw) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("权限规则缺少工具名: " + raw);
        }
        int star = trimmed.indexOf('*');
        if (star >= 0 && star != trimmed.length() - 1) {
            throw new IllegalArgumentException("通配符只能出现在工具名末尾（如 mcp__*）: " + raw);
        }
        String alias = ALIASES.get(trimmed.toLowerCase(Locale.ROOT));
        return alias == null ? trimmed : alias;
    }

    private static void requireValidSpecifier(ToolResourceSlot slot, String specifier, String raw) {
        switch (slot) {
            case PATH, PATH_LIST, PROJECT_NAME -> WriteGlobSet.requireProjectRelativeGlob(specifier);
            case HOST -> {
                String domain = specifier.toLowerCase(Locale.ROOT);
                if (domain.startsWith(DOMAIN_PREFIX)) {
                    domain = domain.substring(DOMAIN_PREFIX.length()).trim();
                }
                if (domain.isEmpty() || domain.contains("/") || domain.contains(":")
                        || domain.contains("*") || domain.contains("@") || domain.contains(" ")
                        || domain.startsWith(".")) {
                    throw new IllegalArgumentException(
                            "网络规则只接受裸域名或 domain: 前缀（不含 scheme/路径/端口/通配符）: " + raw);
                }
            }
            default -> {
                // COMMAND 的 specifier 形态由匹配阶段解释，解析阶段不做额外约束
            }
        }
    }
}
