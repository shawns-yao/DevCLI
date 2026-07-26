package com.devcli.tool.provider;

import com.devcli.tool.ToolOutput;
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

        /**
         * 记录一次文件读取，供过期写入屏障比对版本。stepId 为空表示单 Agent 路径，不参与屏障。
         * 默认空实现，保证既有 ToolContext 实现无需改动。
         */
        default void recordFileRead(Path safePath, String content, String stepId) {
        }

        /**
         * 判断本次写入是否基于过期版本。
         *
         * @param currentContent 写入前从磁盘读到的当前内容（null 表示文件不存在）
         * @return 过期原因；不过期返回 null
         */
        default String staleWriteReason(String stepId, Path safePath, String currentContent) {
            return null;
        }

        String projectPath();

        long commandTimeoutSeconds();

        String executeCommand(String command);

        ToolOutput executeCommandOutput(String command);

        Consumer<String> memorySaver();

        ToolRegistry.MemorySaver memorySaveHandler();

        ToolRegistry.MemoryListHandler memoryListHandler();

        com.devcli.browser.BrowserConnector browserConnector();

        com.devcli.skill.SkillRegistry skillRegistry();

        com.devcli.skill.SkillContextBuffer activeSkillContextBuffer();

        com.devcli.snapshot.SnapshotService snapshotService();

        List<ToolRegistry.Tool> searchableTools();

        boolean isMcpTool(String toolName);

        boolean activateToolDefinition(String toolName);

        long toolCatalogVersion();
    }
}
