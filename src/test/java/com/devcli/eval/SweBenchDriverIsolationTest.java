package com.devcli.eval;

import com.devcli.agent.Agent;
import com.devcli.llm.LlmClient;
import com.devcli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SweBenchDriverIsolationTest {

    @Test
    void soloRetainsOnlyClosedBookToolsAndDisablesLongTermMemory() {
        try (ToolRegistry registry = new ToolRegistry(); Agent agent = new Agent(new NoopClient(), registry)) {
            SweBenchDriver.configureClosedBook(agent, false);

            assertEquals(SweBenchDriver.CLOSED_BOOK_TOOLS, toolNamesWithDelegation(registry));
            assertTrue(agent.getMemoryManager().isMemoryIgnored());
        }
    }

    @Test
    void delegationModeAddsOnlyDelegationToTheSameToolSurface() {
        try (ToolRegistry registry = new ToolRegistry(); Agent agent = new Agent(new NoopClient(), registry)) {
            SweBenchDriver.configureClosedBook(agent, true);

            Set<String> expected = new java.util.HashSet<>(SweBenchDriver.CLOSED_BOOK_TOOLS);
            expected.add("delegate_task");
            assertEquals(expected, toolNamesWithDelegation(registry));
            assertTrue(agent.getMemoryManager().isMemoryIgnored());
        }
    }

    @Test
    void sandboxModeUsesExternalConfigurationWithoutMutatingIt() {
        Properties properties = new Properties();
        properties.setProperty("devcli.command.sandbox.mode", "HOST_WARN");

        assertEquals("HOST_WARN", SweBenchDriver.resolveSandboxMode(
                properties, Map.of("DEVCLI_COMMAND_SANDBOX_MODE", "DOCKER")));
        assertEquals("HOST_WARN", properties.getProperty("devcli.command.sandbox.mode"));
        assertEquals("DOCKER", SweBenchDriver.resolveSandboxMode(
                new Properties(), Map.of()));
    }

    private static Set<String> toolNames(ToolRegistry registry) {
        return registry.getToolDefinitions().stream()
                .map(LlmClient.Tool::name)
                .collect(Collectors.toSet());
    }

    private static Set<String> toolNamesWithDelegation(ToolRegistry registry) {
        return registry.runWithDelegation((args, context) -> null, () -> toolNames(registry));
    }

    private static final class NoopClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return new ChatResponse("assistant", "", List.of(), 0, 0);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            return new ChatResponse("assistant", "", List.of(), 0, 0);
        }

        @Override
        public String getModelName() {
            return "test";
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }
}
