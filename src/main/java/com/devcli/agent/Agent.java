package com.devcli.agent;

import com.devcli.tool.ToolContract;
import com.devcli.hook.HookLifecycle;
import com.devcli.llm.LlmClient;
import com.devcli.llm.LlmTraceLogger;
import com.devcli.context.ContextProfile;
import com.devcli.context.TokenUsageFormatter;
import com.devcli.lsp.LspDiagnosticReport;
import com.devcli.memory.ConversationHistoryCompactor;
import com.devcli.memory.CompactionContext;
import com.devcli.memory.CompactionResult;
import com.devcli.memory.MemoryManager;
import com.devcli.memory.TokenBudget;
import com.devcli.prompt.PromptAssembler;
import com.devcli.prompt.PromptContext;
import com.devcli.prompt.PromptMode;
import com.devcli.render.PlainRenderer;
import com.devcli.render.Renderer;
import com.devcli.render.StatusInfo;
import com.devcli.concurrent.CancellationContext;
import com.devcli.event.RunEvent;
import com.devcli.event.RunEventSink;
import com.devcli.skill.SkillContextBuffer;
import com.devcli.skill.SkillRegistry;
import com.devcli.util.AnsiStyle;
import com.devcli.tool.ToolRegistry;
import com.devcli.tool.ToolContract.ToolExecutionResult;
import com.devcli.tool.ToolContract.ToolInvocation;
import com.devcli.trace.TraceContext;
import com.devcli.trace.TraceRecorder;
import com.devcli.image.ImageReferenceParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 核心类 - 实现 ReAct 循环
 */
