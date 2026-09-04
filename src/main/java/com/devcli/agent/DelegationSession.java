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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** 一次主任务的按需委派能力。只装配子循环，不负责规划、评审或自动重做。 */
final class DelegationSession implements DelegateTaskTool.Handler {
    private static final int MAX_REPORT_CHARS = 12000;
    private static final int MAX_STORED_REPORTS = 64;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ToolRegistry parent;
    private final Function<String, LlmClient> modelResolver;
    private final Map<String, LlmClient> models = new ConcurrentHashMap<>();
    private final AgentBudget parentBudget;
    private final String systemPrompt;
    private final RunEventSink events;
    private final PromptRepository prompts;
    private final int maxChildIterations;
    private final Map<String, String> reportStore = new ConcurrentHashMap<>();
    private final Deque<String> reportOrder = new ArrayDeque<>();
    private final Map<String, List<LlmClient.Tool>> toolSnapshots = new ConcurrentHashMap<>();

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
        String id = "delegate-" + UUID.randomUUID().toString().substring(0, 12);
        String role = arguments.getOrDefault("role", "").toLowerCase(Locale.ROOT);
        if (!java.util.Set.of("explorer", "planner", "worker", "reviewer").contains(role)
                || arguments.getOrDefault("task", "").isBlank()) {
            return reportFailure(id, ToolOutput.error(ToolErrorCode.INVALID_ARGUMENTS,
                    "需要有效角色和非空子任务", false), "failed");
        }
        if (parent.currentToolAccessScope() != ToolRegistry.ToolAccessScope.FULL) {
            return reportFailure(id, ToolOutput.rejected(ToolErrorCode.CAPABILITY_DENIED,
                    "受限子任务不能继续委派"), "blocked");
        }
        AgentBudget budget = parentBudget.fork();
        if (budget.check() != AgentBudget.ExitReason.WITHIN_BUDGET) {
            return reportFailure(id, ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                    budget.describeExit(budget.check()), false), "blocked");
        }
        ToolOutput result;
        events.emit(new RunEvent.CustomMessage("delegation.started", "子任务开始",
                Map.of("child_id", id, "report_id", id, "role", role)));
        try {
            context.throwIfCancelled();
            String rolePrompt = prompts.loadRequired("modes/delegate-" + role + ".md");
            LlmClient client = models.computeIfAbsent(role, modelResolver);
            if (client == null) throw new IllegalArgumentException("子 Agent 模型不可用: " + role);
            if (role.equals("worker")) {
                try (WorkspaceExecutionSession workspace = WorkspaceExecutionSession.open(parent, id)) {
                    result = executeChild(workspace.toolRegistry(), client, budget, id, role, rolePrompt,
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
                            appendPatchEvidence(report, patch);
                            boolean reviewRequired = DelegationReviewGate.requiresIndependentReview(
                                    new DelegationReviewGate.Signals(
                                            applied.modifiedResources(), childEverHadMutationFailure(result)));
                            report.put("independent_review_required", reviewRequired);
                            if (reviewRequired) {
                                ToolOutput review = runIndependentReview(report.toString(), arguments, context);
                                DelegationReviewProtocol.Decision decision = DelegationReviewProtocol.evaluate(review.text());
                                if (!review.isSuccess() || !decision.protocolValid() || !decision.approved()) {
                                    return reportFailure(id, ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                                            "独立 Reviewer 未通过: " + decision.summary(), false), "failed");
                                }
                                report.put("independent_review", "APPROVED");
                                if (decision.advisories() > 0) {
                                    var advisories = report.putArray("advisories");
                                    decision.advisoryIssues().forEach(advisories::add);
                                }
                            } else {
                                report.put("independent_review", "NOT_REQUIRED");
                            }
                            report.put("report_id", id).put("status", "done");
                            storeReport(id, report.toString());
                            result = ToolOutput.success(report.toString()).withModifiedResources(applied.modifiedResources());
                        } else {
                            result = ToolOutput.error(ToolErrorCode.RESOURCE_CONFLICT,
                                    applied.failureDescription(), false);
                        }
                    }
                }
            } else {
                try (ToolRegistry child = parent.forkForProject(Path.of(parent.getProjectPath()))) {
                    result = executeChild(child, client, budget, id, role, rolePrompt,
                            arguments, context, ToolRegistry.ToolAccessScope.READ_ONLY);
                    if (result.isSuccess()) {
                        result = registerChildReport(id, result);
                    }
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
        result = ensureReport(id, result, arguments);
        events.emit(new RunEvent.CustomMessage("delegation.completed", "子任务结束",
                Map.of("child_id", id, "report_id", id, "role", role,
                        "status", result.status().name())));
        return result;
    }

    private ToolOutput ensureReport(String id, ToolOutput result, Map<String, String> arguments) {
        if (result == null) {
            return reportFailure(id, ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                    "子任务返回空结果", false), "failed");
        }
        try {
            JsonNode root = JSON.readTree(result.text());
            if (root != null && root.isObject() && root.has("report_id") && root.has("status")) {
                ObjectNode report = (ObjectNode) root;
                appendRequestMetadata(report, arguments);
                String normalized = report.toString();
                storeReport(id, normalized);
                return new ToolOutput(result.status(), result.errorCode(), result.retryable(), normalized,
                        result.imageParts(), result.modifiedResources(), result.sideChannels());
            }
        } catch (IOException ignored) {
        }
        return result.isSuccess()
                ? registerChildReport(id, result)
                : reportFailure(id, result, result.status() == com.devcli.tool.ToolStatus.CANCELLED
                        ? "blocked" : "failed");
    }

    private void appendRequestMetadata(ObjectNode report, Map<String, String> arguments) {
        if (arguments == null || arguments.isEmpty()) return;
        ObjectNode request = report.with("request");
        copyRequestValue(request, "task", arguments.get("task"));
        copyRequestValue(request, "context", arguments.get("context"));
        copyRequestValue(request, "deliverable", arguments.get("deliverable"));
        copyRequestValue(request, "constraints", arguments.get("constraints"));
        copyRequestValue(request, "entry_points", arguments.get("entry_points"));
        copyRequestValue(request, "allowed_tools", arguments.get("allowed_tools"));
        copyRequestValue(request, "allowed_write_paths", arguments.get("allowed_write_paths"));
        copyRequestValue(request, "budget", arguments.get("budget"));
        copyRequestValue(request, "upstream_report_id", arguments.get("upstream_report_id"));
    }

    private void copyRequestValue(ObjectNode request, String name, String raw) {
        if (raw == null || raw.isBlank()) return;
        try {
            JsonNode parsed = JSON.readTree(raw);
            if (parsed != null && (parsed.isArray() || parsed.isObject())) {
                request.set(name, parsed);
                return;
            }
        } catch (IOException ignored) {
        }
        request.put(name, raw);
    }

    private ToolOutput reportFailure(String id, ToolOutput output, String status) {
        ObjectNode report = JSON.createObjectNode();
        report.put("report_id", id).put("status", status == null ? "failed" : status)
                .put("summary", bounded(output == null ? "" : output.text()))
                .put("transcript_ref", "delegation:" + id);
        if (output != null) {
            report.put("error_code", output.errorCode().name()).put("retryable", output.retryable());
            output.modifiedResources().forEach(report.withArray("modified_resources")::add);
        }
        report.putArray("evidence");
        report.putArray("dead_ends");
        report.putArray("open_questions");
        report.putArray("facts_discovered");
        String normalized = report.toString();
        storeReport(id, normalized);
        if (output == null) return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED, normalized, false);
        return new ToolOutput(output.status(), output.errorCode(), output.retryable(), normalized,
                output.imageParts(), output.modifiedResources(), output.sideChannels());
    }

    private boolean childEverHadMutationFailure(ToolOutput result) {
        try {
            return JSON.readTree(result.text()).path("ever_had_mutation_failure").asBoolean(false);
        } catch (IOException ignored) {
            return false;
        }
    }

    private void storeReport(String id, String report) {
        synchronized (reportOrder) {
            reportStore.put(id, report);
            reportOrder.remove(id);
            reportOrder.addLast(id);
            while (reportOrder.size() > MAX_STORED_REPORTS) {
                String expired = reportOrder.removeFirst();
                reportStore.remove(expired);
            }
        }
    }

    private ToolOutput registerChildReport(String id, ToolOutput result) {
        try {
            JsonNode root = JSON.readTree(result.text());
            if (root != null && root.isObject()) {
                ObjectNode report = (ObjectNode) root;
                report.put("report_id", id);
                if (!report.has("status")) report.put("status", "done");
                if (!report.has("summary")) report.put("summary", bounded(result.text()));
                if (!report.has("transcript_ref")) report.put("transcript_ref", "delegation:" + id);
                if (!report.has("modified_resources")) report.putArray("modified_resources");
                if (!report.has("evidence")) report.set("evidence", report.path("tool_evidence").deepCopy());
                if (!report.has("dead_ends")) report.putArray("dead_ends");
                if (!report.has("open_questions")) report.putArray("open_questions");
                if (!report.has("facts_discovered")) report.putArray("facts_discovered");
                String normalized = report.toString();
                storeReport(id, normalized);
                return ToolOutput.success(normalized).withModifiedResources(result.modifiedResources());
            }
        } catch (IOException ignored) {
            // Preserve a bounded textual report when a child did not use the structured format.
        }
        ObjectNode wrapper = JSON.createObjectNode();
        wrapper.put("child_id", id).put("report_id", id).put("status", "done")
                .put("summary", bounded(result.text())).put("transcript_ref", "delegation:" + id);
        wrapper.putArray("evidence");
        wrapper.putArray("dead_ends");
        wrapper.putArray("open_questions");
        wrapper.putArray("facts_discovered");
        String normalized = wrapper.toString();
        storeReport(id, normalized);
        return ToolOutput.success(normalized).withModifiedResources(result.modifiedResources());
    }

    private void appendPatchEvidence(ObjectNode report, PatchSet patch) {
        var patches = report.putArray("patches");
        for (PatchSet.FileChange change : patch.changes()) {
            ObjectNode entry = patches.addObject();
            entry.put("path", change.relativePath());
            entry.put("type", change.type().name());
            entry.put("before_hash", change.beforeHash());
            entry.put("after_hash", change.afterHash());
        }
    }

    private ToolOutput runIndependentReview(String workerReport,
                                            Map<String, String> arguments,
                                            ToolExecutionContext context) {
        try {
            LlmClient reviewer = models.computeIfAbsent("reviewer", modelResolver);
            if (reviewer == null) {
                return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                        "独立 Reviewer 模型不可用", false);
            }
            Map<String, String> reviewArguments = Map.of(
                    "task", "独立复核委派 Worker 的实际修改，只判断是否违反任务要求",
                    "context", "原始任务：" + arguments.getOrDefault("task", "")
                            + "\nWorker 结构化报告（仅作线索，必须自行读取文件核对）：\n" + workerReport);
            try (ToolRegistry reviewerRegistry = parent.forkForProject(Path.of(parent.getProjectPath()))) {
                return executeChild(reviewerRegistry, reviewer, parentBudget.fork(),
                        "delegate-reviewer-" + UUID.randomUUID().toString().substring(0, 8),
                        "reviewer",
                        prompts.loadRequired("modes/delegate-reviewer.md"), reviewArguments,
                        context, ToolRegistry.ToolAccessScope.READ_ONLY);
            }
        } catch (Exception e) {
            return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                    "独立 Reviewer 执行失败: " + e.getMessage(), false);
        }
    }

    private ToolOutput executeChild(ToolRegistry registry, LlmClient client, AgentBudget budget,
                                    String id, String role, String rolePrompt, Map<String, String> arguments,
                                    ToolExecutionContext context, ToolRegistry.ToolAccessScope scope) {
        return registry.runWithAllowedTools(parseStringSet(arguments.get("allowed_tools")),
                () -> registry.runWithAllowedWritePaths(parseStringList(arguments.get("allowed_write_paths")),
                        () -> executeChildInternal(registry, client, budget, id, role, rolePrompt,
                                arguments, context, scope)));
    }

    private ToolOutput executeChildInternal(ToolRegistry registry, LlmClient client, AgentBudget budget,
                                             String id, String role, String rolePrompt, Map<String, String> arguments,
                                             ToolExecutionContext context, ToolRegistry.ToolAccessScope scope) {
        var skillBuffer = parent.activeSkillContextBuffer();
        registry.restrictForDelegation();
        if (skillBuffer != null) registry.setSkillContextBuffer(skillBuffer.copy());
        registry.setContextProfile(ContextProfile.from(client));
        List<LlmClient.Tool> tools = registry.runWithToolAccess(scope,
                () -> toolSnapshots.computeIfAbsent(role + "|" + String.join(",", parseStringSet(arguments.get("allowed_tools"))), ignored -> {
                    registry.prefetchToolDefinitionsForInput(arguments.get("task"));
                    return List.copyOf(registry.getToolDefinitions());
                }));
        try (RunContext run = CancellationContext.startRunContext(Path.of(registry.getProjectPath()));
             CancellationToken.Registration registration = context.cancellationToken().onCancel(
                     cancelled -> run.cancellationToken().cancel(cancelled.reason(), cancelled.message()))) {
            return registry.runWithToolAccess(scope, () -> registry.runWithResourceLease(id, () -> {
                try {
                    return new ChildLoop(registry, client, budget, id, rolePrompt, arguments, context, tools,
                            childMaxIterations(arguments)).run();
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
        private final List<LlmClient.Tool> tools;
        private final List<LlmClient.Message> history = new ArrayList<>();
        private final List<String> evidence = new ArrayList<>();
        private final int childMaxIterations;
        private final java.util.Set<String> unresolvedMutations = new java.util.HashSet<>();
        private boolean everHadMutationFailure;
        private final ConversationHistoryCompactor compactor;

        ChildLoop(ToolRegistry registry, LlmClient client, AgentBudget budget, String id,
                  String rolePrompt, Map<String, String> arguments, ToolExecutionContext context,
                  List<LlmClient.Tool> tools, int childMaxIterations) {
            this.registry = registry;
            this.client = client;
            this.budget = budget;
            this.id = id;
            this.context = context;
            this.tools = tools;
            this.childMaxIterations = childMaxIterations;
            history.add(LlmClient.Message.system(systemPrompt + "\n\n你是受主 Agent 委派的子 Agent。"
                    + rolePrompt + "\n只完成下述子任务，不能继续委派或扩大授权范围。"
                    + "所有文件路径相对于当前工作区：" + registry.getProjectPath()
                    + "\n返回简洁结果、修改文件、验证证据和未完成项；不要把未执行的检查称为通过。"));
            StringBuilder taskBuilder = new StringBuilder("任务：")
                    .append(arguments.get("task"))
                    .append("\n必要背景：").append(arguments.getOrDefault("context", ""));
            appendBriefField(taskBuilder, "交付物", arguments.get("deliverable"));
            appendBriefField(taskBuilder, "约束", arguments.get("constraints"));
            appendBriefField(taskBuilder, "入口文件/符号", arguments.get("entry_points"));
            appendBriefField(taskBuilder, "允许工具", arguments.get("allowed_tools"));
            appendBriefField(taskBuilder, "允许写入路径", arguments.get("allowed_write_paths"));
            appendBriefField(taskBuilder, "预算", arguments.get("budget"));
            String task = taskBuilder.toString();
            String upstreamReportId = arguments.getOrDefault("upstream_report_id", "").trim();
            if (!upstreamReportId.isBlank()) {
                String upstreamReport = reportStore.get(upstreamReportId);
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
            compactor.setPostCompactContextSupplier(() -> AgentRuntimeSupport.buildPostCompactRestoreSection(
                    "", registry, registry.getSkillContextBuffer()));
        }

        ToolOutput run() {
            return new AgentExecutionEngine<ToolOutput>(client, budget, HookLifecycle.load(registry)).run(this);
        }
        @Override public List<LlmClient.Message> history() { return history; }
        @Override public List<LlmClient.Tool> toolDefinitions(int iteration) { return tools; }
        @Override public LlmClient.StreamListener streamListener() { return LlmClient.StreamListener.NO_OP; }
        @Override public int maxIterations() { return childMaxIterations; }
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
                    TokenBudget.estimateToolDefinitionsTokens(tools)));
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
                    .put("summary", bounded(response.content())).put("iterations", currentBudget.iteration());
            var checks = report.putArray("tool_evidence");
            evidence.forEach(checks::add);
            report.set("evidence", checks.deepCopy());
            report.putArray("dead_ends");
            report.putArray("open_questions");
            report.putArray("facts_discovered");
            report.putArray("modified_resources");
            report.put("transcript_ref", "delegation:" + id);
            report.put("verification", "工具状态仅表示执行结果；主 Agent 仍需核对任务验收条件");
            report.put("ever_had_mutation_failure", everHadMutationFailure);
            if (registry.currentToolAccessScope() == ToolRegistry.ToolAccessScope.ISOLATED_PROJECT
                    && !unresolvedMutations.isEmpty()) {
                report.put("status", "failed").put("error", "仍有未解决的副作用工具失败，未应用工作区修改");
                return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED, report.toString(), false);
            }
            return ToolOutput.success(report.toString());
        }
        @Override public ToolOutput cancelled(AgentBudget currentBudget) {
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
            return terminalReport("failed", "子任务模型调用失败: " + error.getMessage(),
                    ToolErrorCode.EXECUTION_FAILED, false);
        }

        private ToolOutput terminalReport(String status, String summary,
                                           ToolErrorCode errorCode, boolean retryable) {
            ObjectNode report = JSON.createObjectNode();
            report.put("child_id", id).put("report_id", id).put("status", status)
                    .put("summary", bounded(summary)).put("transcript_ref", "delegation:" + id);
            report.putArray("modified_resources");
            report.putArray("evidence");
            report.putArray("dead_ends");
            report.putArray("open_questions");
            report.putArray("facts_discovered");
            com.devcli.tool.ToolStatus toolStatus = "cancelled".equals(status)
                    ? com.devcli.tool.ToolStatus.CANCELLED : com.devcli.tool.ToolStatus.ERROR;
            return new ToolOutput(toolStatus, errorCode, retryable,
                    report.toString(), List.of(), List.of(), List.of());
        }

        private void appendBriefField(StringBuilder task, String label, String value) {
            if (value != null && !value.isBlank()) {
                task.append("\n").append(label).append("：").append(value);
            }
        }
    }

    private int childMaxIterations(Map<String, String> arguments) {
        String raw = arguments.get("budget");
        if (raw == null || raw.isBlank()) return maxChildIterations;
        try {
            int requested = JSON.readTree(raw).path("max_iterations").asInt(maxChildIterations);
            return Math.max(1, Math.min(maxChildIterations, requested));
        } catch (IOException | RuntimeException ignored) {
            return maxChildIterations;
        }
    }

    private Set<String> parseStringSet(String raw) {
        return new java.util.LinkedHashSet<>(parseStringList(raw));
    }

    private List<String> parseStringList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            JsonNode node = JSON.readTree(raw);
            if (!node.isArray()) return List.of();
            List<String> values = new ArrayList<>();
            node.forEach(item -> {
                if (item.isTextual() && !item.asText().isBlank()) values.add(item.asText());
            });
            return List.copyOf(values);
        } catch (IOException ignored) {
            return List.of();
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
