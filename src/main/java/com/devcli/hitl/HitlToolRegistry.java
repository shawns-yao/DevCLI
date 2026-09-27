package com.devcli.hitl;

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
import com.devcli.tool.patch.ApplyPatchRiskInspector;
import com.devcli.config.ConfigResolver;
import com.devcli.policy.CommandGuard;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 在统一工具执行管线的 HITL 阶段插入人工审批。
 */
public class HitlToolRegistry extends ToolRegistry {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final HitlHandler hitlHandler;
    private final int deleteApprovalThreshold = ConfigResolver.intValue(
            "devcli.delete.approval.threshold", "DEVCLI_DELETE_APPROVAL_THRESHOLD", 50, 1, 500);
    private final ReentrantLock approvalLock;
    /** 用户规则层；空集合表示没有规则，审批链行为与未引入规则时完全一致。 */
    private volatile PermissionRuleSet permissionRules = PermissionRuleSet.EMPTY;
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

    private HitlToolRegistry(HitlHandler hitlHandler, ReentrantLock approvalLock) {
        super();
        this.hitlHandler = hitlHandler;
        this.approvalLock = approvalLock;
        registerExecutionMiddleware(ToolExecutionPipeline.Stage.HITL, this::applyHitl);
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
        registerExecutionMiddleware(ToolExecutionPipeline.Stage.HITL, this::applyHitl);
    }

