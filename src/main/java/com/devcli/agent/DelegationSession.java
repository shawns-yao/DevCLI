package com.devcli.agent;

import com.devcli.context.ContextProfile;
import com.devcli.config.ConfigResolver;
import com.devcli.hook.HookLifecycle;
import com.devcli.llm.LlmClient;
import com.devcli.memory.ConversationHistoryCompactor;
import com.devcli.memory.TokenBudget;
import com.devcli.prompt.PromptRepository;
import com.devcli.runtime.CancellationContext;
import com.devcli.runtime.CancellationToken;
import com.devcli.runtime.RunContext;
import com.devcli.runtime.event.RunEvent;
import com.devcli.runtime.event.RunEventSink;
import com.devcli.tool.DelegateTaskTool;
import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolExecutionContext;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;
import com.devcli.workspace.PatchSet;
import com.devcli.workspace.WorkspaceExecutionSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** 一次主任务的按需委派能力。只装配子循环，不负责规划、评审或自动重做。 */
final class DelegationSession implements DelegateTaskTool.Handler {
    private static final int MAX_REPORT_CHARS = 12000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ToolRegistry parent;
    private final Function<String, LlmClient> modelResolver;
    private final Map<String, LlmClient> models = new ConcurrentHashMap<>();
    private final AgentBudget parentBudget;
    private final String systemPrompt;
    private final RunEventSink events;
    private final PromptRepository prompts;
    private final int maxChildIterations;

    DelegationSession(ToolRegistry parent, Function<String, LlmClient> modelResolver,
                      AgentBudget parentBudget, String systemPrompt, RunEventSink events) {
        this.parent = parent;
        this.modelResolver = modelResolver;
        this.parentBudget = parentBudget;
        this.systemPrompt = systemPrompt;
        this.events = events;
        this.prompts = new PromptRepository(Path.of(System.getProperty("user.home"), ".devcli", "prompts"),
                Path.of(parent.getProjectPath()).toAbsolutePath().resolve(".devcli/prompts"));
        this.maxChildIterations = ConfigResolver.intValue("devcli.delegate.max.iterations",
                "DEVCLI_DELEGATE_MAX_ITERATIONS", 32, 1, 100);
    }

