package com.devcli.tool;

import com.devcli.tool.ToolContract;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolCancellationCapabilityTest {

    @Test
    void distinguishesCooperativeAndInterruptOnlyTools() {
        ToolContract.Tool interruptOnly = new ToolContract.Tool(
                "plain", "plain", JsonNodeFactory.instance.objectNode(), args -> "ok");
        ToolContract.Tool cooperative = ToolContract.Tool.contextualStructured(
                "contextual", "contextual", JsonNodeFactory.instance.objectNode(),
                (args, context) -> ToolOutput.success("ok"), 5);

        assertEquals(ToolContract.ToolCancellationCapability.INTERRUPT_ONLY,
                interruptOnly.cancellationCapability());
        assertEquals(ToolContract.ToolCancellationCapability.COOPERATIVE,
                cooperative.cancellationCapability());

        try (ToolRegistry registry = new ToolRegistry()) {
            registry.registerTool(interruptOnly);
            registry.registerTool(cooperative);
            assertEquals(ToolContract.ToolCancellationCapability.INTERRUPT_ONLY,
                    registry.toolCancellationCapability("plain"));
            assertEquals(ToolContract.ToolCancellationCapability.COOPERATIVE,
                    registry.toolCancellationCapability("contextual"));
        }
    }
}
