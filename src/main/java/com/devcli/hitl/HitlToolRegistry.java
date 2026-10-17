package com.devcli.hitl;

import static com.devcli.tool.ToolContract.*;
import com.devcli.browser.BrowserCheckResult;
import com.devcli.policy.AuditLog;
import com.devcli.policy.PermissionEvaluator;
import com.devcli.policy.PermissionMode;
import com.devcli.policy.PermissionRuleSet;
import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolExecutionPipeline;
import com.devcli.tool.ResourceLeaseMaintenance;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;
import com.devcli.tool.provider.DeleteFilesTool;
import com.devcli.config.ConfigResolver;
import com.devcli.policy.CommandGuard;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 在统一工具执行管线的 HITL 阶段插入人工审批。
 */
public class HitlToolRegistry extends ToolRegistry {
    private final HitlHandler hitlHandler;
    private final int deleteApprovalThreshold = ConfigResolver.intValue(
            "devcli.delete.approval.threshold", "DEVCLI_DELETE_APPROVAL_THRESHOLD", 50, 1, 500);
    private final ReentrantLock approvalLock;
    /** 用户规则层；空集合表示没有规则，审批链行为与未引入规则时完全一致。 */
    private volatile PermissionRuleSet permissionRules = PermissionRuleSet.EMPTY;
    private com.devcli.config.ProjectPermissionsStore projectPermissions;
    private String permissionProject;
    private java.nio.file.Path permissionRoot;
    private java.util.concurrent.atomic.AtomicInteger consecutiveBlocks = new java.util.concurrent.atomic.AtomicInteger();
    private java.util.concurrent.atomic.AtomicInteger totalBlocks = new java.util.concurrent.atomic.AtomicInteger();

    public HitlToolRegistry withProjectPermissions(com.devcli.config.ProjectPermissionsStore store, String project) {
        projectPermissions = store;
        permissionProject = project;
        permissionRoot = contextProjectRoot();
        return this;
    }

    private boolean trustedProject() {
        return projectPermissions != null && permissionRoot.equals(contextProjectRoot())
                && projectPermissions.isTrusted(permissionProject);
    }

    private PermissionRuleSet effectiveRules() {
        if (projectPermissions == null || !permissionRoot.equals(contextProjectRoot())) return permissionRules;
        var allow = new java.util.ArrayList<>(permissionRules.allow());
        allow.addAll(com.devcli.policy.PermissionRule.parseAll(projectPermissions.allowRules(permissionProject)));
        return new PermissionRuleSet(permissionRules.hardDeny(), permissionRules.softDeny(), allow, permissionRules.environment());
    }

