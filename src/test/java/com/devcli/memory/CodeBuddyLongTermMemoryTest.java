package com.devcli.memory;

import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeBuddyLongTermMemoryTest {

    @TempDir
    Path tempDir;

    @Test
    void separatesGlobalAndProjectMemoriesInsideConfiguredRoot() {
        try (LongTermMemory memory = new LongTermMemory(tempDir)) {
            LongTermMemory.SaveResult global = memory.save(
                    MemoryScope.GLOBAL, "语言偏好", "跨项目回答偏好", "user",
                    "默认使用简体中文回答。", null, true);

            Path project = tempDir.resolve("workspace");
            memory.setActiveProjectPath(project.toString());
            LongTermMemory.SaveResult projectResult = memory.save(
                    MemoryScope.PROJECT, "构建方式", "当前项目的构建命令", "project",
                    "使用 mvn.cmd -Pquick test。", null, true);

            assertEquals(LongTermMemory.SaveStatus.CREATED, global.status());
            assertEquals(LongTermMemory.SaveStatus.CREATED, projectResult.status());
            assertTrue(Files.isRegularFile(tempDir.resolve("global").resolve(global.fileName())));
            assertTrue(Files.isRegularFile(tempDir.resolve("projects")
                    .resolve(MemoryPaths.projectKey(project.toString()))
                    .resolve("memory").resolve(projectResult.fileName())));
            assertEquals(2, memory.getAll().size());
        }
    }

    @Test
    void rejectsAutomaticConflictAndLetsExplicitUpdateCreateRevision() {
        try (LongTermMemory memory = new LongTermMemory(tempDir)) {
            memory.setActiveProjectPath(tempDir.resolve("workspace").toString());
            LongTermMemory.SaveResult created = memory.save(
                    MemoryScope.PROJECT, "Java 版本", "项目 Java 版本", "project",
                    "项目使用 Java 17。", null, false);
            LongTermMemory.SaveResult conflict = memory.save(
                    MemoryScope.PROJECT, "Java 版本", "项目 Java 版本", "project",
                    "项目使用 Java 21。", null, false);

            assertEquals(LongTermMemory.SaveStatus.CREATED, created.status());
            assertEquals(LongTermMemory.SaveStatus.CONFLICT, conflict.status());
            assertEquals("项目使用 Java 17。",
                    memory.retrieve(created.fileName()).orElseThrow().getContent());

            LongTermMemory.SaveResult updated = memory.save(
                    MemoryScope.PROJECT, "Java 版本", "项目 Java 版本", "project",
                    "项目使用 Java 21。", null, true);

            assertEquals(LongTermMemory.SaveStatus.UPDATED, updated.status());
            assertEquals(2, updated.memory().revision());
            assertEquals("项目使用 Java 21。",
                    memory.retrieve(created.fileName()).orElseThrow().getContent());
        }
    }

    @Test
    void keepsExpiredMemoryForAuditButExcludesItFromRecall() {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        Path project = tempDir.resolve("workspace");
        try (LongTermMemory memory = new LongTermMemory(
                tempDir, Clock.fixed(createdAt, ZoneOffset.UTC))) {
            memory.setActiveProjectPath(project.toString());
            memory.save(MemoryScope.PROJECT, "发布窗口", "临时发布安排", "project",
                    "本周五冻结发布。", createdAt.plusSeconds(86_400), true);
        }

        try (LongTermMemory memory = new LongTermMemory(
                tempDir, Clock.fixed(createdAt.plusSeconds(172_800), ZoneOffset.UTC))) {
            memory.setActiveProjectPath(project.toString());
            assertEquals(1, memory.getAll().size());
            assertTrue(memory.getAll().get(0).isExpired());
            assertTrue(memory.candidates().isEmpty());
            assertFalse(memory.indexContext().contains("发布窗口"));
        }
    }

    @Test
    void reportsDifferentGlobalAndProjectVersionsOfSameTopic() {
        try (LongTermMemory memory = new LongTermMemory(tempDir)) {
            memory.save(MemoryScope.GLOBAL, "测试偏好", "跨项目测试习惯", "user",
                    "优先运行单元测试。", null, true);
            memory.setActiveProjectPath(tempDir.resolve("workspace").toString());
            memory.save(MemoryScope.PROJECT, "测试偏好", "本项目测试习惯", "project",
                    "优先运行 phase16 冒烟测试。", null, true);

            assertEquals(1, memory.detectScopeConflicts().size());
            assertTrue(memory.indexContext().contains("作用域冲突"));
            assertTrue(memory.indexContext().contains("项目记忆覆盖同名全局记忆"));
        }
    }

    @Test
    void importsLegacyRecordCardsWithoutDeletingTheirSource() throws Exception {
        Path legacyCard = tempDir.resolve("records").resolve("aa").resolve("legacy.md");
        Files.createDirectories(legacyCard.getParent());
        Files.writeString(legacyCard, """
                ---
                devcli-memory-format: 1
                payload: {"id":"fact-1","content":"用户偏好简体中文。","type":"PREFERENCE","subject":"语言偏好","created":"2026-01-01T00:00:00Z","revision":3,"active":true,"expiresAt":""}
                ---

                # 语言偏好
                """);

        try (LongTermMemory memory = new LongTermMemory(tempDir)) {
            assertEquals(1, memory.getAll().size());
            MemoryEntry migrated = memory.getAll().get(0);
            assertEquals("用户偏好简体中文。", migrated.getContent());
            assertEquals("global", migrated.getScope());
            assertEquals("user", migrated.getMemoryType());
            assertEquals(3, migrated.getRevision());
            assertTrue(Files.isRegularFile(tempDir.resolve("global").resolve(migrated.getId())));
        }

        assertTrue(Files.isRegularFile(legacyCard), "迁移不得删除旧版权威文件");
    }

    @Test
    void boundsSelectorManifestBeforeCallingTheModel() {
        Instant now = Instant.parse("2026-09-21T00:00:00Z");
        List<TopicMemory> candidates = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            candidates.add(TopicMemory.of(tempDir.resolve("topic-" + i + ".md"),
                    "主题" + i, "候选记忆用途与适用场景说明".repeat(20), "project",
                    "", now, null, 1, now));
        }

        String manifest = LongTermMemorySelector.formatManifest(candidates);

        assertTrue(MemoryEntry.estimateTokens(manifest) <= 4_096,
                "选择器清单必须有独立输入预算，实际 token="
                        + MemoryEntry.estimateTokens(manifest));
        assertTrue(manifest.lines().count() < candidates.size(),
                "超量候选应按完整条目截断，不能把全部 200 条发送给模型");
    }

    @Test
    void selectorCannotReturnCandidateOmittedFromBoundedManifest() throws Exception {
        Instant now = Instant.parse("2026-09-21T00:00:00Z");
        List<TopicMemory> candidates = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            TopicMemory candidate = TopicMemory.of(tempDir.resolve("topic-" + i + ".md"),
                    "主题" + i, "候选记忆用途与适用场景说明".repeat(20), "project",
                    "正文 " + i, now, null, 1, now);
            candidates.add(candidate);
        }
        TopicMemory omitted = candidates.get(candidates.size() - 1);
        Files.writeString(omitted.file(), omitted.render());

        List<TopicMemory> selected = LongTermMemorySelector.select(
                new FixedSelectionClient(omitted.fileName()), "任意问题", candidates);

        assertTrue(selected.isEmpty(),
                "模型不得通过猜测文件名选中未发送到有界清单中的候选");
    }

    @Test
    void completedLegacyMigrationDoesNotReparseSourceOnEveryConstruction() throws Exception {
        Path legacyCard = tempDir.resolve("records").resolve("aa").resolve("legacy.md");
        Files.createDirectories(legacyCard.getParent());
        Files.writeString(legacyCard, """
                ---
                payload: {"id":"fact-1","content":"用户偏好简体中文。","type":"PREFERENCE","subject":"语言偏好","created":"2026-01-01T00:00:00Z","revision":1,"active":true,"expiresAt":""}
                ---
                """);

        try (LongTermMemory first = new LongTermMemory(tempDir)) {
            assertEquals(1, first.size());
        }
        Files.writeString(legacyCard, "已完成迁移后不再是可解析的旧卡片");

        try (LongTermMemory second = new LongTermMemory(tempDir)) {
            assertEquals(1, second.size(), "已迁移目标仍应可读");
            assertFalse(second.getStatusSummary().contains("旧记忆迁移失败"),
                    "一次性迁移完成后不应在每次构造时重新解析保留的旧源文件");
        }
    }

    @Test
    void compatibilityStoreRejectsUnknownScopeInsteadOfFallingBack() {
        MemoryEntry entry = new MemoryEntry("scope.md", "只应写入明确作用域",
                MemoryEntry.MemoryType.FACT,
                Map.of(MemoryEntry.META_SCOPE, "company",
                        MemoryEntry.META_NAME, "非法作用域",
                        MemoryEntry.META_MEMORY_TYPE, "reference"),
                8);

        try (LongTermMemory memory = new LongTermMemory(tempDir)) {
            memory.store(entry);

            assertEquals(0, memory.size());
            assertFalse(Files.exists(tempDir.resolve("global/scope.md")));
        }
    }

    private static final class FixedSelectionClient implements LlmClient {
        private final String fileName;

        private FixedSelectionClient(String fileName) {
            this.fileName = fileName;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return new ChatResponse("assistant",
                    "{\"selected_memories\":[\"" + fileName + "\"]}",
                    List.of(), 0, 0);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                 StreamListener listener) {
            return chat(messages, tools);
        }

        @Override
        public String getModelName() {
            return "fixed-selection";
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }
}
