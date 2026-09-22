package com.devcli.hitl;

import com.devcli.policy.AuditLog;
import com.devcli.policy.TaskGrant;
import com.devcli.tool.ToolRegistry.ToolAccessScope;
import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ToolGovernanceLifecycleTest {
    @TempDir Path directory;
    private String previousAuditDirectory;

    @BeforeEach
    void isolateAudit() {
        previousAuditDirectory = System.getProperty("devcli.audit.dir");
        System.setProperty("devcli.audit.dir", directory.resolve("audit").toString());
    }

    @AfterEach
    void restoreAudit() {
        if (previousAuditDirectory == null) System.clearProperty("devcli.audit.dir");
        else System.setProperty("devcli.audit.dir", previousAuditDirectory);
    }

    @Test
    void forkLosesGrantWhenParentTaskCompletes() throws Exception {
        Handler handler = new Handler();
        try (HitlToolRegistry parent = registry(handler)) {
            Path forkRoot = Files.createDirectory(directory.resolve("fork"));
            ToolRegistry fork = parent.runWithTaskGrant(TaskGrant.WORKSPACE_WRITES, () -> {
                ToolRegistry child = parent.forkForProject(forkRoot);
                assertTrue(child.executeToolOutput("write_file", write("during.txt")).isSuccess());
                return child;
            });
            try (fork) {
                assertTrue(fork.currentTaskGrant().isEmpty());
                assertEquals(ToolErrorCode.HITL_REJECTED,
                        fork.executeToolOutput("write_file", write("after.txt")).errorCode());
                assertFalse(Files.exists(forkRoot.resolve("after.txt")));
                assertEquals(1, handler.calls.get());
            }
        }
    }

    @Test
    void forkParallelCallsShareGrantAndKeepInvocationIdentity() throws Exception {
        Handler handler = new Handler();
        try (HitlToolRegistry parent = registry(handler)) {
            Path forkRoot = Files.createDirectory(directory.resolve("fork"));
            ToolRegistry.runWithToolTask("execution-1", () ->
                    parent.runWithTaskGrant(TaskGrant.WORKSPACE_WRITES, () -> {
                        try (ToolRegistry fork = parent.forkForProject(forkRoot)) {
                            return fork.runWithResourceLease("step-1", () -> fork.executeTools(List.of(
                                    new ToolRegistry.ToolInvocation("call-a", "write_file", write("a.txt")),
                                    new ToolRegistry.ToolInvocation("call-b", "write_file", write("b.txt")))));
                        }
                    }));
            assertEquals(0, handler.calls.get());
            assertTrue(Files.exists(forkRoot.resolve("a.txt")));
            assertTrue(Files.exists(forkRoot.resolve("b.txt")));
            List<AuditLog.AuditEntry> entries = audit().readRecent(10);
            assertEquals(2, entries.size());
            assertEquals(entries.get(0).identity().grantId(), entries.get(1).identity().grantId());
            assertNotNull(entries.get(0).identity().grantId());
            assertEquals(java.util.Set.of("call-a", "call-b"), entries.stream()
                    .map(e -> e.identity().invocationId()).collect(java.util.stream.Collectors.toSet()));
            for (var entry : entries) {
                assertEquals("execution-1", entry.identity().taskId());
                assertEquals("step-1", entry.identity().stepId());
                assertEquals("task_grant", entry.approver());
            }
        }
    }

    @Test
    void projectSwitchDoesNotCarryRootGrant() throws Exception {
        try (HitlToolRegistry registry = registry(new Handler())) {
            Path other = Files.createDirectory(directory.resolve("other"));
            registry.runWithTaskGrant(TaskGrant.WORKSPACE_WRITES, () -> {
                registry.setProjectPath(other.toString());
                assertTrue(registry.currentTaskGrant().isEmpty());
                assertEquals(ToolErrorCode.HITL_REJECTED,
                        registry.executeToolOutput("write_file", write("blocked.txt")).errorCode());
                try (ToolRegistry child = registry.forkForProject(other)) {
                    assertTrue(child.currentTaskGrant().isEmpty());
                }
                return null;
            });
            assertFalse(Files.exists(other.resolve("blocked.txt")));
        }
    }

    @Test
    void forkProjectSwitchDoesNotCarryInheritedGrant() throws Exception {
        try (HitlToolRegistry registry = registry(new Handler())) {
            Path forkRoot = Files.createDirectory(directory.resolve("fork"));
            Path other = Files.createDirectory(directory.resolve("other"));
            registry.runWithTaskGrant(TaskGrant.WORKSPACE_WRITES, () -> {
                try (ToolRegistry fork = registry.forkForProject(forkRoot)) {
                    assertFalse(fork.currentTaskGrant().isEmpty());
                    fork.setProjectPath(other.toString());
                    assertTrue(fork.currentTaskGrant().isEmpty());
                    fork.setProjectPath(directory.toString());
                    assertTrue(fork.currentTaskGrant().isEmpty());
                }
                return null;
            });
        }
    }

    @Test
    void exceptionalExitRevokesInheritedThreadGrant() throws Exception {
        AtomicReference<Thread> child = new AtomicReference<>();
        AtomicReference<TaskGrant> observed = new AtomicReference<>();
        try (HitlToolRegistry registry = registry(new Handler())) {
            assertThrows(IllegalStateException.class, () ->
                    registry.runWithTaskGrant(TaskGrant.WORKSPACE_WRITES, () -> {
                        child.set(new Thread(() -> observed.set(registry.currentTaskGrant())));
                        throw new IllegalStateException("task failed");
                    }));
            child.get().start();
            child.get().join(5_000);
            assertFalse(child.get().isAlive());
            assertEquals(TaskGrant.NONE, observed.get());
        }
    }

    @Test
    void explicitEmptyGrantTemporarilyRevokesParentGrant() {
        try (HitlToolRegistry registry = registry(new Handler())) {
            registry.runWithTaskGrant(TaskGrant.WORKSPACE_WRITES, () -> {
                registry.runWithTaskGrant(null, () -> {
                    assertTrue(registry.currentTaskGrant().isEmpty());
                    return null;
                });
                assertEquals(TaskGrant.WORKSPACE_WRITES, registry.currentTaskGrant());
                return null;
            });
            assertTrue(registry.currentTaskGrant().isEmpty());
        }
    }

    @Test
    void capabilityDenialIsAuditedBeforeApproval() {
        Handler handler = new Handler();
        try (HitlToolRegistry registry = registry(handler)) {
            registry.runWithTaskGrant(TaskGrant.WORKSPACE_WRITES, () ->
                    registry.runWithToolAccess(ToolAccessScope.READ_ONLY, () -> {
                        assertEquals(ToolErrorCode.CAPABILITY_DENIED,
                                registry.executeToolOutput("write_file", write("blocked.txt")).errorCode());
                        return null;
                    }));
            assertEquals(0, handler.calls.get());
            var entry = audit().readRecent(1).get(0);
            assertEquals("deny", entry.outcome());
            assertEquals("policy", entry.approver());
            assertNotNull(entry.identity().invocationId());
            assertNotNull(entry.identity().grantId());
        }
    }

    @Test
    void explicitApprovalAndRejectionCarryActualApprovalSource() {
        Handler handler = new Handler();
        try (HitlToolRegistry registry = registry(handler)) {
            handler.approve = true;
            assertTrue(registry.executeToolOutput("write_file", write("approved.txt")).isSuccess());
            var allowed = audit().readRecent(1).get(0);
            assertEquals("hitl", allowed.approver());
            assertEquals(handler.lastRequest.callerContext(), allowed.identity().approvalId());
            assertNull(allowed.identity().grantId());
            handler.approve = false;
            assertEquals(ToolErrorCode.HITL_REJECTED,
                    registry.executeToolOutput("write_file", write("denied.txt")).errorCode());
            var denied = audit().readRecent(1).get(0);
            assertEquals("deny", denied.outcome());
            assertEquals(handler.lastRequest.callerContext(), denied.identity().approvalId());
            assertNotEquals(allowed.identity().approvalId(), denied.identity().approvalId());
        }
    }

    private HitlToolRegistry registry(Handler handler) {
        HitlToolRegistry registry = new HitlToolRegistry(handler);
        registry.setProjectPath(directory.toString());
        return registry;
    }

    private AuditLog audit() {
        return new AuditLog(directory.resolve("audit"));
    }

    private static String write(String filename) {
        return "{\"path\":\"" + filename + "\",\"content\":\"verified\"}";
    }

    private static final class Handler implements HitlHandler {
        final AtomicInteger calls = new AtomicInteger();
        boolean approve;
        ApprovalRequest lastRequest;

        @Override public ApprovalResult requestApproval(ApprovalRequest request) {
            calls.incrementAndGet();
            lastRequest = request;
            return approve ? ApprovalResult.approve() : ApprovalResult.reject("not authorized");
        }
        @Override public boolean isEnabled() { return true; }
        @Override public void setEnabled(boolean enabled) { }
    }
}
