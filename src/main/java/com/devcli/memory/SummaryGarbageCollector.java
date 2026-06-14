package com.devcli.memory;

import java.util.List;

/**
 * 摘要垃圾回收：把九段滚动摘要程序化裁剪到字符预算内，<b>不调 LLM</b>。
 *
 * <p>策略：
 * <ol>
 *   <li>折叠"逐条用户消息"到最近 N 条（保尾部，最近的优先），更早的折叠成一行计数</li>
 *   <li>仍超预算则按"低→高优先级"逐段截断（保头部）；高优先段
 *       （主要请求与意图 / 待办任务 / 当前在做什么 / 下一步）不参与截断，尽量保住</li>
 * </ol>
 *
 * <p>裁剪是有损的粗操作；裁剪后仍超预算的极端情况由上层（{@code ConversationHistoryCompactor}）
 * 再决定是否走 LLM recompress 兜底。
 */
public class SummaryGarbageCollector {

    /** 逐条用户消息默认最多保留条数（更早的折叠成一行计数）。 */
    static final int DEFAULT_MAX_USER_MESSAGE_LINES = 20;

    /** 段截断的最小保留字符（低于此不值得截）。 */
    private static final int MIN_SECTION_CHARS = 200;

    /**
     * 低→高优先级截断顺序。GC 从前往后裁；高优先段（意图/待办/当前/下一步）<b>不在此列</b>，
     * 尽量不动。"逐条用户消息"只折叠不在此截断（折叠保尾部，截断保头部，二者冲突）。
     */
    private static final List<String> TRUNCATE_ORDER = List.of(
            "问题解决过程",
            "踩过的坑和修复",
            "文件和代码",
            "关键技术概念"
    );

    private final int maxUserMessageLines;

    public SummaryGarbageCollector() {
        this(DEFAULT_MAX_USER_MESSAGE_LINES);
    }

    public SummaryGarbageCollector(int maxUserMessageLines) {
        this.maxUserMessageLines = Math.max(1, maxUserMessageLines);
    }

    /**
     * 裁剪 summary 到 maxChars 内（原地修改并返回）。未超预算时不动。
     */
    public RollingSummary gc(RollingSummary summary, int maxChars) {
        if (summary == null || summary.totalChars() <= maxChars) {
            return summary;
        }
        collapseUserMessages(summary);
        for (String section : TRUNCATE_ORDER) {
            if (summary.totalChars() <= maxChars) {
                break;
            }
            truncateSection(summary, section, maxChars);
        }
        return summary;
    }

    private void collapseUserMessages(RollingSummary summary) {
        String content = summary.get("逐条用户消息");
        if (content.isBlank()) {
            return;
        }
        String[] lines = content.split("\n");
        if (lines.length <= maxUserMessageLines) {
            return;
        }
        int collapsed = lines.length - maxUserMessageLines;
        StringBuilder sb = new StringBuilder();
        sb.append("(更早 ").append(collapsed).append(" 条用户消息已折叠)\n");
        for (int i = lines.length - maxUserMessageLines; i < lines.length; i++) {
            sb.append(lines[i]).append("\n");
        }
        summary.set("逐条用户消息", sb.toString().strip());
    }

    private void truncateSection(RollingSummary summary, String section, int maxChars) {
        int overflow = summary.totalChars() - maxChars;
        if (overflow <= 0) {
            return;
        }
        String content = summary.get(section);
        if (content.length() <= MIN_SECTION_CHARS) {
            return;
        }
        // 留 40 字符给截断标记的余量
        int target = Math.max(MIN_SECTION_CHARS, content.length() - overflow - 40);
        if (target >= content.length()) {
            return;
        }
        String truncated = content.substring(0, target).strip()
                + "\n[... 已折叠 " + (content.length() - target) + " 字符 ...]";
        summary.set(section, truncated);
    }
}
