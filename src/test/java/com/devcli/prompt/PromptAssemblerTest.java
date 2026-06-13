package com.devcli.prompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptAssemblerTest {

    @TempDir
    Path tempDir;

    @Test
    void assemblesBuiltinPromptWithDynamicSections() {
        PromptAssembler assembler = PromptAssembler.createDefault();

        String prompt = assembler.assemble(PromptMode.AGENT, PromptContext.builder()
                .memoryContext("## 相关记忆\n用户偏好中文。")
                .externalContext("## MCP Resources\n- demo://resource")
                .skillIndex("## 可用 Skills\n- web-access")
                .build());

        assertTrue(prompt.contains("## Language"));
        assertTrue(prompt.contains("## Mode: ReAct Agent"));
        assertTrue(prompt.contains("用户偏好中文"));
        assertTrue(prompt.contains("demo://resource"));
        assertTrue(prompt.contains("web-access"));
    }

    @Test
    void builtinPromptRequiresEvidenceBeforeClaimingLongTermMemoryIsEmpty() {
        PromptAssembler assembler = PromptAssembler.createDefault();

        String prompt = assembler.assemble(PromptMode.AGENT, PromptContext.empty());

        assertTrue(prompt.contains("list_memory"));
        assertTrue(prompt.contains("长期记忆索引快照"));
        assertTrue(prompt.contains("不要在缺少"));
    }
    @Test
    void projectOverrideReplacesBuiltinModePrompt() throws Exception {
        Path projectPrompts = tempDir.resolve("project");
        Files.createDirectories(projectPrompts.resolve("modes"));
        Files.writeString(projectPrompts.resolve("modes/agent.md"), "## Mode: Override\n\n项目覆盖 prompt");

        PromptAssembler assembler = new PromptAssembler(new PromptRepository(
                tempDir.resolve("user"),
                projectPrompts
        ));

        String prompt = assembler.assemble(PromptMode.AGENT, PromptContext.empty());

        assertTrue(prompt.contains("项目覆盖 prompt"));
        assertTrue(prompt.contains("## Language"));
    }

    @Test
    void baseOverrideMustKeepLanguageSection() throws Exception {
        Path projectPrompts = tempDir.resolve("project");
        Files.createDirectories(projectPrompts);
        Files.writeString(projectPrompts.resolve("base.md"), "## Identity\n\nmissing language");

        PromptAssembler assembler = new PromptAssembler(new PromptRepository(
                tempDir.resolve("user"),
                projectPrompts
        ));

        assertThrows(IllegalStateException.class,
                () -> assembler.assemble(PromptMode.AGENT, PromptContext.empty()));
    }

    @Test
    void stickyMemoryGetsInjectedAsDedicatedSection() {
        // PR-B：StickyMemory.renderForPrompt() 输出应被 PromptAssembler 包成 ## Sticky Memory 段
        PromptAssembler assembler = PromptAssembler.createDefault();

        String prompt = assembler.assemble(PromptMode.AGENT, PromptContext.builder()
                .stickyMemory("### 用户偏好\n- 用简体中文\n- 不引入 SymbolSolver")
                .memoryContext("## 相关记忆\n历史项目 X")
                .build());

        // 必有 Sticky Memory 段
        assertTrue(prompt.contains("## Sticky Memory"),
                "stickyMemory 非空时应被包成 ## Sticky Memory 段");
        assertTrue(prompt.contains("用简体中文"));
        assertTrue(prompt.contains("不引入 SymbolSolver"));

        // KV cache 顺序：Sticky Memory 应在 Project Context 之前（更稳定的层在前）
        int stickyIdx = prompt.indexOf("## Sticky Memory");
        int projectIdx = prompt.indexOf("## Project Context");
        assertTrue(stickyIdx > 0 && projectIdx > 0);
        assertTrue(stickyIdx < projectIdx,
                "KV cache 友好布局：Sticky 应在 Project Context 之前");
    }

    @Test
    void emptyStickyDoesNotCreateEmptySection() {
        PromptAssembler assembler = PromptAssembler.createDefault();

        String prompt = assembler.assemble(PromptMode.AGENT, PromptContext.builder()
                .stickyMemory("")
                .memoryContext("## 相关记忆\n仅长期记忆")
                .build());

        assertTrue(!prompt.contains("## Sticky Memory"),
                "stickyMemory 为空时不应产生空段污染 prompt");
    }
}
