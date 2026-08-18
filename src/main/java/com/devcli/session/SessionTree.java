package com.devcli.session;

import java.time.Instant;
import java.util.List;

/** 持久会话树的只读投影视图。 */
public record SessionTree(String threadId, String activeBranchId, List<Branch> branches) {
    public SessionTree {
        threadId = threadId == null ? "" : threadId;
        activeBranchId = activeBranchId == null ? "" : activeBranchId;
        branches = branches == null ? List.of() : List.copyOf(branches);
    }

    public record Branch(String id, String name, String parentBranchId,
                         long forkEventId, boolean active, Instant createdAt) {
        public Branch {
            id = id == null ? "" : id;
            name = name == null || name.isBlank() ? id : name;
            parentBranchId = parentBranchId == null ? "" : parentBranchId;
            createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        }
    }
}
