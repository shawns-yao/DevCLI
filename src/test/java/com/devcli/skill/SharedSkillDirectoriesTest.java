package com.devcli.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SharedSkillDirectoriesTest {
    @TempDir Path directory;

    @Test
    void readsSharedSkillsInPlaceAndKeepsDevCliOverrides() throws Exception {
        Path shared = directory.resolve("codex/skills");
        Path local = directory.resolve("devcli/skills");
        writeSkill(shared, "shared", "Shared body");
        writeSkill(shared, "override", "Shared version");
        writeSkill(local, "override", "Local version");
        SkillRegistry registry = new SkillRegistry(null, local, null, null, List.of(shared));
        registry.reload();
        assertEquals(2, registry.enabledSkills().size());
        assertEquals(shared.resolve("shared/SKILL.md"), registry.findSkill("shared").skillMdPath());
        assertEquals(local.resolve("override/SKILL.md"), registry.findSkill("override").skillMdPath());
        writeSkill(shared, "shared", "Updated body");
        registry.reload();
        assertTrue(registry.findSkill("shared").body().contains("Updated body"));
    }

    private static void writeSkill(Path root, String name, String body) throws Exception {
        Path file = root.resolve(name).resolve("SKILL.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "---\nname: " + name + "\ndescription: Example\n---\n" + body);
    }
}
