package com.devcli.agent;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.devcli.llm.LlmClient;
import com.devcli.llm.LlmException;
import com.devcli.llm.LlmTraceLogger;
import com.devcli.lsp.LspDiagnosticReport;
import com.devcli.memory.CompactBoundaryRuntimeState;
import com.devcli.memory.ConversationHistoryCompactor;
import com.devcli.memory.PostCompactRestoreContext;
import com.devcli.context.ContextProfile;
import com.devcli.prompt.PromptAssembler;
import com.devcli.prompt.PromptContext;
import com.devcli.prompt.PromptMode;
import com.devcli.skill.SkillContextBuffer;
import com.devcli.skill.SkillIndexFormatter;
import com.devcli.skill.SkillRegistry;
import com.devcli.tool.ToolRegistry;
import com.devcli.tool.ToolStatus;
import com.devcli.tool.ToolRegistry.ToolExecutionResult;
import com.devcli.tool.ToolRegistry.ToolInvocation;
import com.devcli.util.AnsiStyle;
import com.devcli.util.TerminalMarkdownRenderer;
import com.devcli.image.ImageReferenceParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 子代理 - 可配置角色的轻量 Agent
 *
 * 每个 SubAgent 有独立的角色、系统提示词和对话历史，
 * 但共享 LLM 客户端和工具注册表。
 */
public class SubAgent {
    private static final Logger log = LoggerFactory.getLogger(SubAgent.class);
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    static final int DEFAULT_REVIEWER_MAX_ITERATIONS = 2;
    static final int MAX_REVIEWER_MAX_ITERATIONS = 8;

    /**
     * Forked SubAgent execution starts from a frozen shared prefix, then appends a task-specific suffix.
     * Keeping this prefix immutable makes parallel worker requests cache-friendly at the prompt boundary.
     */
    public record ForkContext(List<LlmClient.Message> sharedPrefix,
                              List<LlmClient.Tool> toolDefinitions,
                              String skillBodySnapshot,
                              String modelName,
                              String providerName,
                              String fingerprint) {
        public ForkContext {
            sharedPrefix = List.copyOf(sharedPrefix == null ? List.of() : sharedPrefix);
            toolDefinitions = toolDefinitions == null ? null : List.copyOf(toolDefinitions);
            skillBodySnapshot = skillBodySnapshot == null ? "" : skillBodySnapshot;
            modelName = modelName == null ? "" : modelName;
            providerName = providerName == null ? "" : providerName;
            fingerprint = fingerprint == null || fingerprint.isBlank()
                    ? computeFingerprint(sharedPrefix, toolDefinitions, skillBodySnapshot, modelName, providerName)
                    : fingerprint;
        }
    }

    record ToolEvidence(String name, ToolStatus status, String result) {
        public ToolEvidence {
            name = name == null ? "unknown" : name;
            status = status == null ? ToolStatus.ERROR : status;
            result = result == null ? "" : result;
        }
    }

    record ExecutionEvidence(List<ToolEvidence> toolResults) {
        public ExecutionEvidence {
            toolResults = List.copyOf(toolResults == null ? List.of() : toolResults);
        }

        public static ExecutionEvidence empty() {
            return new ExecutionEvidence(List.of());
        }

        public long successfulToolCalls() {
            return toolResults.stream().filter(result -> result.status() == ToolStatus.SUCCESS).count();
        }

        public boolean hasSuccessfulToolCall() {
            return successfulToolCalls() > 0;
        }
    }

    private static final class ExecutionEvidenceAccumulator {
        private final List<ToolEvidence> toolResults = new ArrayList<>();

        private void add(ToolExecutionResult result) {
            if (result != null) {
                toolResults.add(new ToolEvidence(result.name(), result.status(), result.result()));
            }
        }

        private ExecutionEvidence snapshot() {
            return new ExecutionEvidence(toolResults);
        }
    }

    private final String name;
    private final AgentRole role;
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final List<LlmClient.Message> conversationHistory;
    private Supplier<String> externalContextSupplier = () -> "";
    private Supplier<String> stickyMemorySupplier = () -> "";
    private Supplier<String> memoryContextSupplier = () -> "";
    private Supplier<String> workingMemorySupplier = () -> "";
    private Supplier<String> postCompactRestoreSupplier = () -> "";
    private TriConsumer<String, String, String> toolResultConsumer = (name, args, result) -> {};
    private SkillRegistry skillRegistry;
    private SkillContextBuffer skillContextBuffer;
    private final ConversationHistoryCompactor historyCompactor;
    private final PromptAssembler promptAssembler = PromptAssembler.createDefault();
    private final AtomicReference<ExecutionEvidenceAccumulator> executionEvidence =
            new AtomicReference<>(new ExecutionEvidenceAccumulator());
    private String currentSkillActivationText = "";

    public SubAgent(String name, AgentRole role, LlmClient llmClient, ToolRegistry toolRegistry) {
        this.name = name;
        this.role = role;
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.conversationHistory = new ArrayList<>();
        this.historyCompactor = new ConversationHistoryCompactor(llmClient);
        this.historyCompactor.setPostCompactContextSupplier(this::buildPostCompactRestoreSection);
        this.historyCompactor.setCompactBoundaryRuntimeStateSupplier(this::buildCompactBoundaryRuntimeState);
        this.historyCompactor.setMicrocompactOutputRoot(java.nio.file.Path.of(this.toolRegistry.getProjectPath()));
        this.conversationHistory.add(LlmClient.Message.system(getSystemPrompt()));
    }

