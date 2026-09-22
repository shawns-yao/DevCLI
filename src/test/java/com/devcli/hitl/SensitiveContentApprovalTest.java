package com.devcli.hitl;

import com.devcli.mcp.protocol.McpToolDescriptor;
import com.devcli.policy.TaskGrant;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Targeted public tool-entry checks. MCP transport and user decisions are local test adapters. */
class SensitiveContentApprovalTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET = "sk-testcredential01234567890123456789";
    @TempDir Path root;

    private HitlToolRegistry registry(Handler handler) {
        HitlToolRegistry registry = new HitlToolRegistry(handler);
        registry.setProjectPath(root.toString());
        return registry;
    }

    private void source() throws Exception {
        Files.writeString(root.resolve("Example.java"), "// OPENAI_API_KEY=" + SECRET + "\nclass Example {}");
    }

    @Test
    void ordinarySourceSecretRequiresApprovalWithoutShowingSecret() throws Exception {
        source();
        Handler handler = new Handler();
        try (var registry = registry(handler)) {
            ToolOutput result = registry.executeToolOutput("read_file", "{\"path\":\"Example.java\"}");
            assertFalse(result.isSuccess());
            assertFalse(result.text().contains(SECRET));
            assertEquals(1, handler.requests.size());
            ApprovalRequest request = handler.requests.get(0);
            assertTrue(request.contentReview());
            assertTrue(request.redactionAllowed());
            assertTrue(request.toDisplayText().contains("凭据"));
            assertTrue(request.toDisplayText().contains("模型"));
            assertFalse(request.toDisplayText().contains(SECRET));
            assertFalse(request.arguments().contains(SECRET));
        }
    }

    @Test
    void redactedReadDoesNotChangeOriginalAndIsNotRemembered() throws Exception {
        source();
        Handler handler = new Handler();
        handler.answer = ignored -> ApprovalResult.redact();
        try (var registry = registry(handler)) {
            for (int i = 0; i < 2; i++) {
                ToolOutput result = registry.executeToolOutput("read_file", "{\"path\":\"Example.java\"}");
                assertTrue(result.isSuccess(), result.text());
                assertFalse(result.text().contains(SECRET));
                assertTrue(result.text().contains("class Example"));
                assertTrue(result.text().contains("脱敏"));
            }
            assertEquals(2, handler.requests.size());
            assertTrue(Files.readString(root.resolve("Example.java")).contains(SECRET));
        }
    }

    @Test
    void allowOnceIsBoundToReadSnapshot() throws Exception {
        source();
        Handler handler = new Handler();
        handler.answer = ignored -> {
            try {
                Files.writeString(root.resolve("Example.java"), "password=different-secret");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            return ApprovalResult.approve();
        };
        try (var registry = registry(handler)) {
            ToolOutput result = registry.executeToolOutput("read_file", "{\"path\":\"Example.java\"}");
            assertTrue(result.isSuccess(), result.text());
            assertTrue(result.text().contains(SECRET));
            assertFalse(result.text().contains("different-secret"));
        }
    }

    @Test
    void paginationCannotHideCredentialPrefix() throws Exception {
        source();
        Handler handler = new Handler();
        try (var registry = registry(handler)) {
            ToolOutput result = registry.executeToolOutput("read_file",
                    "{\"path\":\"Example.java\",\"offset\":25,\"limit\":5}");
            assertFalse(result.isSuccess());
            assertEquals(1, handler.requests.size());
        }
    }

    @Test
    void noHandlerOrDisabledHitlDoesNotReleaseSecrets() throws Exception {
        source();
        Handler handler = new Handler();
        handler.enabled = false;
        try (var registry = registry(handler); var base = new ToolRegistry()) {
            base.setProjectPath(root.toString());
            assertFalse(registry.executeToolOutput("read_file", "{\"path\":\"Example.java\"}").isSuccess());
            assertFalse(base.executeToolOutput("read_file", "{\"path\":\"Example.java\"}").isSuccess());
            assertEquals(0, handler.requests.size());
        }
    }

    @Test
    void ordinaryFileDoesNotPrompt() throws Exception {
        Files.writeString(root.resolve("safe.txt"), "ordinary source");
        Handler handler = new Handler();
        try (var registry = registry(handler)) {
            assertTrue(registry.executeToolOutput("read_file", "{\"path\":\"safe.txt\"}").isSuccess());
            assertEquals(0, handler.requests.size());
        }
    }

    @Test
    void domainGrantAndApproveAllDoNotBypassSensitiveUrl() {
        Handler handler = new Handler();
        handler.approvedAll = true;
        try (var registry = registry(handler)) {
            ToolOutput result = registry.runWithTaskGrant(
                    TaskGrant.NONE.withNetworkHosts(List.of("example.com")),
                    () -> registry.executeToolOutput("web_fetch",
                            "{\"url\":\"https://example.com/?token=" + SECRET + "\"}"));
            assertFalse(result.isSuccess());
            assertEquals(1, handler.requests.size());
            assertFalse(handler.requests.get(0).redactionAllowed());
            assertTrue(handler.requests.get(0).toDisplayText().contains("example.com"));
            assertFalse(handler.requests.get(0).toDisplayText().contains(SECRET));
        }
    }

    @Test
    void sensitiveSearchRejectedBeforeProviderIsInvoked() {
        Handler handler = new Handler();
        try (var registry = registry(handler)) {
            ToolOutput result = registry.executeToolOutput("web_search",
                    "{\"query\":\"password=local-secret\"}");
            assertFalse(result.isSuccess());
            assertEquals(1, handler.requests.size());
            assertTrue(handler.requests.get(0).redactionAllowed());
        }
    }

    private void browser(HitlToolRegistry registry, AtomicReference<String> sent) throws Exception {
        registry.registerMcpToolOutput(new McpToolDescriptor("chrome-devtools", "fill",
                "mcp__chrome-devtools__fill", "input",
                JSON.readTree("{\"type\":\"object\",\"properties\":{\"uid\":{\"type\":\"string\"},"
                        + "\"value\":{\"type\":\"string\"}},\"required\":[\"uid\",\"value\"]}")),
                args -> { sent.set(args); return ToolOutput.success("sent"); });
    }

    @Test
    void browserReceivesOnlyRedactedValueDespiteApproveAll() throws Exception {
        Handler handler = new Handler();
        handler.approvedAll = true;
        handler.answer = request -> request.contentReview() ? ApprovalResult.redact() : ApprovalResult.approve();
        AtomicReference<String> sent = new AtomicReference<>();
        try (var registry = registry(handler)) {
            browser(registry, sent);
            ToolOutput output = registry.executeToolOutput("mcp__chrome-devtools__fill",
                    "{\"uid\":\"input-1\",\"value\":\"" + SECRET + "\"}");
            assertTrue(output.isSuccess(), output.text());
            assertNotNull(sent.get());
            assertFalse(sent.get().contains(SECRET));
            assertEquals("input-1", JSON.readTree(sent.get()).path("uid").asText());
            assertEquals(1, handler.requests.stream().filter(ApprovalRequest::contentReview).count());
        }
    }

    @Test
    void invalidBulkOrModifyDecisionCannotApproveContent() throws Exception {
        Handler handler = new Handler();
        AtomicReference<String> sent = new AtomicReference<>();
        try (var registry = registry(handler)) {
            browser(registry, sent);
            for (ApprovalResult decision : List.of(ApprovalResult.approveAll(),
                    ApprovalResult.approveAllByServer(), ApprovalResult.modify("{}"))) {
                handler.answer = ignored -> decision;
                assertFalse(registry.executeToolOutput("mcp__chrome-devtools__fill",
                        "{\"uid\":\"input-1\",\"value\":\"" + SECRET + "\"}").isSuccess());
                assertNull(sent.get());
            }
        }
    }

    @Test
    void approvalModificationCannotInjectSecretAfterInspection() throws Exception {
        Handler handler = new Handler();
        handler.answer = ignored -> ApprovalResult.modify(
                "{\"uid\":\"input-1\",\"value\":\"" + SECRET + "\"}");
        AtomicReference<String> sent = new AtomicReference<>();
        try (var registry = registry(handler)) {
            browser(registry, sent);
            assertFalse(registry.executeToolOutput("mcp__chrome-devtools__fill",
                    "{\"uid\":\"input-1\",\"value\":\"normal\"}").isSuccess());
            assertNull(sent.get());
        }
    }

    @Test
    void encodedSensitiveUrlAndNestedMcpSecretAreBlocked() throws Exception {
        Handler handler = new Handler();
        AtomicInteger sent = new AtomicInteger();
        try (var registry = registry(handler)) {
            assertFalse(registry.executeToolOutput("web_fetch",
                    "{\"url\":\"https://example.com/?q=password%3Dencoded-secret\"}").isSuccess());
            registry.registerMcpToolOutput(new McpToolDescriptor("service", "send",
                    "mcp__service__send", "send", JSON.readTree("{\"type\":\"object\"}")),
                    args -> { sent.incrementAndGet(); return ToolOutput.success("sent"); });
            assertFalse(registry.executeToolOutput("mcp__service__send",
                    "{\"body\":{\"password\":\"nested-secret\"}}").isSuccess());
            assertEquals(0, sent.get());
            assertFalse(handler.requests.get(1).toDisplayText().contains("nested-secret"));
            assertFalse(handler.requests.get(1).redactionAllowed());
        }
    }

    private static final class Handler implements HitlHandler {
        boolean enabled = true;
        boolean approvedAll;
        List<ApprovalRequest> requests = new ArrayList<>();
        Function<ApprovalRequest, ApprovalResult> answer = ignored -> ApprovalResult.reject("no");
        public ApprovalResult requestApproval(ApprovalRequest request) {
            requests.add(request);
            return answer.apply(request);
        }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
        public boolean isApprovedAllByTool(String name) { return approvedAll; }
        public boolean isApprovedAllByServer(String name) { return approvedAll; }
    }
}
