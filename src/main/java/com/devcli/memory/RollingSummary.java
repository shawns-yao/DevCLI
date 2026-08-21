package com.devcli.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/** 固定九段的滚动摘要。九段负责信息分类，生命周期负责条目演进。 */
public class RollingSummary {

    public static final List<String> SECTIONS = List.of(
            "主要请求与意图", "关键技术概念", "文件和代码", "踩过的坑和修复", "问题解决过程",
            "逐条用户消息", "待办任务", "当前在做什么", "下一步"
    );

    private static final String ITEM_PREFIX = "<!-- summary-item ";
    private static final String ITEM_SUFFIX = " -->";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final LinkedHashMap<String, List<SummaryItem>> sections = new LinkedHashMap<>();

    public RollingSummary() {
        for (String section : SECTIONS) {
            sections.put(section, new ArrayList<>());
        }
    }

    public String get(String section) {
        return items(section).stream()
                .filter(SummaryItem::isVisible)
                .map(SummaryItem::content)
                .filter(content -> !content.isBlank())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    /** 兼容旧调用：整段替换为一个带默认生命周期的条目。 */
    public void set(String section, String content) {
        if (!SECTIONS.contains(section)) {
            return;
        }
        sections.get(section).clear();
        String normalized = content == null ? "" : content.strip();
        if (!normalized.isEmpty()) {
            addItem(SummaryItem.create(section, legacySubject(section, normalized), normalized,
                    defaultLifecycle(section), defaultImportance(section), List.of()));
        }
    }

    public void addItem(SummaryItem item) {
        if (item != null && SECTIONS.contains(item.section())) {
            sections.get(item.section()).add(item);
        }
    }

    public List<SummaryItem> items(String section) {
        List<SummaryItem> values = sections.get(section);
        return values == null ? List.of() : List.copyOf(values);
    }

    public List<SummaryItem> allItems() {
        return sections.values().stream().flatMap(List::stream).toList();
    }

    public Optional<SummaryItem> findItem(String section, String subject) {
        List<SummaryItem> values = sections.get(section);
        if (values == null) {
            return Optional.empty();
        }
        for (int i = values.size() - 1; i >= 0; i--) {
            if (values.get(i).subject().equals(subject)) {
                return Optional.of(values.get(i));
            }
        }
        return Optional.empty();
    }

    void replaceItem(SummaryItem current, SummaryItem replacement) {
        List<SummaryItem> values = sections.get(current.section());
        int index = values.indexOf(current);
        if (index >= 0) {
            values.set(index, replacement);
        }
    }

    void removeItems(Predicate<SummaryItem> predicate) {
        sections.values().forEach(items -> items.removeIf(predicate));
    }

    public boolean isEmpty() {
        return allItems().stream().noneMatch(SummaryItem::isVisible);
    }

    /** 可见内容总字符数，不把已覆盖或过期事实计入 Prompt 预算。 */
    public int totalChars() {
        return SECTIONS.stream().mapToInt(section -> get(section).length()).sum();
    }

    public static RollingSummary parse(String markdown) {
        RollingSummary summary = new RollingSummary();
        if (markdown == null || markdown.isBlank()) {
            return summary;
        }
        String currentSection = null;
        ItemMetadata currentMetadata = null;
        StringBuilder content = new StringBuilder();
        for (String line : markdown.split("\n", -1)) {
            String heading = parseSectionHeading(line);
            if (heading != null) {
                flush(summary, currentSection, currentMetadata, content);
                currentSection = heading;
                currentMetadata = null;
                content.setLength(0);
                continue;
            }
            if (currentSection == null) {
                continue;
            }
            ItemMetadata metadata = parseMetadata(line);
            if (metadata != null) {
                flush(summary, currentSection, currentMetadata, content);
                currentMetadata = metadata;
                content.setLength(0);
            } else {
                content.append(line).append('\n');
            }
        }
        flush(summary, currentSection, currentMetadata, content);
        return summary;
    }

    private static void flush(RollingSummary summary, String section,
                              ItemMetadata metadata, StringBuilder content) {
        if (section == null) {
            return;
        }
        String body = content.toString().strip();
        if (metadata == null) {
            if (!body.isEmpty()) {
                summary.set(section, body);
            }
            return;
        }
        String storedContent = metadata.lifecycle == SummaryItem.Lifecycle.SUPERSEDED
                || metadata.lifecycle == SummaryItem.Lifecycle.EXPIRED ? "" : body;
        summary.addItem(new SummaryItem(metadata.id, section, metadata.subject, storedContent,
                metadata.lifecycle, metadata.importance, metadata.revision,
                metadata.compactionCount, metadata.supersededBy, metadata.evidenceRefs));
    }

    private static String parseSectionHeading(String line) {
        String trimmed = line.strip();
        if (!trimmed.startsWith("##")) {
            return null;
        }
        String name = trimmed.replaceFirst("^#+\\s*", "").trim();
        return SECTIONS.contains(name) ? name : null;
    }

    private static ItemMetadata parseMetadata(String line) {
        String trimmed = line.strip();
        if (!trimmed.startsWith(ITEM_PREFIX) || !trimmed.endsWith(ITEM_SUFFIX)) {
            return null;
        }
        String json = trimmed.substring(ITEM_PREFIX.length(), trimmed.length() - ITEM_SUFFIX.length());
        try {
            return MAPPER.readValue(json, ItemMetadata.class);
        } catch (JsonProcessingException | IllegalArgumentException ignored) {
            return null;
        }
    }

    /** 已覆盖事实只输出审计元数据，不把旧值重新注入 Prompt。 */
    public String render() {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, List<SummaryItem>> entry : sections.entrySet()) {
            result.append("## ").append(entry.getKey()).append('\n');
            for (SummaryItem item : entry.getValue()) {
                result.append(ITEM_PREFIX).append(metadataJson(item)).append(ITEM_SUFFIX).append('\n');
                if (item.isVisible() && !item.content().isBlank()) {
                    result.append(item.content().strip()).append('\n');
                }
            }
            result.append('\n');
        }
        return result.toString().strip();
    }

    private static String metadataJson(SummaryItem item) {
        ItemMetadata metadata = new ItemMetadata(item.id(), item.subject(), item.lifecycle(),
                item.importance(), item.revision(), item.compactionCount(), item.supersededBy(),
                item.evidenceRefs());
        try {
            return MAPPER.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法序列化摘要条目", e);
        }
    }

    static SummaryItem.Lifecycle defaultLifecycle(String section) {
        return switch (section) {
            case "主要请求与意图", "关键技术概念" -> SummaryItem.Lifecycle.STABLE;
            case "待办任务", "下一步" -> SummaryItem.Lifecycle.UNRESOLVED;
            case "当前在做什么", "逐条用户消息" -> SummaryItem.Lifecycle.ACTIVE;
            default -> SummaryItem.Lifecycle.RESOLVED;
        };
    }

    private static int defaultImportance(String section) {
        return switch (section) {
            case "主要请求与意图", "待办任务", "当前在做什么", "下一步" -> 90;
            case "文件和代码", "踩过的坑和修复", "关键技术概念" -> 70;
            default -> 50;
        };
    }

    private static String legacySubject(String section, String content) {
        return "legacy:" + SummaryItem.create(section, "legacy", content,
                defaultLifecycle(section), defaultImportance(section), List.of()).id();
    }

    private record ItemMetadata(String id, String subject, SummaryItem.Lifecycle lifecycle,
                                int importance, int revision, int compactionCount,
                                String supersededBy, List<String> evidenceRefs) {
    }
}
