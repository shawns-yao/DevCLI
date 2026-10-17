package com.devcli.agent;

import com.devcli.tool.ToolContract;
import com.devcli.context.ContextProfile;
import com.devcli.hook.HookLifecycle;
import com.devcli.llm.LlmClient;
import com.devcli.memory.ConversationHistoryCompactor;
import com.devcli.memory.TokenBudget;
import com.devcli.concurrent.CancellationContext;
import com.devcli.concurrent.CancellationToken;
import com.devcli.event.RunEvent;
import com.devcli.event.RunEventSink;
import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolExecutionContext;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** 委派子任务的执行内核适配与独立历史，不负责工作区提交。 */
final class DelegationChildLoop implements AgentExecutionEngine.Delegate<ToolOutput> {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final RunEventSink events;
    private final DelegationState state;
    private final DelegationCheckpoint checkpoint;
    private final java.util.function.Function<ObjectNode, String> reportSerializer;
    private final ToolRegistry registry;
    private final LlmClient client;
    private final AgentBudget budget;
    private final String id;
    private final ToolExecutionContext context;
    private final List<LlmClient.Tool> tools;
    private final com.devcli.event.ConversationJournal history = new com.devcli.event.ConversationJournal(List.of());
    private final List<String> evidence = new ArrayList<>();
    private final Deque<ObjectNode> observations = new ArrayDeque<>();
    private final Deque<ObjectNode> deadEnds = new ArrayDeque<>();
    private boolean hasSuccessfulEvidence;
    private final int childMaxIterations;
    private final java.util.Set<String> unresolvedMutations = new java.util.HashSet<>();
    private boolean everHadMutationFailure;
    private final ConversationHistoryCompactor compactor;
    private final Map<String, String> arguments;

    DelegationChildLoop(ToolRegistry registry, LlmClient client, AgentBudget budget, String id,
              String rolePrompt, Map<String, String> arguments, ToolExecutionContext context,
              List<LlmClient.Tool> tools, int childMaxIterations, String upstreamReport,
              DelegationState.Snapshot resumed, String systemPrompt, RunEventSink events,
                        DelegationState state, DelegationCheckpoint checkpoint,
                        java.util.function.Function<ObjectNode, String> reportSerializer) throws IOException {
        this.events = events;
        this.state = state;
        this.checkpoint = checkpoint;
        this.reportSerializer = reportSerializer;
        this.registry = registry;
        this.client = client;
        this.budget = budget;
        this.id = id;
        this.context = context;
        this.tools = tools;
        this.childMaxIterations = childMaxIterations;
        this.arguments = arguments;
        history.add(LlmClient.Message.system(systemPrompt + "\n\n你是受主 Agent 委派的子 Agent。"
                + rolePrompt + "\n只完成下述子任务，不能继续委派或扩大授权范围。"
                + "所有文件路径相对于当前工作区：" + registry.getProjectPath()
                + "\n返回简洁结果、修改文件、验证证据和未完成项；不要把未执行的检查称为通过。"));
        if (resumed != null) {
            history.addAll(pairedHistory(resumed.history()));
            {
                JsonNode previous = JSON.readTree(resumed.report());
                if (previous != null) {
                    previous.path("evidence").forEach(node -> {
                        if (observations.size() == 64) observations.removeFirst();
                        if (node.isObject()) observations.addLast((ObjectNode) node.deepCopy());
                    });
                    previous.path("dead_ends").forEach(node -> {
                        if (deadEnds.size() == 32) deadEnds.removeFirst();
                        if (node.isObject()) deadEnds.addLast((ObjectNode) node.deepCopy());
                    });
                    hasSuccessfulEvidence = observations.stream()
                            .anyMatch(node -> "SUCCESS".equals(node.path("status").asText()));
                    everHadMutationFailure = previous.path("ever_had_mutation_failure").asBoolean(false);
                }
            }
            history.add(LlmClient.Message.internalUser("继续同一子任务。当前规则、工具权限和预算重新绑定。"
                    + "旧工作区路径不可复用；未提交补丁只在基线匹配时恢复。此前失败尝试是历史证据，"
                    + "本次仍须实际完成并验证，不能把旧工具成功状态当作本次验收。"));
        }
        StringBuilder taskBuilder = new StringBuilder("任务：")
                .append(arguments.get("task"))
                .append("\n必要背景：").append(arguments.getOrDefault("context", ""));
        appendBriefField(taskBuilder, "交付物", arguments.get("deliverable"));
        appendBriefField(taskBuilder, "任务契约", arguments.get("task_spec"));
        appendBriefField(taskBuilder, "约束", arguments.get("constraints"));
        appendBriefField(taskBuilder, "入口文件/符号", arguments.get("entry_points"));
        appendBriefField(taskBuilder, "允许工具", arguments.get("allowed_tools"));
        appendBriefField(taskBuilder, "允许写入路径", arguments.get("allowed_write_paths"));
        appendBriefField(taskBuilder, "预算", arguments.get("budget"));
        String task = taskBuilder.toString();
        String upstreamReportId = arguments.getOrDefault("upstream_report_id", "").trim();
        if (!upstreamReportId.isBlank()) {
            if (upstreamReport == null) {
                task += "\n程序注入的上游结构化报告（原文，不得改写）：\n[上游报告不可用：" + upstreamReportId + "]";
            } else {
                task += "\n程序注入的上游结构化报告（原文，不得改写）：\n" + upstreamReport;
            }
        }
        history.add(LlmClient.Message.user(AgentRuntimeSupport.prependSkillBodies(
                registry.getSkillContextBuffer(), task, false)));
        // 压缩沿用现有实现；摘要模型调用也计入共享预算。
        compactor = new ConversationHistoryCompactor(new BudgetedSummaryClient(client, budget.fork(), events));
        compactor.setMicrocompactOutputRoot(Path.of(registry.getProjectPath()));
        compactor.setSummaryToolsSupplier(() -> tools);
        compactor.setPostCompactContextSupplier(() -> AgentRuntimeSupport.buildPostCompactRestoreSection(
                "", registry, registry.getSkillContextBuffer()));
    }

