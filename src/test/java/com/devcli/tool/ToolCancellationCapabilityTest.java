package com.devcli.tool;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolCancellationCapabilityTest {

    @Test
    void exposesDeclaredCancellationCapability() {
        ToolRegistry.Tool interruptOnly = new ToolRegistry.Tool(
                "plain", "plain", JsonNodeFactory.instance.objectNode(), args -> "ok");
        ToolRegistry.Tool cooperative = new ToolRegistry.Tool(
                "cooperative", "cooperative", JsonNodeFactory.instance.objectNode(),
                args -> "ok", ToolRegistry.ToolEffect.READ_ONLY, 5,
                ToolRegistry.ToolCancellationCapability.COOPERATIVE);

        try (ToolRegistry registry = new ToolRegistry()) {
            registry.registerTool(interruptOnly);
            registry.registerTool(cooperative);
            assertEquals(ToolRegistry.ToolCancellationCapability.INTERRUPT_ONLY,
                    registry.toolCancellationCapability("plain"));
            assertEquals(ToolRegistry.ToolCancellationCapability.COOPERATIVE,
                    registry.toolCancellationCapability("cooperative"));
        }
    }
}
