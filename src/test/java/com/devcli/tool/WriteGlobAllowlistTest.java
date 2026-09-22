package com.devcli.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 委派写白名单与任务级授权共用同一套 glob 语义（{@code WriteGlobSet}）。
 *
 * <p>这里钉住的是「`dir/**` 覆盖该目录所有后代」这条规则：Java 自身的 glob `**`
 * 不跨多级目录，如果不显式补齐，`allowed_write_paths: ["src/**"]` 会错误地拒绝
 * `src/main/A.java` 这类真实目标。</p>
 */
class WriteGlobAllowlistTest {

    @TempDir
    Path projectRoot;

    @Test
    void directoryGlobCoversAllDescendants() {
        try (ToolRegistry registry = new ToolRegistry()) {
            registry.setProjectPath(projectRoot.toString());

            registry.runWithAllowedWritePaths(List.of("src/**"), () -> {
                assertTrue(registry.isWritePathAllowed("src/main/A.java"),
                        "dir/** 必须覆盖多级后代路径");
                assertTrue(registry.isWritePathAllowed("src/直接子文件.txt"));
                assertFalse(registry.isWritePathAllowed("test/B.java"));
                assertFalse(registry.isWritePathAllowed("另一个目录/B.java"));
                return null;
            });
        }
    }

    @Test
    void wholeProjectGlobAllowsEverythingInsideRoot() {
        try (ToolRegistry registry = new ToolRegistry()) {
            registry.setProjectPath(projectRoot.toString());

            registry.runWithAllowedWritePaths(List.of("**"), () -> {
                assertTrue(registry.isWritePathAllowed("src/deep/nested/A.java"));
                assertTrue(registry.isWritePathAllowed("top.txt"));
                return null;
            });
        }
    }

    @Test
    void emptyAllowlistKeepsEverythingAllowed() {
        try (ToolRegistry registry = new ToolRegistry()) {
            registry.setProjectPath(projectRoot.toString());

            assertTrue(registry.isWritePathAllowed("anywhere/at/all.txt"),
                    "未设置白名单时不受 glob 限制");
        }
    }
}