    ToolOutput run() {
        ToolOutput result;
        String recoveryError = "";
        try {
            result = new AgentExecutionEngine<ToolOutput>(client, budget, HookLifecycle.load(registry)).run(this);
        } finally {
            if (state.isActive(id)) {
                try {
                    state.saveRecovery(id, arguments, history.messages(), null);
                } catch (IOException e) {
                    recoveryError = e.getMessage();
                }
            }
        }
        if (state.isActive(id)) {
            try {
                ObjectNode report = (ObjectNode) JSON.readTree(result.text());
                report.put("resume_available", recoveryError.isEmpty());
                if (!recoveryError.isEmpty()) report.put("recovery_error", DelegationSession.bounded(recoveryError));
                result = new ToolOutput(result.status(), result.errorCode(), result.retryable(),
                        reportSerializer.apply(report), result.imageParts(), result.modifiedResources(), result.sideChannels());
            } catch (IOException ignored) { }
        }
        return result;
    }
    @Override public com.devcli.event.ConversationJournal history() { return history; }
    @Override public List<LlmClient.Tool> toolDefinitions(int iteration) { return tools; }
    @Override public ToolContract.ToolSnapshot toolSnapshot(int iteration) {
        return registry.snapshotForCurrentAccess().withDefinitions(tools);
    }
    @Override public LlmClient.StreamListener streamListener() { return LlmClient.StreamListener.NO_OP; }
    @Override public int maxIterations() { return childMaxIterations; }
    @Override public boolean isCancelled() { return context.isCancelled() || CancellationContext.isCancelled(); }
    @Override public RunEventSink eventSink() {
        // 子循环终态不能变成父运行终态，文本和历史也不混入父模型上下文。
        return event -> {
            events.emit(new RunEvent.AgentEvent(id, event));
            if (event instanceof RunEvent.ModelUsage) events.emit(event);
        };
    }
    @Override public void beforeIteration(int iteration, AgentBudget currentBudget) {
        history.revise("compaction", messages -> compactor.compactIfNeeded(messages, AgentRuntimeSupport.buildCompactionContext(
                ContextProfile.from(client).historyTriggerTokens(
                        TokenBudget.estimateToolDefinitionsTokens(tools)),
                registry, id)));
        if (registry.getSkillContextBuffer() != null) {
            String pending = registry.getSkillContextBuffer().drain();
            if (!pending.isBlank()) history.add(LlmClient.Message.internalUser(pending));
        }
    }
    @Override public List<ToolContract.ToolExecutionResult> executeTools(List<LlmClient.ToolCall> calls, int iteration) {
        return executeTools(calls, iteration, null);
    }
    @Override public List<ToolContract.ToolExecutionResult> executeTools(
            List<LlmClient.ToolCall> calls, int iteration, ToolContract.ToolSnapshot snapshot) {
        return registry.executeTools(calls.stream().map(call -> new ToolContract.ToolInvocation(
                call.id(), call.function().name(), call.function().arguments())).toList(), snapshot);
    }
    @Override public void afterToolResults(LlmClient.ChatResponse response,
            List<ToolContract.ToolExecutionResult> results, int iteration, AgentBudget currentBudget) {
        for (var result : results) {
            if (checkpoint != null) checkpoint.observeEvidence().accept(result);
            if (evidence.size() == 64) evidence.remove(0);
            evidence.add(result.name() + ":" + result.status() + ":" + result.errorCode());
            ObjectNode observed = JSON.createObjectNode();
            observed.put("invocation_id", result.id()).put("tool", result.name())
                    .put("status", result.status().name()).put("error_code", result.errorCode().name())
                    .put("arguments", excerpt(result.argumentsJson()))
                    .put("output_excerpt", excerpt(result.result()));
            result.sideChannels().stream()
                    .filter(com.devcli.tool.ToolResultArtifact.class::isInstance)
                    .map(com.devcli.tool.ToolResultArtifact.class::cast)
                    .filter(artifact -> !artifact.artifactRef().isBlank()).findFirst()
                    .ifPresent(artifact -> observed.put("result_ref", artifact.artifactRef()));
            if (observations.size() == 64) observations.removeFirst();
            observations.addLast(observed);
            if (result.status() == com.devcli.tool.ToolStatus.SUCCESS) {
                hasSuccessfulEvidence = true;
            } else {
                if (deadEnds.size() == 32) deadEnds.removeFirst();
                deadEnds.addLast(observed);
            }
            var effect = registry.toolEffect(result.name());
            if (effect != ToolContract.ToolEffect.READ_ONLY && effect != ToolContract.ToolEffect.LOCAL_CONTEXT) {
                String key = mutationKey(result);
                if (result.status() == com.devcli.tool.ToolStatus.SUCCESS) unresolvedMutations.remove(key);
                else {
                    unresolvedMutations.add(key);
                    everHadMutationFailure = true;
                }
            }
            if (result.hasImageParts()) history.add(LlmClient.Message.user(result.imageParts(), LlmClient.MessageSource.TOOL));
        }
    }
    @Override public Map<String, String> refreshStaleContext() { return registry.refreshStaleContext(id); }
    @Override public String contextScope() { return id; }
    @Override public ToolOutput completed(LlmClient.ChatResponse response, AgentBudget currentBudget) {
        ObjectNode report = JSON.createObjectNode();
        report.put("child_id", id).put("report_id", id).put("status", "done")
                .put("model", client.getModelName())
                .put("summary", DelegationSession.bounded(response.content())).put("iterations", currentBudget.iteration());
        appendEvidence(report);
        report.putArray("facts_discovered");
        report.putArray("modified_resources");
        report.put("transcript_ref", "delegation:" + id);
        report.put("verification", "工具状态仅表示执行结果；主 Agent 仍需核对任务验收条件");
        if (registry.currentToolAccessScope() == ToolContract.ToolAccessScope.ISOLATED_PROJECT
                && !unresolvedMutations.isEmpty()) {
            report.put("status", "failed").put("error", "仍有未解决的副作用工具失败，未应用工作区修改");
            return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED, reportSerializer.apply(report), false);
        }
        return ToolOutput.success(reportSerializer.apply(report));
    }
    @Override public ToolOutput cancelled(AgentBudget currentBudget) {
        if (context.cancellation().map(c -> c.reason() == CancellationToken.Reason.TIMEOUT).orElse(false)) {
            return terminalReport("timed_out", "子任务超过执行期限", ToolErrorCode.TIMEOUT, false);
        }
        return terminalReport("cancelled", "子任务已取消", ToolErrorCode.CANCELLED, false);
    }
    @Override public ToolOutput budgetExceeded(AgentBudget.ExitReason reason, AgentBudget currentBudget) {
        return terminalReport("partial", currentBudget.describeExit(reason), ToolErrorCode.EXECUTION_FAILED, false);
    }
    @Override public ToolOutput iterationLimitReached(AgentBudget currentBudget) {
        return terminalReport("partial", "子任务达到 " + childMaxIterations + " 轮上限",
                ToolErrorCode.EXECUTION_FAILED, false);
    }
    @Override public ToolOutput failed(IOException error, AgentBudget currentBudget) {
        if (isCancelled()) return cancelled(currentBudget);
        return terminalReport("failed", "子任务模型调用失败: " + error.getMessage(),
                ToolErrorCode.EXECUTION_FAILED, false);
    }

    private ToolOutput terminalReport(String status, String summary,
                                       ToolErrorCode errorCode, boolean retryable) {
        ObjectNode report = JSON.createObjectNode();
        report.put("child_id", id).put("report_id", id).put("status", status)
                .put("summary", DelegationSession.bounded(summary)).put("transcript_ref", "delegation:" + id);
        report.putArray("modified_resources");
        appendEvidence(report);
        report.withArray("open_questions").add(DelegationSession.bounded(summary));
        report.putArray("facts_discovered");
        com.devcli.tool.ToolStatus toolStatus = "cancelled".equals(status)
                ? com.devcli.tool.ToolStatus.CANCELLED : "timed_out".equals(status)
                ? com.devcli.tool.ToolStatus.TIMEOUT : com.devcli.tool.ToolStatus.ERROR;
        return new ToolOutput(toolStatus, errorCode, retryable,
                reportSerializer.apply(report), List.of(), List.of(), List.of());
    }

    private void appendEvidence(ObjectNode report) {
        var checks = report.putArray("tool_evidence");
        evidence.forEach(checks::add);
        var observed = report.putArray("evidence");
        observations.forEach(observed::add);
        var rejected = report.putArray("dead_ends");
        deadEnds.forEach(rejected::add);
        var unresolved = report.putArray("unresolved_mutations");
        unresolvedMutations.stream().sorted().map(DelegationChildLoop::excerpt).forEach(unresolved::add);
        var questions = report.putArray("open_questions");
        unresolvedMutations.stream().sorted().map(DelegationChildLoop::excerpt).forEach(questions::add);
        report.put("knowledge_outcome", hasSuccessfulEvidence ? "PARTIAL" : "NONE");
        report.put("patch_status", registry.currentToolAccessScope()
                == ToolContract.ToolAccessScope.ISOLATED_PROJECT ? "NOT_APPLIED" : "NOT_APPLICABLE");
        report.put("ever_had_mutation_failure", everHadMutationFailure);
        report.put("evidence_notice", "工具摘录是观察，不是验收结论；失败尝试也不等于永久不可行");
    }

    private void appendBriefField(StringBuilder task, String label, String value) {
        if (value != null && !value.isBlank()) {
            task.append("\n").append(label).append("：").append(value);
        }
    }

