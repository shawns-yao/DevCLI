package com.devcli.skill;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SkillFrontmatterParserTest {

    @Test
    void parsesSingleLineFields() {
        String input = """
                ---
                name: web-access
                description: web access guide
                version: "1.0.0"
                ---
                body content
                """;
        SkillFrontmatterParser.ParseResult r = SkillFrontmatterParser.parse(input);
        Map<String, Object> fm = r.frontmatter();
        assertEquals("web-access", fm.get("name"));
        assertEquals("1.0.0", fm.get("version"));
        assertEquals("body content\n", r.body());
        assertTrue(r.warnings().isEmpty());
    }

    @Test
    void parsesMultilineDescription() {
        String input = """
                ---
                name: web-access
                description: |
                  第一行
                  第二行
                ---
                hello
                """;
        SkillFrontmatterParser.ParseResult r = SkillFrontmatterParser.parse(input);
        Object desc = r.frontmatter().get("description");
        assertEquals("第一行\n第二行\n", desc);
    }

    @Test
    void parsesInlineArray() {
        String input = """
                ---
                name: foo
                tags: [web, browser, fetch]
                ---
                body
                """;
        SkillFrontmatterParser.ParseResult r = SkillFrontmatterParser.parse(input);
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) r.frontmatter().get("tags");
        assertEquals(List.of("web", "browser", "fetch"), tags);
    }

    @Test
    void parsesQuotedArrayItemsContainingCommaAndColon() {
        String input = """
                ---
                name: quoted-array
                description: parser test
                tags: ["C,C", "key:value"]
                ---
                body
                """;

        SkillFrontmatterParser.ParseResult result = SkillFrontmatterParser.parse(input);

        assertTrue(result.valid(), result.warnings().toString());
        assertEquals(List.of("C,C", "key:value"), result.frontmatter().get("tags"));
    }

    @Test
    void parsesContextAndPathsFields() {
        String input = """
                ---
                name: java-review
                context: fork
                paths: [src/**/*.java, pom.xml]
                ---
                body
                """;
        SkillFrontmatterParser.ParseResult r = SkillFrontmatterParser.parse(input);

        assertEquals("fork", r.frontmatter().get("context"));
        @SuppressWarnings("unchecked")
        List<String> paths = (List<String>) r.frontmatter().get("paths");
        assertEquals(List.of("src/**/*.java", "pom.xml"), paths);
    }

    @Test
    void warnsOnMissingClosingMarker() {
        String input = """
                ---
                name: foo
                body without closing
                """;
        SkillFrontmatterParser.ParseResult r = SkillFrontmatterParser.parse(input);
        assertFalse(r.warnings().isEmpty());
        assertTrue(r.warnings().get(0).contains("结束标记"));
    }

    @Test
    void warnsOnMissingOpeningMarker() {
        String input = "name: foo\nbody\n";
        SkillFrontmatterParser.ParseResult r = SkillFrontmatterParser.parse(input);
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("起始标记")));
    }

    @Test
    void warnsOnNestedObjectField() {
        String input = """
                ---
                name: foo
                metadata: { nested: object }
                ---
                body
                """;
        SkillFrontmatterParser.ParseResult r = SkillFrontmatterParser.parse(input);
        assertFalse(r.valid());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("不支持") || w.contains("metadata")));
    }

    @Test
    void stripsQuotesFromStringValues() {
        String input = """
                ---
                name: "quoted-name"
                version: '0.0.1'
                ---
                """;
        SkillFrontmatterParser.ParseResult r = SkillFrontmatterParser.parse(input);
        assertEquals("quoted-name", r.frontmatter().get("name"));
        assertEquals("0.0.1", r.frontmatter().get("version"));
    }

    @Test
    void handlesEmptyBody() {
        String input = "---\nname: foo\n---\n";
        SkillFrontmatterParser.ParseResult r = SkillFrontmatterParser.parse(input);
        assertEquals("foo", r.frontmatter().get("name"));
        assertEquals("", r.body());
    }

    @Test
    void rejectsUnknownFieldsAndInvalidSchema() {
        SkillFrontmatterParser.ParseResult unknown = SkillFrontmatterParser.parse("""
                ---
                name: example
                description: valid
                surprise: true
                ---
                body
                """);
        SkillFrontmatterParser.ParseResult wrongType = SkillFrontmatterParser.parse("""
                ---
                name: example
                description: valid
                allowedTools: read_file
                ---
                body
                """);

        assertFalse(unknown.valid());
        assertFalse(wrongType.valid());
    }
}
