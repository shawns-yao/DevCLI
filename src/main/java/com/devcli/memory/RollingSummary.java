package com.devcli.memory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 滚动摘要的结构化模型：固定九段（对标 Claude Code {@code /compact} 模板的中文化）。
 *
 * <p>九段是历史压缩后的<b>投影视图</b>，不是任务状态源。固定顺序保证渲染稳定，
 * 既便于 LLM 按段更新，又利于 prompt cache 命中。
 *
 * <p>{@link #parse} 对 LLM 输出做 graceful degradation：缺段留空、乱序按标题归位、
 * 段外前导文本并入当前段；只认 {@link #SECTIONS} 里的标题，避免内容里的 {@code ##} 噪声误判。
 */
public class RollingSummary {

    /** 九段固定顺序。 */
    public static final List<String> SECTIONS = List.of(
            "主要请求与意图",
            "关键技术概念",
            "文件和代码",
            "踩过的坑和修复",
            "问题解决过程",
            "逐条用户消息",
            "待办任务",
            "当前在做什么",
            "下一步"
    );

    private final LinkedHashMap<String, String> sections;

    public RollingSummary() {
        this.sections = new LinkedHashMap<>();
        for (String s : SECTIONS) {
            sections.put(s, "");
        }
    }

    public String get(String section) {
        return sections.getOrDefault(section, "");
    }

    public void set(String section, String content) {
        if (SECTIONS.contains(section)) {
            sections.put(section, content == null ? "" : content.strip());
        }
    }

    public boolean isEmpty() {
        return sections.values().stream().allMatch(v -> v == null || v.isBlank());
    }

    /** 九段内容总字符数（不含标题），用于 GC 预算判断。 */
    public int totalChars() {
        int n = 0;
        for (String v : sections.values()) {
            if (v != null) {
                n += v.length();
            }
        }
        return n;
    }

    /**
     * 解析九段 Markdown。按 {@code ## <段名>} 切分；只认 {@link #SECTIONS} 里的标题，
     * 其它行（含非段名的 {@code ##} 行）并入当前段，首个段标题前的文本被丢弃。
     */
    public static RollingSummary parse(String markdown) {
        RollingSummary summary = new RollingSummary();
        if (markdown == null || markdown.isBlank()) {
            return summary;
        }
        String current = null;
        StringBuilder buf = new StringBuilder();
        for (String line : markdown.split("\n", -1)) {
            String heading = parseSectionHeading(line);
            if (heading != null) {
                if (current != null) {
                    summary.appendSection(current, buf.toString());
                }
                buf.setLength(0);
                current = heading;
            } else if (current != null) {
                buf.append(line).append("\n");
            }
        }
        if (current != null) {
            summary.appendSection(current, buf.toString());
        }
        return summary;
    }

    /** 识别 "## 段名" 标题行，返回匹配 {@link #SECTIONS} 的段名；非段标题返回 null。 */
    private static String parseSectionHeading(String line) {
        String trimmed = line.strip();
        if (!trimmed.startsWith("##")) {
            return null;
        }
        String name = trimmed.replaceFirst("^#+\\s*", "").trim();
        return SECTIONS.contains(name) ? name : null;
    }

    private void appendSection(String section, String content) {
        String trimmed = content.strip();
        if (trimmed.isEmpty()) {
            return;
        }
        String existing = sections.get(section);
        sections.put(section, existing == null || existing.isEmpty()
                ? trimmed
                : existing + "\n" + trimmed);
    }

    /**
     * 渲染成固定顺序的九段 Markdown。空段保留标题（结构稳定，利于 LLM 续写与 prompt cache），
     * 内容为空时标题下不留正文。
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sections.entrySet()) {
            sb.append("## ").append(e.getKey()).append("\n");
            String content = e.getValue();
            if (content != null && !content.isBlank()) {
                sb.append(content.strip()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().strip();
    }
}
