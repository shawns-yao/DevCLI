package com.devcli.hitl;

import com.devcli.browser.BrowserCheckResult;
import com.devcli.policy.AuditLog;
import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolExecutionPipeline;
import com.devcli.tool.ResourceLeaseMaintenance;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 在统一工具执行管线的 HITL 阶段插入人工审批。
 */
public class HitlToolRegistry extends ToolRegistry {

    private final HitlHandler hitlHandler;
    private final ReentrantLock approvalLock;

    public HitlToolRegistry(HitlHandler hitlHandler) {
        this(hitlHandler, new ReentrantLock(true));
    }

    private HitlToolRegistry(HitlHandler hitlHandler, ReentrantLock approvalLock) {
        super();
        this.hitlHandler = hitlHandler;
        this.approvalLock = approvalLock;
        registerExecutionMiddleware(ToolExecutionPipeline.Stage.HITL, this::applyHitl);
    }

    private HitlToolRegistry(HitlHandler hitlHandler,
                             ResourceLeaseMaintenance maintenance,
                             ReentrantLock approvalLock) {
        super(maintenance);
        this.hitlHandler = hitlHandler;
        this.approvalLock = approvalLock;
        registerExecutionMiddleware(ToolExecutionPipeline.Stage.HITL, this::applyHitl);
    }

    private ToolOutput applyHitl(ToolExecutionPipeline.Context context,
                                 ToolExecutionPipeline.Chain chain) {
        String name = context.name();
        String argumentsJson = context.argumentsJson();
        if (!hitlHandler.isEnabled() || !requiresApproval(name)) {
            return chain.proceed(context);
        }

        BrowserCheckResult browserCheck = checkBrowserTool(name, argumentsJson, true);
        if (browserCheck.blocked()) {
            return chain.proceed(context);
        }
        if (browserCheck.requiresPerCallApproval()) {
            return executeAfterExplicitApproval(context, chain, browserCheck.sensitiveNotice());
        }

        String mcpServer = ApprovalPolicy.mcpServerName(name);
        boolean forcePerCallApproval = mcpToolRequiresPerCallApproval(name);
        if (!forcePerCallApproval
                && (hitlHandler.isApprovedAllByTool(name)
                || hitlHandler.isApprovedAllByServer(mcpServer))) {
            return chain.proceed(context);
        }

        return executeAfterExplicitApproval(context, chain,
                forcePerCallApproval ? mcpToolApprovalNotice(name) : null);
    }

    private ToolOutput executeAfterExplicitApproval(ToolExecutionPipeline.Context context,
                                                    ToolExecutionPipeline.Chain chain,
                                                    String sensitiveNotice) {
        long start = System.nanoTime();
        String originalArguments = context.argumentsJson();
        ApprovalRequest request = ApprovalRequest.of(
                context.name(), originalArguments, null, null, sensitiveNotice);
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

        if (result.isRejected()) {
            String reason = result.reason() != null && !result.reason().isBlank()
                    ? result.reason()
                    : "用户拒绝了此操作";
            getAuditLog().record(AuditLog.AuditEntry.denyByHitl(
                    context.name(), originalArguments, reason, elapsedMillis(start)));
            return ToolOutput.rejected(ToolErrorCode.HITL_REJECTED,
                    "[HITL] 操作已被拒绝：" + reason);
        }

        if (result.isSkipped()) {
            getAuditLog().record(AuditLog.AuditEntry.denyByHitl(
                    context.name(), originalArguments, "用户跳过", elapsedMillis(start)));
            return ToolOutput.rejected(ToolErrorCode.HITL_REJECTED,
                    "[HITL] 操作已被跳过");
        }

        String effectiveArguments = result.effectiveArguments(originalArguments);
        if (!effectiveArguments.equals(originalArguments)) {
            ToolOutput validationError = validateToolArguments(context.name(), effectiveArguments);
            if (validationError != null) {
                return validationError;
            }
            context.replaceArguments(effectiveArguments);
        }
        return chain.proceed(context);
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    @Override
    protected ToolRegistry createProjectForkRegistry(ResourceLeaseMaintenance maintenance) {
        return new HitlToolRegistry(hitlHandler, maintenance, approvalLock);
    }

    public HitlHandler getHitlHandler() {
        return hitlHandler;
    }
}
