package com.devcli.skill;

import com.devcli.memory.MemoryEntry;

import java.util.ArrayList;
import java.util.List;

/** Markdown-aware Skill 文档分页器，优先在标题边界分页。 */
public final class SkillDocumentPager {
    private SkillDocumentPager() {
    }

    public static Page page(String markdown, int requestedPage, int tokenBudget) {
        List<String> pages = paginate(markdown, Math.max(64, tokenBudget));
        int pageNumber = Math.max(1, requestedPage);
        if (pageNumber > pages.size()) {
            throw new IllegalArgumentException("page 超出范围，可用页数: " + pages.size());
        }
        return new Page(pageNumber, pages.size(), pages.get(pageNumber - 1));
    }

    static List<String> paginate(String markdown, int tokenBudget) {
        String normalized = markdown == null ? "" : markdown.replace("\r\n", "\n");
        List<String> sections = splitSections(normalized);
        List<String> pages = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String section : sections) {
            if (fits(current + section, tokenBudget)) {
                current.append(section);
                continue;
            }
            flush(current, pages);
            if (fits(section, tokenBudget)) {
                current.append(section);
            } else {
                splitOversized(section, tokenBudget, pages, current);
            }
        }
        flush(current, pages);
        if (pages.isEmpty()) pages.add("");
        return List.copyOf(pages);
    }

    private static List<String> splitSections(String markdown) {
        List<String> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : markdown.split("(?<=\\n)", -1)) {
            if (line.matches("(?s)^#{1,6}\\s+.*") && !current.isEmpty()) {
                sections.add(current.toString());
                current.setLength(0);
            }
            current.append(line);
        }
        if (!current.isEmpty()) sections.add(current.toString());
        return sections;
    }

    private static void splitOversized(String value, int tokenBudget,
                                       List<String> pages, StringBuilder current) {
        String remaining = value;
        while (!remaining.isEmpty()) {
            int chars = maxFittingChars(remaining, tokenBudget);
            if (chars <= 0) chars = Math.min(1, remaining.length());
            int boundary = remaining.lastIndexOf('\n', Math.min(chars, remaining.length() - 1));
            if (boundary > 0) chars = boundary + 1;
            pages.add(remaining.substring(0, chars));
            remaining = remaining.substring(chars);
        }
        current.setLength(0);
    }

    private static int maxFittingChars(String value, int tokenBudget) {
        int low = 0;
        int high = value.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (fits(value.substring(0, mid), tokenBudget)) low = mid;
            else high = mid - 1;
        }
        return low;
    }

    private static boolean fits(CharSequence value, int tokenBudget) {
        return MemoryEntry.estimateTokens(value.toString()) <= tokenBudget;
    }

    private static void flush(StringBuilder current, List<String> pages) {
        if (!current.isEmpty()) {
            pages.add(current.toString());
            current.setLength(0);
        }
    }

    public record Page(int number, int total, String content) {
        public boolean hasNext() {
            return number < total;
        }
    }
}
