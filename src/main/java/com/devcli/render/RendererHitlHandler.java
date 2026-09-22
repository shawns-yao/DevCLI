package com.devcli.render;

import com.devcli.hitl.ApprovalPolicy;
import com.devcli.hitl.ApprovalRequest;
import com.devcli.hitl.ApprovalResult;
import com.devcli.hitl.HitlHandler;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 与 {@link Renderer} 协作的 HITL 处理器：
 * 状态（启用开关、全部放行集合）由本类维护，
 * 实际审批 UI 委托给 {@link Renderer#promptApproval(ApprovalRequest)}。
 *
 * <p>这样切换渲染器形态（plain / inline）只需要换一个 Renderer 实例，
 * 不影响审批状态语义。
 */
public final class RendererHitlHandler implements HitlHandler {

    private final Renderer renderer;
    private volatile boolean enabled;
    private final Set<String> approvedAllByTool = ConcurrentHashMap.newKeySet();
    private final Set<String> approvedAllByServer = ConcurrentHashMap.newKeySet();

    public RendererHitlHandler(Renderer renderer, boolean enabled) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.enabled = enabled;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public synchronized ApprovalResult requestApproval(ApprovalRequest request) {
        String mcpServer = ApprovalPolicy.mcpServerName(request.toolName());
        boolean sensitivePerCall = request.sensitiveNotice() != null && !request.sensitiveNotice().isBlank();

        if (!sensitivePerCall && isApprovedAllByTool(request.toolName())) {
            renderer.stream().println("  [HITL] " + request.toolName()
                    + " 已在本次任务中全部放行，自动通过");
            return ApprovalResult.approveAll();
        }
        if (!sensitivePerCall && isApprovedAllByServer(mcpServer)) {
            renderer.stream().println("  [HITL] MCP server " + mcpServer
                    + " 已在本次任务中全部放行，自动通过");
            return ApprovalResult.approveAllByServer();
        }

        ApprovalResult result = renderer.promptApproval(request);
        if (result == null) {
            return ApprovalResult.reject("渲染器返回 null");
        }
        if (request.singleDecisionOnly() && (result.isApprovedAllForTool() || result.isApprovedAllForServer()
                || result.decision() == ApprovalResult.Decision.MODIFIED)) {
            return ApprovalResult.reject("此操作不支持批量批准或修改参数");
        }
        if (!sensitivePerCall && result.isApprovedAllForTool()) {
            approvedAllByTool.add(request.toolName());
        } else if (!sensitivePerCall && result.isApprovedAllForServer() && mcpServer != null) {
            approvedAllByServer.add(mcpServer);
        }
        return result;
    }

    @Override
    public Set<String> approvedAllTools() {
        return Set.copyOf(approvedAllByTool);
    }

    @Override
    public Set<String> approvedAllServers() {
        return Set.copyOf(approvedAllByServer);
    }

    @Override
    public boolean isApprovedAllByTool(String toolName) {
        return toolName != null && approvedAllByTool.contains(toolName);
    }

    @Override
    public boolean isApprovedAllByServer(String serverName) {
        return serverName != null && approvedAllByServer.contains(serverName);
    }

    @Override
    public void clearApprovedAll() {
        approvedAllByTool.clear();
        approvedAllByServer.clear();
    }

    @Override
    public void clearApprovedAllForServer(String serverName) {
        if (serverName != null) {
            approvedAllByServer.remove(serverName);
        }
    }

    @Override
    public void onTaskGrantAllow(String toolName, String reason) {
        renderer.stream().println("  [授权] " + reason + "，已自动放行（未经过人工审批）");
    }
}
