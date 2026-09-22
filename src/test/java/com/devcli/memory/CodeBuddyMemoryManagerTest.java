package com.devcli.memory;

import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeBuddyMemoryManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultsToProjectButAllowsExplicitGlobalMemory() {
        LongTermMemory longTermMemory = new LongTermMemory(tempDir.resolve("memory"));
        Path project = tempDir.resolve("workspace");
        try (MemoryManager manager = new MemoryManager(null, 4_096, 128_000, longTermMemory)) {
            manager.setActiveProjectScope(project.toString());

            MemoryManager.StoreResult projectResult = manager.storeTopic(
                    "", "构建方式", "当前项目构建方式", "project",
                    "使用 mvn.cmd test。", 30, true);
            MemoryManager.StoreResult globalResult = manager.storeTopic(
                    "global", "回答语言", "跨项目回答偏好", "user",
                    "默认使用简体中文。", null, true);

            assertTrue(projectResult.stored());
            assertTrue(globalResult.stored());
            assertTrue(Files.isRegularFile(tempDir.resolve("memory/projects")
                    .resolve(MemoryPaths.projectKey(project.toString()))
                    .resolve("memory").resolve(projectResult.id())));
            assertTrue(Files.isRegularFile(tempDir.resolve("memory/global")
                    .resolve(globalResult.id())));
            assertTrue(longTermMemory.retrieve(projectResult.id()).orElseThrow()
                    .getExpiresAt().isPresent());
        }
    }

    @Test
    void rejectsSensitiveContentAndUnknownScopeWithoutWriting() {
        LongTermMemory longTermMemory = new LongTermMemory(tempDir.resolve("memory"));
        try (MemoryManager manager = new MemoryManager(null, 4_096, 128_000, longTermMemory)) {
            MemoryManager.StoreResult sensitive = manager.storeTopic(
                    "global", "密钥", "不应保存", "user",
                    "api_key=sk-test-secret-value", null, true);
            MemoryManager.StoreResult unknownScope = manager.storeTopic(
                    "company", "偏好", "不明确作用域", "user",
                    "用简体中文。", null, true);

            assertFalse(sensitive.stored());
            assertTrue(sensitive.message().contains("敏感信息"));
            assertFalse(unknownScope.stored());
            assertEquals(0, longTermMemory.size());
        }
    }

    /**
     * 索引自身上限（{@code MemoryIndex.MAX_BYTES}）比本轮记忆预算大一个数量级，
     * 所以注入前必须按预算裁剪——否则「记忆预算」这个配置项对索引完全不生效。
     */
    @Test
    void keepsInjectedMemoryContextWithinBudgetEvenWhenIndexIsMuchLarger() {
        LongTermMemory longTermMemory = new LongTermMemory(tempDir.resolve("memory"));
        Path project = tempDir.resolve("workspace");
        try (MemoryManager manager = new MemoryManager(
                new SelectAllClient(), 4_096, 128_000, longTermMemory)) {
            manager.setActiveProjectScope(project.toString());
            for (int i = 0; i < 50; i++) {
                manager.storeTopic("", "主题" + i, "第 " + i + " 条主题记忆的用途与适用场景说明",
                        "project", "第 " + i + " 条正文内容。", null, true);
            }

            int budget = manager.getContextProfile().memoryContextTokens();
            // 前置事实：未裁剪的索引确实超出预算。否则本用例即使不修也能通过，证伪不了任何东西。
            assertTrue(MemoryEntry.estimateTokens(longTermMemory.indexContext()) > budget,
                    "样本应让索引超出本轮预算 " + budget + "，否则用例无区分度");

            String context = manager.buildContextForQuery("主题3", budget);

            assertFalse(context.isBlank());
            assertTrue(MemoryEntry.estimateTokens(context) <= budget,
                    "注入的记忆上下文不得超过本轮预算，实际 "
                            + MemoryEntry.estimateTokens(context) + " > " + budget);
            assertTrue(context.contains("长期记忆索引已按本轮记忆预算截断"));
        }
    }

    /**
     * 全局目录跨项目共享，项目会话里执行「清空长期记忆」不应连带抹掉它。
     */
    @Test
    void clearingWithoutScopeInProjectSessionKeepsGlobalMemories() {
        LongTermMemory longTermMemory = new LongTermMemory(tempDir.resolve("memory"));
        Path project = tempDir.resolve("workspace");
        try (MemoryManager manager = new MemoryManager(null, 4_096, 128_000, longTermMemory)) {
            manager.setActiveProjectScope(project.toString());
            MemoryManager.StoreResult global = manager.storeTopic(
                    "global", "回答语言", "跨项目回答偏好", "user",
                    "默认使用简体中文。", null, true);
            MemoryManager.StoreResult local = manager.storeTopic(
                    "", "构建方式", "当前项目构建方式", "project",
                    "使用 mvn.cmd test。", null, true);
            Path globalFile = tempDir.resolve("memory/global").resolve(global.id());

            String message = manager.clearLongTerm();

            assertTrue(message.contains("已清空项目长期记忆"), message);
            assertFalse(Files.exists(projectMemoryFile(project, local.id())),
                    "默认清空应清掉当前写入作用域（项目）的记忆");
            assertTrue(Files.isRegularFile(globalFile),
                    "项目会话里清空长期记忆不得抹掉跨项目共享的全局记忆");
            assertTrue(message.contains("/memory clear global"),
                    "结果应提示如何显式清空全局记忆");

            String globalMessage = manager.clearLongTerm("global");

            assertTrue(globalMessage.contains("已清空全局长期记忆"), globalMessage);
            assertFalse(Files.exists(globalFile));

            assertTrue(manager.clearLongTerm("company").contains("可用 global 或 project"),
                    "未知作用域应被拒绝并给出可用取值");
        }
    }

    /** 未绑定项目时没有项目目录可清，默认退化为清全局。 */
    @Test
    void clearingWithoutProjectFallsBackToGlobal() {
        LongTermMemory longTermMemory = new LongTermMemory(tempDir.resolve("memory"));
        try (MemoryManager manager = new MemoryManager(null, 4_096, 128_000, longTermMemory)) {
            MemoryManager.StoreResult global = manager.storeTopic(
                    "global", "回答语言", "跨项目回答偏好", "user", "默认使用简体中文。", null, true);

            assertTrue(manager.clearLongTerm("project").contains("未绑定项目"),
                    "没有项目可清时应明确说明，而不是静默成功");

            String message = manager.clearLongTerm();

            assertTrue(message.contains("已清空全局长期记忆"), message);
            assertFalse(Files.exists(tempDir.resolve("memory/global").resolve(global.id())));
        }
    }

    private Path projectMemoryFile(Path project, String id) {
        return tempDir.resolve("memory/projects")
                .resolve(MemoryPaths.projectKey(project.toString()))
                .resolve("memory").resolve(id);
    }

    /** 把清单里的每个候选都选回来，用来单独考察预算裁剪，不受选择器质量影响。 */
    private static final class SelectAllClient implements LlmClient {

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            String manifest = messages.stream()
                    .filter(message -> "user".equals(message.role()))
                    .map(Message::content)
                    .reduce("", (previous, current) -> current);
            List<String> names = new ArrayList<>();
            for (String line : manifest.split("\n")) {
                String text = line.strip();
                if (!text.startsWith("- [")) continue;
                int close = text.indexOf(']');
                if (close < 0) continue;
                String rest = text.substring(close + 1).strip();
                int paren = rest.indexOf(" (");
                if (paren <= 0) continue;
                names.add(rest.substring(0, paren).strip());
            }
            String json = "{\"selected_memories\":[" + names.stream()
                    .map(name -> "\"" + name + "\"")
                    .collect(Collectors.joining(",")) + "]}";
            return new ChatResponse("assistant", json, List.of(), 0, 0);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            return chat(messages, tools);
        }

        @Override
        public String getModelName() {
            return "stub-select-all";
        }

        @Override
        public String getProviderName() {
            return "stub";
        }
    }
}
