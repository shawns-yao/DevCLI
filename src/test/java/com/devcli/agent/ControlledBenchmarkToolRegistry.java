package com.devcli.agent;

import com.devcli.llm.LlmClient;
import com.devcli.tool.ResourceLeaseMaintenance;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;

import java.util.List;
import java.util.Set;

/** 受控 benchmark 的工具白名单在隔离项目 fork 中保持不变。 */
public final class ControlledBenchmarkToolRegistry extends ToolRegistry {
    private final Set<String> allowedTools;

    public ControlledBenchmarkToolRegistry(Set<String> allowedTools) {
        this.allowedTools = Set.copyOf(allowedTools);
    }

    private ControlledBenchmarkToolRegistry(Set<String> allowedTools, ResourceLeaseMaintenance maintenance) {
        super(maintenance);
        this.allowedTools = Set.copyOf(allowedTools);
    }

    @Override
    protected ToolRegistry createProjectForkRegistry(ResourceLeaseMaintenance maintenance) {
        return new ControlledBenchmarkToolRegistry(allowedTools, maintenance);
    }

    @Override
    public List<LlmClient.Tool> getToolDefinitions() {
        return super.getToolDefinitions().stream()
                .filter(tool -> allowedTools.contains(tool.name()))
                .toList();
    }

    @Override
    public ToolOutput executeToolOutput(String name, String argumentsJson) {
        if (!allowedTools.contains(name)) {
            return ToolOutput.text("benchmark policy rejected tool: " + name);
        }
        return super.executeToolOutput(name, argumentsJson);
    }
}
