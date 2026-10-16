package com.devcli.hitl;

import com.devcli.tool.ToolContract;
import com.devcli.mcp.config.McpToolTrustPolicy;
import com.devcli.mcp.protocol.McpToolDescriptor;
import com.devcli.policy.PermissionRuleSet;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;
import com.devcli.tool.ToolSideChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Public pipeline checks; MCP transport and RAG retrieval are controlled test adapters. */
class ReadOnlySearchApprovalTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path root;

    @Test
    void repeatedGrepOfOrdinarySourceDoesNotAsk() throws Exception {
        Files.writeString(root.resolve("Example.java"), """
                CancellationToken token = context.cancellationToken();
                String username = user.getName();
                String password = configuration.getPassword();
                """);
        Handler handler = new Handler();
        try (var registry = registry(handler)) {
            for (int i = 0; i < 2; i++) {
                var output = registry.executeToolOutput("grep_code",
                        "{\"pattern\":\"token|username|password\"}");
                assertTrue(output.isSuccess(), output.text());
                assertTrue(output.text().contains("Example.java:1:"), output.text());
            }
            assertTrue(handler.requests.isEmpty());
        }
    }

    @Test
    void truncatedGrepSourceDoesNotAsk() throws Exception {
        Handler handler = new Handler();
        try (var registry = registry(handler)) {
            registry.registerTool(ToolContract.Tool.structured("grep_code", "fixture",
                    JSON.readTree("{\"type\":\"object\"}"), args -> ToolOutput.success(
                            "检索结果:\n   CancellationToken token = context.cancellationToken()...")));
            for (int i = 0; i < 2; i++) {
                assertTrue(registry.executeToolOutput("grep_code", "{\"pattern\":\"token\"}").isSuccess());
            }
            assertTrue(handler.requests.isEmpty());
        }
    }

    @Test
    void grepOnlyReviewsReturnedMatches() throws Exception {
        Files.writeString(root.resolve("Example.java"), "password=opaque-secret\nclass Example {}\n");
        Handler handler = new Handler();
        try (var registry = registry(handler)) {
            assertTrue(registry.executeToolOutput("grep_code", "{\"pattern\":\"class Example\"}").isSuccess());
            assertTrue(handler.requests.isEmpty());
            assertFalse(registry.executeToolOutput("grep_code", "{\"pattern\":\"password\"}").isSuccess());
            assertEquals(1, handler.requests.size());
            assertTrue(handler.requests.get(0).contentReview());
        }
    }

    @Test
    void trustedMcpReadCanRepeatButWriteAndUnknownToolsStillAsk() throws Exception {
        Handler handler = new Handler();
        AtomicInteger executed = new AtomicInteger();
        try (var registry = registry(handler)) {
            registry.setMcpToolTrustPolicy("local", new McpToolTrustPolicy(false, Set.of("inspect"), Set.of()));
            register(registry, "inspect", null, executed, "String username = user.getName();");
            register(registry, "replace", null, executed, "changed");
            register(registry, "search_unknown", new McpToolDescriptor.Annotations(true, false, false),
                    executed, "found");
            for (int i = 0; i < 2; i++) {
                assertTrue(registry.executeToolOutput("mcp__local__inspect", "{}").isSuccess());
            }
            assertTrue(handler.requests.isEmpty());
            assertFalse(registry.executeToolOutput("mcp__local__replace", "{}").isSuccess());
            assertFalse(registry.executeToolOutput("mcp__local__search_unknown", "{}").isSuccess());
            assertEquals(2, handler.requests.size());
            assertEquals(2, executed.get());
        }
    }

    @Test
    void localTrustDoesNotApproveSensitiveArgumentsOrResults() throws Exception {
        Handler handler = new Handler();
        AtomicInteger executed = new AtomicInteger();
        try (var registry = registry(handler)) {
            registry.setMcpToolTrustPolicy("local", new McpToolTrustPolicy(false, Set.of("inspect"), Set.of()));
            register(registry, "inspect", null, executed, "password=opaque-secret");
            assertFalse(registry.executeToolOutput("mcp__local__inspect",
                    "{\"query\":\"password=another-secret\"}").isSuccess());
            assertEquals(0, executed.get());
            for (int i = 0; i < 2; i++) {
                var output = registry.executeToolOutput("mcp__local__inspect", "{}");
                assertFalse(output.isSuccess());
                assertFalse(output.text().contains("opaque-secret"));
            }
            assertEquals(3, handler.requests.size());
            assertTrue(handler.requests.stream().allMatch(ApprovalRequest::contentReview));
        }
    }

    @Test
    void destructiveAndOpenWorldAnnotationsOverrideLocalReadOnlyList() throws Exception {
        Handler handler = new Handler();
        AtomicInteger executed = new AtomicInteger();
        try (var registry = registry(handler)) {
            registry.setMcpToolTrustPolicy("local", new McpToolTrustPolicy(false,
                    Set.of("destroy", "remote"), Set.of()));
            register(registry, "destroy", new McpToolDescriptor.Annotations(true, true, false), executed, "x");
            register(registry, "remote", new McpToolDescriptor.Annotations(true, false, true), executed, "x");
            assertFalse(registry.executeToolOutput("mcp__local__destroy", "{}").isSuccess());
            assertFalse(registry.executeToolOutput("mcp__local__remote", "{}").isSuccess());
            assertEquals(2, handler.requests.size());
            assertEquals(0, executed.get());
        }
    }

    @Test
    void trustedReadOnlyMcpStillHonorsExplicitDenyAndAskRules() throws Exception {
        for (boolean hardDeny : new boolean[]{true, false}) {
            Handler handler = new Handler();
            AtomicInteger executed = new AtomicInteger();
            try (var registry = registry(handler)) {
                registry.setMcpToolTrustPolicy("local", new McpToolTrustPolicy(false, Set.of("inspect"), Set.of()));
                register(registry, "inspect", null, executed, "ordinary result");
                registry.withPermissionRules(PermissionRuleSet.parse(
                        hardDeny ? List.of("mcp__local__inspect") : List.of(),
                        hardDeny ? List.of() : List.of("mcp__local__inspect"), List.of(), List.of()));
                assertFalse(registry.executeToolOutput("mcp__local__inspect", "{}").isSuccess());
                assertEquals(hardDeny ? 0 : 1, handler.requests.size());
                assertEquals(0, executed.get());
            }
        }
    }

    @Test
    void redactedMcpResultDropsRawEvidenceAndDoesNotAuthorizeTheNextCall() throws Exception {
        Handler handler = new Handler();
        handler.decision = ApprovalResult.redact();
        try (var registry = registry(handler)) {
            registry.setMcpToolTrustPolicy("local", new McpToolTrustPolicy(false, Set.of("inspect"), Set.of()));
            registry.registerMcpToolOutput(new McpToolDescriptor("local", "inspect", "mcp__local__inspect",
                            "fixture", JSON.readTree("{\"type\":\"object\"}")),
                    args -> ToolOutput.success("password=opaque-secret").withSideChannel(new ToolSideChannel() { }));
            var output = registry.executeToolOutput("mcp__local__inspect", "{}");
            assertTrue(output.isSuccess());
            assertFalse(output.text().contains("opaque-secret"));
            assertTrue(output.sideChannels().isEmpty());
            handler.decision = ApprovalResult.reject("test");
            assertFalse(registry.executeToolOutput("mcp__local__inspect", "{}").isSuccess());
            assertEquals(2, handler.requests.size());
        }
    }

    private HitlToolRegistry registry(Handler handler) {
        var registry = new HitlToolRegistry(handler);
        registry.setProjectPath(root.toString());
        return registry;
    }

    private void register(HitlToolRegistry registry, String name, McpToolDescriptor.Annotations annotations,
                          AtomicInteger executed, String output) throws Exception {
        registry.registerMcpToolOutput(new McpToolDescriptor("local", name, "mcp__local__" + name,
                        "fixture", JSON.readTree("{\"type\":\"object\"}"), annotations),
                args -> { executed.incrementAndGet(); return ToolOutput.success(output); });
    }

    private static final class Handler implements HitlHandler {
        final List<ApprovalRequest> requests = new ArrayList<>();
        ApprovalResult decision = ApprovalResult.reject("test");
        public ApprovalResult requestApproval(ApprovalRequest request) {
            requests.add(request);
            return decision;
        }
        public boolean isEnabled() { return true; }
        public void setEnabled(boolean enabled) { }
    }
}
