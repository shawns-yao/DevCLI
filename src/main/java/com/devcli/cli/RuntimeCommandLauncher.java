package com.devcli.cli;

import com.devcli.config.DevCliConfig;
import com.devcli.llm.LlmClient;
import com.devcli.llm.LlmClientFactory;
import com.devcli.runtime.HeadlessAgentRunner;
import com.devcli.runtime.api.RuntimeApiServer;
import com.devcli.runtime.api.RuntimeCheckpointCandidateFactory;
import com.devcli.runtime.api.RuntimeCheckpointPolicy;
import com.devcli.runtime.api.RuntimeThreadStore;
import com.devcli.runtime.api.TurnRunner;
import com.devcli.runtime.event.RunEventSink;
import com.devcli.runtime.task.DurableTaskManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CLI 的 Runtime API 与无头任务启动边界。
 */
final class RuntimeCommandLauncher {
    private RuntimeCommandLauncher() {
    }

    static boolean isServeCommand(String[] args) {
        return args != null
                && args.length >= 1
                && "serve".equalsIgnoreCase(args[0])
                && Arrays.stream(args).anyMatch("--http"::equalsIgnoreCase);
    }

    static void startAndBlock(String[] args) {
        DevCliConfig config = DevCliConfig.load();
        LlmClient client = LlmClientFactory.createFromConfig(config);
        if (client == null) {
            System.err.println("错误: 未找到可用的 API Key");
            System.err.println("请在 .env 文件中添加 ANTHROPIC_AUTH_TOKEN、OPENAI_API_KEY、GLM_API_KEY、DEEPSEEK_API_KEY、STEP_API_KEY 或 KIMI_API_KEY");
            throw new IllegalStateException("未找到可用的 API Key");
        }
        int port = parsePort(args, 8080);
        try {
            RuntimeThreadStore store = new RuntimeThreadStore(RuntimeThreadStore.defaultDbPath());
            RuntimeApiServer server = new RuntimeApiServer(
                    store,
                    (threadId, input, eventSink) ->
                            runTurn(threadId, input, eventSink, client, store),
                    port,
                    RuntimeApiServer.configuredApiKey());
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.close();
                store.close();
            }, "devcli-runtime-api-shutdown"));
            server.start();
            System.out.println("DevCLI Runtime API 已启动: http://127.0.0.1:" + server.port());
            System.out.println("认证: Authorization: Bearer <DEVCLI_RUNTIME_API_KEY>");
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("Runtime API 启动失败: " + e.getMessage());
            throw new RuntimeException("Runtime API 启动失败", e);
        }
    }

    static int parsePort(String[] args, int defaultPort) {
        if (args == null) {
            return defaultPort;
        }
        for (int index = 0; index < args.length - 1; index++) {
            if ("--port".equalsIgnoreCase(args[index])) {
                try {
                    return Integer.parseInt(args[index + 1]);
                } catch (NumberFormatException ignored) {
                    return defaultPort;
                }
            }
        }
        return defaultPort;
    }

    static DurableTaskManager openTaskManager(AtomicReference<LlmClient> llmClientRef) {
        try {
            return DurableTaskManager.openDefault(prompt -> runTask(prompt, llmClientRef.get()));
        } catch (Exception e) {
            throw new IllegalStateException("后台任务管理器初始化失败: " + e.getMessage(), e);
        }
    }

    private static String runTask(String prompt, LlmClient llmClient) {
        return HeadlessAgentRunner.run(
                llmClient,
                Path.of("."),
                prompt,
                List.of());
    }

    private static TurnRunner.TurnResult runTurn(
            String threadId, String prompt, RunEventSink eventSink,
            LlmClient llmClient, RuntimeThreadStore store) {
        RuntimeThreadStore.ContextView contextView = store.contextView(threadId);
        List<LlmClient.Message> seed = new ArrayList<>(contextView.checkpointMessages());
        for (RuntimeThreadStore.TurnRecord turn : contextView.turns()) {
            seed.add(LlmClient.Message.user(turn.input()));
            seed.add(LlmClient.Message.assistant(turn.output()));
        }
        HeadlessAgentRunner.RunResult runResult = HeadlessAgentRunner.runDetailed(
                llmClient,
                Path.of("."),
                prompt,
                seed,
                RuntimeCheckpointPolicy.configuredTriggerTokens(),
                eventSink);
        TurnRunner.CheckpointCandidate checkpoint = RuntimeCheckpointCandidateFactory
                .fromHistory(runResult.history(), runResult.compacted())
                .orElse(null);
        return new TurnRunner.TurnResult(runResult.output(), checkpoint);
    }
}
