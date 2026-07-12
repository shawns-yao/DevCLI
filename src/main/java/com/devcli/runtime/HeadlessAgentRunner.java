package com.devcli.runtime;

import com.devcli.agent.Agent;
import com.devcli.llm.LlmClient;
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
        Objects.requireNonNull(llmClient, "llmClient");
        Path normalizedProject = Objects.requireNonNull(projectPath, "projectPath")
                .toAbsolutePath()
                .normalize();
        RunContext current = CancellationContext.currentRun();
        if (current != null) {
            if (!current.projectPath().equals(normalizedProject)) {
                throw new IllegalArgumentException("当前运行上下文项目路径不一致");
            }
            return runWithinContext(llmClient, normalizedProject, prompt, seedHistory);
        }
        try (RunContext ignored = CancellationContext.startRunContext(normalizedProject)) {
            return runWithinContext(llmClient, normalizedProject, prompt, seedHistory);
        }
    }

    private static String runWithinContext(LlmClient llmClient, Path projectPath, String prompt,
                                           List<LlmClient.Message> seedHistory) {
        try (ToolRegistry registry = new ToolRegistry()) {
            registry.setProjectPath(projectPath.toString());
            try (Agent agent = new Agent(llmClient, registry)) {
                agent.seedHistory(seedHistory);
                return agent.run(prompt);
            }
        }
    }
}
