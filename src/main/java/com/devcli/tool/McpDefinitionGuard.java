package com.devcli.tool;

import com.devcli.mcp.protocol.McpToolDescriptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static com.devcli.tool.ToolContract.*;

/** 根目录与隔离 fork 共用执行绑定；描述不变的重连也不能继续调用旧客户端。 */
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
        context.putAttribute(BINDING, registered);
        return verify(context);
    }

    ToolOutput verify(ToolExecutionPipeline.Context context) {
        McpRegisteredTool bound = context.attribute(BINDING, McpRegisteredTool.class);
        if (bound == null) return null;
        McpRegisteredTool current = live.get(context.name());
        McpToolDescriptor changing = pending.get(context.name());
        if (current != bound || (changing != null && !bound.descriptor().equals(changing))) {
            invalidateApproval.accept(bound.descriptor().serverName());
            return ToolOutput.rejected(ToolErrorCode.STALE_TOOL_SNAPSHOT,
                    "MCP 工具定义、连接或本地授权已变化，未执行；请刷新工具绑定，旧子任务需重新创建", true);
        }
        return null;
    }

    boolean needsApproval(ToolExecutionPipeline.Context context) {
        return pending.containsKey(context.name());
    }

    void approved(ToolExecutionPipeline.Context context) {
        McpRegisteredTool bound = context.attribute(BINDING, McpRegisteredTool.class);
        pending.remove(context.name(), bound.descriptor());
    }
}
