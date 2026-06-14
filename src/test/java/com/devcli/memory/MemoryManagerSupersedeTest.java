package com.devcli.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端：通过 MemoryManager 写入路径验证同主题事实冲突消解（Fastjson → Jackson）。
 *
 * <p>带 longTermMemory 的构造只使用 contextProfile，不依赖 LlmClient，故传 null 即可，无需 mock。
 */
class MemoryManagerSupersedeTest {
    @TempDir
    Path tempDir;

    private LongTermMemory longTerm;
    private MemoryManager manager;

    @BeforeEach
    void setUp() {
        longTerm = new LongTermMemory(tempDir.toFile());
        manager = new MemoryManager(null, 4096, 128_000, longTerm);
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    void jacksonSupersedesFastjsonThroughStorePolicy() {
        manager.storeFactWithPolicy("项目使用 Fastjson 做 JSON 序列化", true);
        manager.storeFactWithPolicy("出于安全合规改用 Jackson", true);

        List<MemoryEntry> active = longTerm.getAll().stream()
                .filter(MemoryEntry::isActive)
                .filter(e -> "project.json_library".equals(e.getSubject()))
                .toList();
        assertEquals(1, active.size(), "同主题只应保留一条 active 事实");
        assertTrue(active.get(0).getContent().contains("Jackson"));

        assertTrue(manager.retrieveRelevant("Fastjson", 10).stream()
                        .noneMatch(e -> e.getContent().contains("Fastjson")),
                "被取代的 Fastjson 不应作为有效记忆被检索召回");
    }
}
