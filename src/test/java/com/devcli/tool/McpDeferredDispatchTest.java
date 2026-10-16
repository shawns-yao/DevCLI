package com.devcli.tool;

import com.devcli.tool.ToolContract;
import com.devcli.mcp.protocol.McpToolDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class McpDeferredDispatchTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void internalPatchToolIsNeitherDiscoverableNorCallableByTheModel() {
        try (ToolRegistry registry = new ToolRegistry()) {
            assertTrue(registry.getToolDefinitions().stream().noneMatch(tool -> tool.name().equals("apply_patch")));
            assertTrue(registry.searchableTools().stream().noneMatch(tool -> tool.name().equals("apply_patch")));
            var snapshot = registry.snapshotForCurrentAccess();
            var results = registry.executeTools(java.util.List.of(new ToolContract.ToolInvocation(
                    "patch", "apply_patch", "{\"patch\":\"*** Begin Patch\\n*** End Patch\"}")), snapshot);
            assertEquals(ToolErrorCode.CAPABILITY_DENIED, results.getFirst().errorCode());
        }
    }

    @Test
    void discoveryPreservesDefinitionsAndReturnsExactSchema() throws Exception {
        try (ToolRegistry registry = new ToolRegistry()) {
            var schema = JSON.readTree("""
                    {"type":"object","properties":{"Message":{"type":"string"}},"required":["Message"]}
                    """);
            registry.registerMcpToolOutput(new McpToolDescriptor("sample", "echo", "mcp__sample__echo",
                    "Echo a Message", schema), arguments -> ToolOutput.success(arguments));
            var before = registry.getToolDefinitions();
            var found = registry.executeToolOutput("search_tools", "{\"query\":\"mcp__sample__echo\",\"limit\":\"1\"}");
            assertTrue(found.isSuccess(), found.text());
            assertEquals(before, registry.getToolDefinitions(), "搜索不得改变顶层 tools 前缀");
            assertTrue(found.text().contains(schema.toString()), found.text());
            assertTrue(before.stream().anyMatch(tool -> tool.name().equals("call_mcp_tool")));
            assertTrue(before.stream().noneMatch(tool -> tool.name().equals("mcp__sample__echo")));
        }
    }

    @Test
    void dispatchValidatesTargetSchemaAndCurrentCapability() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (ToolRegistry registry = new ToolRegistry()) {
            registry.registerMcpToolOutput(new McpToolDescriptor("sample", "send", "mcp__sample__send",
                    "Send message", JSON.readTree("""
                    {"type":"object","properties":{"message":{"type":"string"}},"required":["message"]}
                    """)), arguments -> { calls.incrementAndGet(); return ToolOutput.success("sent"); });
            registry.executeToolOutput("search_tools", "{\"query\":\"mcp__sample__send\",\"limit\":\"1\"}");
            var target = registry.searchableTools().stream().filter(t -> t.name().equals("mcp__sample__send"))
                    .findFirst().orElseThrow();
            var arguments = JSON.createObjectNode().put("tool_name", target.name());
            arguments.putObject("arguments");
            assertEquals(ToolErrorCode.INVALID_ARGUMENTS,
                    registry.executeToolOutput("call_mcp_tool", arguments.toString()).errorCode());
            arguments.putObject("arguments").put("message", "hello");
            ToolOutput denied = registry.runWithToolAccess(ToolContract.ToolAccessScope.READ_ONLY,
                    () -> registry.executeToolOutput("call_mcp_tool", arguments.toString()));
            assertEquals(ToolErrorCode.CAPABILITY_DENIED, denied.errorCode(), denied.text());
            assertEquals(0, calls.get());
            assertTrue(registry.executeToolOutput("call_mcp_tool", arguments.toString()).isSuccess());
            assertEquals(1, calls.get());
            registry.runWithAllowedTools(java.util.Set.of(target.name()), () -> {
                assertTrue(registry.getToolDefinitions().stream().anyMatch(t -> t.name().equals("call_mcp_tool")));
                assertTrue(registry.executeToolOutput("call_mcp_tool", arguments.toString()).isSuccess());
                return null;
            });
            assertEquals(2, calls.get());
            registry.registerMcpToolOutput(new McpToolDescriptor("sample", "send", target.name(),
                    "Send updated message", JSON.readTree("""
                    {"type":"object","properties":{"body":{"type":"string"}},"required":["body"]}
                    """)), input -> { calls.incrementAndGet(); return ToolOutput.success("sent"); });
            assertEquals(ToolErrorCode.INVALID_ARGUMENTS,
                    registry.executeToolOutput("call_mcp_tool", arguments.toString()).errorCode());
            assertEquals(2, calls.get(), "当前 schema 校验失败不得执行目标工具");
            arguments.putObject("arguments").put("body", "updated");
            assertTrue(registry.executeToolOutput("call_mcp_tool", arguments.toString()).isSuccess());
            assertEquals(3, calls.get());
        }
    }
}
