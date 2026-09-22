package com.devcli.tool.patch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 降级匹配的四级顺序与边界。
 *
 * <p>用例移植自 Codex {@code seek_sequence.rs}，其中"模式比输入长"是官方修过的越界 panic，
 * 必须保留防回归覆盖。</p>
 */
class SeekSequenceTest {

    private static List<String> lines(String... values) {
        return List.of(values);
    }

    @Test
    void exactMatchWins() {
        assertEquals(1, SeekSequence.seek(lines("foo", "bar", "baz"), lines("bar", "baz"), 0, false));
    }

    @Test
    void ignoresTrailingWhitespaceWhenExactMatchFails() {
        assertEquals(0, SeekSequence.seek(lines("foo   ", "bar\t\t"), lines("foo", "bar"), 0, false));
    }

    @Test
    void ignoresLeadingAndTrailingWhitespaceAsLastResort() {
        assertEquals(0, SeekSequence.seek(lines("    foo   ", "   bar\t"), lines("foo", "bar"), 0, false));
    }

    @Test
    void normalizesUnicodePunctuation() {
        List<String> file = lines("import asyncio  # local import \u2013 avoids top\u2011level dep");
        List<String> pattern = lines("import asyncio  # local import - avoids top-level dep");

        assertEquals(0, SeekSequence.seek(file, pattern, 0, false),
                "排版用的连字符应被归一化后匹配");
    }

    @Test
    void patternLongerThanInputReturnsMissInsteadOfThrowing() {
        assertEquals(-1, SeekSequence.seek(lines("just one line"), lines("too", "many", "lines"), 0, false));
    }

    @Test
    void emptyPatternIsNoOpAtStart() {
        assertEquals(2, SeekSequence.seek(lines("a", "b", "c"), List.of(), 2, false));
    }

    @Test
    void endOfFileSearchPrefersTheTail() {
        List<String> file = lines("target", "middle", "target");

        assertEquals(2, SeekSequence.seek(file, lines("target"), 0, true),
                "声明 End of File 时应优先命中末尾出现的位置");
        assertEquals(0, SeekSequence.seek(file, lines("target"), 0, false));
    }

    @Test
    void searchStartsAtRequestedIndex() {
        List<String> file = lines("target", "middle", "target");

        assertEquals(2, SeekSequence.seek(file, lines("target"), 1, false),
                "查找必须从 line_index 之后开始，避免同一上下文被重复命中");
    }
}
