package com.devcli.memory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 长期记忆的只读 Markdown 审计视图。
 *
 * <p>定位：SQLite（{@link LongTermMemory}）是唯一权威事实源，本类只把自动学习的记忆
 * 渲染成人可打开、可审计的 Markdown 快照——<b>单向导出，不回写、不双写</b>。
 * 用户要删改仍走 {@code /memory forget} 等正式入口，编辑导出文件不影响记忆库。</p>
 */
public final class MemoryAuditReport {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
                    .withZone(ZoneId.systemDefault());

    /** 类型展示顺序：稳定事实/反馈优先，工具结果与对话靠后。 */
    private static final List<MemoryEntry.MemoryType> TYPE_ORDER = List.of(
            MemoryEntry.MemoryType.FACT,
            MemoryEntry.MemoryType.FEEDBACK,
            MemoryEntry.MemoryType.SUMMARY,
            MemoryEntry.MemoryType.CONVERSATION,
            MemoryEntry.MemoryType.TOOL_RESULT);

    private static final Map<MemoryEntry.MemoryType, String> TYPE_TITLE = Map.of(
            MemoryEntry.MemoryType.FACT, "事实 / 偏好",
            MemoryEntry.MemoryType.FEEDBACK, "反馈",
            MemoryEntry.MemoryType.SUMMARY, "摘要",
            MemoryEntry.MemoryType.CONVERSATION, "对话",
            MemoryEntry.MemoryType.TOOL_RESULT, "工具结果");

    private MemoryAuditReport() {
    }

    public static String render(List<MemoryEntry> entries, Instant generatedAt) {
        List<MemoryEntry> safe = entries == null ? List.of() : entries;
        StringBuilder out = new StringBuilder();
        out.append("# DevCLI 长期记忆审计快照\n\n");
        out.append("> 本文件是**只读导出**，权威来源为本地 SQLite 记忆库；编辑本文件不会回写。\n")
                .append("> 删除请用 `/memory forget <id>`，生成时间 ")
                .append(generatedAt == null ? "-" : TIME_FMT.format(generatedAt))
                .append("，共 ").append(safe.size()).append(" 条。\n\n");

        Map<MemoryEntry.MemoryType, List<MemoryEntry>> byType = safe.stream()
                .collect(Collectors.groupingBy(MemoryEntry::getType));

        for (MemoryEntry.MemoryType type : TYPE_ORDER) {
            List<MemoryEntry> group = byType.getOrDefault(type, List.of()).stream()
                    .sorted(Comparator
                            .comparing(MemoryEntry::isActive).reversed()
                            .thenComparing(MemoryEntry::getTimestamp,
                                    Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
            if (group.isEmpty()) {
                continue;
            }
            out.append("## ").append(TYPE_TITLE.getOrDefault(type, type.name()))
                    .append("（").append(group.size()).append("）\n\n");
            for (MemoryEntry entry : group) {
                appendEntry(out, entry);
            }
        }
        return out.toString();
    }

    private static void appendEntry(StringBuilder out, MemoryEntry entry) {
        String subject = entry.getSubject();
        out.append("### `").append(entry.getId()).append('`');
        if (subject != null && !subject.isBlank()) {
            out.append(" ").append(subject);
        }
        out.append('\n');

        out.append("- 状态：").append(stateOf(entry));
        out.append(" ｜ 修订 v").append(entry.getRevision());
        out.append(" ｜ 召回 ").append(entry.getRecallCount()).append(" 次");
        if (entry.getTimestamp() != null) {
            out.append(" ｜ 更新 ").append(TIME_FMT.format(entry.getTimestamp()));
        }
        out.append('\n');

        String scope = scopeOf(entry.getMetadata());
        if (!scope.isBlank()) {
            out.append("- 作用域：").append(scope).append('\n');
        }

        String content = entry.getContent() == null ? "" : entry.getContent().strip();
        content.lines().forEach(line -> out.append("> ").append(line).append('\n'));
        out.append('\n');
    }

    private static String stateOf(MemoryEntry entry) {
        if (!entry.isActive()) {
            return "已归档";
        }
        if (entry.getSupersededBy() != null && !entry.getSupersededBy().isBlank()) {
            return "已被 " + entry.getSupersededBy() + " 取代";
        }
        return "生效中";
    }

    private static String scopeOf(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "";
        }
        String type = metadata.getOrDefault("scope_type", "");
        String key = metadata.getOrDefault("scope_key", "");
        if (type.isBlank() && key.isBlank()) {
            return "";
        }
        return (type.isBlank() ? "-" : type) + "/" + (key.isBlank() ? "-" : key);
    }
}
