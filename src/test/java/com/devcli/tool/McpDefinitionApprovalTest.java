package com.devcli.tool;

import com.devcli.hitl.ApprovalRequest;
import com.devcli.hitl.ApprovalResult;
import com.devcli.hitl.HitlHandler;
import com.devcli.hitl.HitlToolRegistry;
import com.devcli.mcp.McpToolDiscoveryEntry;
import com.devcli.mcp.protocol.McpToolDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class McpDefinitionApprovalTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void changedDescriptionInvalidatesBlanketApprovalAndRequiresSingleDecision() {
        RecordingHandler handler = new RecordingHandler();
        AtomicInteger calls = new AtomicInteger();
        try (HitlToolRegistry registry = new HitlToolRegistry(handler)) {
            register(registry, "Read original", calls);
            handler.blanket = true;
            assertTrue(registry.executeToolOutput("mcp__sample__read", "{}").isSuccess());
            assertTrue(handler.requests.isEmpty());
            register(registry, "Read changed", calls);
            assertFalse(handler.blanket);
            assertTrue(registry.executeToolOutput("mcp__sample__read", "{}").isSuccess());
            assertEquals(1, handler.requests.size());
            assertTrue(handler.requests.get(0).definitionReview());
            assertTrue(handler.requests.get(0).singleDecisionOnly());
            assertTrue(handler.requests.get(0).sensitiveNotice().contains("Read changed"));
            assertEquals(2, calls.get());
        }
    }

    @Test
    void definitionChangedDuringApprovalCannotExecuteOrAcknowledgeNewDefinition() {
        RecordingHandler handler = new RecordingHandler();
        AtomicInteger calls = new AtomicInteger();
        try (HitlToolRegistry registry = new HitlToolRegistry(handler)) {
            register(registry, "original", calls);
            register(registry, "second", calls);
            handler.duringApproval = () -> register(registry, "third", calls);
            assertEquals(ToolErrorCode.STALE_TOOL_SNAPSHOT,
                    registry.executeToolOutput("mcp__sample__read", "{}").errorCode());
            assertEquals(0, calls.get());
            handler.duringApproval = () -> { };
            assertTrue(registry.executeToolOutput("mcp__sample__read", "{}").isSuccess());
            assertTrue(handler.requests.get(1).definitionReview());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void frozenProjectForkRejectsChangedRootDefinition(@TempDir Path project) {
        RecordingHandler handler = new RecordingHandler();
        AtomicInteger calls = new AtomicInteger();
        try (HitlToolRegistry root = new HitlToolRegistry(handler)) {
            register(root, "original", calls);
            handler.blanket = true;
            try (ToolRegistry fork = root.forkForProject(project)) {
                assertTrue(handler.blanket, "创建 fork 不得撤销未变化定义的批准");
                register(root, "changed", calls);
                assertEquals(ToolErrorCode.STALE_TOOL_SNAPSHOT,
                        fork.executeToolOutput("mcp__sample__read", "{}").errorCode());
                assertTrue(handler.requests.isEmpty());
                assertEquals(0, calls.get());
            }
        }
    }

    @Test
    void completeDefinitionFingerprintAndBoundedThirdPartyDescription() throws Exception {
        var first = descriptor("a".repeat(3000));
        var changed = descriptor("changed");
        assertNotEquals(McpToolDiscoveryEntry.fingerprint(List.of(first)),
                McpToolDiscoveryEntry.fingerprint(List.of(changed)));
        var reordered = new McpToolDescriptor("sample", "read", first.namespacedName(), first.description(),
                JSON.readTree("{\"properties\":{},\"type\":\"object\"}"), null);
        assertEquals(McpToolDiscoveryEntry.fingerprint(List.of(first)),
                McpToolDiscoveryEntry.fingerprint(List.of(reordered)));
        String description = McpToolCatalog.mcpDescription(first);
        assertTrue(description.startsWith("[第三方提供，UNTRUSTED]"));
        assertTrue(description.contains("描述已截断"));
        assertTrue(description.length() < 2200);
    }

    private static McpToolDescriptor descriptor(String description) {
        var schema = JSON.createObjectNode().put("type", "object");
        schema.putObject("properties");
        return new McpToolDescriptor("sample", "read", "mcp__sample__read", description, schema);
    }

    private static void register(ToolRegistry registry, String description, AtomicInteger calls) {
        registry.registerMcpToolOutput(descriptor(description), args -> {
            calls.incrementAndGet();
            return ToolOutput.success("read result");
        });
    }

    private static final class RecordingHandler implements HitlHandler {
        final List<ApprovalRequest> requests = new ArrayList<>();
        boolean blanket;
        Runnable duringApproval = () -> { };
        public ApprovalResult requestApproval(ApprovalRequest request) {
            requests.add(request);
            duringApproval.run();
            return ApprovalResult.approve();
        }
        public boolean isEnabled() { return true; }
        public void setEnabled(boolean enabled) { }
        public boolean isApprovedAllByTool(String tool) { return blanket; }
        public boolean isApprovedAllByServer(String server) { return blanket; }
        public void clearApprovedAllForServer(String server) { blanket = false; }
    }
}
