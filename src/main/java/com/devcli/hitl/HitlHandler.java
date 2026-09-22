package com.devcli.hitl;

import java.util.Set;

/**
 * HITL 审批交互接口 - 定义人工审批的交互契约
 *
 * 实现类负责与用户交互，收集用户对危险操作的审批决策。
 * 当前仓库提供基于终端的实现（TerminalHitlHandler）。
 *
 * 设计约定：
 * - 审批是同步阻塞操作，实现类需等待用户输入后才返回
 * - 实现类不负责判断"是否需要审批"，该判断由 ApprovalPolicy 负责
 * - 实现类只负责"展示请求 + 收集决策"
 */
public interface HitlHandler {

    /**
     * 向用户展示审批请求并收集决策
     *
     * @param request 待审批的工具调用信息
     * @return 用户的审批决策
     */
    ApprovalResult requestApproval(ApprovalRequest request);

    /**
     * HITL 是否处于启用状态
     * 如果未启用，调用方可以直接跳过审批流程
     */
    boolean isEnabled();

    /**
     * 启用/禁用 HITL 审批
     *
     * @param enabled true 表示启用，false 表示关闭
     */
    void setEnabled(boolean enabled);

    default boolean isApprovedAllByTool(String toolName) {
        return false;
    }

    default boolean isApprovedAllByServer(String serverName) {
        return false;
    }

    default void clearApprovedAll() {
    }

    default void clearApprovedAllForServer(String serverName) {
    }

    /**
     * 当前「全部放行」缓存的只读快照（工具维度）。
     *
     * <p>只读视图只用于让用户查看当前生效的放行范围；判定仍走
     * {@link #isApprovedAllByTool(String)}。默认返回空集合，便于不维护该状态的实现直接继承。</p>
     */
    default Set<String> approvedAllTools() {
        return Set.of();
    }

    /** 当前「全部放行」缓存的只读快照（MCP server 维度）。 */
    default Set<String> approvedAllServers() {
        return Set.of();
    }

    /**
     * 命中任务级授权、无需人工审批时的可见通知。
     *
     * <p>自动放行必须可见且可归因，不能静默通过；实现类只负责展示，不参与判定。</p>
     */
    default void onTaskGrantAllow(String toolName, String reason) {
    }
}
