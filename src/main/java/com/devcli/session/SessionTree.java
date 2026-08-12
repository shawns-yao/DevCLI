package com.devcli.session;

import java.util.List;

/** 持久会话树的只读投影；只管理对话上下文，不恢复工作区文件。 */
public record SessionTree(String sessionId, String activeBranchId, List<Branch> branches,
                          List<MessageNode> messages) {
    public SessionTree {
        sessionId = sessionId == null ? "" : sessionId;
        activeBranchId = activeBranchId == null ? "" : activeBranchId;
        branches = branches == null ? List.of() : List.copyOf(branches);
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public record Branch(String id, String name, String parentId,
                         long forkEventId, boolean active) {
        public Branch {
            id = id == null ? "" : id;
            name = name == null || name.isBlank() ? id : name;
            parentId = parentId == null ? "" : parentId;
            forkEventId = Math.max(0, forkEventId);
        }
    }

    /** 可作为 fork 锚点的持久消息节点；正文只保留有界预览。 */
    public record MessageNode(String id, String parentId, String branchId,
                              String role, String preview, long eventId) {
        public MessageNode {
            id = id == null ? "" : id;
            parentId = parentId == null ? "" : parentId;
            branchId = branchId == null ? "" : branchId;
            role = role == null ? "" : role;
            preview = preview == null ? "" : preview;
            eventId = Math.max(0, eventId);
        }
    }
}
