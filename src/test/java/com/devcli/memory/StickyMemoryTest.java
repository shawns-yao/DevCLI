package com.devcli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StickyMemory 关键契约测试。
 *
 * <p>守住的不变量：
 * <ol>
 *   <li>{@code renderForPrompt} 在没有任何来源时返回空串（PromptAssembler 会跳过空段）</li>
 *   <li>三层文件按"用户 / 项目 / 本地"顺序拼接，标题清晰</li>
 *   <li>pin/unpin 持久化到 JSON，重新加载能复现</li>
 *   <li>同 content 重复 pin 不新增条目，仅更新时间戳</li>
 *   <li>软上限超过时不阻断，状态摘要明确提示超限</li>
 * </ol>
 */
class StickyMemoryTest {

    @TempDir
    Path tempDir;

    @Test
    void emptyStateRendersEmptyString() {
        StickyMemory sticky = new StickyMemory(tempDir);
        sticky.reloadFiles(tempDir);
        assertEquals("", sticky.renderForPrompt(),
                "没有 DEVCLI.md 也没有 pinned 时应返回空串，避免污染 system prompt");
    }

    @Test
    void pinPersistsAndReloads() {
        StickyMemory sticky = new StickyMemory(tempDir);
        StickyMemory.PinnedFact f = sticky.pin("用户偏好简体中文", "user-cli");
        assertNotNull(f.id);
        assertEquals("用户偏好简体中文", f.content);

        // 新实例从同一目录重建，应能加载到 pinned
        StickyMemory reloaded = new StickyMemory(tempDir);
        List<StickyMemory.PinnedFact> facts = reloaded.listPinned();
        assertEquals(1, facts.size());
        assertEquals("用户偏好简体中文", facts.get(0).content);
        assertEquals("user-cli", facts.get(0).source);
    }

    @Test
    void pinDeduplicatesIdenticalContent() {
        StickyMemory sticky = new StickyMemory(tempDir);
        StickyMemory.PinnedFact a = sticky.pin("项目根 /home/dev/myapp", "user-cli");
        StickyMemory.PinnedFact b = sticky.pin("项目根 /home/dev/myapp", "llm-tool");
        assertEquals(a.id, b.id, "同 content 应复用 id，不新增条目");
        assertEquals(1, sticky.listPinned().size());
        assertEquals("llm-tool", sticky.listPinned().get(0).source,
                "重复 pin 应更新 source 字段");
    }

    @Test
    void unpinRemovesAndPersists() {
        StickyMemory sticky = new StickyMemory(tempDir);
        StickyMemory.PinnedFact f = sticky.pin("可删除事实", "user-cli");
        assertTrue(sticky.unpin(f.id));
        assertEquals(0, sticky.listPinned().size());

        StickyMemory reloaded = new StickyMemory(tempDir);
        assertEquals(0, reloaded.listPinned().size(), "unpin 应持久化到磁盘");
    }

    @Test
    void unpinOnUnknownIdReturnsFalse() {
        StickyMemory sticky = new StickyMemory(tempDir);
        assertFalse(sticky.unpin("pin-doesnotexist"));
    }

    @Test
    void renderIncludesAllSourcesWithHeaders() throws IOException {
        // 准备三层文件
        Path projectRoot = tempDir.resolve("project");
        Path home = tempDir.resolve("home");
        Files.createDirectories(home.resolve(".devcli"));
        Files.createDirectories(projectRoot.resolve(".devcli"));

        Files.writeString(home.resolve(".devcli").resolve("DEVCLI.md"),
                "用户全局：用中文沟通");
        Files.writeString(projectRoot.resolve("DEVCLI.md"),
                "项目约定：Java 17 + Maven");
        Files.writeString(projectRoot.resolve(".devcli").resolve("DEVCLI.local.md"),
                "本地补充：跳过测试快速打包");

        // 重定向 user.home 到 tempDir/home（StickyMemory 用 user.home + .devcli 找用户级文件）
        String origHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", home.toString());
            StickyMemory sticky = new StickyMemory(tempDir.resolve("memory"));
            sticky.reloadFiles(projectRoot);
            sticky.pin("最终决策：使用 SymbolSolver 不引入", "user-cli");

            String rendered = sticky.renderForPrompt();
            assertTrue(rendered.contains("用户全局：用中文沟通"));
            assertTrue(rendered.contains("项目约定：Java 17 + Maven"));
            assertTrue(rendered.contains("本地补充：跳过测试快速打包"));
            assertTrue(rendered.contains("最终决策：使用 SymbolSolver 不引入"));

            // 顺序：用户全局 < 项目约定 < 本地补充 < pinned
            int posUser = rendered.indexOf("用户全局");
            int posProject = rendered.indexOf("项目约定");
            int posLocal = rendered.indexOf("本地补充");
            int posPinned = rendered.indexOf("最终决策");
            assertTrue(posUser >= 0 && posUser < posProject);
            assertTrue(posProject < posLocal);
            assertTrue(posLocal < posPinned);
        } finally {
            System.setProperty("user.home", origHome);
        }
    }

    @Test
    void missingFilesAreSilentlyIgnored() {
        // 没有 DEVCLI.md 文件时不应抛异常
        StickyMemory sticky = new StickyMemory(tempDir);
        sticky.reloadFiles(tempDir);
        assertEquals("", sticky.renderForPrompt());
    }

    @Test
    void statusSummaryShowsOverCapWhenStickyMemoryIsTooLarge() {
        StickyMemory sticky = new StickyMemory(tempDir);
        sticky.pin("x ".repeat(StickyMemory.MAX_STICKY_TOKENS * 5), "user-cli");

        String summary = sticky.getStatusSummary();
        assertTrue(summary.contains("超限"), "超过 sticky 软上限时 /memory 状态应直接提示超限");
        assertTrue(summary.contains("cap " + StickyMemory.MAX_STICKY_TOKENS));
    }

    @Test
    void rejectsBlankPin() {
        StickyMemory sticky = new StickyMemory(tempDir);
        assertThrows(IllegalArgumentException.class, () -> sticky.pin("", "user-cli"));
        assertThrows(IllegalArgumentException.class, () -> sticky.pin("   ", "user-cli"));
        assertThrows(IllegalArgumentException.class, () -> sticky.pin(null, "user-cli"));
    }
}
