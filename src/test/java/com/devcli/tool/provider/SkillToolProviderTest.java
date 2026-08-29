package com.devcli.tool.provider;

import com.devcli.context.ContextProfile;
import com.devcli.skill.SkillContextBuffer;
import com.devcli.skill.SkillRegistry;
import com.devcli.skill.SkillStateStore;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SkillToolProviderTest {

    @Test
    void loadsSkillByBudgetedMarkdownPagesWithoutCliFallback(@TempDir Path tempDir) throws Exception {
        Path user = tempDir.resolve("skills");
        writeSkill(user, "long-guide", "docs", """
                # 第一节
                第一节正文内容。%s
                # 第二节
                第二节正文内容。%s
                """.formatted("甲".repeat(5000), "乙".repeat(5000)));
        SkillRegistry skills = new SkillRegistry(null, user, null,
                new SkillStateStore(tempDir.resolve("skills.json")));
        skills.reload();

        try (ToolRegistry tools = new ToolRegistry()) {
            SkillContextBuffer buffer = new SkillContextBuffer();
            tools.setSkillRegistry(skills);
            tools.setSkillContextBuffer(buffer);
            tools.setContextProfile(ContextProfile.custom(8_000, 4_000));

            ToolOutput first = tools.executeToolOutput("load_skill", "{\"name\":\"long-guide\",\"page\":1}");
            String injected = buffer.drain();

            assertTrue(first.text().contains("第 1/"), first.text());
            assertTrue(first.text().contains("page=2"), first.text());
            assertFalse(first.text().contains("/skill show"), first.text());
            assertTrue(injected.contains("# 第一节"), injected);
            assertFalse(injected.contains("# 第二节"), injected);
            assertTrue(com.devcli.memory.MemoryEntry.estimateTokens(injected)
                    <= tools.getContextProfile().skillBodyTokens() + 80);
        }
    }

    @Test
    void readsReferencePageAndRejectsTraversal(@TempDir Path tempDir) throws Exception {
        Path user = tempDir.resolve("skills");
        writeSkill(user, "guide", "docs", "body");
        Path references = user.resolve("guide/references");
        Files.createDirectories(references);
        Files.writeString(references.resolve("details.md"), "# Details\nreference body");
        Files.writeString(user.resolve("outside.txt"), "secret");
        SkillRegistry skills = new SkillRegistry(null, user, null,
                new SkillStateStore(tempDir.resolve("skills.json")));
        skills.reload();

        try (ToolRegistry tools = new ToolRegistry()) {
            tools.setSkillRegistry(skills);
            ToolOutput ok = tools.executeToolOutput("load_skill",
                    "{\"name\":\"guide\",\"reference\":\"details.md\",\"page\":1}");
            ToolOutput escaped = tools.executeToolOutput("load_skill",
                    "{\"name\":\"guide\",\"reference\":\"../outside.txt\",\"page\":1}");

            assertTrue(ok.isSuccess());
            assertTrue(ok.text().contains("reference body"));
            assertFalse(escaped.isSuccess());
            assertFalse(escaped.text().contains("secret"));
        }
    }

    private static void writeSkill(Path root, String name, String description, String body) throws Exception {
        Path directory = root.resolve(name);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"), "---\nname: " + name
                + "\ndescription: " + description + "\n---\n" + body);
    }
}
