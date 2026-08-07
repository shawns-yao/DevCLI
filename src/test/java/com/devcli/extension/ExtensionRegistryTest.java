package com.devcli.extension;

import com.devcli.hook.HookDefinition;
import com.devcli.hook.HookEvent;
import com.devcli.mcp.config.McpServerConfig;
import com.devcli.skill.Skill;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionRegistryTest {

    @Test
    void adaptsAndListsAllExtensionKinds() {
        ExtensionRegistry registry = new ExtensionRegistry();
        Skill skill = new Skill("review", "review code", "1.0", "dev",
                List.of("quality"), List.of("read_file"), Skill.Context.INLINE, List.of(),
                Skill.Source.PROJECT, "body", Path.of("SKILL.md"), Path.of("references"));
        HookDefinition hook = new HookDefinition("audit", "audit", HookEvent.TURN_END,
                true, "list_dir", JsonNodeFactory.instance.objectNode(),
                HookDefinition.FailureMode.WARN, false);
        McpServerConfig mcp = new McpServerConfig();
        mcp.setCommand("npx");

        registry.register(ExtensionRegistry.fromSkill(skill));
        registry.register(ExtensionRegistry.fromHook(hook));
        registry.register(ExtensionRegistry.fromMcpServer("devtools", mcp));
        registry.register(ExtensionRegistry.command("/help", "show help"));

        assertEquals(4, registry.size());
        assertEquals(1, registry.list(ExtensionContract.Kind.SKILL).size());
        assertEquals("project", registry.find("skill:review").orElseThrow()
                .descriptor().metadata().get("source"));
        assertTrue(registry.find("mcp:devtools").orElseThrow().descriptor().enabled());
    }

    @Test
    void rejectsDuplicateIdsAndAllowsExplicitReplacement() {
        ExtensionRegistry registry = new ExtensionRegistry();
        ExtensionContract first = ExtensionRegistry.command("/help", "first");
        ExtensionContract second = ExtensionRegistry.command("/help", "second");

        registry.register(first);

        assertThrows(IllegalStateException.class, () -> registry.register(second));
        registry.registerOrReplace(second);
        assertEquals("second", registry.find("command:/help").orElseThrow()
                .descriptor().metadata().get("description"));
        assertTrue(registry.remove("command:/help"));
        assertFalse(registry.find("command:/help").isPresent());
    }

    @Test
    void replacesOneExtensionKindWithoutTouchingOtherKinds() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.register(ExtensionRegistry.command("/help", "help"));
        HookDefinition first = new HookDefinition("first", "first", HookEvent.TURN_START,
                true, "list_dir", JsonNodeFactory.instance.objectNode(),
                HookDefinition.FailureMode.WARN, false);
        HookDefinition second = new HookDefinition("second", "second", HookEvent.TURN_END,
                true, "list_dir", JsonNodeFactory.instance.objectNode(),
                HookDefinition.FailureMode.WARN, false);

        registry.replaceKind(ExtensionContract.Kind.HOOK,
                List.of(ExtensionRegistry.fromHook(first), ExtensionRegistry.fromHook(second)));

        assertEquals(1, registry.list(ExtensionContract.Kind.COMMAND).size());
        assertEquals(2, registry.list(ExtensionContract.Kind.HOOK).size());
        assertTrue(registry.find("hook:second").isPresent());
    }
}