    private PermissionRuleSet classifierRules(String tool) {
        var rules = effectiveRules();
        if (com.devcli.policy.ToolResourceSlot.of(tool) != com.devcli.policy.ToolResourceSlot.COMMAND) return rules;
        return new PermissionRuleSet(rules.hardDeny(), rules.softDeny(), rules.allow().stream()
                .filter(com.devcli.policy.CommandPermissionRules::isNarrow).toList(), rules.environment());
    }
    /**
     * 权限模式；默认逐个询问。
     *
     * <p>用持有者而不是普通字段，是因为模式可以在会话中途由 {@code /mode} 切换，而项目 fork 是
     * 新建实例——复制值会让已存在的 fork 停留在旧模式上。fork 与根注册表共享同一个持有者，
     * 切换立即对全部 fork 生效。</p>
     */
    private java.util.concurrent.atomic.AtomicReference<PermissionMode> permissionMode =
            new java.util.concurrent.atomic.AtomicReference<>(PermissionMode.DEFAULT);
    /**
     * 权限分类器；{@code null} 表示未装配。
     *
     * <p>未装配时 {@code auto} 不会放行任何未决动作——它会拒绝并计入连续失败，
     * 达到阈值后回落到 {@code default}。判定链上「拿不到答案」只能收紧，不能放宽。</p>
     */
    private volatile PermissionClassifier permissionClassifier;
    /**
     * 可信意图上下文来源。默认空上下文。
     *
     * <p>用 {@link java.util.function.Supplier} 而不是固定字符串：历史在会话中持续增长，
     * 装配时取一次会让分类器永远看到启动那一刻的空历史。与分类器一样是启动装配一次、之后只读。</p>
     *
     * <p>返回文本必须由 {@link TrustedIntentContext} 过滤并中和。</p>
     */
    private volatile java.util.function.Supplier<String> trustedIntentContext =
            () -> TrustedIntentContext.render(java.util.List.of());
    /** 分类器连续失败次数：成功一次即归零，达阈值后退出 {@code auto} 模式。 */
    private java.util.concurrent.atomic.AtomicInteger classifierFailures =
            new java.util.concurrent.atomic.AtomicInteger();
    private final int classifierFailureThreshold = ConfigResolver.intValue(
            "devcli.permission.classifier.failure.threshold",
            "DEVCLI_PERMISSION_CLASSIFIER_FAILURE_THRESHOLD", 3, 1, 100);
    /** 审批判定所需的路径能力：策略边界解析 + 项目相对键。 */
    private final ApprovalGate.PathScope approvalPathScope = new ApprovalGate.PathScope() {
        @Override
        public java.nio.file.Path resolve(String path) {
            return resolveSafePath(path);
        }

        @Override
        public String relativeKey(String path) {
            return projectRelativePathOrNull(path);
        }
    };

    public HitlToolRegistry(HitlHandler hitlHandler) {
        this(hitlHandler, new ReentrantLock(true));
    }

    /**
     * 装配用户规则层。默认空集合，此时规则层完全不参与判定。
     *
     * <p>在启动装配阶段调用一次；项目 fork 继承同一份规则集。</p>
     */
    public HitlToolRegistry withPermissionRules(PermissionRuleSet rules) {
        this.permissionRules = rules == null ? PermissionRuleSet.EMPTY : rules;
        return this;
    }

    /**
     * 装配权限模式。默认 {@link PermissionMode#DEFAULT}，即未命中规则时逐个询问。
     *
     * <p>启动时装配一次，之后由 {@code /mode} 随时切换；项目 fork 共享同一个持有者。</p>
     */
    public HitlToolRegistry withPermissionMode(PermissionMode mode) {
        PermissionMode effective = mode == null ? PermissionMode.DEFAULT : mode;
        PermissionMode previous = this.permissionMode.getAndSet(effective);
        if (previous != effective) {
            classifierFailures.set(0);
            consecutiveBlocks.set(0);
            totalBlocks.set(0);
        }
        return this;
    }

    /** 当前权限模式。 */
    public PermissionMode currentPermissionMode() {
        return permissionMode.get();
    }

    /**
     * 装配权限分类器，供 {@code auto} 模式使用。
     *
     * <p>启动时装配一次。不装配也能切到 {@code auto}，但该模式下未决动作会拒绝并计入连续失败——
     * 缺一个判定来源只能收紧，不能放宽。</p>
     */
    public HitlToolRegistry withPermissionClassifier(PermissionClassifier classifier) {
        this.permissionClassifier = classifier;
        return this;
    }

    /**
     * 装配可信意图上下文来源，供 {@code auto} 模式下的分类器判定用户意图。
     *
     * <p>不装配时分类器收到空历史，也就是「没有任何用户意图证据」，判定会更偏阻止——
     * 这与「缺一个判定来源只能收紧」的取向一致，不会因为漏接线而静默放宽。</p>
     *
     * <p>装配方必须传经 {@link TrustedIntentContext#render} 处理的文本。</p>
     */
    public HitlToolRegistry withTrustedIntentContext(java.util.function.Supplier<String> context) {
        this.trustedIntentContext = context == null
                ? () -> TrustedIntentContext.render(java.util.List.of())
                : context;
        return this;
    }

    /** 后台子任务只使用派发时的可信意图，后续用户回合不会扩大其授权。 */
    public void freezeTrustedIntentContext() {
        String snapshot = trustedIntentContext.get();
        trustedIntentContext = () -> snapshot;
    }

