package com.devcli.memory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 压缩后恢复上下文的预算和去重组装器。
 */
public final class PostCompactRestoreContext {
    public static final int DEFAULT_MAX_CHARS = 8_000;

    private PostCompactRestoreContext() {
    }

    public static String render(Section... sections) {
        return render(DEFAULT_MAX_CHARS, sections);
    }

    public static String render(int maxChars, Section... sections) {
        if (sections == null || sections.length == 0) {
            return "";
        }
        int safeMaxChars = Math.max(512, maxChars);
        LinkedHashSet<String> seenLines = new LinkedHashSet<>();
        List<String> renderedSections = new ArrayList<>();
        for (Section section : sections) {
            String rendered = renderSection(section, seenLines);
            if (!rendered.isBlank()) {
                renderedSections.add(rendered);
            }
        }
        String joined = String.join("\n\n", renderedSections).trim();
        if (joined.length() <= safeMaxChars) {
            return joined;
        }
        return truncateAtLineBoundary(joined, safeMaxChars);
    }

    private static String renderSection(Section section, LinkedHashSet<String> seenLines) {
        if (section == null || section.body == null || section.body.isBlank()) {
            return "";
        }
        String title = normalizeTitle(section.title);
        StringBuilder sb = new StringBuilder(title).append("\n\n");
        int kept = 0;
        for (String rawLine : section.body.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = rawLine.stripTrailing();
            if (line.isBlank()) {
                continue;
            }
            String dedupeKey = normalizeDedupeKey(line);
            if (!dedupeKey.isBlank() && !seenLines.add(dedupeKey)) {
                continue;
            }
            sb.append(line).append('\n');
            kept++;
        }
        return kept == 0 ? "" : sb.toString().trim();
    }

    private static String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return "### 恢复内容";
        }
        String trimmed = title.trim();
        return trimmed.startsWith("### ") ? trimmed : "### " + trimmed;
    }

    private static String normalizeDedupeKey(String line) {
        String normalized = line.replaceAll("\\s+", " ").trim();
        if (normalized.startsWith("### ")) {
            return "";
        }
        return normalized;
    }

    private static String truncateAtLineBoundary(String text, int maxChars) {
        int cut = Math.min(text.length(), maxChars);
        int lineBreak = text.lastIndexOf('\n', cut);
        if (lineBreak > 256) {
            cut = lineBreak;
        }
        int omitted = text.length() - cut;
        return text.substring(0, cut).trim() + "\n\n[恢复上下文已截断 " + omitted + " 字符]";
    }

    public record Section(String title, String body) {
    }
}
