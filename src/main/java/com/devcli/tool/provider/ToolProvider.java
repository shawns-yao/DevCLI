package com.devcli.tool.provider;

import com.devcli.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

public interface ToolProvider {
    void register(ToolContext context);

    interface ToolContext {
        void registerTool(ToolRegistry.Tool tool);

        JsonNode createToolParameters(ToolParameter... params);

        Path resolveSafePath(String path);

        int maxWriteFileBytes();

        String currentResourceLeaseStep();

        void acquireWriteLease(String stepId, Path path);

        boolean isWriteLeaseValid(String stepId, Path path);

        void recordFileWrite(String displayPath, Path safePath, String before, String content, String stepId);

        String projectPath();

        long commandTimeoutSeconds();

        Consumer<String> memorySaver();

        ToolRegistry.MemorySaver memorySaveHandler();

        ToolRegistry.MemoryListHandler memoryListHandler();

        com.devcli.browser.BrowserConnector browserConnector();

        com.devcli.snapshot.SnapshotService snapshotService();

        List<ToolRegistry.Tool> searchableTools();

        boolean isMcpTool(String toolName);

        boolean activateToolDefinition(String toolName);

        long toolCatalogVersion();
    }
}