    private HitlToolRegistry(HitlHandler hitlHandler, ReentrantLock approvalLock) {
        super();
        this.hitlHandler = hitlHandler;
        this.approvalLock = approvalLock;
    }

    /**
     * @param sharedMode 与根注册表共享的模式持有者；{@code null} 表示新建一个（根注册表用）
     */
    private HitlToolRegistry(HitlHandler hitlHandler,
                             ResourceLeaseMaintenance maintenance,
                             ReentrantLock approvalLock,
                             java.util.concurrent.atomic.AtomicReference<PermissionMode> sharedMode,
                             java.util.concurrent.atomic.AtomicInteger sharedClassifierFailures) {
        super(maintenance);
        this.hitlHandler = hitlHandler;
        this.approvalLock = approvalLock;
        if (sharedMode != null) {
            this.permissionMode = sharedMode;
        }
        if (sharedClassifierFailures != null) {
            this.classifierFailures = sharedClassifierFailures;
        }
    }

    @Override
    protected ToolOutput reviewToolApproval(ToolExecutionPipeline.Context context, ToolExecutionPipeline.Chain chain) {
        String name = context.name();
        boolean approvalRequired = requiresApproval(name);
        boolean command = toolEffect(name) == ToolEffect.HOST_PROCESS;
        boolean host = command && commandRunsWithoutIsolation(context);
        if (command) context.putAttribute("approval.project", getProjectPath());
        ApprovalGate.Result gate = ApprovalGate.decide(name, parsedArgumentsOf(context), currentTaskGrant(), approvalPathScope);
        if (gate.decision() == ApprovalGate.Decision.DENY) return denyPolicy(context, gate.reason());

        PermissionRuleSet activeRules = effectiveRules();
        // 主机规则始终要求明确的命令范围；auto 的其他命令同样丢弃过宽 allow。
        PermissionEvaluator.Result rules = PermissionEvaluator.evaluate(name,
                ApprovalGate.resourceValues(name, parsedArgumentsOf(context), approvalPathScope), activeRules,
                host || currentPermissionMode() == PermissionMode.AUTO);
        if (rules.outcome() == PermissionEvaluator.Outcome.HARD_DENY) return denyPolicy(context, rules.reason());

        BrowserCheckResult browser = checkBrowserTool(name, context.argumentsJson(), true);
        if (browser.blocked()) return chain.proceed(context);
        if (browser.requiresPerCallApproval()) return askOrDeny(context, chain, browser.sensitiveNotice());

        boolean allowedByRules = rules.outcome() == PermissionEvaluator.Outcome.ALLOW;
        boolean authorized = allowedByRules || gate.decision() == ApprovalGate.Decision.ALLOW;
        DeleteFilesTool.Plan deletion = context.attribute(DeleteFilesTool.PLAN_ATTRIBUTE, DeleteFilesTool.Plan.class);
        if (deletion != null && (deletion.changes().size() >= deleteApprovalThreshold || !authorized)) {
            return askOrDeny(context, chain, deletion.notice());
        }
        if (toolEffect(name) == ToolEffect.HOST_PROCESS
                && CommandGuard.containsDeletion(parsedArgumentsOf(context).path("command").asText())) {
            return askOrDeny(context, chain, "检测到命令删除操作，必须单次确认；无法可靠统计目标数量及递归范围。优先使用 delete_files 提供明确文件清单。");
        }
        if ("revert_turn".equals(name)) {
            return askOrDeny(context, chain, "工作区回滚会批量覆盖当前文件，必须单次人工确认。");
        }
        if (mcpToolRequiresPerCallApproval(name)) return askOrDeny(context, chain, mcpToolApprovalNotice(name));
        if (rules.outcome() == PermissionEvaluator.Outcome.SOFT_DENY) return askOrDeny(context, chain, rules.reason());
        if (host && currentToolAccessScope() == com.devcli.tool.ToolContract.ToolAccessScope.FULL) {
            return askOrDeny(context, chain,
                    "此命令将脱离沙箱，在真实项目和主机权限下执行；文件、网络不再受沙箱约束，必须本次人工批准。");
        }
        if (host && !trustedProject()) {
            return askOrDeny(context, chain, "当前项目尚未信任，主机命令仅允许本次确认。构建脚本可能执行任意主机代码。");
        }
        if (authorized) {
            context.putAttribute(PIPELINE_APPROVAL_SOURCE, allowedByRules ? "permission_rule" : "task_grant");
            hitlHandler.onTaskGrantAllow(name, allowedByRules ? rules.reason() : gate.reason());
            return chain.proceed(context);
        }
        if (command && !host) {
            context.putAttribute(PIPELINE_APPROVAL_SOURCE, "sandbox");
            return chain.proceed(context);
        }
        if (!approvalRequired) return chain.proceed(context);
        if (currentPermissionMode().autoAllows(name)) {
            context.putAttribute(PIPELINE_APPROVAL_SOURCE, "permission_mode");
            hitlHandler.onTaskGrantAllow(name, currentPermissionMode().autoAllowReason());
            return chain.proceed(context);
        }
        if (currentPermissionMode() == PermissionMode.AUTO) {
            try {
                context.putAttribute("approval.trusted.intent", trustedIntentContext.get());
            } catch (TrustedIntentContext.IncompleteContextException incomplete) {
                context.putAttribute("approval.intent.incomplete", Boolean.TRUE);
                return askOrDeny(context, chain, incomplete.getMessage());
            }
        }
        if (hitlHandler.isApprovedAllByTool(name) || hitlHandler.isApprovedAllByServer(ApprovalPolicy.mcpServerName(name))) {
            context.putAttribute(PIPELINE_APPROVAL_SOURCE, "hitl_reused");
            return chain.proceed(context);
        }
        return askOrClassify(context, chain);
    }

