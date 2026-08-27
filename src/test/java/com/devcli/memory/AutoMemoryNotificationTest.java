package com.devcli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 长期记忆写入的可见性与晋升协议边界。
 *
 * <p>原始用户消息不能直接落库；显式保存仍必须回传可见事件。
 */
class AutoMemoryNotificationTest {

    @TempDir
    Path tempDir;

    private MemoryManager newManager() {
        return new MemoryManager(null, 4096, 128_000,
                new LongTermMemory(tempDir.resolve("store").toFile()));
    }

    @Test
    void userMessageDoesNotBypassTaskPromotionProtocol() {
        List<MemoryManager.AutoSavedFact> events = new ArrayList<>();
        try (MemoryManager manager = newManager()) {
            manager.setAutoSaveListener(events::add);

            manager.addUserMessage("别再自动格式化整个文件");

            assertTrue(events.isEmpty(), "任务结束前不能绕过 Curator 直接落库");
            assertTrue(manager.getLongTermMemory().getAll().isEmpty());
        }
    }

    @Test
    void doesNotNotifyWhenPolicySkipsTheFact() {
        List<MemoryManager.AutoSavedFact> events = new ArrayList<>();
        try (MemoryManager manager = newManager()) {
            manager.setAutoSaveListener(events::add);

            manager.addUserMessage("帮我看一下这个报错");

            assertTrue(events.isEmpty(), "未落库就不应产生提示，避免噪音: " + events);
        }
    }

    @Test
    void explicitSaveAlsoReportsThroughTheSameChannel() {
        List<MemoryManager.AutoSavedFact> events = new ArrayList<>();
        try (MemoryManager manager = newManager()) {
            manager.setAutoSaveListener(events::add);

            MemoryManager.StoreResult result = manager.storeFactWithPolicy("默认用简体中文回答", true);

            assertTrue(result.stored());
            assertEquals(1, events.size(), "显式保存也走同一条可见通道，口径统一");
            assertEquals("explicit", events.get(0).source());
        }
    }

    @Test
    void deletesSingleEntryById() {
        try (MemoryManager manager = newManager()) {
            MemoryManager.StoreResult result = manager.storeFactWithPolicy("默认用简体中文回答", true);
            assertTrue(result.stored());
            String id = result.id();
            assertFalse(id.isBlank(), "写入结果必须带 id，提示里才能给出可执行的删除命令");

            assertTrue(manager.forgetLongTermMemory(id), "应能按 id 删除单条长期记忆");
            assertTrue(manager.getLongTermMemory().getAll().stream()
                    .noneMatch(entry -> entry.getId().equals(id)));
            assertFalse(manager.forgetLongTermMemory(id), "重复删除应返回 false");
        }
    }
}
