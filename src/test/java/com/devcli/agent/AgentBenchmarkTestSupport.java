package com.devcli.agent;

import com.devcli.tool.command.CommandExecutionService;

/** 受控 Agent benchmark 的测试侧装配，不改变生产沙箱策略。 */
public final class AgentBenchmarkTestSupport {
    private AgentBenchmarkTestSupport() {
    }

    public static void configureControlledBenchmark(AgentOrchestrator orchestrator) {
        orchestrator.setRequireWorkerToolEvidence(true);
        orchestrator.setPreReviewVerifier(new PreReviewVerifier(
                60,
                request -> CommandExecutionService.Result.completed(
                        0,
                        "benchmark: compilation and behavior checks are executed by the external hidden validator")));
    }
}