    private ToolOutput denyPolicy(ToolExecutionPipeline.Context context, String reason) {
        recordToolAudit(AuditLog.AuditEntry.denyByPolicy(context.name(), context.argumentsJson(), reason, 0), context);
        return ToolOutput.rejected(ToolErrorCode.POLICY_DENIED, "[策略] 已拒绝：" + reason);
    }

    /**
     * 需要「问」的出口：模式收口、或审批通道不可用时一律改为拒绝。
     *
     * <p>所有询问路径都收敛到这里，是为了让 {@code dontAsk} 只需实现一次。
     * WorkBuddy 的语义是「把最后得到的 ask 改写成 deny」，因此凡是会走到询问的动作都要经过这里，
     * 包括非 auto 模式下的 {@code soft_deny} 规则、删除阈值与命令删除确认。</p>
     *
     * <p>审批通道不可用时失败关闭，而不是继续询问：{@code requestApproval} 在处理器关闭后
     * 仍然会去读 stdin，静默阻塞比拒绝更难排查。</p>
     */
    private ToolOutput askOrDeny(ToolExecutionPipeline.Context context,
                                 ToolExecutionPipeline.Chain chain,
                                 String sensitiveNotice) {
        String denial = currentPermissionMode().denyReason();
        boolean canAsk = hitlHandler != null && hitlHandler.isEnabled();
        if (denial.isEmpty() && canAsk) {
            return executeAfterExplicitApproval(context, chain, sensitiveNotice);
        }
        String reason = denial.isEmpty()
                ? "当前没有可用的审批通道（HITL 关闭），未获授权的动作不能放行"
                : denial;
        recordToolAudit(AuditLog.AuditEntry.denyByPolicy(
                context.name(), context.argumentsJson(), reason, 0L), context);
        return ToolOutput.rejected(ToolErrorCode.POLICY_DENIED,
                "[模式] 已拒绝：" + reason + "；改用 /mode default 可恢复逐个询问");
    }

