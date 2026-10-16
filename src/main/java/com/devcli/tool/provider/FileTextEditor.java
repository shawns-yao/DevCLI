package com.devcli.tool.provider;

import com.devcli.tool.patch.SeekSequence;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 文本替换定位；文件权限、版本和写入仍由 FileToolProvider 处理。 */
final class FileTextEditor {
    record Result(String content, int replacements, boolean whitespaceMatched) { }
    private record Match(int start, int end) { }

    private FileTextEditor() { }

    static Result replace(String before, String oldText, String newText, boolean replaceAll) {
        List<Match> matches = new ArrayList<>();
        for (int from = 0, at; (at = before.indexOf(oldText, from)) >= 0; from = at + oldText.length()) {
            matches.add(new Match(at, at + oldText.length()));
        }
        boolean whitespace = matches.isEmpty();
        if (whitespace) {
            String eol = before.contains("\r\n") ? "\r\n" : "\n";
            List<String> lines = List.of(before.split(Pattern.quote(eol), -1));
            List<String> pattern = List.of(oldText.split(Pattern.quote(eol), -1));
            boolean trailingEol = oldText.endsWith(eol);
            if (trailingEol) pattern = pattern.subList(0, pattern.size() - 1);
            int[] offsets = new int[lines.size()];
            for (int i = 1; i < lines.size(); i++) offsets[i] = offsets[i - 1] + lines.get(i - 1).length() + eol.length();
            for (int at : SeekSequence.findWhitespaceMatches(lines, pattern)) {
                int last = at + pattern.size() - 1;
                int end = offsets[last] + lines.get(last).length();
                if (trailingEol && last + 1 < lines.size()) end += eol.length();
                matches.add(new Match(offsets[at], end));
            }
        }
        if (matches.isEmpty()) throw new IllegalArgumentException(
                "old_string 未匹配到。请 read_file 核对原文，空白容错仅匹配完整行");
        if (matches.size() > 1 && !replaceAll) throw new IllegalArgumentException(
                "old_string 匹配到 " + matches.size() + " 处。请增加上下文使其唯一，或明确设置 replace_all=true");
        StringBuilder result = new StringBuilder(before);
        for (int i = matches.size() - 1; i >= 0; i--) {
            Match match = matches.get(i);
            result.replace(match.start(), match.end(), newText);
        }
        return new Result(result.toString(), matches.size(), whitespace);
    }
}
