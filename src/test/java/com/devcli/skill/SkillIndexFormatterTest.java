package com.devcli.skill;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillIndexFormatterTest {

    @Test
    void emptyListReturnsEmptyString() {
        assertEquals("", SkillIndexFormatter.format(List.of()));
        assertEquals("", SkillIndexFormatter.format(null));
    }

    @Test
    void formatsSingleSkillWithDescription() {
        Skill skill = mockSkill("web-access", "联网工具决策手册", Skill.Source.BUILTIN);
        String out = SkillIndexFormatter.format(List.of(skill));
        assertTrue(out.contains("web-access"));
        assertTrue(out.contains("联网工具决策手册"));
        assertTrue(out.contains("load_skill"));
    }

    @Test
    void truncatesLongDescriptionByCodepoint() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) sb.append("中");
        Skill skill = mockSkill("foo", sb.toString(), Skill.Source.USER);
        String out = SkillIndexFormatter.format(List.of(skill));

        // 切出 description 行（"- **foo**：<desc>\n"）后再统计，避免 footer 含"中"字干扰
        String prefix = "**foo**：";
        int start = out.indexOf(prefix) + prefix.length();
        int end = out.indexOf('\n', start);
        String descLine = out.substring(start, end);
        long count = descLine.codePoints().filter(c -> c == '中').count();
        assertEquals(500, count, "description 应按 codepoint 截断到 500");
        assertTrue(descLine.endsWith("..."));
    }

    @Test
    void usesBudgetInsteadOfFixedTwentySkillCap() {
        List<Skill> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add(mockSkill(String.format("skill-%02d", i), "desc " + i, Skill.Source.USER));
        }
        many.add(0, mockSkill("zz-hot", "hot", Skill.Source.USER));
        String out = SkillIndexFormatter.format(many);
        assertTrue(out.contains("zz-hot"));
        assertTrue(out.contains("skill-18"));
        assertTrue(out.contains("skill-19"), "相关 skill 不应仅因固定数量上限而不可见");
    }

    @Test
    void truncatesByCodepointHelperHandlesAsciiAndCjk() {
        assertEquals("abc", SkillIndexFormatter.truncateByCodepoint("abc", 10));
        String s = "中文测试";
        assertEquals(s, SkillIndexFormatter.truncateByCodepoint(s, 4));
        String truncated = SkillIndexFormatter.truncateByCodepoint(s, 2);
        assertTrue(truncated.startsWith("中文"));
        assertTrue(truncated.endsWith("..."));
    }

    @Test
    void indexBudgetIsTokenBasedAndAllowsReloadAfterCompaction() {
        List<Skill> skills = List.of(
                mockSkill("alpha", "A ".repeat(200), Skill.Source.USER),
                mockSkill("beta", "B ".repeat(200), Skill.Source.USER));

        String small = SkillIndexFormatter.format(skills, 80);
        String large = SkillIndexFormatter.format(skills, 500);

        assertTrue(com.devcli.memory.MemoryEntry.estimateTokens(small) <= 90, small);
        assertTrue(large.length() > small.length());
        assertTrue(large.contains("可以再次调用 load_skill"), large);
        assertFalse(large.contains("一次足够"), large);
        SkillIndexFormatter.FormatResult result = SkillIndexFormatter.formatWithMetrics(skills, 80);
        assertTrue(result.omittedCount() > 0);
        assertTrue(result.estimatedTokens() <= 80);
    }

    private static Skill mockSkill(String name, String desc, Skill.Source source) {
        return new Skill(name, desc, "1.0.0", null, List.of(), List.of(), source, "body", null, null);
    }
}
