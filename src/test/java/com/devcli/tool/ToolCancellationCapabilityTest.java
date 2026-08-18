package com.devcli.tool;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolCancellationCapabilityTest {

    @Test
    void distinguishesCooperativeAndInterruptOnlyTools() {
        ToolRegistry.Tool interruptOnly = new ToolRegistry.Tool(
                "plain", "plain", JsonNodeFactory.instance.objectNode(), args -> "ok");
        ToolRegistry.Tool cooperative = ToolRegistry.Tool.contextualStructured(
                "contextual", "contextual", JsonNodeFactory.instance.objectNode(),
                (args, context) -> ToolOutput.success("ok"), 5);

        assertEquals(ToolRegistry.ToolCancellationCapability.INTERRUPT_ONLY,
                interruptOnly.cancellationCapability());
        assertEquals(ToolRegistry.ToolCancellationCapability.COOPERATIVE,
                cooperative.cancellationCapability());

        try (ToolRegistry registry = new ToolRegistry()) {
            registry.registerTool(interruptOnly);
            registry.registerTool(cooperative);
            assertEquals(ToolRegistry.ToolCancellationCapability.INTERRUPT_ONLY,
                    registry.toolCancellationCapability("plain"));
            assertEquals(ToolRegistry.ToolCancellationCapability.COOPERATIVE,
                    registry.toolCancellationCapability("contextual"));
        }
    }
}
