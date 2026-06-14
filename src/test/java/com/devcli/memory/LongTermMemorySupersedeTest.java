package com.devcli.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 主题冲突消解（supersede）行为测试：同 subject 新事实取代旧事实，旧事实软删除保留审计。
 */
class LongTermMemorySupersedeTest {
    @TempDir
    Path tempDir;

    private LongTermMemory memory;

    @BeforeEach
    void setUp() {
        memory = new LongTermMemory(tempDir.toFile());
    }

    @AfterEach
    void tearDown() {
        if (memory != null) {
            memory.close();
        }
    }

    private MemoryEntry fact(String id, String content, String subject) {
        return new MemoryEntry(id, content, MemoryEntry.MemoryType.FACT, Instant.now(),
                Map.of("source", "test"), 10, subject, true, "");
    }

    @Test
    void storeWithSubjectMarksOldSameSubjectInactive() {
        memory.storeWithSubject(fact("f1", "项目用 Fastjson", "project.json_library"));
        memory.storeWithSubject(fact("f2", "改用 Jackson", "project.json_library"));

        // 两条都还在（旧条软删除保留审计），但只有新条 active
        assertEquals(2, memory.size());
        assertFalse(memory.retrieve("f1").orElseThrow().isActive(), "旧事实应被标记为 inactive");
        assertTrue(memory.retrieve("f2").orElseThrow().isActive(), "新事实应为 active");
        assertEquals("f2", memory.retrieve("f1").orElseThrow().getSupersededBy());
    }

    @Test
    void supersededEntryNotReturnedBySearch() {
        memory.storeWithSubject(fact("f1", "项目用 Fastjson", "project.json_library"));
        memory.storeWithSubject(fact("f2", "改用 Jackson", "project.json_library"));

        assertTrue(memory.search("Fastjson", 10).isEmpty(), "被取代的 Fastjson 不应再被检索召回");

        List<MemoryEntry> jacksonHits = memory.search("Jackson", 10);
        assertEquals(1, jacksonHits.size());
        assertEquals("f2", jacksonHits.get(0).getId());
    }

    @Test
    void differentSubjectsDoNotSupersedeEachOther() {
        memory.storeWithSubject(fact("f1", "项目用 Fastjson", "project.json_library"));
        memory.storeWithSubject(fact("f2", "默认测试命令 mvn test", "project.default_test_command"));

        assertTrue(memory.retrieve("f1").orElseThrow().isActive());
        assertTrue(memory.retrieve("f2").orElseThrow().isActive());
    }

    @Test
    void blankSubjectFallsBackToAppendWithoutSupersede() {
        memory.storeWithSubject(fact("f1", "内容A", ""));
        memory.storeWithSubject(fact("f2", "内容B", ""));

        assertTrue(memory.retrieve("f1").orElseThrow().isActive());
        assertTrue(memory.retrieve("f2").orElseThrow().isActive());
    }

    @Test
    void sameContentSupersedeStillKeepsNewActive() {
        // 去重×supersede 边界：先 supersede 再 store，新条与旧条 content 相同也不应被去重跳过
        memory.storeWithSubject(fact("f1", "项目用 Jackson", "project.json_library"));
        memory.storeWithSubject(fact("f2", "项目用 Jackson", "project.json_library"));

        assertFalse(memory.retrieve("f1").orElseThrow().isActive());
        assertTrue(memory.retrieve("f2").orElseThrow().isActive(),
                "同内容覆盖时新条必须保留为 active，不能被旧 inactive 条的 content hash 去重");
        assertEquals(1, memory.search("Jackson", 10).size());
    }

    @Test
    void supersedeStatePersistsAcrossReload() {
        memory.storeWithSubject(fact("f1", "项目用 Fastjson", "project.json_library"));
        memory.storeWithSubject(fact("f2", "改用 Jackson", "project.json_library"));
        memory.close();
        memory = null;

        try (LongTermMemory reloaded = new LongTermMemory(tempDir.toFile())) {
            assertEquals(2, reloaded.size());
            assertFalse(reloaded.retrieve("f1").orElseThrow().isActive());
            assertEquals("f2", reloaded.retrieve("f1").orElseThrow().getSupersededBy());
            assertTrue(reloaded.search("Fastjson", 10).isEmpty());
        }
    }
}
