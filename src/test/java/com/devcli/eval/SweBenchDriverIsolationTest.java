package com.devcli.eval;

import com.devcli.agent.Agent;
import com.devcli.llm.LlmClient;
import com.devcli.memory.ConversationHistoryCompactor;
import com.devcli.memory.TokenBudget;
import com.devcli.runtime.event.RunEvent;
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

    @Test
    void environmentFingerprintRecordsReproductionInputsWithoutLeakingRepositoryPath() {
        Properties properties = new Properties();
        properties.setProperty("java.version", "17.0.12");
        properties.setProperty("devcli.command.sandbox.mode", "HOST_WARN");
        properties.setProperty("devcli.llm.http.protocol", "HTTP_1_1");
        properties.setProperty("devcli.command.sandbox.maven.repository",
                "C:\\private\\maven\\repository");

        String fingerprint = SweBenchDriver.environmentFingerprint(properties, Map.of());

        assertEquals("java=17.0.12 sandbox=HOST_WARN http=HTTP_1_1 mavenRepo=EXPLICIT",
                fingerprint);
        assertTrue(!fingerprint.contains("private"), fingerprint);
        assertEquals("java=unknown sandbox=DOCKER http=AUTO mavenRepo=DEFAULT",
                SweBenchDriver.environmentFingerprint(new Properties(), Map.of()));
    }

    @Test
    void usageCollectorAggregatesAllModelCalls() {
        SweBenchDriver.UsageCollector usage = new SweBenchDriver.UsageCollector();

        usage.emit(new RunEvent.ModelUsage(10, 4, 3, 0.12));
        usage.emit(new RunEvent.ModelUsage(20, 5, 7, 0.34));

        SweBenchDriver.UsageSnapshot snapshot = usage.snapshot();
        assertEquals(30, snapshot.inputTokens());
        assertEquals(9, snapshot.outputTokens());
        assertEquals(10, snapshot.cachedInputTokens());
        assertEquals(0.46, snapshot.estimatedCostCny(), 0.000001);
    }

    @Test
    void headlessPlanReviewAlwaysExecutesValidatedPlan() {
        assertEquals(
                com.devcli.agent.AgentOrchestrator.TeamPlanReviewAction.EXECUTE,
                SweBenchDriver.headlessPlanReviewDecision(null).action());
    }

    @Test
    void contextModeIsStrictlyNormalized() {
        assertEquals("raw", SweBenchDriver.normalizeContextMode(" RAW "));
        assertEquals("compact", SweBenchDriver.normalizeContextMode("compact"));
    }

    @Test
    void invalidContextModeIsRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> SweBenchDriver.normalizeContextMode("summary"));
    }

    @Test
    void continuationRoundsAreBounded() {
        assertEquals(4, SweBenchDriver.normalizeContinuationRounds(" 4 "));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> SweBenchDriver.normalizeContinuationRounds("0"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> SweBenchDriver.normalizeContinuationRounds("65"));
    }

    @Test
    void contextSnapshotUsesTheRealHistoryAndToolSchemaThreshold() {
        try (ToolRegistry registry = new ToolRegistry(); Agent agent = new Agent(new NoopClient(), registry)) {
            SweBenchDriver.configureClosedBook(agent, false);

            SweBenchDriver.ContextWindowSnapshot snapshot =
                    SweBenchDriver.contextWindowSnapshot(agent, 3);

            assertEquals(3, snapshot.round());
            assertEquals(TokenBudget.estimateMessagesTokens(agent.getConversationHistory()),
                    snapshot.historyTokens());
            int toolTokens = TokenBudget.estimateToolDefinitionsTokens(registry.getToolDefinitions());
            assertEquals(agent.getMemoryManager().getContextProfile().historyTriggerTokens(toolTokens),
                    snapshot.triggerTokens());
        }
    }

    @Test
    void compactionSwitchDefaultsOnAndCanBeDisabled() {
        assertTrue(ConversationHistoryCompactor.isCompactionEnabled(new Properties(), Map.of()));
        Properties disabled = new Properties();
        disabled.setProperty(ConversationHistoryCompactor.COMPACTION_ENABLED_PROPERTY, "false");
        assertTrue(!ConversationHistoryCompactor.isCompactionEnabled(
                disabled, Map.of()));
        disabled.setProperty(ConversationHistoryCompactor.COMPACTION_ENABLED_PROPERTY, "invalid");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ConversationHistoryCompactor.isCompactionEnabled(disabled, Map.of()));
    }

    @Test
    void unavailableModelStopsContinuationThroughRealSession(@org.junit.jupiter.api.io.TempDir java.nio.file.Path project) {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        LlmClient unavailable = new NoopClient() {
            @Override
            public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
                calls.incrementAndGet();
                throw com.devcli.llm.LlmErrors.fromHttp("openai", "gpt-5.6-luna", 503,
                        "model_not_found", 0);
            }
        };
        SweBenchDriver.runReact(unavailable, project, "Read the workspace and report its contents",
                true, new SweBenchDriver.UsageCollector(), 4, java.util.List.of());
        assertEquals(1, calls.get(), "External model failure must not start additional continuation rounds");
    }

    private static Set<String> toolNames(ToolRegistry registry) {
        return registry.getToolDefinitions().stream()
                .map(LlmClient.Tool::name)
                .collect(Collectors.toSet());
    }

    private static Set<String> toolNamesWithDelegation(ToolRegistry registry) {
        return registry.runWithDelegation((args, context) -> null, () -> toolNames(registry));
    }

    private static class NoopClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return new ChatResponse("assistant", "", List.of(), 0, 0);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
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