    @Override
    public ToolOutput execute(Map<String, String> arguments, ToolExecutionContext context) {
        String role = arguments.getOrDefault("role", "").toLowerCase(Locale.ROOT);
        if (!java.util.Set.of("explorer", "planner", "worker", "reviewer").contains(role)
                || arguments.getOrDefault("task", "").isBlank()) {
            return ToolOutput.error(ToolErrorCode.INVALID_ARGUMENTS, "需要有效角色和非空子任务", false);
        }
        if (parent.currentToolAccessScope() != ToolRegistry.ToolAccessScope.FULL) {
            return ToolOutput.rejected(ToolErrorCode.CAPABILITY_DENIED, "受限子任务不能继续委派");
        }
        AgentBudget budget = parentBudget.fork();
        if (budget.check() != AgentBudget.ExitReason.WITHIN_BUDGET) {
            return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED, budget.describeExit(budget.check()), false);
        }
        String id = "delegate-" + UUID.randomUUID().toString().substring(0, 12);
        ToolOutput result;
        events.emit(new RunEvent.CustomMessage("delegation.started", "子任务开始",
                Map.of("child_id", id, "role", role)));
        try {
            context.throwIfCancelled();
            String rolePrompt = prompts.loadRequired("modes/delegate-" + role + ".md");
            LlmClient client = models.computeIfAbsent(role, modelResolver);
            if (client == null) throw new IllegalArgumentException("子 Agent 模型不可用: " + role);
            if (role.equals("worker")) {
                try (WorkspaceExecutionSession workspace = WorkspaceExecutionSession.open(parent, id)) {
                    result = executeChild(workspace.toolRegistry(), client, budget, id, rolePrompt,
                            arguments, context, ToolRegistry.ToolAccessScope.ISOLATED_PROJECT);
                    if (result.isSuccess()) {
                        context.throwIfCancelled();
                        PatchSet patch = workspace.patchSet();
                        PatchSet.ApplyResult applied = workspace.commit(patch,
                                ignored -> context.throwIfCancelled(), ignored -> { });
                        if (applied.applied()) {
                            ObjectNode report = (ObjectNode) JSON.readTree(result.text());
                            var files = report.putArray("modified_resources");
                            applied.modifiedResources().forEach(files::add);
                            result = ToolOutput.success(report.toString()).withModifiedResources(applied.modifiedResources());
                        } else {
                            result = ToolOutput.error(ToolErrorCode.RESOURCE_CONFLICT,
                                    applied.failureDescription(), false);
                        }
                    }
                }
            } else {
                try (ToolRegistry child = parent.forkForProject(Path.of(parent.getProjectPath()))) {
                    result = executeChild(child, client, budget, id, rolePrompt,
                            arguments, context, ToolRegistry.ToolAccessScope.READ_ONLY);
                }
            }
        } catch (CancellationException e) {
            result = ToolOutput.cancelled("子任务已取消，未应用未提交的修改");
        } catch (Exception e) {
            result = ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                    "子任务失败: " + e.getMessage(), false);
        } finally {
            parent.forgetStaleWriteScope(id);
        }
        events.emit(new RunEvent.CustomMessage("delegation.completed", "子任务结束",
                Map.of("child_id", id, "role", role, "status", result.status().name())));
        return result;
    }

    private ToolOutput executeChild(ToolRegistry registry, LlmClient client, AgentBudget budget,
                                    String id, String rolePrompt, Map<String, String> arguments,
                                    ToolExecutionContext context, ToolRegistry.ToolAccessScope scope) {
        var skillBuffer = parent.activeSkillContextBuffer();
        registry.restrictForDelegation();
        if (skillBuffer != null) registry.setSkillContextBuffer(skillBuffer.copy());
        registry.setContextProfile(ContextProfile.from(client));
        registry.prefetchToolDefinitionsForInput(arguments.get("task"));
        try (RunContext run = CancellationContext.startRunContext(Path.of(registry.getProjectPath()));
             CancellationToken.Registration registration = context.cancellationToken().onCancel(
                     cancelled -> run.cancellationToken().cancel(cancelled.reason(), cancelled.message()))) {
            return registry.runWithToolAccess(scope, () -> registry.runWithResourceLease(id, () -> {
                try {
                    return new ChildLoop(registry, client, budget, id, rolePrompt, arguments, context).run();
                } finally {
                    registry.releaseResourceLeases(id);
                }
            }));
        }
    }

    private final class ChildLoop implements AgentExecutionEngine.Delegate<ToolOutput> {
        private final ToolRegistry registry;
        private final LlmClient client;
        private final AgentBudget budget;
        private final String id;
        private final ToolExecutionContext context;
        private final List<LlmClient.Message> history = new ArrayList<>();
        private final List<String> evidence = new ArrayList<>();
        private final java.util.Set<String> unresolvedMutations = new java.util.HashSet<>();
        private final ConversationHistoryCompactor compactor;

        ChildLoop(ToolRegistry registry, LlmClient client, AgentBudget budget, String id,
                  String rolePrompt, Map<String, String> arguments, ToolExecutionContext context) {
            this.registry = registry;
            this.client = client;
            this.budget = budget;
            this.id = id;
            this.context = context;
            history.add(LlmClient.Message.system(systemPrompt + "\n\n你是受主 Agent 委派的子 Agent。"
                    + rolePrompt + "\n只完成下述子任务，不能继续委派或扩大授权范围。"
                    + "所有文件路径相对于当前工作区：" + registry.getProjectPath()
                    + "\n返回简洁结果、修改文件、验证证据和未完成项；不要把未执行的检查称为通过。"));
            String task = "任务：" + arguments.get("task")
                    + "\n必要背景：" + arguments.getOrDefault("context", "");
            history.add(LlmClient.Message.user(AgentRuntimeSupport.prependSkillBodies(
                    registry.getSkillContextBuffer(), task, false)));
            // 压缩沿用现有实现；摘要模型调用也计入共享预算。
            compactor = new ConversationHistoryCompactor(new BudgetedSummaryClient(client, budget.fork(), events));
            compactor.setMicrocompactOutputRoot(Path.of(registry.getProjectPath()));
            compactor.setPostCompactContextSupplier(() -> AgentRuntimeSupport.buildPostCompactRestoreSection(
                    "", registry, registry.getSkillContextBuffer()));
        }

        ToolOutput run() {
            return new AgentExecutionEngine<ToolOutput>(client, budget, HookLifecycle.load(registry)).run(this);
        }
        @Override public List<LlmClient.Message> history() { return history; }
        @Override public List<LlmClient.Tool> toolDefinitions(int iteration) { return registry.getToolDefinitions(); }
        @Override public LlmClient.StreamListener streamListener() { return LlmClient.StreamListener.NO_OP; }
        @Override public int maxIterations() { return maxChildIterations; }
        @Override public boolean isCancelled() { return context.isCancelled() || CancellationContext.isCancelled(); }
        @Override public RunEventSink eventSink() {
            // 子循环终态不能变成父运行终态，文本和历史也不混入父模型上下文。
            return event -> {
                if (event instanceof RunEvent.ModelUsage) events.emit(event);
                else if (event instanceof RunEvent.ToolResults results) {
                    events.emit(new RunEvent.CustomMessage("delegation.tools", "子任务工具结果",
                            Map.of("child_id", id, "results", results.results().stream()
                                    .map(r -> r.name() + ":" + r.status()).toList().toString())));
                }
            };
        }
        @Override public void beforeIteration(int iteration, AgentBudget currentBudget) {
            compactor.compactIfNeeded(history, ContextProfile.from(client).historyTriggerTokens(
                    TokenBudget.estimateToolDefinitionsTokens(registry.getToolDefinitions())));
            if (registry.getSkillContextBuffer() != null) {
                String pending = registry.getSkillContextBuffer().drain();
                if (!pending.isBlank()) history.add(LlmClient.Message.internalUser(pending));
            }
        }
        @Override public List<ToolRegistry.ToolExecutionResult> executeTools(List<LlmClient.ToolCall> calls, int iteration) {
            return registry.executeTools(calls.stream().map(call -> new ToolRegistry.ToolInvocation(
                    call.id(), call.function().name(), call.function().arguments())).toList());
        }
        @Override public void afterToolResults(LlmClient.ChatResponse response,
                List<ToolRegistry.ToolExecutionResult> results, int iteration, AgentBudget currentBudget) {
            for (var result : results) {
                if (evidence.size() < 64) evidence.add(result.name() + ":" + result.status()
                        + ":" + result.errorCode());
                var effect = registry.toolEffect(result.name());
                if (effect != ToolRegistry.ToolEffect.READ_ONLY && effect != ToolRegistry.ToolEffect.LOCAL_CONTEXT) {
                    String key = mutationKey(result);
                    if (result.status() == com.devcli.tool.ToolStatus.SUCCESS) unresolvedMutations.remove(key);
                    else unresolvedMutations.add(key);
                }
                if (result.hasImageParts()) history.add(LlmClient.Message.user(result.imageParts(), LlmClient.MessageSource.TOOL));
            }
        }
        @Override public Map<String, String> refreshStaleContext() { return registry.refreshStaleContext(id); }
        @Override public String contextScope() { return id; }
        @Override public ToolOutput completed(LlmClient.ChatResponse response, AgentBudget currentBudget) {
            ObjectNode report = JSON.createObjectNode();
            report.put("child_id", id).put("model", client.getModelName())
                    .put("summary", bounded(response.content())).put("iterations", currentBudget.iteration());
            var checks = report.putArray("tool_evidence");
            evidence.forEach(checks::add);
            report.put("verification", "工具状态仅表示执行结果；主 Agent 仍需核对任务验收条件");
            if (registry.currentToolAccessScope() == ToolRegistry.ToolAccessScope.ISOLATED_PROJECT
                    && !unresolvedMutations.isEmpty()) {
                report.put("error", "仍有未解决的副作用工具失败，未应用工作区修改");
                return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED, report.toString(), false);
            }
            return ToolOutput.success(report.toString());
        }
        @Override public ToolOutput cancelled(AgentBudget currentBudget) { return ToolOutput.cancelled("子任务已取消"); }
        @Override public ToolOutput budgetExceeded(AgentBudget.ExitReason reason, AgentBudget currentBudget) {
            return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED, currentBudget.describeExit(reason), false);
        }
        @Override public ToolOutput iterationLimitReached(AgentBudget currentBudget) {
            return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED, "子任务达到 " + maxChildIterations + " 轮上限", false);
        }
        @Override public ToolOutput failed(IOException error, AgentBudget currentBudget) {
            return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED, "子任务模型调用失败: " + error.getMessage(), false);
        }
    }

    private static String bounded(String text) {
        if (text == null) return "";
        return text.length() <= MAX_REPORT_CHARS ? text : text.substring(0, MAX_REPORT_CHARS) + "\n[结果已截断]";
    }

    private static String mutationKey(ToolRegistry.ToolExecutionResult result) {
        try {
            var arguments = JSON.readTree(result.argumentsJson());
            String target = "execute_command".equals(result.name())
                    ? arguments.path("command").asText("") : arguments.path("path").asText("");
            return result.name() + ":" + target;
        } catch (IOException e) {
            return result.name();
        }
    }

    private record BudgetedSummaryClient(LlmClient delegate, AgentBudget budget, RunEventSink events) implements LlmClient {
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            if (CancellationContext.isCancelled() || budget.tryBeginIteration() == 0) {
                throw new IOException("子任务预算耗尽或已取消，停止摘要调用");
            }
            ChatResponse response = delegate.chat(messages, tools, listener);
            budget.recordTokens(response.inputTokens(), response.outputTokens(), response.cachedInputTokens());
            events.emit(new RunEvent.ModelUsage(response.inputTokens(), response.outputTokens(),
                    response.cachedInputTokens(), com.devcli.context.TokenUsageFormatter.estimatedCostCnyValue(
                            delegate, response.inputTokens(), response.outputTokens(), response.cachedInputTokens())));
            return response;
        }
        @Override public String getModelName() { return delegate.getModelName(); }
        @Override public String getProviderName() { return delegate.getProviderName(); }
        @Override public int maxContextWindow() { return delegate.maxContextWindow(); }
        @Override public int maxOutputTokens() { return delegate.maxOutputTokens(); }
    }
}