    /**
     * 交给分类器的出口：{@code auto} 模式下由它判定，其余情况照常询问。
     *
     * <p>只接收规则、项目编辑和独立安全要求处理后仍未决的动作。</p>
     *
     * <p>与 {@link #askOrDeny} 分开是必要的：后者承载的是「有独立理由必须问」的动作——删除确认、
     * 命令删除、回滚确认、逐次审批。把它们一起交给分类器，等于让模型替用户取消
     * 这些刻意不可免除的硬要求。</p>
     */
    private ToolOutput askOrClassify(ToolExecutionPipeline.Context context,
                                     ToolExecutionPipeline.Chain chain) {
        PermissionClassifier classifier = permissionClassifier;
        if (currentPermissionMode().askPolicy() != PermissionMode.AskPolicy.AUTO) {
            return askOrDeny(context, chain, null);
        }
        if (classifier == null) {
            return denyByClassifierFailure(context, "权限分类器未装配");
        }
        long remainingNanos = context.executionContext().remainingNanos();
        long classifierBudgetNanos = TimeUnit.MILLISECONDS.toNanos(
                Math.max(0L, classifier.decisionTimeoutMillis()));
        if (remainingNanos != Long.MAX_VALUE
                && classifierBudgetNanos > 0L
                && remainingNanos < classifierBudgetNanos) {
            return denyByClassifierFailure(context,
                    "工具剩余时间不足以覆盖权限分类器超时预算，未启动分类器请求；"
                            + "可调小 devcli.permission.classifier.timeout.seconds"
                            + "（当前分类器预算 " + TimeUnit.NANOSECONDS.toSeconds(classifierBudgetNanos)
                            + " 秒，工具剩余 " + TimeUnit.NANOSECONDS.toSeconds(remainingNanos) + " 秒）");
        }
        PermissionClassifier.Verdict verdict;
        try {
            verdict = classifier.classify(new PermissionClassifier.Request(
                    context.name(), context.argumentsJson(), getProjectPath(),
                    currentPermissionMode().id(), context.attribute("approval.trusted.intent", String.class), classifierRules(context.name())));
        } catch (java.io.IOException failure) {
            return denyByClassifierFailure(context, failure.getMessage());
        }
        classifierFailures.set(0);
        if (verdict.block()) {
            recordToolAudit(AuditLog.AuditEntry.denyByPolicy(context.name(), context.argumentsJson(),
                    "分类器阻止：" + verdict.reason(), 0L), context);
            int consecutive = consecutiveBlocks.incrementAndGet();
            int total = totalBlocks.incrementAndGet();
            if (consecutive >= 3 || total >= 20) {
                permissionMode.set(PermissionMode.DEFAULT);
                return askOrDeny(context, chain, "auto 已连续拦截 " + consecutive + " 次、累计拦截 " + total
                        + " 次，已切换 default；请确认当前操作。分类器理由：" + verdict.reason());
            }
            return ToolOutput.rejected(ToolErrorCode.POLICY_DENIED,
                    "[分类器] 已阻止：" + verdict.reason() + "；改用 /mode default 可恢复逐个询问");
        }
        consecutiveBlocks.set(0);
        context.putAttribute(PIPELINE_APPROVAL_SOURCE, "permission_classifier");
        if (hitlHandler != null) {
            hitlHandler.onTaskGrantAllow(context.name(), "分类器放行：" + verdict.reason());
        }
        return chain.proceed(context);
    }