    private ToolOutput applyHitl(ToolExecutionPipeline.Context context,
                                 ToolExecutionPipeline.Chain chain) {
        String name = context.name();
        String argumentsJson = context.argumentsJson();
        if ("execute_command".equals(name) && commandExecutesOnHost()) {
            // The exact final command is reviewed immediately before backend dispatch.
            return chain.proceed(context);
        }
        if (!requiresApproval(name)) {
            return chain.proceed(context);
        }
        // 这里曾经有 hitlHandler.isEnabled() 早退，已删除：策略硬边界与用户规则层必须无条件执行，
        // 否则一条 deny 规则会挂在「是否开启人工审批」这个开关后面而静默失效。
        // 「要不要问」改由权限模式决定，见下方的模式短路与 askOrDeny 收口。

        BrowserCheckResult browserCheck = checkBrowserTool(name, argumentsJson, true);
        if (browserCheck.blocked()) {
            return chain.proceed(context);
        }
        if (browserCheck.requiresPerCallApproval()) {
            return askOrDeny(context, chain, browserCheck.sensitiveNotice());
        }

        // 求值链第 0 步：策略硬边界。路径越界、命令黑名单、非法 URL scheme 先拒绝。
        // 规则层与任务授权都在它之后，因此任何规则都不能放宽系统策略边界。
        ApprovalGate.Result gate = ApprovalGate.decide(
                name, parsedArgumentsOf(context), currentTaskGrant(), approvalPathScope);
        if (gate.decision() == ApprovalGate.Decision.DENY) {
            recordToolAudit(AuditLog.AuditEntry.denyByPolicy(
                    name, argumentsJson, gate.reason(), 0L), context);
            return ToolOutput.rejected(ToolErrorCode.POLICY_DENIED,
                    "[策略] 已拒绝：" + gate.reason() + "；用户授权不能放宽系统策略边界");
        }

        // 非 auto 模式按 hard_deny → soft_deny → allow 求值；auto 只提前执行 hard_deny，
        // 其余规则交给分类器结合可信用户意图统一判断。
        PermissionEvaluator.Result rules = PermissionEvaluator.evaluate(
                name,
                ApprovalGate.resourceValues(name, parsedArgumentsOf(context), approvalPathScope),
                permissionRules);
        if (rules.outcome() == PermissionEvaluator.Outcome.HARD_DENY) {
            recordToolAudit(AuditLog.AuditEntry.denyByPolicy(
                    name, argumentsJson, rules.reason(), 0L), context);
            return ToolOutput.rejected(ToolErrorCode.POLICY_DENIED,
                    "[规则] 已拒绝：" + rules.reason() + "；放行规则与任务授权都不能覆盖它");
        }
        // auto 模式下规则层不短路：分类器是那个模式的判定者，用户规则作为它的输入参与同一次判定
        // （见 docs/adr/0013）。allow 与 soft_deny 若在此短路，注入提示词的规则就是死文本——
        // 分类器永远看不到它们，用户声明的边界与例外都不会被采纳。
        //
        // 其余模式没有分类器可用，规则层照常短路：soft_deny 退回「强制人工审批」，
        // allow 直接放行。hard_deny 在两种模式下都短路——它的判定结果与分类器的硬阻止检查完全
        // 相同（无条件阻止），短路不会改变结论，却省掉一次模型调用、也不受端点可用性影响。
        boolean classifierDecides =
                currentPermissionMode().askPolicy() == PermissionMode.AskPolicy.AUTO;
        // 求值链第 5 步：模式短路。bypassPermissions 与 acceptEdits 在规则层之后、任务授权之前放行。
        // 放在这里而不是更晚：更晚会被任务授权范围二次约束，「不询问」等于没生效。
        // 只接管规则层未决的动作；soft_deny 在非 auto 模式下仍强制人工确认。
        if (rules.outcome() == PermissionEvaluator.Outcome.UNDECIDED && currentPermissionMode().autoAllows(name)) {
            context.putAttribute(PIPELINE_APPROVAL_SOURCE, "permission_mode");
            hitlHandler.onTaskGrantAllow(name, currentPermissionMode().autoAllowReason());
            return chain.proceed(context);
        }

        boolean allowedByRules = rules.outcome() == PermissionEvaluator.Outcome.ALLOW;
        // 任务授权与放行规则处于同一层级：都表示「用户已经授权」，删除阈值只按数量兜底。
        // 这里不因 auto 模式而改变：删除阈值、命令删除、补丁与回滚是各自独立的安全要求，
        // 不是规则层的短路出口，不能因为分类器接管了规则判定就跟着放宽。
        boolean authorized = gate.decision() == ApprovalGate.Decision.ALLOW || allowedByRules;

        DeleteFilesTool.Plan deletion = context.attribute(DeleteFilesTool.PLAN_ATTRIBUTE,
                DeleteFilesTool.Plan.class);
        if (deletion != null && (deletion.changes().size() >= deleteApprovalThreshold || !authorized)) {
            return askOrDeny(context, chain, deletion.notice());
        }
        if ("execute_command".equals(name)
                && CommandGuard.containsDeletion(parsedArgumentsOf(context).path("command").asText())) {
            return askOrDeny(context, chain,
                    "检测到命令删除操作，必须单次确认；无法可靠统计目标数量及递归范围。"
                            + "优先使用 delete_files 提供明确文件清单；恢复取决于命令行为及已有备份。");
        }
        if ("apply_patch".equals(name)) {
            String patchNotice = ApplyPatchRiskInspector.explicitApprovalNotice(
                    parsedArgumentsOf(context).path("patch").asText());
            if (patchNotice != null) {
                return askOrDeny(context, chain, patchNotice);
            }
        }
        if ("revert_turn".equals(name)) {
            return askOrDeny(context, chain,
                    "工作区回滚会批量覆盖当前文件，必须单次人工确认；恢复前虽会创建快照，"
                            + "仍不得由自动分类器代替用户决定。");
        }
        if (!classifierDecides && rules.outcome() == PermissionEvaluator.Outcome.SOFT_DENY) {
            return askOrDeny(context, chain, rules.reason());
        }
        if (!classifierDecides && authorized) {
            context.putAttribute(PIPELINE_APPROVAL_SOURCE,
                    allowedByRules ? "permission_rule" : "task_grant");
            hitlHandler.onTaskGrantAllow(name, allowedByRules ? rules.reason() : gate.reason());
            return chain.proceed(context);
        }

        String mcpServer = ApprovalPolicy.mcpServerName(name);
        boolean forcePerCallApproval = mcpToolRequiresPerCallApproval(name);
        if (!forcePerCallApproval
                && (hitlHandler.isApprovedAllByTool(name)
                || hitlHandler.isApprovedAllByServer(mcpServer))) {
            context.putAttribute(PIPELINE_APPROVAL_SOURCE, "hitl_reused");
            return chain.proceed(context);
        }

        if (forcePerCallApproval) {
            return askOrDeny(context, chain, mcpToolApprovalNotice(name));
        }
        // 走到这里的动作分两类：没有任何规则或安全机制要求询问、只是默认策略是「问」的；
        // 以及 auto 模式下命中用户 allow / soft_deny 规则、规则层刻意没有短路的。
        // 删除确认、命令删除、补丁与回滚确认、浏览器与 MCP 的逐次审批都在上面各自短路了。
        return askOrClassify(context, chain);
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
     * <p>进到这里的有两类动作。一类是走完整条求值链、没有任何规则或安全机制要求询问、
     * 仅仅因为默认策略才要问的；另一类是命中用户 {@code allow} / {@code soft_deny} 规则的——
     * {@code auto} 模式下规则层不短路，规则作为分类器输入参与同一次判定，因为只有分类器看得见
     * 对话历史，而这两类规则的语义（软阻止可被明确意图清除、允许例外可被用户边界压过）
     * 都要求判断意图。</p>
     *
     * <p>与 {@link #askOrDeny} 分开是必要的：后者承载的是「有独立理由必须问」的动作——删除确认、
     * 命令删除、补丁与回滚确认、逐次审批。把它们一起交给分类器，等于让模型替用户取消
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
                    currentPermissionMode().id(), trustedIntentContext.get(), permissionRules));
        } catch (java.io.IOException failure) {
            return denyByClassifierFailure(context, failure.getMessage());
        }
        classifierFailures.set(0);
        if (verdict.block()) {
            recordToolAudit(AuditLog.AuditEntry.denyByPolicy(context.name(), context.argumentsJson(),
                    "分类器阻止：" + verdict.reason(), 0L), context);
            return ToolOutput.rejected(ToolErrorCode.POLICY_DENIED,
                    "[分类器] 已阻止：" + verdict.reason() + "；改用 /mode default 可恢复逐个询问");
        }
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
    protected ToolOutput reviewHostCommand(com.devcli.tool.command.CommandExecutionService.Request request) {
        // 主机命令同样是工具调用，规则层与模式层必须覆盖它——否则一条 deny 规则会挂在
        // 「命令走哪条后端」这个与规则无关的分支后面而静默失效。
        //
        // 这里只吸收收紧方向的判定。allow 规则与 bypassPermissions 不参与：它们的方向是放宽，
        // 会让主机命令绕过单次人工确认，而那是刻意不可免除的硬要求（见 docs/adr/0006「已知缺口」）。
        String hostCommand = request.command() == null ? "" : request.command();
        PermissionEvaluator.Result hostRules = PermissionEvaluator.evaluate(
                "execute_command", java.util.List.of(hostCommand), permissionRules);
        if (hostRules.outcome() == PermissionEvaluator.Outcome.HARD_DENY) {
            recordHostCommandDenial(request, hostRules.reason(), "host_rule");
            return ToolOutput.rejected(ToolErrorCode.POLICY_DENIED,
                    "[规则] 已拒绝：" + hostRules.reason() + "；主机执行不接受任何放宽");
        }
        String modeDenial = currentPermissionMode().denyReason();
        if (!modeDenial.isEmpty()) {
            recordHostCommandDenial(request, modeDenial, "host_mode");
            return ToolOutput.rejected(ToolErrorCode.POLICY_DENIED,
                    "[模式] 已拒绝：" + modeDenial + "；主机执行必须单次人工确认");
        }
        String args;
        try {
            args = JSON.writeValueAsString(java.util.Map.of(
                    "command", request.command(), "working_directory", request.projectRoot().toString()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            return ToolOutput.rejected(ToolErrorCode.HITL_REJECTED, "命令参数无法安全展示，未执行");
        }
        String approvalId = request.executionContext().invocationId() + "/host-" + java.util.UUID.randomUUID();
        ApprovalResult result = null;
        if (hitlHandler != null && hitlHandler.isEnabled()) {
            try {
                approvalLock.lockInterruptibly();
                try {
                    if (request.executionContext().isCancelled()) {
                        return ToolOutput.cancelled("主机执行审批已取消，未启动进程");
                    }
                    if (request.executionContext().remainingNanos() == 0) {
                        return ToolOutput.timedOut("命令期限已到，未请求主机执行审批");
                    }
                    result = hitlHandler.requestApproval(ApprovalRequest.hostCommand(args, approvalId));
                } finally {
                    approvalLock.unlock();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return ToolOutput.cancelled("等待主机执行审批时被取消");
            }
        }
        boolean approved = result != null && result.decision() == ApprovalResult.Decision.APPROVED;
        getAuditLog().record((approved
                ? AuditLog.AuditEntry.allow("host_command_approval", "单次批准主机执行", 0)
                : AuditLog.AuditEntry.denyByHitl("host_command_approval", "{}",
                        "未获得单次主机执行批准", 0))
                .withIdentity(new AuditLog.ExecutionIdentity(null, currentResourceLeaseStep(), null,
                        request.executionContext().invocationId(), approvalId, "host_once"),
                        AuditLog.APPROVER_HITL));
        return approved ? null : ToolOutput.rejected(ToolErrorCode.HITL_REJECTED,
                "主机执行仅接受单次明确批准；任务授权、全部批准、修改参数或关闭审批均不能放行");
    }

    /** 主机命令被规则层或模式层拦下时的审计记录。 */
    private void recordHostCommandDenial(com.devcli.tool.command.CommandExecutionService.Request request,
                                         String reason, String stage) {
        getAuditLog().record(AuditLog.AuditEntry.denyByPolicy("execute_command", "{}", reason, 0L)
                .withIdentity(new AuditLog.ExecutionIdentity(null, currentResourceLeaseStep(), null,
                        request.executionContext().invocationId(), null, stage),
                        AuditLog.APPROVER_POLICY));
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
                        inspection.typeLabels(), purpose, target, redactionAllowed));
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
        ApprovalResult result;
        try {
            approvalLock.lockInterruptibly();
            try {
                result = hitlHandler.requestApproval(request);
            } finally {
                approvalLock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolOutput.cancelled("等待人工审批时执行被取消");
        }

        if (result == null || result.isRedacted()) {
            return ToolOutput.rejected(ToolErrorCode.HITL_REJECTED,
                    "当前审批不支持此决策，未执行");
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
        }
        context.putAttribute(PIPELINE_APPROVAL_ID, approvalId);
        context.putAttribute(PIPELINE_APPROVAL_SOURCE, AuditLog.APPROVER_HITL);
        return chain.proceed(context);
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    @Override
    protected ToolRegistry createProjectForkRegistry(ResourceLeaseMaintenance maintenance) {
        return new HitlToolRegistry(hitlHandler, maintenance, approvalLock, permissionMode,
                classifierFailures)
                .withPermissionRules(permissionRules)
                .withPermissionClassifier(permissionClassifier)
                // fork 属于同一个会话，历史来源与根注册表相同：不传递会让 fork 内的分类器
                // 看不到任何用户意图，同一动作在主注册表被放行、在 fork 里被阻止。
                .withTrustedIntentContext(trustedIntentContext);
    }

    public HitlHandler getHitlHandler() {
        return hitlHandler;
    }
}
