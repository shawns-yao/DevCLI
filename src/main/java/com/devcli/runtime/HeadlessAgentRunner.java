package com.devcli.runtime;

import com.devcli.agent.Agent;
import com.devcli.llm.LlmClient;
import com.devcli.memory.CompactBoundaryMetadata;
import com.devcli.runtime.event.RunEventSink;
import com.devcli.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 无头 Agent 的统一生命周期入口。
 */
public final class HeadlessAgentRunner {
    private HeadlessAgentRunner() {
    }

    public static String run(LlmClient llmClient, Path projectPath, String prompt,
                             List<LlmClient.Message> seedHistory) {
        return run(llmClient, null, projectPath, prompt, seedHistory);
    }

    public static String run(LlmClient llmClient, LlmClient memoryCuratorClient,
                             Path projectPath, String prompt,
                             List<LlmClient.Message> seedHistory) {
        return runDetailed(llmClient, memoryCuratorClient, projectPath, prompt,
                seedHistory, 0, RunEventSink.NO_OP).output();
    }

    public static RunResult runDetailed(LlmClient llmClient, Path projectPath, String prompt,
                                        List<LlmClient.Message> seedHistory,
                                        int checkpointTriggerTokens) {
        return runDetailed(llmClient, projectPath, prompt, seedHistory,
                checkpointTriggerTokens, RunEventSink.NO_OP);
    }

    public static RunResult runDetailed(LlmClient llmClient, Path projectPath, String prompt,
                                        List<LlmClient.Message> seedHistory,
                                        int checkpointTriggerTokens,
                                        RunEventSink eventSink) {
        return runDetailed(llmClient, null, projectPath, prompt, seedHistory,
                checkpointTriggerTokens, eventSink);
    }

    private static RunResult runDetailed(LlmClient llmClient, LlmClient memoryCuratorClient,
                                         Path projectPath, String prompt,
                                         List<LlmClient.Message> seedHistory,
                                         int checkpointTriggerTokens,
                                         RunEventSink eventSink) {
        Objects.requireNonNull(llmClient, "llmClient");
        Path normalizedProject = Objects.requireNonNull(projectPath, "projectPath")
                .toAbsolutePath()
                .normalize();
        RunContext current = CancellationContext.currentRun();
        if (current != null) {
            if (!current.projectPath().equals(normalizedProject)) {
                throw new IllegalArgumentException("当前运行上下文项目路径不一致");
            }
            return runWithinContext(
                    llmClient, memoryCuratorClient, normalizedProject, prompt, seedHistory,
                    checkpointTriggerTokens, eventSink);
        }
        try (RunContext ignored = CancellationContext.startRunContext(normalizedProject)) {
            return runWithinContext(
                    llmClient, memoryCuratorClient, normalizedProject, prompt, seedHistory,
                    checkpointTriggerTokens, eventSink);
        }
    }

    private static RunResult runWithinContext(LlmClient llmClient, LlmClient memoryCuratorClient,
                                              Path projectPath, String prompt,
                                              List<LlmClient.Message> seedHistory,
                                              int checkpointTriggerTokens,
                                              RunEventSink eventSink) {
        try (AgentSessionRuntime session = AgentSessionRuntime.create(
                llmClient, memoryCuratorClient, projectPath, eventSink)) {
            Agent agent = session.agent();
            agent.seedHistory(seedHistory);
            String output = session.runBlocking(prompt).output();
            boolean compactedAfterTurn = checkpointTriggerTokens > 0
                    && agent.compactHistoryForPersistence(checkpointTriggerTokens);
            List<LlmClient.Message> history = agent.getConversationHistory();
            boolean compactedDuringTurn = hasNewCompactionBoundary(seedHistory, history);
            String durableOutput = output == null || output.isBlank()
                    ? latestAssistantContent(history)
                    : output;
            return new RunResult(
                    durableOutput, history, compactedAfterTurn || compactedDuringTurn);
        }
    }

    private static String latestAssistantContent(List<LlmClient.Message> history) {
        if (history == null || history.isEmpty()) return "";
        for (int index = history.size() - 1; index >= 0; index--) {
            LlmClient.Message message = history.get(index);
            if (message != null && "assistant".equals(message.role())
                    && message.content() != null && !message.content().isBlank()) {
                return message.content();
            }
        }
        return "";
    }

    static boolean hasNewCompactionBoundary(
            List<LlmClient.Message> before,
            List<LlmClient.Message> after) {
        return !Objects.equals(latestCompactionBoundary(before), latestCompactionBoundary(after));
    }

    private static String latestCompactionBoundary(List<LlmClient.Message> messages) {
        if (messages == null || messages.isEmpty()) return "";
        String latest = "";
        for (LlmClient.Message message : messages) {
            if (message != null
                    && CompactBoundaryMetadata.parseFromSummaryMessage(message.content()).isPresent()) {
                latest = message.content();
            }
        }
        return latest;
    }

    public record RunResult(String output, List<LlmClient.Message> history, boolean compacted) {
        public RunResult {
            output = output == null ? "" : output;
            history = history == null ? List.of() : List.copyOf(history);
        }
    }
}