    /**
     * 分类器调用失败时的收口。
     *
     * <p>失败一律按拒绝处理，绝不猜测。连续失败达阈值后退出 {@code auto} 并回落 {@code default}：
     * 退出提示挂在这次拒绝的消息里，用户必然看到；状态栏的模式位会同步变化，不需要额外的提示通道
     * （{@code hitl} 包不得依赖 {@code render}，也没有面向用户的通用通知接口）。</p>
     */
    private ToolOutput denyByClassifierFailure(ToolExecutionPipeline.Context context, String detail) {
        String reason = detail == null || detail.isBlank() ? "分类器不可用" : detail;
        int failures = classifierFailures.incrementAndGet();
        String exitNotice = "";
        if (failures >= classifierFailureThreshold) {
            permissionMode.set(PermissionMode.DEFAULT);
            classifierFailures.set(0);
            exitNotice = "；分类器已连续失败 " + failures + " 次，已退出 auto 模式并回落 default，"
                    + "后续动作恢复逐个询问";
        }
        recordToolAudit(AuditLog.AuditEntry.denyByPolicy(context.name(), context.argumentsJson(),
                "分类器失败：" + reason, 0L), context);
        return ToolOutput.rejected(ToolErrorCode.POLICY_DENIED,
                "[分类器] 已拒绝：" + reason + exitNotice);
    }

