package com.devcli.cli;

import com.devcli.config.ProjectPermissionsStore;
import com.devcli.hitl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CliProjectTrustTest {
    @TempDir Path root;
    @Test void firstProjectDecisionPersistsWithoutTrustingSiblings() throws Exception {
        Path project = Files.createDirectory(root.resolve("project"));
        Path file = root.resolve("user/project-permissions.json");
        var store = new ProjectPermissionsStore(file);
        AtomicInteger prompts = new AtomicInteger();
        var handler = new HitlHandler() {
            public ApprovalResult requestApproval(ApprovalRequest request) {
                prompts.incrementAndGet(); assertEquals("project_trust", request.toolName());
                assertTrue(request.singleDecisionOnly()); return ApprovalResult.approve();
            }
            public boolean isEnabled() { return true; }
            public void setEnabled(boolean enabled) { }
        };
        try (var tools = new HitlToolRegistry(handler)) {
            CliProjectTrust.bind(tools, store, project, handler);
            CliProjectTrust.bind(tools, new ProjectPermissionsStore(file), project.resolve("."), handler);
            assertEquals(1, prompts.get());
            assertTrue(new ProjectPermissionsStore(file).isTrusted(ProjectPermissionsStore.key(project)));
            assertFalse(store.isTrusted(root.resolve("sibling").toString()));
        }
    }

    @Test void negativeDecisionIsSavedWithoutTrustingTheProject() throws Exception {
        var store = new ProjectPermissionsStore(root.resolve("user/state.json"));
        var handler = new HitlHandler() {
            public ApprovalResult requestApproval(ApprovalRequest request) { return ApprovalResult.reject("no"); }
            public boolean isEnabled() { return true; }
            public void setEnabled(boolean enabled) { }
        };
        try (var tools = new HitlToolRegistry(handler)) {
            tools.setProjectPath(root.toString());
            CliProjectTrust.bind(tools, store, root, handler);
            var reopened = new ProjectPermissionsStore(root.resolve("user/state.json"));
            assertTrue(reopened.hasDecision(ProjectPermissionsStore.key(root)));
            assertFalse(reopened.isTrusted(ProjectPermissionsStore.key(root)));
        }
    }
}
