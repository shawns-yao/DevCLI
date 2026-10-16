package com.devcli.cli;

import com.devcli.config.ProjectPermissionsStore;
import com.devcli.hitl.ApprovalRequest;
import com.devcli.hitl.ApprovalResult;
import com.devcli.hitl.HitlHandler;
import com.devcli.hitl.HitlToolRegistry;
import java.io.IOException;
import java.nio.file.Path;

/** 交互入口取得项目级决定；无交互入口只绑定已有授权。 */
final class CliProjectTrust {
    private CliProjectTrust() { }

    static void bind(HitlToolRegistry tools, ProjectPermissionsStore store, Path path, HitlHandler handler) throws IOException {
        String project = ProjectPermissionsStore.key(path);
        if (!store.hasDecision(project)) {
            ApprovalResult answer = handler.requestApproval(ApprovalRequest.projectTrust(project));
            store.decideTrust(project, answer.decision() == ApprovalResult.Decision.APPROVED);
        }
        tools.withProjectPermissions(store, project);
    }
}