public class Agent implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Agent.class);
    private LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final boolean ownsToolRegistry;
    private com.devcli.event.ConversationJournal conversation;
    private final List<LlmClient.Message> userIntentHistory = new ArrayList<>();
    private boolean userIntentIncomplete;
    private final MemoryManager memoryManager;
    private final ConversationHistoryCompactor historyCompactor;
    private final ContextReferenceGuard.ReferenceRegistry contextReferenceRegistry =
            new ContextReferenceGuard.ReferenceRegistry();
    private Supplier<String> externalContextSupplier = () -> "";
    private Supplier<String> ruleContextSupplier = () -> "";
    private Supplier<CompactionContext> compactionContextSupplier;
    private SkillRegistry skillRegistry;
    private SkillContextBuffer skillContextBuffer;
    private Renderer renderer;
    private RunEventSink runEventSink = RunEventSink.NO_OP;
    private final TraceRecorder traceRecorder = new TraceRecorder();
    private Supplier<String> permissionModeSupplier = () -> null;
    private final PromptAssembler promptAssembler = PromptAssembler.createDefault();
    private AgentTurnInbox turnInbox = new AgentTurnInbox();
    private final AtomicReference<com.devcli.concurrent.CancellationToken> activeCancellationToken =
            new AtomicReference<>();
    private String currentSkillActivationText = "";
    /** ReAct 的工作记忆跨用户轮次保留；显式清理后再复用同一会话标识重新开启。 */
    private final String sessionTaskId = "react-run-"
            + java.util.UUID.randomUUID().toString().substring(0, 8);
    /** 单会话累计派出量。与工作记忆同生命周期，不随单轮 Token 预算重置。详见 ADR 0010。 */
    private final AtomicInteger delegationSpawnCount = new AtomicInteger();
    private volatile DelegationState delegationState;

    public Agent(LlmClient llmClient) {
        this(llmClient, new ToolRegistry(), true);
    }

    public Agent(LlmClient llmClient, ToolRegistry toolRegistry) {
        this(llmClient, toolRegistry, false);
    }

    Agent(LlmClient llmClient, ToolRegistry toolRegistry, boolean ownsToolRegistry) {
        this(llmClient, toolRegistry, ownsToolRegistry, new MemoryManager(llmClient));
    }

    /** 允许嵌入方选择记忆存储，委派不会额外创建持久记忆实例。 */
    public Agent(LlmClient llmClient, ToolRegistry toolRegistry, MemoryManager memoryManager) {
        this(llmClient, toolRegistry, false, memoryManager);
    }

    private Agent(LlmClient llmClient, ToolRegistry toolRegistry, boolean ownsToolRegistry,
                  MemoryManager memoryManager) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.ownsToolRegistry = ownsToolRegistry;
        this.conversation = new com.devcli.event.ConversationJournal(List.of());
        this.memoryManager = java.util.Objects.requireNonNull(memoryManager, "memoryManager");
        this.historyCompactor = new ConversationHistoryCompactor(llmClient);
        AgentRuntimeSupport.configureCompactor(
                historyCompactor,
                memoryManager,
                this.toolRegistry,
                this::buildPostCompactRestoreSection,
                this::buildCompactBoundaryRuntimeState);
        AgentRuntimeSupport.bindMemory(this.toolRegistry, memoryManager);
        if (toolRegistry instanceof com.devcli.hitl.HitlToolRegistry approvals) {
            approvals.withTrustedIntentContext(this::trustedIntentContext);
        }
        conversation.add(LlmClient.Message.system(buildSystemPrompt()));
    }

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
        this.memoryManager.setLlmClient(llmClient);
        this.historyCompactor.setLlmClient(llmClient);
        this.toolRegistry.setContextProfile(memoryManager.getContextProfile());
    }

    public void setMemoryCuratorClient(LlmClient curatorClient) {
        memoryManager.setMemoryCuratorClient(curatorClient);
    }

    /**
     * 注入历史对话（Runtime API 跨 turn 上下文重放）：消息插入到 system message 之后。
     * 仅允许在对话为空（只有 system message）时调用一次；已有对话内容时忽略并 warn，
     * 防止历史插入到进行中的上下文中间破坏顺序。注入后的窗口治理仍由
     * {@link ConversationHistoryCompactor} 在 run 循环内统一负责。
     */
    public void seedHistory(List<LlmClient.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        if (conversation.size() > 1) {
            log.warn("seedHistory ignored: conversation already has {} messages", conversation.size());
            return;
        }
        conversation.addAll(messages);
        memoryManager.beginTask(sessionTaskId);
        memoryManager.restoreWorkPlan(messages);
    }

    /**
     * 当前对话历史的只读快照。
     *
     * <p>这是模型窗口投影，包含派生上下文，不得作为用户授权来源。</p>
     *
     * <p>返回副本而不是内部列表，避免调用方修改模型窗口。</p>
     */
    public List<LlmClient.Message> conversationHistorySnapshot() {
        return conversation.messages();
    }

    /** 仅接受宿主收到的用户原文；不从摘要、工具结果或历史投影恢复授权。 */
    public synchronized void recordUserIntent(String input) {
        if (input == null || input.isBlank()) return;
        userIntentHistory.add(LlmClient.Message.user(input));
        if (userIntentHistory.size() > 64) {
            userIntentHistory.remove(0);
            userIntentIncomplete = true;
        }
    }

    public synchronized String trustedIntentContext() {
        if (userIntentIncomplete) throw new com.devcli.hitl.TrustedIntentContext.IncompleteContextException();
        return com.devcli.hitl.TrustedIntentContext.render(userIntentHistory);
    }

    public synchronized void markTrustedIntentIncomplete() {
        userIntentIncomplete = true;
    }

    public void setExternalContextSupplier(Supplier<String> externalContextSupplier) {
        this.externalContextSupplier = externalContextSupplier == null ? () -> "" : externalContextSupplier;
    }

    /** 注入规则上下文渲染源；返回的 Markdown 整段会进入 system prompt。 */
    public void setRuleContextSupplier(Supplier<String> ruleContextSupplier) {
        this.ruleContextSupplier = ruleContextSupplier == null ? () -> "" : ruleContextSupplier;
        memoryManager.setRuleContextSupplier(this.ruleContextSupplier);
    }

    /** @deprecated 使用 {@link #setRuleContextSupplier(Supplier)}。 */
    @Deprecated
    public void setStickyMemorySupplier(Supplier<String> supplier) { setRuleContextSupplier(supplier); }

    public void setSkillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    public void setSkillContextBuffer(SkillContextBuffer skillContextBuffer) {
        this.skillContextBuffer = skillContextBuffer;
    }

    public void setRenderer(Renderer renderer) {
        this.renderer = renderer;
    }

    public void setRunEventSink(RunEventSink runEventSink) {
        this.runEventSink = runEventSink == null ? RunEventSink.NO_OP : runEventSink;
    }

    public RunEventSink getRunEventSink() {
        return runEventSink;
    }

    /** 先追加消息事实，再推进可裁剪的模型窗口。 */
    public void appendConversationMessage(LlmClient.Message message) {
        conversation.add(message);
    }

    public com.devcli.event.ConversationJournal.Recording recordConversation() {
        return conversation.recordTo(runEventSink);
    }

    public void restoreConversation(List<RunEvent> events, List<LlmClient.Message> imported) {
        clearHistory();
        if (events.isEmpty()) {
            var initial = new ArrayList<>(imported);
            if (initial.isEmpty() || !"system".equals(initial.get(0).role())) initial.add(0, conversation.get(0));
            conversation = new com.devcli.event.ConversationJournal(initial);
        } else {
            conversation = com.devcli.event.ConversationJournal.restore(events);
            conversation.revise("tool_pairing_recovery", messages -> { messages.clear(); messages.addAll(imported); return null; });
        }
        memoryManager.restoreWorkPlan(conversation.messages());
    }

    /** 绑定 Runtime 事件游标，使压缩边界可在重启后定位并校验。 */
    public void setCompactionSourceCursorSupplier(
            java.util.function.Supplier<ConversationHistoryCompactor.CompactionSourceCursor> supplier) {
        historyCompactor.setCompactionSourceCursorSupplier(supplier);
    }

    public void setCompactionContextSupplier(Supplier<CompactionContext> supplier) {
        this.compactionContextSupplier = supplier;
    }

    private CompactionContext compactionContext(int triggerTokens) {
        CompactionContext supplied = compactionContextSupplier == null
                ? null : compactionContextSupplier.get();
        if (supplied == null) {
            supplied = AgentRuntimeSupport.buildCompactionContext(
                    triggerTokens, memoryManager, toolRegistry, sessionTaskId);
        }
        return supplied.withTriggerTokens(triggerTokens);
    }

    public void setOriginalHistorySupplier(
            java.util.function.Supplier<List<LlmClient.Message>> supplier) {
        historyCompactor.setOriginalHistorySupplier(supplier);
    }

    public void setTurnInbox(AgentTurnInbox turnInbox) {
        this.turnInbox = turnInbox == null ? new AgentTurnInbox() : turnInbox;
    }

    public AgentTurnInbox getTurnInbox() {
        return turnInbox;
    }

    public void abort() {
        if (delegationState != null) delegationState.cancelAll();
        com.devcli.concurrent.CancellationToken token = activeCancellationToken.get();
        if (token != null) {
            token.cancel();
        }
    }

    public boolean isRunning() {
        return activeCancellationToken.get() != null;
    }

    /**
     * 注入权限模式的快照源，用于状态栏 / StatusInfo 显示。
     * Main 启动后用 {@code reactAgent.setPermissionModeSupplier(() -> hitlToolRegistry.currentPermissionMode().id())} 接进来。
     *
     * <p>取代原来的 HITL 开关快照：状态栏要显示的不再是「问不问」这一个布尔，而是当前模式
     * （只读 / 自动放行编辑 / 直接放行等）。布尔表达不了模式差异，见 docs/adr/0006。</p>
     */
    public void setPermissionModeSupplier(Supplier<String> supplier) {
        this.permissionModeSupplier = supplier == null ? () -> null : supplier;
    }

    /**
     * 获取渲染器；首次调用时如果未设置，懒加载一个 {@link PlainRenderer} 兜底，
     * 保证旧调用方（构造 Agent 后没有 setRenderer 的代码、单测等）行为不变。
     */
    private Renderer renderer() {
        if (renderer == null) {
            renderer = new PlainRenderer();
        }
        return renderer;
    }

    /**
     * 运行 Agent 循环
     */
    public String run(String userInput) {
        return run(userInput, LlmClient.ToolChoice.AUTO);
    }

    /**
     * 运行 Agent 循环，并允许受控执行入口约束首轮工具选择。
     */
    public String run(String userInput, LlmClient.ToolChoice initialToolChoice) {
        return run(userInput, userInput, initialToolChoice);
    }

    /** 用户原文与附件展开后的模型输入分别传入，附件不构成授权。 */
    public String run(String userInput, String modelInput, LlmClient.ToolChoice initialToolChoice) {
        recordUserIntent(userInput);
        com.devcli.concurrent.CancellationToken inheritedToken = CancellationContext.current();
        activeCancellationToken.set(inheritedToken);
        memoryManager.beginTask(sessionTaskId);
        memoryManager.setActiveProjectScope(toolRegistry.getProjectPath());
        String result = "";
        try (var recording = conversation.recordTo(runEventSink)) {
            result = runInternal(modelInput, initialToolChoice);
            return result;
        } finally {
            memoryManager.completeTask(sessionTaskId, userInput, result, toolRegistry.getProjectPath());
            memoryManager.endTask(sessionTaskId);
            historyCompactor.setSummaryCallGuard(null);
            historyCompactor.setSummaryUsageConsumer(null);
            activeCancellationToken.compareAndSet(inheritedToken, null);
        }
    }

    private String runInternal(String userInput, LlmClient.ToolChoice initialToolChoice) {
        LlmClient.ToolChoice effectiveInitialToolChoice = initialToolChoice == null
                ? LlmClient.ToolChoice.AUTO
                : initialToolChoice;
        log.info("ReAct run started: inputLength={}", userInput == null ? 0 : userInput.length());
        currentSkillActivationText = userInput == null ? "" : userInput;
        memoryManager.setActiveProjectScope(toolRegistry.getProjectPath());
        // 写入当前会话工作记忆；真实 messages 由 conversation 维护。
        memoryManager.addUserMessage(userInput);
        // system prompt 只放会话级稳定内容，仅在真变化时替换，保住前缀缓存
        refreshSystemPromptIfChanged();

        // 检索相关长期记忆，与 skill 索引、工作记忆一起作为当轮快照前置到 user 消息
        ContextProfile contextProfile = memoryManager.getContextProfile();
        String memoryContext = memoryManager.buildContextForQuery(userInput, contextProfile.memoryContextTokens());

        // 添加用户输入到历史（当轮快照与 skill body 依次前置到原文之前）
        String userMessageContent = prependTurnContext(
                prependSkillBodies(userInput), buildTurnContext(memoryContext));
        LlmClient.Message userMessage = ImageReferenceParser.userMessage(
                userMessageContent,
                Path.of(toolRegistry.getProjectPath()));
        appendConversationMessage(userMessage);
        StringBuilder reasoningTranscript = new StringBuilder();
        AgentStreamPresenter streamRenderer = AgentStreamPresenter.react(renderer());

        long startNanos = System.nanoTime();
        AgentBudget budget = AgentBudget.fromLlmClient(llmClient);
        AgentRuntimeSupport.bindCompactionBudget(historyCompactor, budget, memoryManager);
        TraceContext traceContext = TraceContext.root("react");
        TurnExecutionMetrics metrics = new TurnExecutionMetrics();
        traceRecorder.record(traceContext, "run.start", java.util.Map.of(
                "model", modelLabel(),
                "inputChars", userInput == null ? 0 : userInput.length()
        ));
        pushStatus(budget, startNanos, "running");

        // 主退出条件 = LLM 自己决定（不再调用工具就返回）；
        // budget 仅在 token 用尽 / 检测到死循环 / 超出硬轮数时兜底。
        LlmClient primaryClient = llmClient;
        Path projectRoot = Path.of(toolRegistry.getProjectPath());
        if (delegationState == null || !delegationState.belongsTo(projectRoot)) {
            if (delegationState != null) delegationState.close();
            delegationState = new DelegationState(projectRoot);
        }
        DelegationSession delegation = new DelegationSession(toolRegistry,
                role -> com.devcli.llm.LlmClientFactory.createDelegatedAgent(primaryClient, role),
                budget, conversation.get(0).content(), runEventSink, delegationSpawnCount, delegationState);
        return toolRegistry.runWithDelegation(delegation, () -> new AgentExecutionEngine<String>(
                llmClient, budget, HookLifecycle.load(toolRegistry), contextReferenceRegistry).run(
                new AgentExecutionEngine.Delegate<>() {
                    @Override
                    public com.devcli.event.ConversationJournal history() {
                        return conversation;
                    }

                    @Override
                    public List<LlmClient.Tool> toolDefinitions(int iteration) {
                        return toolSnapshot(iteration).definitions();
                    }

                    @Override
                    public com.devcli.tool.ToolContract.ToolSnapshot toolSnapshot(int iteration) {
                        com.devcli.tool.ToolContract.ToolSnapshot snapshot =
                                toolRegistry.snapshotForCurrentAccess();
                        contextDiagnostics().logRequestContext("react iteration=" + iteration, snapshot.definitions());
                        streamRenderer.beginThinking();
                        return snapshot;
                    }

                    @Override
                    public com.devcli.tool.ToolPresentation toolPresentation(String toolName) {
                        return toolRegistry.toolPresentation(toolName);
                    }

                    @Override
                    public LlmClient.StreamListener streamListener() {
                        return LlmClient.StreamListener.NO_OP;
                    }

                    @Override
                    public RunEventSink eventSink() {
                        return RunEventSink.composite(runEventSink, streamRenderer);
                    }

                    @Override
                    public LlmClient.ToolChoice toolChoice(int iteration) {
                        return iteration == 1
                                ? effectiveInitialToolChoice
                                : LlmClient.ToolChoice.AUTO;
                    }

                    @Override
                    public List<AgentTurnInbox.Item> drainSteeringMessages() {
                        return turnInbox.drainSteering();
                    }

                    @Override
                    public List<AgentTurnInbox.Item> drainFollowUpMessages() {
                        return turnInbox.drainFollowUp();
                    }

                    @Override
                    public void queuedMessagesDelivered(AgentTurnInbox.Channel channel,
                                                        List<AgentTurnInbox.Item> messages) {
                        streamRenderer.resetBetweenIterations();
                        for (AgentTurnInbox.Item message : messages) {
                            renderer().stream().println("> " + message.text());
                            memoryManager.addUserMessage(message.text());
                            recordUserIntent(message.text());
                        }
                        AgentTurnInbox.Snapshot snapshot = turnInbox.snapshot();
                        runEventSink.emit(new RunEvent.QueueUpdated(
                                channel.name().toLowerCase(java.util.Locale.ROOT),
                                snapshot.steering().size(),
                                snapshot.followUp().size(),
                                "delivered"));
                    }

                    @Override
                    public void beforeIteration(int iteration, AgentBudget currentBudget) {
                        String delegationNotice = delegationState.drainNotifications();
                        if (!delegationNotice.isBlank()) {
                            conversation.add(LlmClient.Message.internalUser(delegationNotice));
                        }
                        // 不在这里重建 system prompt：迭代内重建会让 messages[0] 每轮变化，
                        // 其后全部历史前缀失配。工具证据本轮已在 tool_result 原文里，
                        // 跨压缩边界由 ConversationHistoryCompactor 的 post_compact_restore 兜底。
                        injectPendingLspDiagnostics();
                        maybeCompactHistory();
                    }

                    @Override
                    public void afterResponse(LlmClient.ChatResponse response, int iteration,
                                              AgentBudget currentBudget) {
                        traceRecorder.record(traceContext, "llm.response", java.util.Map.of(
                                "iteration", iteration,
                                "toolCalls", response.toolCalls() == null ? 0 : response.toolCalls().size(),
                                "inputTokens", response.inputTokens(),
                                "outputTokens", response.outputTokens()
                        ));
                        LlmTraceLogger.logReasoning(log,
                                "react iteration=" + iteration,
                                llmClient,
                                response.reasoningContent());
                        pushStatus(currentBudget, startNanos, "running");
                    }

                    @Override
                    public LlmClient.ChatResponse normalizeResponse(
                            LlmClient.ChatResponse response,
                            int iteration,
                            AgentBudget currentBudget) {
                        return RequiredToolProtocol.adaptRequiredToolEnvelope(
                                response, effectiveInitialToolChoice);
                    }

                    @Override
                    public String retryInstructionAfterResponseWithoutTools(
                            LlmClient.ChatResponse response,
                            int iteration,
                            AgentBudget currentBudget) {
                        if (iteration == 1 && effectiveInitialToolChoice.hasSpecificTool()) {
                            return RequiredToolProtocol.buildToolEnvelopeRepairPrompt(
                                    effectiveInitialToolChoice.toolName());
                        }
                        return "";
                    }

                    @Override
                    public void beforeToolExecution(LlmClient.ChatResponse response, int iteration,
                                                    AgentBudget currentBudget) {
                        appendReasoning(reasoningTranscript, response.reasoningContent());
                        log.info("LLM requested {} tool call(s) in iteration {}",
                                response.toolCalls().size(), iteration);
                        streamRenderer.resetBetweenIterations();
                    }

                    @Override
                    public List<ToolExecutionResult> executeTools(List<LlmClient.ToolCall> toolCalls,
                                                                  int iteration) {
                        return executeToolCalls(toolCalls, iteration);
                    }

                    @Override
                    public List<ToolExecutionResult> executeTools(List<LlmClient.ToolCall> toolCalls,
                                                                  int iteration,
                                                                  com.devcli.tool.ToolContract.ToolSnapshot snapshot) {
                        return executeToolCalls(toolCalls, iteration, snapshot);
                    }

                    @Override
                    public void afterToolResults(LlmClient.ChatResponse response,
                                                 List<ToolExecutionResult> toolResults,
                                                 int iteration,
                                                 AgentBudget currentBudget) {
                        metrics.record(toolResults);
                        for (ToolExecutionResult toolResult : toolResults) {
                            traceRecorder.record(traceContext, "tool.result", java.util.Map.of(
                                    "iteration", iteration,
                                    "tool", toolResult.name(),
                                    "elapsedMillis", toolResult.elapsedMillis(),
                                    "timedOut", toolResult.timedOut(),
                                    "resultPreview", preview(toolResult.result(), 300)
                            ));
                            memoryManager.addToolResult(toolResult);
                        }
                        appendImageToolMessages(toolResults);
                    }

                    @Override
                    public String instructionAfterToolResults(
                            LlmClient.ChatResponse response,
                            List<ToolExecutionResult> toolResults,
                            int iteration,
                            AgentBudget currentBudget) {
                        return memoryManager.drainCurrentStateConflictInstruction();
                    }

                    @Override
                    public java.util.Map<String, String> refreshStaleContext() {
                        return toolRegistry.refreshStaleContext(
                                toolRegistry.currentResourceLeaseStep());
                    }

                    @Override
                    public String contextScope() {
                        return toolRegistry.currentResourceLeaseStep();
                    }

                    @Override
                    public String completed(LlmClient.ChatResponse response,
                                            AgentBudget currentBudget) {
                        appendReasoning(reasoningTranscript, response.reasoningContent());
                        memoryManager.addAssistantMessage(response.content());
                        memoryManager.recordTokenUsage(
                                response.inputTokens(),
                                response.outputTokens(),
                                response.cachedInputTokens());
                        pushStatus(currentBudget, startNanos, "idle");
                        log.info("ReAct run finished: inputTokens={}, outputTokens={}, reasoningChars={}, answerChars={}",
                                currentBudget.totalInputTokens(),
                                currentBudget.totalOutputTokens(),
                                response.reasoningContent() == null ? 0 : response.reasoningContent().length(),
                                response.content() == null ? 0 : response.content().length());
                        if (log.isDebugEnabled()) {
                            log.debug("Assistant answer preview: {}", preview(response.content(), 500));
                        }

                        if (streamRenderer.hasStreamedOutput()) {
                            streamRenderer.finish();
                            maintainSessionPreSummaryAfterTurn(
                                    metrics.toolCalls, metrics.largestToolResultChars, currentBudget);
                            return "";
                        }
                        streamRenderer.clearThinkingPanel();
                        String finalResponse = formatUserFacingResponse(
                                reasoningTranscript.toString(), response.content());
                        maintainSessionPreSummaryAfterTurn(
                                metrics.toolCalls, metrics.largestToolResultChars, currentBudget);
                        return finalResponse;
                    }

                    @Override
                    public String cancelled(AgentBudget currentBudget) {
                        log.info("ReAct run cancelled");
                        streamRenderer.finish();
                        pushStatus(currentBudget, startNanos, "idle");
                        return "⏹️ 已取消当前任务。";
                    }

                    @Override
                    public String budgetExceeded(AgentBudget.ExitReason reason,
                                                 AgentBudget currentBudget) {
                        FailureFeedback feedback = FailureFeedback.forBudget(reason, currentBudget);
                        log.warn("ReAct run exhausted budget: reason={}, iteration={}, tokens={}/{}",
                                reason, currentBudget.iteration(),
                                currentBudget.totalInputTokens() + currentBudget.totalOutputTokens(),
                                currentBudget.tokenBudget());
                        streamRenderer.finish();
                        pushStatus(currentBudget, startNanos, "idle");
                        return "❌ " + feedback.render();
                    }

                    @Override
                    public String iterationLimitReached(AgentBudget currentBudget) {
                        return budgetExceeded(
                                AgentBudget.ExitReason.HARD_ITERATION_LIMIT, currentBudget);
                    }

                    @Override
                    public String failed(IOException error, AgentBudget currentBudget) {
                        log.error("LLM call failed in ReAct loop", error);
                        streamRenderer.finish();
                        pushStatus(currentBudget, startNanos, "idle");
                        return "❌ " + FailureFeedback.fromReason(
                                "调用 LLM 失败: " + error.getMessage()).render();
                    }
                }));
    }

    private static final class TurnExecutionMetrics {
        private int toolCalls;
        private int largestToolResultChars;

        private void record(List<ToolExecutionResult> results) {
            toolCalls += results.size();
            for (ToolExecutionResult result : results) {
                largestToolResultChars = Math.max(
                        largestToolResultChars,
                        result.result() == null ? 0 : result.result().length());
            }
        }
    }

    /**
     * 清空对话历史（保留系统提示），不影响长期记忆
     */
    public void clearHistory() {
        if (delegationState != null) {
            delegationState.close();
            delegationState = null;
        }
        LlmClient.Message systemMsg = conversation.get(0);
        conversation.reset(List.of(systemMsg));
        synchronized (this) {
            userIntentHistory.clear();
            userIntentIncomplete = false;
        }

        // 清空当前会话工作记忆
        memoryManager.clearShortTerm();
        if (skillContextBuffer != null) {
            skillContextBuffer.clear();
        }
    }

    /**
     * 仅在 system prompt 内容真正变化时替换 conversation[0]。
     *
     * <p>prompt cache 契约：system prompt 是整个请求的 token 前缀，逐字节稳定才能让其后的
     * 全部对话历史命中自动前缀缓存。这里只包含会话级规则；Project Context、记忆与
     * Skill 索引进入当轮消息，只有会话规则实际变化时才重建此前缀。
     */
    private void refreshSystemPromptIfChanged() {
        String assembled = buildSystemPrompt();
        LlmClient.Message current = conversation.isEmpty() ? null : conversation.get(0);
        if (current != null && "system".equals(current.role()) && assembled.equals(current.content())) {
            return;
        }
        conversation.revise("session_rules", messages -> { messages.set(0, LlmClient.Message.system(assembled)); return null; });
    }

    /**
     * 组装 system prompt。按轮次变化的记忆 / skill 索引 / 工作记忆不在这里，
     * 由 {@link #buildTurnContext(String)} 前置到当轮 user 消息。
     */
    private String buildSystemPrompt() {
        return promptAssembler.assemble(PromptMode.AGENT, PromptContext.builder()
                .ruleContext(buildRuleContext())
                .build());
    }

    /**
     * 组装当轮上下文快照（长期记忆检索结果 / skill 索引 / 工作记忆），
     * 由调用方前置到当轮 user 消息内容里——append-only，不改写既有消息。
     */
    private String buildTurnContext(String memoryContext) {
        return promptAssembler.assembleTurnContext(PromptContext.builder()
                .externalContext(buildExternalContext())
                .memoryContext(memoryContext)
                .sessionMemory(memoryManager.buildSessionMemorySection())
                .skillIndex(buildSkillIndex())
                .build());
    }

    private static String prependTurnContext(String content, String turnContext) {
        if (turnContext == null || turnContext.isBlank()) {
            return content;
        }
        return turnContext + "\n\n" + content;
    }

    private String buildRuleContext() {
        try {
            String rules = ruleContextSupplier.get();
            return rules == null ? "" : rules.trim();
        } catch (Exception e) {
            log.warn("Failed to render rule context", e);
            return "";
        }
    }

    private void maybeCompactHistory() {
        if (historyCompactor == null) return;
        ContextProfile profile = memoryManager.getContextProfile();
        int toolDefinitionTokens = TokenBudget.estimateToolDefinitionsTokens(
                toolRegistry.getToolDefinitions());
        int trigger = profile.historyTriggerTokens(toolDefinitionTokens);
        try {
            String projectPath = toolRegistry.getProjectPath();
            if (projectPath != null) {
                historyCompactor.setMicrocompactOutputRoot(Path.of(projectPath));
            }
            CompactionResult compaction = conversation.revise("compaction", messages -> historyCompactor.compactIfNeeded(
                    messages, compactionContext(trigger)));
            boolean compacted = compaction.compacted();
            if (compacted) {
                renderer().stream().println("📦 上下文接近窗口上限，已把早期对话压缩为摘要后继续。");
            }
        } catch (Exception e) {
            log.warn("conversation compaction failed", e);
        }
    }

    private void injectPendingLspDiagnostics() {
        LspDiagnosticReport report = toolRegistry.flushPendingLspDiagnostics();
        if (report == null || report.isEmpty()) {
            return;
        }
        conversation.add(LlmClient.Message.internalUser(report.promptText()));
        renderer().stream().println(report.displayText());
        log.info("Injected LSP diagnostics into ReAct conversation");
    }

    private String buildSkillIndex() {
        return AgentRuntimeSupport.buildSkillIndex(
                skillRegistry, currentSkillActivationText, toolRegistry, log);
    }

    private String prependSkillBodies(String userInput) {
        if (skillContextBuffer == null || skillContextBuffer.isEmpty()) {
            return userInput;
        }
        return AgentRuntimeSupport.prependSkillBodies(
                skillContextBuffer, "用户输入：\n" + userInput);
    }

    private String buildPostCompactRestoreSection() {
        return AgentRuntimeSupport.buildPostCompactRestoreSection(
                memoryManager.buildPostCompactRestoreSection(), toolRegistry, skillContextBuffer);
    }

    private com.devcli.memory.CompactBoundaryRuntimeState buildCompactBoundaryRuntimeState() {
        return AgentRuntimeSupport.buildCompactBoundaryRuntimeState(
                memoryManager, toolRegistry, skillContextBuffer, false);
    }

    private void maintainSessionPreSummaryAfterTurn(int turnToolCalls, int largestToolResultChars, AgentBudget budget) {
        // 预摘要只用于为未来的语义压缩准备可复用缓存，不能阻塞主 Agent turn。
        // Provider 连接可能因半关闭的 SSE 响应等待到 callTimeout；主链路应继续执行，
        // 真正达到阈值时由 ConversationHistoryCompactor 自己负责有界重试/降级。
        java.util.concurrent.CompletableFuture<MemoryManager.SessionPreSummaryMaintenanceResult> future =
                memoryManager.maintainSessionPreSummaryAfterTurnAsync(
                        conversation.messages(),
                        turnToolCalls,
                        largestToolResultChars,
                        memoryManager.getContextProfile().historyTriggerTokens(
                                TokenBudget.estimateToolDefinitionsTokens(toolRegistry.getToolDefinitions())),
                        () -> (long) budget.totalInputTokens() + budget.totalOutputTokens() < budget.tokenBudget(),
                        response -> budget.recordTokens(response.inputTokens(), response.outputTokens(),
                                response.cachedInputTokens()));
        future
                .whenComplete((result, error) -> {
                    if (error != null) {
                        log.debug("session pre-summary maintenance finished asynchronously with error", error);
                    } else if (result == MemoryManager.SessionPreSummaryMaintenanceResult.MAINTAINED) {
                        log.debug("session pre-summary refreshed after ReAct turn");
                    }
                });
    }

    private String buildExternalContext() {
        if (!memoryManager.getContextProfile().mcpResourceIndexEnabled()) {
            return "";
        }
        try {
            String context = externalContextSupplier.get();
            return context == null ? "" : context.trim();
        } catch (Exception e) {
            log.warn("Failed to build external context", e);
            return "";
        }
    }

    /**
     * 获取对话历史（用于调试）
     */
    public List<LlmClient.Message> getConversationHistory() {
        return new ArrayList<>(conversation.messages());
    }

    public boolean compactHistoryForPersistence(int triggerTokens) {
        if (triggerTokens <= 0) return false;
        try (var recording = recordConversation()) {
            return conversation.revise("compaction", messages -> historyCompactor.compactIfNeeded(
                    messages, compactionContext(triggerTokens))).compacted();
        }
    }

    public com.devcli.memory.CompactionResult compactHistoryNow(int keepRecentTurns) {
        AgentRuntimeSupport.bindCompactionBudget(historyCompactor, AgentBudget.fromLlmClient(llmClient), memoryManager);
        int toolTokens = TokenBudget.estimateToolDefinitionsTokens(toolRegistry.getToolDefinitions());
        int trigger = memoryManager.getContextProfile().historyTriggerTokens(toolTokens);
        try (var recording = recordConversation()) {
            return conversation.revise("manual_compaction", messages ->
                    historyCompactor.compactNow(messages, compactionContext(trigger), keepRecentTurns));
        }
    }

    /**
     * 获取记忆管理器
     */
    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    /**
     * 关闭底层 MemoryManager（释放 SQLite 长期记忆连接）。
     * 主要给单元测试 / 短生命周期场景用；Main 长进程不需要主动调，JVM 退出释放。
     */
    @Override
    public void close() {
        if (delegationState != null) delegationState.close();
        try {
            if (memoryManager != null) {
                memoryManager.close();
            }
        } finally {
            if (ownsToolRegistry && toolRegistry != null) {
                toolRegistry.close();
            }
        }
    }

    public String getContextStatus() { return contextDiagnostics().status(); }

    private AgentContextDiagnostics contextDiagnostics() {
        return new AgentContextDiagnostics(llmClient, toolRegistry, memoryManager, conversation.messages());
    }

    private String modelLabel() { return llmClient.getModelName() + " (" + llmClient.getProviderName() + ")"; }

    /**
     * 获取工具注册表（用于同步项目路径等配置）
     */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    /** 把当前预算/耗时/HITL 状态推送给 renderer 状态栏。 */
    private void pushStatus(AgentBudget budget, long startNanos, String phase) {
        try {
            String model = llmClient == null ? "—" : llmClient.getModelName();
            long totalTokens = budget == null ? 0L
                    : (long) (budget.totalInputTokens() + budget.totalOutputTokens());
            long contextWindow = llmClient == null ? 0L : llmClient.maxContextWindow();
            String permissionMode = permissionModeSupplier.get();
            long elapsed = (System.nanoTime() - startNanos) / 1_000_000L;
            String cost = budget == null ? null : TokenUsageFormatter.estimatedCostCny(
                    llmClient,
                    budget.totalInputTokens(),
                    budget.totalOutputTokens(),
                    budget.totalCachedInputTokens());
            renderer().updateStatus(StatusInfo.tokens(
                    model,
                    contextWindow,
                    budget == null ? 0L : budget.totalInputTokens(),
                    budget == null ? 0L : budget.totalOutputTokens(),
                    budget == null ? 0L : budget.totalCachedInputTokens(),
                    cost,
                    permissionMode,
                    elapsed,
                    phase == null || phase.isBlank()
                            ? (totalTokens > 0 || elapsed > 0 ? "running" : "idle")
                            : phase));
        } catch (Exception e) {
            log.debug("status push failed", e);
        }
    }

    private void appendReasoning(StringBuilder reasoningTranscript, String reasoningContent) {
        if (reasoningContent == null || reasoningContent.isBlank()) {
            return;
        }
        if (!reasoningTranscript.isEmpty()) {
            reasoningTranscript.append("\n\n");
        }
        reasoningTranscript.append(reasoningContent.trim());
    }

    private List<ToolExecutionResult> executeToolCalls(List<LlmClient.ToolCall> toolCalls, int iteration) {
        return executeToolCalls(toolCalls, iteration, null);
    }

    private List<ToolExecutionResult> executeToolCalls(
            List<LlmClient.ToolCall> toolCalls,
            int iteration,
            com.devcli.tool.ToolContract.ToolSnapshot snapshot) {
        List<ToolInvocation> invocations = new ArrayList<>();
        for (LlmClient.ToolCall toolCall : toolCalls) {
            String toolName = toolCall.function().name();
            String toolArgs = toolCall.function().arguments();
            log.info("Scheduling tool: {} (iteration={})", toolName, iteration);
            log.debug("Tool args [{}]: {}", toolName, toolArgs);
            invocations.add(new ToolInvocation(toolCall.id(), toolName, toolArgs));
        }

        if (invocations.size() > 1) {
            log.info("Executing {} tool calls in parallel (iteration={})", invocations.size(), iteration);
        }
        List<ToolExecutionResult> results = toolRegistry.executeTools(invocations, snapshot);
        for (ToolExecutionResult result : results) {
            log.debug("Tool result preview [{}]: {}", result.name(), preview(result.result(), 300));
            emitToolResultSummary(result);
        }
        return results;
    }

    private void emitToolResultSummary(ToolExecutionResult result) {
        if (result == null || result.name() == null) {
            return;
        }
        if (!"web_search".equals(result.name()) && !"web_fetch".equals(result.name())) {
            return;
        }
        String title = result.presentation().title().isBlank()
                ? result.name()
                : result.presentation().title();
        String target = extractJsonArg(
                result.argumentsJson(), result.presentation().primaryArgument());
        String terminal = result.status() == com.devcli.tool.ToolStatus.SUCCESS
                ? "已完成"
                : "状态 " + result.status().name();
        renderer().stream().println(AnsiStyle.subtle("  → " + title
                + (target.isBlank() ? "" : " · " + target)
                + " · " + terminal));
    }

    private String extractJsonArg(String json, String key) {
        if (json == null || json.isBlank() || key == null || key.isBlank()) {
            return "";
        }
        try {
            return new ObjectMapper().readTree(json).path(key).asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private void appendImageToolMessages(List<ToolExecutionResult> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return;
        }
        for (ToolExecutionResult result : toolResults) {
            if (!result.hasImageParts()) {
                continue;
            }
            List<LlmClient.ContentPart> parts = new ArrayList<>();
            parts.add(LlmClient.ContentPart.text("工具 " + result.name() + " 返回了图片内容，请结合上面的工具文本结果分析。"));
            parts.addAll(result.imageParts());
            conversation.add(LlmClient.Message.user(parts, LlmClient.MessageSource.TOOL));
        }
    }

    private String formatUserFacingResponse(String reasoningContent, String answer) {
        String normalizedReasoning = reasoningContent == null ? "" : reasoningContent.trim();
        String normalizedAnswer = answer == null ? "" : answer.trim();

        if (normalizedReasoning.isEmpty()) {
            return normalizedAnswer;
        }
        if (normalizedAnswer.isEmpty()) {
            return "🧠 思考过程:\n" + normalizedReasoning;
        }
        return "🧠 思考过程:\n" + normalizedReasoning + "\n\n▪ " + normalizedAnswer;
    }

    private String preview(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

}
