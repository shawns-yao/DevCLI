package com.devcli.cli;

import com.devcli.agent.Agent;
import com.devcli.config.DevCliConfig;
import com.devcli.event.RunEvent;
import com.devcli.event.RunEventSink;
import com.devcli.hitl.ApprovalRequest;
import com.devcli.hitl.ApprovalResult;
import com.devcli.hitl.HitlHandler;
import com.devcli.hitl.HitlToolRegistry;
import com.devcli.llm.LlmClient;
import com.devcli.llm.LlmClientFactory;
import com.devcli.render.PlainRenderer;
import com.devcli.runtime.AgentSessionRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/** 一次性任务入口；stdout 为单个 JSON 对象，审批永不读取 stdin。 */
final class PrintCommandLauncher {
    private static final ObjectMapper JSON = new ObjectMapper();

    private PrintCommandLauncher() { }

    static boolean isPrintCommand(String[] args) {
        return args.length > 0 && args[0].equals("-p");
    }

    static int execute(String[] args, PrintStream output, PrintStream diagnostics) {
        if (args.length != 2 || args[1].isBlank()) {
            output.println(JSON.createObjectNode().put("status", "failed")
                    .put("error", "用法: devcli -p \"任务\""));
            return 2;
        }
        try {
            DevCliConfig config = DevCliConfig.load();
            LlmClient client = LlmClientFactory.createFromConfig(config);
            if (client == null) throw new IllegalStateException("未找到可用的 API Key，请检查 provider 配置");
            return run(args[1], client, config, Path.of("."), output, diagnostics);
        } catch (Exception failure) {
            output.println(JSON.createObjectNode().put("status", "failed").put("error", failure.getMessage()));
            return 1;
        }
    }

    static int run(String prompt, LlmClient client, DevCliConfig config, Path project,
                   PrintStream output, PrintStream diagnostics) throws Exception {
        Usage usage = new Usage();
        HitlHandler approvals = new HitlHandler() {
            public ApprovalResult requestApproval(ApprovalRequest request) {
                return ApprovalResult.reject("无交互模式无法取得单次审批，请预先配置允许规则");
            }
            public boolean isEnabled() { return false; }
            public void setEnabled(boolean enabled) { }
        };
        try (HitlToolRegistry tools = new HitlToolRegistry(approvals)
                .withPermissionRules(CliSettings.loadPermissionRules(config))
                .withPermissionMode(CliSettings.startupPermissionMode(config))
                .withPermissionClassifier(CliSettings.permissionClassifier(new AtomicReference<>(client)))) {
            tools.setProjectPath(project.toAbsolutePath().normalize().toString());
            tools.withProjectPermissions(com.devcli.config.ProjectPermissionsStore.openDefault(),
                    com.devcli.config.ProjectPermissionsStore.key(project));
            try (var store = RuntimeCommandLauncher.openRuntimeStore();
                 Agent agent = new Agent(client, tools);
                 PlainRenderer renderer = PlainRenderer.nonInteractive(diagnostics);
                 AgentSessionRuntime session = AgentSessionRuntime.adopt(agent, project)) {
                agent.setRenderer(renderer);
                agent.setPermissionModeSupplier(() -> tools.currentPermissionMode().id());
                var rules = new com.devcli.memory.RuleContext(com.devcli.memory.LongTermMemory.resolveMemoryDir());
                rules.reloadFiles(project.toAbsolutePath().normalize());
                agent.setRuleContextSupplier(rules::renderForPrompt);
                agent.setRunEventSink(RunEventSink.composite(usage, CliApplication.TRACE_SINK));
                var scope = tools.currentPermissionMode().capability() == com.devcli.policy.PermissionMode.Capability.READ_ONLY
                        ? com.devcli.tool.ToolContract.ToolAccessScope.READ_ONLY : com.devcli.tool.ToolContract.ToolAccessScope.FULL;
                String threadId = store.createThread();
                AgentSessionRuntime.RunResult result = com.devcli.runtime.api.ConversationTurnRecorder.run(
                        agent, store, threadId, "react", prompt, prompt,
                        () -> tools.runWithToolAccess(scope, () -> session.runBlocking(prompt)),
                        AgentSessionRuntime.RunResult::output);
                String status = result.cancelled() ? "cancelled"
                        : usage.state == RunEvent.ExecutionState.COMPLETED ? "completed" : "failed";
                ObjectNode response = JSON.createObjectNode().put("status", status).put("output", result.output())
                        .put("thread_id", threadId);
                response.set("usage", usage.json());
                output.println(response);
                return result.cancelled() ? 130 : status.equals("completed") ? 0 : 1;
            }
        }
    }

    private static final class Usage implements RunEventSink {
        long input;
        long output;
        long cached;
        RunEvent.ExecutionState state;

        @Override
        public void emit(RunEvent event) {
            if (event instanceof RunEvent.ModelUsage usage) {
                input += usage.inputTokens();
                output += usage.outputTokens();
                cached += Math.min(usage.inputTokens(), usage.cachedInputTokens());
            } else if (event instanceof RunEvent.ExecutionStateChanged execution) {
                state = execution.state();
            }
        }

        ObjectNode json() {
            return JSON.createObjectNode().put("input_tokens", input).put("output_tokens", output)
                    .put("cached_input_tokens", cached).put("cache_hit_ratio", input == 0 ? 0D : (double) cached / input);
        }
    }
}
