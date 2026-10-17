package com.devcli.tool;

import com.devcli.mcp.protocol.McpToolDescriptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static com.devcli.tool.ToolContract.*;

/** 根目录与隔离 fork 共用定义版本；审批不能授权后来替换的描述或 schema。 */
final class McpDefinitionGuard {
    static final String BINDING = "mcp.approval.definition";
    private final Map<String, McpRegisteredTool> live;
    private final Map<String, McpToolDescriptor> pending = new ConcurrentHashMap<>();
    private final Consumer<String> invalidateApproval;

    McpDefinitionGuard(Map<String, McpRegisteredTool> live, Consumer<String> invalidateApproval) {
        this.live = live;
        this.invalidateApproval = invalidateApproval;
    }

    void changed(String server, McpToolDescriptor descriptor) {
        pending.put(descriptor.namespacedName(), descriptor);
        invalidateApproval.accept(server);
    }

    void removed(String server, String name) {
        pending.remove(name);
        invalidateApproval.accept(server);
    }

    ToolOutput bind(ToolExecutionPipeline.Context context, McpRegisteredTool registered) {
        if (registered == null) return null;
        context.putAttribute(BINDING, registered.descriptor());
        return verify(context);
    }

    ToolOutput verify(ToolExecutionPipeline.Context context) {
        McpToolDescriptor bound = context.attribute(BINDING, McpToolDescriptor.class);
        if (bound == null) return null;
        McpRegisteredTool current = live.get(context.name());
        McpToolDescriptor changing = pending.get(context.name());
        if (current == null || !bound.equals(current.descriptor())
                || (changing != null && !bound.equals(changing))) {
            invalidateApproval.accept(bound.serverName());
            return ToolOutput.rejected(ToolErrorCode.STALE_TOOL_SNAPSHOT,
                    "MCP 工具定义已变化，未执行；请通过 search_tools 重新读取定义并审批", true);
        }
        return null;
    }

    boolean needsApproval(ToolExecutionPipeline.Context context) {
        return pending.containsKey(context.name());
    }

    void approved(ToolExecutionPipeline.Context context) {
        McpToolDescriptor bound = context.attribute(BINDING, McpToolDescriptor.class);
        pending.remove(context.name(), bound);
    }
}