    @Override
    protected ToolOutput reviewMemorySave(ToolExecutionPipeline.Context context) {
        PermissionEvaluator.Result rules = PermissionEvaluator.evaluate(
                "save_memory", ApprovalGate.resourceValues("save_memory",
                        parsedArgumentsOf(context), approvalPathScope), permissionRules);
        if (rules.outcome() == PermissionEvaluator.Outcome.HARD_DENY
                || !currentPermissionMode().denyReason().isEmpty()) {
            return ToolOutput.rejected(ToolErrorCode.POLICY_DENIED, "当前权限策略禁止保存长期记忆");
        }
        ApprovalResult answer = null;
        if (hitlHandler != null && hitlHandler.isEnabled()) {
            try {
                approvalLock.lockInterruptibly();
                try {
                    if (context.executionContext().isCancelled()) return ToolOutput.cancelled("记忆审批已取消");
                    if (context.executionContext().remainingNanos() == 0) return ToolOutput.timedOut("记忆审批已超时");
                    answer = hitlHandler.requestApproval(ApprovalRequest.of("save_memory",
                            context.argumentsJson(), null, context.invocationId(),
                            "user_request".equals(parsedArgumentsOf(context).path("intent").asText())
                                    ? "模型声明用户明确要求保存以下主题、作用域和内容，可能更新同名记忆；仅本次确认有效。"
                                    : "模型主动提出保存以下 project/feedback 经验，不覆盖已有主题；仅本次确认有效。"));
                } finally {
                    approvalLock.unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolOutput.cancelled("记忆审批已取消");
            }
        }
        if (context.executionContext().isCancelled()) return ToolOutput.cancelled("记忆审批已取消");
        if (context.executionContext().remainingNanos() == 0) return ToolOutput.timedOut("记忆审批已超时");
        if (answer != null && answer.decision() == ApprovalResult.Decision.APPROVED) {
            context.putAttribute(PIPELINE_APPROVAL_SOURCE, "memory_once");
            return null;
        }
        recordToolAudit(AuditLog.AuditEntry.denyByHitl("save_memory", "{}",
                "未获得单次记忆写入确认", 0L), context);
        return ToolOutput.rejected(ToolErrorCode.HITL_REJECTED,
                "长期记忆写入仅接受单次确认；自动分类、全部批准或关闭审批均不能放行");
    }

    @Override
    protected com.devcli.policy.SensitiveContentPolicy.Decision reviewSensitiveContent(
            String tool, com.devcli.policy.SensitiveContentPolicy.Inspection inspection,
            String purpose, String target, boolean redactionAllowed) {
        if (!hitlHandler.isEnabled()) {
            return com.devcli.policy.SensitiveContentPolicy.Decision.REJECT;
        }
        ApprovalResult result;
        try {
            approvalLock.lockInterruptibly();
            try {
                result = hitlHandler.requestApproval(ApprovalRequest.content(tool,
                        inspection.typeLabels(), purpose, target, redactionAllowed, inspection.source()));
            } finally {
                approvalLock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return com.devcli.policy.SensitiveContentPolicy.Decision.REJECT;
        }
        if (result != null && result.decision() == ApprovalResult.Decision.APPROVED) {
            recordContentDecision(tool, inspection.typeLabels(), purpose, target, "allow_once");
            return com.devcli.policy.SensitiveContentPolicy.Decision.ALLOW_ONCE;
        }
        if (result != null && result.isRedacted() && redactionAllowed) {
            recordContentDecision(tool, inspection.typeLabels(), purpose, target, "redact");
            return com.devcli.policy.SensitiveContentPolicy.Decision.REDACT;
        }
        recordContentDecision(tool, inspection.typeLabels(), purpose, target, "reject");
        return com.devcli.policy.SensitiveContentPolicy.Decision.REJECT;
    }

    private ToolOutput executeAfterExplicitApproval(ToolExecutionPipeline.Context context,
                                                    ToolExecutionPipeline.Chain chain,
                                                    String sensitiveNotice) {
        long start = System.nanoTime();
        String originalArguments = context.argumentsJson();
        String approvalId = context.invocationId() + "/approval";
        context.putAttribute(PIPELINE_APPROVAL_ID, approvalId);
        ApprovalRequest request = ApprovalRequest.of(
                context.name(), originalArguments, null, approvalId, sensitiveNotice);
        boolean command = toolEffect(context.name()) == ToolEffect.HOST_PROCESS;
        String approvedProject = context.attribute("approval.project", String.class);
        boolean host = command && commandRunsWithoutIsolation(context);
        if (host) {
            String rule = trustedProject() && sensitiveNotice == null
                    ? com.devcli.policy.CommandPermissionRules.suggestedRule(parsedArgumentsOf(context).path("command").asText()) : null;
            var display = ((com.fasterxml.jackson.databind.node.ObjectNode) parsedArgumentsOf(context)).deepCopy();
            display.put("working_directory", getProjectPath());
            request = ApprovalRequest.hostCommand(display.toString(), approvalId, sensitiveNotice, rule);
        }
        boolean incompleteIntent = Boolean.TRUE.equals(context.attribute("approval.intent.incomplete", Boolean.class));
        if (incompleteIntent && !host) {
            request = new ApprovalRequest(request.toolName(), request.arguments(), request.dangerLevel(),
                    request.riskDescription(), request.suggestion(), request.callerContext(),
                    sensitiveNotice, true, false);
        }
        ApprovalResult result;
        try {
            approvalLock.lockInterruptibly();
            try {
                if (context.executionContext().isCancelled()) return ToolOutput.cancelled("审批已取消，未执行");
                if (context.executionContext().remainingNanos() == 0) return ToolOutput.timedOut("审批期限已到，未执行");
                result = hitlHandler.requestApproval(request);
            } finally {
                approvalLock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolOutput.cancelled("等待人工审批时执行被取消");
        }

        if (context.executionContext().isCancelled()) return ToolOutput.cancelled("审批期间已取消，未执行");
        if (context.executionContext().remainingNanos() == 0) return ToolOutput.timedOut("审批期间期限已到，未执行");

        if (result == null || result.isRedacted()
                || (host && result.decision() != ApprovalResult.Decision.APPROVED
                && result.decision() != ApprovalResult.Decision.APPROVED_PREFIX
                && !result.isRejected())
                || (incompleteIntent && result.decision() != ApprovalResult.Decision.APPROVED)) {
            return ToolOutput.rejected(ToolErrorCode.HITL_REJECTED,
                    "当前审批不支持此决策，未执行");
        }
        if (result.decision() == ApprovalResult.Decision.APPROVED_PREFIX) {
            if (request.commandAllowRule() == null) return ToolOutput.rejected(ToolErrorCode.HITL_REJECTED, "当前操作不能保存命令前缀");
            try {
                projectPermissions.allow(permissionProject, request.commandAllowRule());
            } catch (java.io.IOException failure) {
                return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED, "命令规则保存失败，未执行：" + failure.getMessage(), false);
            }
        }
        if (result.isRejected()) {
            String reason = result.reason() != null && !result.reason().isBlank()
                    ? result.reason()
                    : "用户拒绝了此操作";
            recordToolAudit(AuditLog.AuditEntry.denyByHitl(
                    context.name(), originalArguments, reason, elapsedMillis(start)), context);
            return ToolOutput.rejected(ToolErrorCode.HITL_REJECTED,
                    "[HITL] 操作已被拒绝：" + reason);
        }

        if (result.isSkipped()) {
            recordToolAudit(AuditLog.AuditEntry.denyByHitl(
                    context.name(), originalArguments, "用户跳过", elapsedMillis(start)), context);
            return ToolOutput.rejected(ToolErrorCode.HITL_REJECTED,
                    "[HITL] 操作已被跳过");
        }

        String effectiveArguments = result.effectiveArguments(originalArguments);
        if (!effectiveArguments.equals(originalArguments)) {
            if (context.attribute("content.reviewed.arguments", String.class) != null) {
                return ToolOutput.rejected(ToolErrorCode.HITL_REJECTED,
                        "已审查的敏感参数发生变化，请重新发起调用并审批");
            }
            if (("web_fetch".equals(context.name()) || "web_search".equals(context.name())
                    || context.name().startsWith("mcp__"))) {
                return ToolOutput.rejected(ToolErrorCode.HITL_REJECTED,
                        "外发参数修改后需要重新提交内容审查");
            }
            if (context.attribute(DeleteFilesTool.PLAN_ATTRIBUTE, DeleteFilesTool.Plan.class) != null
                    || ("execute_command".equals(context.name())
                    && (CommandGuard.containsDeletion(originalArguments)
                    || CommandGuard.containsDeletion(effectiveArguments)))) {
                return ToolOutput.rejected(ToolErrorCode.HITL_REJECTED,
                        "删除相关参数已改变，请重新发起工具调用，以重新展示范围并审批");
            }
            ToolOutput validationError = validateToolArguments(context.name(), effectiveArguments);
            if (validationError != null) {
                return validationError;
            }
            validationError = validateToolSemantics(context.name(), effectiveArguments);
            if (validationError != null) {
                return validationError;
            }
            context.replaceArguments(effectiveArguments);
            if (command && host != commandRunsWithoutIsolation(context)) {
                return ToolOutput.rejected(ToolErrorCode.HITL_REJECTED, "执行环境已改变，请重新提交命令以展示沙箱边界并审批");
            }
            ToolOutput commandDenial = validateCommandRequest(context);
            if (commandDenial != null) return commandDenial;
        }
        context.putAttribute(PIPELINE_APPROVAL_ID, approvalId);
        context.putAttribute(PIPELINE_APPROVAL_SOURCE, AuditLog.APPROVER_HITL);
        if (command) context.putAttribute("approval.project", approvedProject);
        return chain.proceed(context);
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    @Override
    protected ToolRegistry createProjectForkRegistry(ResourceLeaseMaintenance maintenance) {
        return new HitlToolRegistry(hitlHandler, maintenance, approvalLock, permissionMode,
                classifierFailures)
                .shareBlocks(consecutiveBlocks, totalBlocks)
                .inheritProjectPermissions(this)
                .withPermissionRules(permissionRules)
                .withPermissionClassifier(permissionClassifier)
                // fork 属于同一个会话，历史来源与根注册表相同：不传递会让 fork 内的分类器
                // 看不到任何用户意图，同一动作在主注册表被放行、在 fork 里被阻止。
                .withTrustedIntentContext(trustedIntentContext);
    }

    private HitlToolRegistry shareBlocks(java.util.concurrent.atomic.AtomicInteger consecutive,
                                         java.util.concurrent.atomic.AtomicInteger total) {
        consecutiveBlocks = consecutive;
        totalBlocks = total;
        return this;
    }

    private HitlToolRegistry inheritProjectPermissions(HitlToolRegistry parent) {
        projectPermissions = parent.projectPermissions;
        permissionProject = parent.permissionProject;
        permissionRoot = parent.permissionRoot;
        return this;
    }

    public HitlHandler getHitlHandler() {
        return hitlHandler;
    }
}