    public void setExternalContextSupplier(Supplier<String> externalContextSupplier) {
        this.externalContextSupplier = externalContextSupplier == null ? () -> "" : externalContextSupplier;
        refreshSystemPrompt();
    }

    /**
     * 注入 Sticky Memory 渲染源（PR-B）：与 Agent 一致语义，由 Main 启动时接进来。
     * SubAgent 在 setStickyMemorySupplier 后不立即重建 system prompt——下次调 LLM 时
     * 由 getSystemPrompt 拿到最新 sticky 内容。
     */
    public void setStickyMemorySupplier(Supplier<String> stickyMemorySupplier) {
        this.stickyMemorySupplier = stickyMemorySupplier == null ? () -> "" : stickyMemorySupplier;
        refreshSystemPrompt();
    }

    public void setMemoryContextSupplier(Supplier<String> memoryContextSupplier) {
        this.memoryContextSupplier = memoryContextSupplier == null ? () -> "" : memoryContextSupplier;
        refreshSystemPrompt();
    }

    public void setWorkingMemorySupplier(Supplier<String> workingMemorySupplier) {
        this.workingMemorySupplier = workingMemorySupplier == null ? () -> "" : workingMemorySupplier;
        refreshSystemPrompt();
    }

    public void setPostCompactRestoreSupplier(Supplier<String> postCompactRestoreSupplier) {
        this.postCompactRestoreSupplier = postCompactRestoreSupplier == null ? () -> "" : postCompactRestoreSupplier;
    }

    public void setToolResultConsumer(TriConsumer<String, String, String> toolResultConsumer) {
        this.toolResultConsumer = toolResultConsumer == null ? (name, args, result) -> {} : toolResultConsumer;
    }