/** 中断留下的工具调用只补充未知结果，不重放工具或推断成功。 */
private List<LlmClient.Message> pairedHistory(List<LlmClient.Message> messages) {
    List<LlmClient.Message> paired = new ArrayList<>();
    Set<String> pending = new java.util.LinkedHashSet<>();
    for (LlmClient.Message message : messages) {
        if ("tool".equals(message.role())) {
            if (pending.remove(message.toolCallId())) paired.add(message);
            continue;
        }
        pending.forEach(callId -> paired.add(new LlmClient.Message("tool",
                "上次执行中断，结果未知；需要重新读取文件核验", null, null, callId)));
        pending.clear();
        paired.add(message);
        if (message.toolCalls() != null) message.toolCalls().forEach(call -> pending.add(call.id()));
    }
    pending.forEach(callId -> paired.add(new LlmClient.Message("tool",
            "上次执行中断，结果未知；需要重新读取文件核验", null, null, callId)));
    return paired;
}

private static String excerpt(String text) {
    String safe = com.devcli.policy.SensitiveDataRedactor.redact(text == null ? "" : text);
    return safe.length() <= 512 ? safe : safe.substring(0, 512) + "\n[excerpt truncated]";
}

private static String mutationKey(ToolContract.ToolExecutionResult result) {
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
