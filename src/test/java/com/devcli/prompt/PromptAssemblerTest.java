package com.devcli.prompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptAssemblerTest {

    @TempDir
    Path tempDir;

    @Test
    void assemblesBuiltinPromptWithSessionStableSectionsOnly() {
        PromptAssembler assembler = PromptAssembler.createDefault();

        String prompt = assembler.assemble(PromptMode.AGENT, PromptContext.builder()
                .memoryContext("## 相关记忆\n用户偏好中文。")
                .externalContext("## MCP Resources\n- demo://resource")
                .skillIndex("## 可用 Skills\n- web-access")
                .sessionMemory("## 证据\nWORKING_EVIDENCE")
                .build());

        assertTrue(prompt.contains("## Language"));
        assertTrue(prompt.contains("## Mode: ReAct Agent"));
        // 会话级稳定内容留在 system prompt
        assertTrue(prompt.contains("demo://resource"));
        // 按轮次变化的内容不得进 system prompt，否则其后全部历史前缀失配
        assertFalse(prompt.contains("用户偏好中文"));
        assertFalse(prompt.contains("web-access"));
        assertFalse(prompt.contains("WORKING_EVIDENCE"));
    }

    @Test
    void turnContextCarriesPerTurnSections() {
        PromptAssembler assembler = PromptAssembler.createDefault();

        String turnContext = assembler.assembleTurnContext(PromptContext.builder()
                .memoryContext("## 相关记忆\n用户偏好中文。")
                .skillIndex("## 可用 Skills\n- web-access")
                .sessionMemory("## 证据\nWORKING_EVIDENCE")
                .build());

        assertTrue(turnContext.contains("## Turn Context"));
        assertTrue(turnContext.contains("只有最后一份有效"),
                "多份快照共存时必须显式标注取代关系，避免 LLM 把过期证据当现状");
        assertTrue(turnContext.contains("用户偏好中文"));
        assertTrue(turnContext.contains("web-access"));
        assertTrue(turnContext.contains("WORKING_EVIDENCE"));
    }

    @Test
    void turnContextIsEmptyWhenNoPerTurnContentExists() {
        PromptAssembler assembler = PromptAssembler.createDefault();

        assertEquals("", assembler.assembleTurnContext(PromptContext.empty()),
                "三段都为空时不应注入空块污染消息");
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
    void ruleContextGetsInjectedAsDedicatedSection() {
        PromptAssembler assembler = PromptAssembler.createDefault();

        String prompt = assembler.assemble(PromptMode.AGENT, PromptContext.builder()
                .ruleContext("### 强约束\n- 用简体中文\n- 不引入 SymbolSolver")
                .externalContext("## MCP Resources\n- demo://resource")
                .build());

        assertTrue(prompt.contains("## Rule Context"),
                "ruleContext 非空时应被包成 ## Rule Context 段");
        assertTrue(prompt.contains("用简体中文"));
        assertTrue(prompt.contains("不引入 SymbolSolver"));

        // KV cache 顺序：Rule Context 应在 Project Context 之前（更稳定的层在前）
        int stickyIdx = prompt.indexOf("## Rule Context");
        int projectIdx = prompt.indexOf("## Project Context");
        assertTrue(stickyIdx > 0 && projectIdx > 0);
        assertTrue(stickyIdx < projectIdx,
                "KV cache 友好布局：Sticky 应在 Project Context 之前");
    }

    @Test
    void emptyStickyDoesNotCreateEmptySection() {
        PromptAssembler assembler = PromptAssembler.createDefault();

        String prompt = assembler.assemble(PromptMode.AGENT, PromptContext.builder()
                .ruleContext("")
                .externalContext("## MCP Resources\n- demo://resource")
                .build());

        assertTrue(!prompt.contains("## Rule Context"),
                "ruleContext 为空时不应产生空段污染 prompt");
    }

    @Test
    void systemPromptIsFullyIdenticalWhenOnlyPerTurnSectionsChange() {
        // prompt cache 契约：自动前缀缓存按请求 token 前缀命中，而 system prompt 是整个请求的前缀。
        // 只要它有任何一个字节变化，其后<b>全部对话历史</b>都会失配——把易变段放到 system prompt
        // 内部尾部并不能解决问题。因此要求：轮次级内容变化时 system prompt 必须完全一致。
        PromptAssembler assembler = PromptAssembler.createDefault();

        String withA = assembler.assemble(PromptMode.AGENT, PromptContext.builder()
                .memoryContext("MEMORY_TOKEN_AAA")
                .sessionMemory("WORKING_TOKEN_AAA")
                .skillIndex("SKILL_TOKEN_AAA")
                .build());
        String withB = assembler.assemble(PromptMode.AGENT, PromptContext.builder()
                .memoryContext("MEMORY_TOKEN_BBB_totally_different")
                .sessionMemory("WORKING_TOKEN_BBB_totally_different")
                .skillIndex("SKILL_TOKEN_BBB_totally_different")
                .build());

        assertEquals(withA, withB,
                "轮次级内容变化不得改变 system prompt，否则其后全部历史前缀缓存失配");
        assertTrue(withA.contains("## Language"));
        assertTrue(withA.length() > 200,
                "静态前缀应有实质长度供 prefix cache 命中，实际: " + withA.length());
    }

    @Test
    void sessionStableSectionChangeIsAllowedToInvalidatePrefix() {
        // rule context 属会话级稳定层，它真变化时前缀失配是正确且必要的
        PromptAssembler assembler = PromptAssembler.createDefault();

        String withSticky = assembler.assemble(PromptMode.AGENT, PromptContext.builder()
                .ruleContext("RULE_AAA")
                .build());
        String withoutSticky = assembler.assemble(PromptMode.AGENT, PromptContext.empty());

        assertTrue(withSticky.contains("RULE_AAA"));
        assertFalse(withoutSticky.contains("RULE_AAA"));
    }
}