    public void setSkillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
        refreshSystemPrompt();
    }

    public void setSkillContextBuffer(SkillContextBuffer skillContextBuffer) {
        this.skillContextBuffer = skillContextBuffer;
    }

    /**
     * 根据角色获取系统提示词
     */
    private String getSystemPrompt() {
        return promptAssembler.assemble(promptMode(), PromptContext.builder()
                .memoryContext(buildMemoryContext())
                .externalContext(buildExternalContext())
                .stickyMemory(buildStickyMemory())
                .workingMemory(buildWorkingMemory())
                .skillIndex(buildSkillIndex())
                .build());
    }

    private String buildMemoryContext() {
        try {
            String memory = memoryContextSupplier.get();
            return memory == null ? "" : memory.trim();
        } catch (Exception e) {
            log.warn("Failed to render memory context in SubAgent {}", name, e);
            return "";
        }
    }

    private String buildWorkingMemory() {
        try {
            String memory = workingMemorySupplier.get();
            return memory == null ? "" : memory.trim();
        } catch (Exception e) {
            log.warn("Failed to render working memory in SubAgent {}", name, e);
            return "";
        }
    }

    private String buildPostCompactRestoreSection() {
        List<PostCompactRestoreContext.Section> sections = new ArrayList<>();
        String workingMemory = buildPostCompactRestoreMemory();
        if (!workingMemory.isBlank()) {
            sections.add(new PostCompactRestoreContext.Section("工作记忆恢复", workingMemory));
        }
        String mcpTools = buildMcpPostCompactRestoreSection();
        if (!mcpTools.isBlank()) {
            sections.add(new PostCompactRestoreContext.Section("MCP 工具状态", mcpTools));
        }
        if (skillContextBuffer != null) {
            String skills = skillContextBuffer.renderPostCompactRestoreSection();
            if (!skills.isBlank()) {
                sections.add(new PostCompactRestoreContext.Section("已调用 Skill 恢复", skills));
            }
        }
        return PostCompactRestoreContext.render(sections.toArray(PostCompactRestoreContext.Section[]::new));
    }

    private String buildPostCompactRestoreMemory() {
        try {
            String memory = postCompactRestoreSupplier.get();
            return memory == null ? "" : memory.trim();
        } catch (Exception e) {
            log.warn("Failed to render post-compact restore memory in SubAgent {}", name, e);
            return "";
        }
    }

    private String buildMcpPostCompactRestoreSection() {
        String snapshot = toolRegistry.mcpToolSnapshot();
        if (snapshot == null || snapshot.isBlank() || "none".equalsIgnoreCase(snapshot.trim())) {
            return "";
        }
        return "- snapshot: " + snapshot.trim();
    }

    private CompactBoundaryRuntimeState buildCompactBoundaryRuntimeState() {
        return new CompactBoundaryRuntimeState(
                skillContextBuffer == null ? List.of() : skillContextBuffer.activeSkillNames(),
                CompactBoundaryRuntimeState.mergeRagEpochSnapshots(
                        extractRagEpochSnapshot(buildWorkingMemory()),
                        toolRegistry.currentRagIndexEpochSnapshot()),
                toolRegistry.mcpToolSnapshot(),
                false);
    }

    private static String extractRagEpochSnapshot(String workingMemory) {
        if (workingMemory == null || workingMemory.isBlank()) {
            return "none";
        }
        LinkedHashSet<String> epochs = new LinkedHashSet<>();
        for (String line : workingMemory.split("\\R")) {
            int idx = line.indexOf("indexEpoch=");
            if (idx < 0) {
                continue;
            }
            int start = idx + "indexEpoch=".length();
            int end = line.indexOf(" | ", start);
            String epoch = (end < 0 ? line.substring(start) : line.substring(start, end)).trim();
            if (!epoch.isBlank()) {
                epochs.add(epoch);
            }
        }
        return epochs.isEmpty() ? "none" : String.join(", ", epochs);
    }

    private String buildStickyMemory() {
        try {
            String sticky = stickyMemorySupplier.get();
            return sticky == null ? "" : sticky.trim();
        } catch (Exception e) {
            log.warn("Failed to render sticky memory in SubAgent {}", name, e);
            return "";
        }
    }

    private PromptMode promptMode() {
        return switch (role) {
            case PLANNER -> PromptMode.TEAM_PLANNER;
            case WORKER -> PromptMode.TEAM_WORKER;
            case REVIEWER -> PromptMode.TEAM_REVIEWER;
        };
    }

    private void maybeCompactHistory(PrintStream out) {
        maybeCompactHistory(conversationHistory, out);
    }

    private void maybeCompactHistory(List<LlmClient.Message> history, PrintStream out) {
        if (historyCompactor == null) return;
        ContextProfile profile = toolRegistry == null ? null : toolRegistry.getContextProfile();
        if (profile == null) return;
        try {
            historyCompactor.setMicrocompactOutputRoot(java.nio.file.Path.of(toolRegistry.getProjectPath()));
            boolean compacted = historyCompactor.compactIfNeeded(history, profile.compressionTriggerTokens());
            if (compacted && out != null) {
                out.println("📦 [" + name + "] 上下文接近窗口上限，已把早期对话压缩为摘要后继续。");
            }
        } catch (Exception e) {
            log.warn("[{}] conversationHistory compaction failed", name, e);
        }
    }

    private String buildSkillIndex() {
        if (skillRegistry == null) return "";
        try {
            return SkillIndexFormatter.format(skillRegistry.enabledSkillsForText(
                    currentSkillActivationText,
                    toolRegistry.getProjectPath()));
        } catch (Exception e) {
            log.warn("[{}] failed to build skill index", name, e);
            return "";
        }
    }

    private String prependSkillBodies(String content) {
        return prependSkillBodies(content, true);
    }

    private String prependSkillBodies(String content, boolean consumeBuffer) {
        if (skillContextBuffer == null || skillContextBuffer.isEmpty()) {
            return content;
        }
        String skillBodies = consumeBuffer ? skillContextBuffer.drain() : skillContextBuffer.snapshot();
        return prependSkillBodies(content, skillBodies);
    }

    private static String prependSkillBodies(String content, String skillBodies) {
        if (skillBodies == null || skillBodies.isEmpty()) return content;
        return skillBodies + "\n" + content;
    }

    private void refreshSystemPrompt() {
        if (!conversationHistory.isEmpty()) {
            conversationHistory.set(0, LlmClient.Message.system(getSystemPrompt()));
        }
    }

    private String buildExternalContext() {
        if (!toolRegistry.getContextProfile().mcpResourceIndexEnabled()) {
            return "";
        }
        try {
            String context = externalContextSupplier.get();
            return context == null ? "" : context.trim();
        } catch (Exception e) {
            log.warn("[{}] failed to build external context", name, e);
            return "";
        }
    }

    public ForkContext createForkContext() {
        List<LlmClient.Message> sharedPrefix = List.of(LlmClient.Message.system(getSystemPrompt()));
        List<LlmClient.Tool> toolDefinitions = shouldUseTools() ? toolDefinitionsForRole() : null;
        String skillBodySnapshot = skillContextBuffer == null ? "" : skillContextBuffer.snapshot();
        String modelName = llmClient == null ? "" : llmClient.getModelName();
        String providerName = llmClient == null ? "" : llmClient.getProviderName();
        return new ForkContext(sharedPrefix, toolDefinitions, skillBodySnapshot, modelName, providerName, null);
    }

    /**
     * 执行任务，返回结果消息（默认输出到 System.out）
     */
    public AgentMessage execute(AgentMessage task) {
        return execute(task, System.out);
    }

    /**
     * 执行任务并将流式输出写入指定 PrintStream。并发执行时为每个步骤传入独立的 PrintStream，
     * 避免多个 Agent 同时写入 System.out 造成输出交错。
     */
    public AgentMessage execute(AgentMessage task, PrintStream out) {
        return execute(task, out, LlmClient.ToolChoice.AUTO);
    }

    private AgentMessage execute(AgentMessage task, PrintStream out, LlmClient.ToolChoice toolChoice) {
        return execute(task, out, toolChoice, "");
    }

    private AgentMessage execute(AgentMessage task, PrintStream out,
                                 LlmClient.ToolChoice toolChoice,
                                 String completionToolName) {
        log.info("[{}] executing task from {}: type={}", name, task.fromAgent(), task.type());
        executionEvidence.set(new ExecutionEvidenceAccumulator());
        currentSkillActivationText = task == null || task.content() == null ? "" : task.content();
        pruneHistoricalImagePayloads();
        refreshSystemPrompt();
        return executeWithHistory(task, out, conversationHistory, null, toolChoice,
                completionToolName);
    }

    public AgentMessage executeForked(AgentMessage task, ForkContext forkContext, PrintStream out) {
        return executeForked(task, forkContext, out, LlmClient.ToolChoice.AUTO);
    }

    private AgentMessage executeForked(AgentMessage task, ForkContext forkContext, PrintStream out,
                                       LlmClient.ToolChoice toolChoice) {
        return executeForked(task, forkContext, out, toolChoice, "");
    }

    private AgentMessage executeForked(AgentMessage task, ForkContext forkContext, PrintStream out,
                                       LlmClient.ToolChoice toolChoice,
                                       String completionToolName) {
        executionEvidence.set(new ExecutionEvidenceAccumulator());
        currentSkillActivationText = task == null || task.content() == null ? "" : task.content();
        ForkContext context = forkContext == null ? createForkContext() : forkContext;
        List<LlmClient.Message> forkedHistory = new ArrayList<>(context.sharedPrefix());
        return executeWithHistory(task, out, forkedHistory, context, toolChoice,
                completionToolName);
    }

    private AgentMessage executeWithHistory(AgentMessage task, PrintStream out,
                                            List<LlmClient.Message> history,
                                            ForkContext forkContext,
                                            LlmClient.ToolChoice initialToolChoice,
                                            String completionToolName) {
        String taskContent = forkContext == null
                ? prependSkillBodies(task.content(), true)
                : prependSkillBodies(task.content(), forkContext.skillBodySnapshot());

        // 将任务注入对话
        history.add(ImageReferenceParser.userMessage(
                taskContent,
                Path.of(toolRegistry.getProjectPath())));

        SubAgentStreamRenderer streamRenderer = new SubAgentStreamRenderer(name, role, out);

        AgentBudget budget = createExecutionBudget();
        return new AgentExecutionEngine<AgentMessage>(llmClient, budget).run(
                new AgentExecutionEngine.Delegate<>() {
                    @Override
                    public List<LlmClient.Message> history() {
                        return history;
                    }

                    @Override
                    public List<LlmClient.Tool> toolDefinitions(int iteration) {
                        return toolDefinitionsFor(forkContext);
                    }

                    @Override
                    public LlmClient.StreamListener streamListener() {
                        return streamRenderer;
                    }

                    @Override
                    public LlmClient.ToolChoice toolChoice(int iteration) {
                        return iteration == 1 && initialToolChoice != null
                                ? initialToolChoice
                                : LlmClient.ToolChoice.AUTO;
                    }

                    @Override
                    public void beforeIteration(int iteration, AgentBudget currentBudget) {
                        if (forkContext == null && !history.isEmpty()
                                && "system".equals(history.get(0).role())) {
                            history.set(0, LlmClient.Message.system(getSystemPrompt()));
                        }
                        injectPendingLspDiagnostics(history, out);
                        maybeCompactHistory(history, out);
                    }

                    @Override
                    public void afterResponse(LlmClient.ChatResponse response, int iteration,
                                              AgentBudget currentBudget) {
                        LlmTraceLogger.logReasoning(log,
                                "sub-agent name=" + name + " role=" + role
                                        + " iteration=" + iteration,
                                llmClient,
                                response.reasoningContent());
                        logPromptCacheDiagnostics(forkContext, response, currentBudget);
                    }

                    @Override
                    public LlmClient.ChatResponse normalizeResponse(
                            LlmClient.ChatResponse response,
                            int iteration,
                            AgentBudget currentBudget) {
                        return adaptRequiredToolEnvelope(response, initialToolChoice);
                    }

                    @Override
                    public String retryInstructionAfterResponseWithoutTools(
                            LlmClient.ChatResponse response,
                            int iteration,
                            AgentBudget currentBudget) {
                        if (iteration == 1 && initialToolChoice != null
                                && initialToolChoice.hasSpecificTool()) {
                            return TeamWorkerProtocol.buildToolEnvelopeRepairPrompt(
                                    initialToolChoice.toolName());
                        }
                        return "";
                    }

                    @Override
                    public void beforeToolExecution(LlmClient.ChatResponse response, int iteration,
                                                    AgentBudget currentBudget) {
                        printToolCalls(out, response.toolCalls());
                        streamRenderer.resetBetweenIterations();
                    }

                    @Override
                    public List<ToolExecutionResult> executeTools(List<LlmClient.ToolCall> toolCalls,
                                                                  int iteration) {
                        return executeToolCalls(toolCalls);
                    }

                    @Override
                    public void afterToolResults(LlmClient.ChatResponse response,
                                                 List<ToolExecutionResult> toolResults,
                                                 int iteration,
                                                 AgentBudget currentBudget) {
                        for (ToolExecutionResult toolResult : toolResults) {
                            executionEvidence.get().add(toolResult);
                            toolResultConsumer.accept(
                                    toolResult.name(), toolResult.argumentsJson(), toolResult.result());
                        }
                        appendImageToolMessages(history, toolResults);
                    }

                    @Override
                    public Optional<AgentMessage> completedAfterToolResults(
                            LlmClient.ChatResponse response,
                            List<ToolExecutionResult> toolResults,
                            int iteration,
                            AgentBudget currentBudget) {
                        if (completionToolName == null || completionToolName.isBlank()) {
                            return Optional.empty();
                        }
                        for (ToolExecutionResult toolResult : toolResults) {
                            if (completionToolName.equals(toolResult.name())
                                    && toolResult.status() == ToolStatus.SUCCESS) {
                                streamRenderer.finish();
                                return Optional.of(AgentMessage.result(name, role, ""));
                            }
                        }
                        return Optional.empty();
                    }

                    @Override
                    public AgentMessage completed(LlmClient.ChatResponse response,
                                                  AgentBudget currentBudget) {
                        streamRenderer.finish();
                        if (initialToolChoice != null && initialToolChoice.hasSpecificTool()
                                && !executionEvidence.get().snapshot().hasSuccessfulToolCall()) {
                            return AgentMessage.result(name, role, "");
                        }
                        return AgentMessage.result(name, role, response.content());
                    }

                    @Override
                    public AgentMessage cancelled(AgentBudget currentBudget) {
                        streamRenderer.finish();
                        return AgentMessage.error(name, role, "任务已取消");
                    }

                    @Override
                    public AgentMessage budgetExceeded(AgentBudget.ExitReason reason,
                                                       AgentBudget currentBudget) {
                        streamRenderer.finish();
                        String description = currentBudget.describeExit(reason);
                        log.warn("[{}] run exhausted budget: reason={}, iteration={}, tokens={}/{}",
                                name, reason, currentBudget.iteration(),
                                currentBudget.totalInputTokens() + currentBudget.totalOutputTokens(),
                                currentBudget.tokenBudget());
                        return AgentMessage.error(name, role, description);
                    }

                    @Override
                    public AgentMessage iterationLimitReached(AgentBudget currentBudget) {
                        return budgetExceeded(
                                AgentBudget.ExitReason.HARD_ITERATION_LIMIT, currentBudget);
                    }

                    @Override
                    public AgentMessage failed(IOException error, AgentBudget currentBudget) {
                        log.error("[{}] LLM call failed", name, error);
                        streamRenderer.finish();
                        return AgentMessage.error(name, role, describeLlmFailure(error));
                    }
                });
    }

    private AgentBudget createExecutionBudget() {
        AgentBudget base = AgentBudget.fromLlmClient(llmClient);
        if (role != AgentRole.REVIEWER) {
            return base;
        }
        return new AgentBudget(
                base.tokenBudget(), base.stagnationWindow(), resolveReviewerMaxIterations());
    }

    static int resolveReviewerMaxIterations() {
        String raw = System.getProperty("devcli.team.reviewer.max.iterations");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("DEVCLI_TEAM_REVIEWER_MAX_ITERATIONS");
        }
        try {
            int parsed = raw == null || raw.isBlank()
                    ? DEFAULT_REVIEWER_MAX_ITERATIONS
                    : Integer.parseInt(raw.trim());
            return Math.max(1, Math.min(MAX_REVIEWER_MAX_ITERATIONS, parsed));
        } catch (NumberFormatException ignored) {
            return DEFAULT_REVIEWER_MAX_ITERATIONS;
        }
    }

    static LlmClient.ChatResponse adaptRequiredToolEnvelope(
            LlmClient.ChatResponse response, LlmClient.ToolChoice toolChoice) {
        if (response == null || response.hasToolCalls() || toolChoice == null
                || !toolChoice.hasSpecificTool() || response.content() == null
                || response.content().isBlank()) {
            return response;
        }
        try (JsonParser parser = JSON_MAPPER.getFactory().createParser(response.content().trim())) {
            JsonNode root = JSON_MAPPER.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) {
                return response;
            }
            Iterator<String> fields = root.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                if (!"name".equals(field) && !"tool".equals(field) && !"arguments".equals(field)) {
                    return response;
                }
            }
            boolean hasName = root.has("name");
            boolean hasTool = root.has("tool");
            if (hasName == hasTool) {
                return response;
            }
            String name = hasName
                    ? root.path("name").asText("")
                    : root.path("tool").asText("");
            JsonNode arguments = root.get("arguments");
            if (!toolChoice.toolName().equals(name) || arguments == null || !arguments.isObject()) {
                return response;
            }
            String callId = "fallback_" + Integer.toUnsignedString(
                    response.content().hashCode(), 16);
            LlmClient.ToolCall toolCall = new LlmClient.ToolCall(
                    callId,
                    new LlmClient.ToolCall.Function(name, JSON_MAPPER.writeValueAsString(arguments)));
            return new LlmClient.ChatResponse(
                    response.role(), "", response.reasoningContent(), List.of(toolCall),
                    response.inputTokens(), response.outputTokens(), response.cachedInputTokens());
        } catch (IOException ignored) {
            return response;
        }
    }

    static String describeLlmFailure(IOException error) {
        if (error instanceof LlmException llmError) {
            return "LLM 调用失败 [code=" + llmError.code()
                    + ",retryable=" + llmError.retryable() + "]: " + llmError.getMessage();
        }
        return "LLM 调用失败: " + (error == null ? "unknown" : error.getMessage());
    }

    /**
     * 执行任务（带上下文注入），用于 Worker 接收额外上下文
     */
    public AgentMessage executeWithContext(AgentMessage task, String context) {
        return executeWithContext(task, context, System.out);
    }

    public AgentMessage executeWithContext(AgentMessage task, String context, PrintStream out) {
        return executeWithContext(task, context, out, LlmClient.ToolChoice.AUTO);
    }

    AgentMessage executeWithContext(AgentMessage task, String context, PrintStream out,
                                    LlmClient.ToolChoice toolChoice) {
        return executeWithContext(task, context, out, toolChoice, "");
    }

    AgentMessage executeWithContext(AgentMessage task, String context, PrintStream out,
                                    LlmClient.ToolChoice toolChoice,
                                    String completionToolName) {
        String enrichedContent = task.content();
        if (context != null && !context.isEmpty()) {
            enrichedContent = context + "\n\n当前任务：" + task.content();
        }
        AgentMessage enrichedTask = new AgentMessage(task.fromAgent(), task.fromRole(),
                enrichedContent, task.type());
        return execute(enrichedTask, out, toolChoice,
                completionToolName);
    }

    public AgentMessage executeForkedWithContext(AgentMessage task, String context,
                                                 ForkContext forkContext, PrintStream out) {
        return executeForkedWithContext(
                task, context, forkContext, out, LlmClient.ToolChoice.AUTO);
    }

    AgentMessage executeForkedWithContext(AgentMessage task, String context,
                                          ForkContext forkContext, PrintStream out,
                                          LlmClient.ToolChoice toolChoice) {
        return executeForkedWithContext(task, context, forkContext, out, toolChoice, "");
    }

    AgentMessage executeForkedWithContext(AgentMessage task, String context,
                                          ForkContext forkContext, PrintStream out,
                                          LlmClient.ToolChoice toolChoice,
                                          String completionToolName) {
        String enrichedContent = task.content();
        if (context != null && !context.isEmpty()) {
            enrichedContent = context + "\n\n当前任务：" + task.content();
        }
        AgentMessage enrichedTask = new AgentMessage(task.fromAgent(), task.fromRole(),
                enrichedContent, task.type());
        return executeForked(enrichedTask, forkContext, out, toolChoice,
                completionToolName);
    }

    /**
     * 检查结果（Reviewer 专用）
     */
    public AgentMessage review(String originalTask, String executionResult) {
        return review(originalTask, executionResult, System.out);
    }

    public AgentMessage review(String originalTask, String executionResult, PrintStream out) {
        String reviewInput = "原始任务：" + originalTask + "\n\n执行结果：\n" + executionResult;
        AgentMessage reviewTask = AgentMessage.task("orchestrator", reviewInput);
        return execute(reviewTask, out);
    }

    public AgentMessage reviewForked(String originalTask, String executionResult,
                                     ForkContext forkContext, PrintStream out) {
        String reviewInput = "原始任务：" + originalTask + "\n\n执行结果：\n" + executionResult;
        AgentMessage reviewTask = AgentMessage.task("orchestrator", reviewInput);
        return executeForked(reviewTask, forkContext, out);
    }

    /**
     * 清空对话历史（保留系统提示词），用于处理下一个独立任务
     */
    public void clearHistory() {
        LlmClient.Message systemMsg = conversationHistory.get(0);
        conversationHistory.clear();
        conversationHistory.add(systemMsg);
    }

    private void pruneHistoricalImagePayloads() {
        int messageCount = 0;
        int imageCount = 0;
        for (int i = 0; i < conversationHistory.size(); i++) {
            LlmClient.Message message = conversationHistory.get(i);
            int images = message.imagePartCount();
            if (images <= 0) {
                continue;
            }
            conversationHistory.set(i, message.withoutImageContent());
            messageCount++;
            imageCount += images;
        }
        if (imageCount > 0) {
            log.info("[{}] pruned historical image payloads before sub-agent turn: messages={}, images={}",
                    name, messageCount, imageCount);
        }
    }

    private boolean shouldUseTools() {
        return role == AgentRole.WORKER || role == AgentRole.REVIEWER;
    }

    private List<LlmClient.Tool> toolDefinitionsFor(ForkContext forkContext) {
        if (!shouldUseTools()) {
            return null;
        }
        if (forkContext != null) {
            return forkContext.toolDefinitions() == null ? null : forkContext.toolDefinitions();
        }
        return toolDefinitionsForRole();
    }

    private List<LlmClient.Tool> toolDefinitionsForRole() {
        List<LlmClient.Tool> tools = toolRegistry.getToolDefinitions();
        if (role != AgentRole.REVIEWER) {
            return tools;
        }
        return tools.stream()
                .filter(tool -> tool.name().equals("read_file")
                        || tool.name().equals("list_dir")
                        || tool.name().equals("grep_code")
                        || tool.name().equals("execute_command"))
                .toList();
    }

    private void logPromptCacheDiagnostics(ForkContext forkContext,
                                           LlmClient.ChatResponse response,
                                           AgentBudget budget) {
        if (forkContext == null || response == null) {
            return;
        }
        int input = Math.max(0, response.inputTokens());
        int cached = Math.max(0, Math.min(input, response.cachedInputTokens()));
        int hitPct = input == 0 ? 0 : (int) Math.round(cached * 100.0 / input);
        log.info("[{}] fork cache diagnostics: fingerprint={}, provider={}, model={}, iteration={}, input={}, cached={}, hitPct={}%",
                name,
                forkContext.fingerprint(),
                forkContext.providerName(),
                forkContext.modelName(),
                budget.iteration(),
                input,
                cached,
                hitPct);
    }

    private void injectPendingLspDiagnostics(PrintStream out) {
        injectPendingLspDiagnostics(conversationHistory, out);
    }

    private void injectPendingLspDiagnostics(List<LlmClient.Message> history, PrintStream out) {
        LspDiagnosticReport report = toolRegistry.flushPendingLspDiagnostics();
        if (report == null || report.isEmpty()) {
            return;
        }
        history.add(LlmClient.Message.user(report.promptText()));
        if (out != null) {
            out.println(report.displayText());
        }
        log.info("[{}] injected LSP diagnostics into sub-agent conversation", name);
    }

    private List<ToolExecutionResult> executeToolCalls(List<LlmClient.ToolCall> toolCalls) {
        List<ToolExecutionResult> results = new ArrayList<>();
        List<ToolInvocation> invocations = new ArrayList<>();
        List<String> allowedToolNames = allowedToolNamesForRole();
        for (LlmClient.ToolCall toolCall : toolCalls) {
            String toolName = toolCall.function().name();
            String toolArgs = toolCall.function().arguments();
            log.info("[{}] scheduling tool: {}", name, toolName);
            log.debug("[{}] tool args [{}]: {}", name, toolName, toolArgs);
            ToolInvocation invocation = new ToolInvocation(toolCall.id(), toolName, toolArgs);
            if (allowedToolNames != null && !allowedToolNames.contains(toolName)) {
                results.add(ToolExecutionResult.failed(invocation,
                        role.name() + " 不允许调用工具 " + toolName));
                continue;
            }
            invocations.add(invocation);
        }

        if (invocations.size() > 1) {
            log.info("[{}] executing {} tool calls in parallel", name, invocations.size());
        }
        if (!invocations.isEmpty()) {
            AtomicReference<List<ToolExecutionResult>> executed = new AtomicReference<>(List.of());
            toolRegistry.runWithSkillContextBuffer(skillContextBuffer,
                    () -> executed.set(toolRegistry.executeTools(invocations)));
            results.addAll(executed.get());
        }
        return results;
    }

    private List<String> allowedToolNamesForRole() {
        if (role != AgentRole.REVIEWER) {
            return null;
        }
        return List.of("read_file", "list_dir", "grep_code", "execute_command");
    }

    private void appendImageToolMessages(List<ToolExecutionResult> toolResults) {
        appendImageToolMessages(conversationHistory, toolResults);
    }

    private void appendImageToolMessages(List<LlmClient.Message> history, List<ToolExecutionResult> toolResults) {
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
            history.add(LlmClient.Message.user(parts));
        }
    }

    private static void printToolCalls(PrintStream out, List<LlmClient.ToolCall> toolCalls) {
        Map<String, List<LlmClient.ToolCall>> grouped = new LinkedHashMap<>();
        for (LlmClient.ToolCall tc : toolCalls) {
            grouped.computeIfAbsent(tc.function().name(), k -> new ArrayList<>()).add(tc);
        }
        for (var group : grouped.entrySet()) {
            String toolName = group.getKey();
            List<LlmClient.ToolCall> calls = group.getValue();
            out.println(AnsiStyle.subtle("  " + toolLabel(toolName, calls.size())));
            for (LlmClient.ToolCall tc : calls) {
                String detail = extractKeyParam(toolName, tc.function().arguments());
                if (!detail.isEmpty()) {
                    out.println(AnsiStyle.subtle("    └ " + detail));
                }
            }
        }
    }

    private static String toolLabel(String toolName, int count) {
        return switch (toolName) {
            case "read_file" -> "📖 读取 " + count + " 个文件";
            case "write_file" -> "✏️ 写入 " + count + " 个文件";
            case "list_dir" -> "📂 列出 " + count + " 个目录";
            case "execute_command" -> "⚡ 执行 " + count + " 条命令";
            case "create_project" -> "🏗️ 创建 " + count + " 个项目";
            case "grep_code" -> "🔎 精确搜索代码 " + count + " 次";
            case "search_code" -> "🔍 搜索代码 " + count + " 次";
            case "web_search" -> "🌐 联网搜索 " + count + " 次";
            case "web_fetch" -> "📰 抓取 " + count + " 个网页";
            case "save_memory" -> "💾 保存长期记忆 " + count + " 条";
            case "list_memory" -> "🧠 查看长期记忆 " + count + " 次";
            default -> toolName != null && toolName.startsWith("mcp__")
                    ? formatMcpLabel(toolName, count)
                    : "🔧 " + toolName + " × " + count;
        };
    }

    private static String formatMcpLabel(String toolName, int count) {
        String[] parts = toolName.split("__", 3);
        String display = parts.length == 3 ? parts[1] + "." + parts[2] : toolName;
        return count == 1
                ? "🔌 调用 MCP 工具 " + display
                : "🔌 调用 MCP 工具 " + display + " × " + count;
    }

    private static String extractKeyParam(String toolName, String argsJson) {
        try {
            JsonNode node = JSON_MAPPER.readTree(argsJson);
            String key = switch (toolName) {
                case "read_file", "write_file", "list_dir" -> "path";
                case "execute_command" -> "command";
                case "create_project" -> "name";
                case "grep_code" -> "pattern";
                case "search_code", "web_search" -> "query";
                case "web_fetch" -> "url";
                case "save_memory" -> "fact";
                case "list_memory" -> "limit";
                default -> null;
            };
            if (key == null) {
                return argsJson.length() > 80 ? argsJson.substring(0, 77) + "..." : argsJson;
            }
            String value = node.path(key).asText("");
            if (value.length() > 80) {
                value = value.substring(0, 77) + "...";
            }
            return value;
        } catch (Exception e) {
            return argsJson.length() > 80 ? argsJson.substring(0, 77) + "..." : argsJson;
        }
    }

    ExecutionEvidence getLastExecutionEvidence() {
        ExecutionEvidenceAccumulator accumulator = executionEvidence.get();
        return accumulator == null ? ExecutionEvidence.empty() : accumulator.snapshot();
    }

    public String getName() {
        return name;
    }

    public AgentRole getRole() {
        return role;
    }

    private static String computeFingerprint(List<LlmClient.Message> sharedPrefix,
                                             List<LlmClient.Tool> toolDefinitions,
                                             String skillBodySnapshot,
                                             String modelName,
                                             String providerName) {
        StringBuilder sb = new StringBuilder();
        sb.append("provider=").append(providerName == null ? "" : providerName).append('\n');
        sb.append("model=").append(modelName == null ? "" : modelName).append('\n');
        sb.append("messages=").append(sharedPrefix == null ? 0 : sharedPrefix.size()).append('\n');
        if (sharedPrefix != null) {
            for (LlmClient.Message message : sharedPrefix) {
                sb.append(message.role()).append(':').append(message.content()).append('\n');
            }
        }
        sb.append("tools=").append(toolDefinitions == null ? 0 : toolDefinitions.size()).append('\n');
        if (toolDefinitions != null) {
            for (LlmClient.Tool tool : toolDefinitions) {
                sb.append(tool.name()).append(':')
                        .append(tool.description()).append(':')
                        .append(tool.parameters() == null ? "" : tool.parameters().toString())
                        .append('\n');
            }
        }
        sb.append("skills=").append(skillBodySnapshot == null ? "" : skillBodySnapshot);
        return sha256Prefix(sb.toString());
    }

    private static String sha256Prefix(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(8, hash.length); i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    @FunctionalInterface
    public interface TriConsumer<A, B, C> {
        void accept(A a, B b, C c);
    }

    /**
     * SubAgent 流式渲染器，分区展示 reasoning_content 与 content。
     *
     * 与 {@link com.devcli.agent.Agent.StreamRenderer} 使用同一策略应对
     * "content 开始后又追加 reasoning"的场景：迟到的 reasoning 会被累积到 lateReasoning，
     * 在 finish() 时以"🧠 补充思考"独立展示，避免混入结果区。
     */
    private static final class SubAgentStreamRenderer implements LlmClient.StreamListener {
        private final String agentName;
        private final AgentRole role;
        private final PrintStream out;
        private final StringBuilder pendingReasoning = new StringBuilder();
        private final StringBuilder lateReasoning = new StringBuilder();
        private TerminalMarkdownRenderer reasoningRenderer;
        private TerminalMarkdownRenderer contentRenderer;
        private boolean reasoningStarted;
        private boolean contentStarted;
        private boolean streamedOutput;

        private SubAgentStreamRenderer(String agentName, AgentRole role, PrintStream out) {
            this.agentName = agentName;
            this.role = role;
            this.out = out;
        }

        @Override
        public void onReasoningDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (contentStarted) {
                lateReasoning.append(delta);
                return;
            }
            if (!reasoningStarted) {
                pendingReasoning.append(delta);
                if (pendingReasoning.toString().isBlank()) {
                    return;
                }
                out.println(AnsiStyle.heading("🧠 " + reasoningLabel() + " [" + agentName + "]"));
                reasoningRenderer = new TerminalMarkdownRenderer(out);
                reasoningRenderer.append(pendingReasoning.toString());
                pendingReasoning.setLength(0);
                reasoningStarted = true;
                streamedOutput = true;
            } else {
                reasoningRenderer.append(delta);
            }
            out.flush();
        }

        @Override
        public void onContentDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!contentStarted) {
                if (reasoningStarted && reasoningRenderer != null) {
                    reasoningRenderer.finish();
                    out.println();
                } else if (pendingReasoning.length() > 0 && !pendingReasoning.toString().isBlank()) {
                    // 实质 reasoning 尚未流出就被 content 打断：先补打思考过程再切到结果
                    out.println(AnsiStyle.heading("🧠 " + reasoningLabel() + " [" + agentName + "]"));
                    TerminalMarkdownRenderer r = new TerminalMarkdownRenderer(out);
                    r.append(pendingReasoning.toString());
                    r.finish();
                    out.println();
                    pendingReasoning.setLength(0);
                    reasoningStarted = true;
                }
                out.println(AnsiStyle.section("🤖 " + contentLabel() + " [" + agentName + "]"));
                contentRenderer = new TerminalMarkdownRenderer(out);
                contentStarted = true;
                streamedOutput = true;
            }
            contentRenderer.append(delta);
            out.flush();
        }

        private String reasoningLabel() {
            return switch (role) {
                case PLANNER -> "规划思考";
                case WORKER -> "执行思考";
                case REVIEWER -> "审查思考";
            };
        }

        private String contentLabel() {
            // WORKER/REVIEWER 可能在 tool_calls 前先 narrate，用"输出"避免"结果"暗示已经完成。
            return switch (role) {
                case PLANNER -> "规划结果";
                case WORKER -> "执行输出";
                case REVIEWER -> "审查输出";
            };
        }

        /**
         * 在两次迭代（通常是 tool-call 分支）之间调用：收尾当前渲染器并重置状态，
         * 让下一轮迭代的 reasoning/content 能重新打印各自的标题。
         */
        private void resetBetweenIterations() {
            if (reasoningRenderer != null) {
                reasoningRenderer.finish();
                reasoningRenderer = null;
            }
            if (contentRenderer != null) {
                contentRenderer.finish();
                contentRenderer = null;
            }
            String late = lateReasoning.toString().trim();
            if (!late.isEmpty()) {
                out.println();
                out.println(AnsiStyle.heading("🧠 补充思考 [" + agentName + "]"));
                TerminalMarkdownRenderer r = new TerminalMarkdownRenderer(out);
                r.append(late);
                r.finish();
                lateReasoning.setLength(0);
                streamedOutput = true;
            }
            pendingReasoning.setLength(0);
            reasoningStarted = false;
            contentStarted = false;
            if (streamedOutput) {
                out.println();
            }
        }

        private void finish() {
            if (reasoningRenderer != null) {
                reasoningRenderer.finish();
            }
            if (contentRenderer != null) {
                contentRenderer.finish();
            }
            String late = lateReasoning.toString().trim();
            if (!late.isEmpty()) {
                out.println();
                out.println(AnsiStyle.heading("🧠 补充思考 [" + agentName + "]"));
                TerminalMarkdownRenderer r = new TerminalMarkdownRenderer(out);
                r.append(late);
                r.finish();
                lateReasoning.setLength(0);
                streamedOutput = true;
            }
            if (streamedOutput) {
                out.println("\n");
            }
        }
    }
}
