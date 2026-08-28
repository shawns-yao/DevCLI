package com.devcli.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolSideEffectSerializationTest {

    @Test
    void leafSideEffectsAreSerializedButReadsAndDelegationAreNot(@TempDir Path projectDir) {
        try (ToolRegistry registry = new ToolRegistry()) {
            registry.setProjectPath(projectDir.toString());

            // 叶子副作用：同一并行批次必须串行，避免竞态同一文件 / 工作区
            assertTrue(registry.isSerializedSideEffect("write_file"));
            assertTrue(registry.isSerializedSideEffect("execute_command"));
            assertTrue(registry.isSerializedSideEffect("create_project"));
            assertTrue(registry.isSerializedSideEffect("save_memory"));

            // 只读 / 本地上下文不加锁，继续并行
            assertFalse(registry.isSerializedSideEffect("read_file"));
            assertFalse(registry.isSerializedSideEffect("grep_code"));
            assertFalse(registry.isSerializedSideEffect("list_dir"));
            assertFalse(registry.isSerializedSideEffect("load_skill"));

            // 委派是编排工具：子操作各自加锁，自身持锁会跨线程自锁
            assertFalse(registry.isSerializedSideEffect(DelegateTaskTool.NAME));
        }
    }

    @Test
    void removeToolHidesToolFromDefinitions(@TempDir Path projectDir) {
        try (ToolRegistry registry = new ToolRegistry()) {
            registry.setProjectPath(projectDir.toString());
            assertTrue(registry.hasTool("web_search"));

            registry.removeTool("web_search");

            assertFalse(registry.hasTool("web_search"));
            assertTrue(registry.getToolDefinitions().stream()
                    .noneMatch(tool -> "web_search".equals(tool.name())));
            // 其余工具不受影响
            assertTrue(registry.hasTool("read_file"));
        }
    }
}
