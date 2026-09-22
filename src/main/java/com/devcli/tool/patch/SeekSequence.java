package com.devcli.tool.patch;

import java.util.List;

/**
 * 在文件行列表中定位一段模式行，逐级放宽匹配严格度。
 *
 * <p>移植自 Codex {@code apply-patch/src/seek_sequence.rs} 的语义：模型给出的上下文行
 * 经常与文件存在空白差异，甚至把排版用的弯引号、全角空格写成 ASCII。严格逐字匹配会让
 * 合法补丁反复失败，因此按 精确 → 忽略行尾空白 → 忽略两侧空白 → Unicode 标点归一化
 * 四级降级查找。降级只放宽"定位"，不放宽"替换内容"。</p>
 */
final class SeekSequence {

    private SeekSequence() {
    }

    /**
     * @param lines   目标文件的行列表（已按 LF 归一化，且不含尾部空元素）
     * @param pattern 待定位的模式行
     * @param start   起始查找下标
     * @param endOfFile 为 true 时先尝试从文件末尾对齐，失败再回退到 {@code start}
     * @return 命中起始下标；未命中返回 -1
     */
    static int seek(List<String> lines, List<String> pattern, int start, boolean endOfFile) {
        if (pattern.isEmpty()) {
            // 空模式是显式 no-op：调用方据此走"纯插入"分支，不应被当成未命中。
            return Math.min(Math.max(start, 0), lines.size());
        }
        if (pattern.size() > lines.size()) {
            // 模式比文件还长时不可能命中；提前返回，避免下面的切片越界。
            return -1;
        }

        int searchStart = start < 0 ? 0 : start;
        if (endOfFile) {
            int eofStart = lines.size() - pattern.size();
            searchStart = Math.max(eofStart, searchStart);
        }
        int last = lines.size() - pattern.size();

        for (int i = searchStart; i <= last; i++) {
            if (matches(lines, pattern, i, MatchMode.EXACT)) {
                return i;
            }
        }
        for (int i = searchStart; i <= last; i++) {
            if (matches(lines, pattern, i, MatchMode.RSTRIP)) {
                return i;
            }
        }
        for (int i = searchStart; i <= last; i++) {
            if (matches(lines, pattern, i, MatchMode.TRIM)) {
                return i;
            }
        }
        for (int i = searchStart; i <= last; i++) {
            if (matches(lines, pattern, i, MatchMode.NORMALIZED)) {
                return i;
            }
        }
        return -1;
    }

    private enum MatchMode {
        EXACT,
        RSTRIP,
        TRIM,
        NORMALIZED
    }

    private static boolean matches(List<String> lines, List<String> pattern, int offset,
                                   MatchMode mode) {
        for (int index = 0; index < pattern.size(); index++) {
            if (!equalsLine(lines.get(offset + index), pattern.get(index), mode)) {
                return false;
            }
        }
        return true;
    }

    private static boolean equalsLine(String actual, String expected, MatchMode mode) {
        return switch (mode) {
            case EXACT -> actual.equals(expected);
            case RSTRIP -> actual.stripTrailing().equals(expected.stripTrailing());
            case TRIM -> actual.strip().equals(expected.strip());
            case NORMALIZED -> normalize(actual).equals(normalize(expected));
        };
    }

    /** 把常见排版字符归一化为 ASCII 等价物，让 ASCII 补丁也能定位到排版过的源码。 */
    private static String normalize(String line) {
        StringBuilder result = new StringBuilder(line.length());
        for (char value : line.strip().toCharArray()) {
            result.append(switch (value) {
                case '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015', '\u2212' -> '-';
                case '\u2018', '\u2019', '\u201A', '\u201B' -> '\'';
                case '\u201C', '\u201D', '\u201E', '\u201F' -> '"';
                case '\u00A0', '\u2002', '\u2003', '\u2004', '\u2005', '\u2006', '\u2007',
                     '\u2008', '\u2009', '\u200A', '\u202F', '\u205F', '\u3000' -> ' ';
                default -> value;
            });
        }
        return result.toString();
    }
}
