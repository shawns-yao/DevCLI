package com.paicli.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 长期记忆 - 跨对话持久化的关键信息。
 *
 * <p>职责：
 * <ol>
 *   <li>持久化用户偏好、项目事实、关键决策等</li>
 *   <li>支持关键词检索（{@link #search}）和语义检索（通过 {@link #setVectorIndex} 钩子）</li>
 *   <li>store 时基于 content hash 去重（O(1) 查 set，不再 O(N) 全表扫）</li>
 *   <li>持久化通过 {@link LongTermMemoryStore} 抽象，默认 {@link SqliteLongTermMemoryStore}</li>
 * </ol>
 *
 * <p>v2 持久化改造（消除写盘放大）：
 * <ul>
 *   <li><b>v1（旧版）</b>：每次 store/delete/clear 都全量序列化 JSON 写整个文件，1k 条 entry 单次 ~50-200ms</li>
 *   <li><b>v2（当前）</b>：单次 SQLite UPSERT/DELETE，O(1) 写盘。共用 {@code memory_vectors.db}</li>
 *   <li><b>迁移</b>：构造时检测旧 {@code long_term_memory.json}，存在则读入 → 写库 → 重命名为 .bak 备份</li>
 * </ul>
 */
public class LongTermMemory implements Memory, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(LongTermMemory.class);
    private static final String STORAGE_DIR_PROPERTY = "paicli.memory.dir";
    private static final String STORAGE_DIR_ENV = "PAICLI_MEMORY_DIR";
    private static final String LEGACY_JSON_FILE = "long_term_memory.json";
    private static final String LEGACY_JSON_BACKUP = "long_term_memory.json.bak";

    private final Map<String, MemoryEntry> entries = new ConcurrentHashMap<>();
    /** content hash 集合：去重快速查（O(1) vs 旧版 O(N) 字符串全表比对）。 */
    private final Set<Integer> contentHashes = ConcurrentHashMap.newKeySet();
    private final AtomicInteger tokenCounter = new AtomicInteger(0);
    private final LongTermMemoryStore store;
    private final boolean persistentStore;

    /** PR-C 语义检索钩子。 */
    private java.util.function.Consumer<MemoryEntry> onStoreHook = entry -> {};
    private java.util.function.Consumer<String> onDeleteHook = id -> {};
    private Runnable onClearHook = () -> {};

    /** 默认构造：用 SQLite store 写到 {@link #resolveMemoryDir()}，启动时迁移旧 JSON。 */
    public LongTermMemory() {
        this(new SqliteLongTermMemoryStore(resolveMemoryDir()), resolveMemoryDir());
    }

    /**
     * 兼容旧测试入口：传 storageDir 时仍按 SQLite 落到该目录，并在该目录下做 JSON 迁移。
     * 不再写 JSON——仅启动时把 JSON 一次性导入 SQLite。
     */
    public LongTermMemory(File storageDir) {
        this(new SqliteLongTermMemoryStore(storageDir.toPath()), storageDir.toPath());
    }

    /**
     * 测试 / 自定义场景：直接传一个 store 实现 + 迁移目录（用于 in-memory store 测试）。
     */
    public LongTermMemory(LongTermMemoryStore store, Path migrationDir) {
        this.store = store;
        this.persistentStore = store != null && store.isPersistent();
        ensureDir(migrationDir);
        migrateLegacyJsonIfNeeded(migrationDir);
        loadFromStore();
    }

    @Override
    public synchronized void store(MemoryEntry entry) {
        // Bug #13 修复：整个方法加锁，确保去重检查和插入原子性
        if (entry == null) return;
        MemoryEntry previousById = entries.get(entry.getId());
        if (previousById == null && findDuplicateContent(entry) != null) {
            return;
        }

        boolean persisted = store.upsert(entry);
        if (!persisted && persistentStore) {
            log.warn("LongTermMemory store rejected {}; entry was not added to memory", entry.getId());
            return;
        }
        if (!persisted) {
            log.warn("LongTermMemory store did not confirm persistence for {}; using in-memory fallback", entry.getId());
        }

        int hash = entry.getContent().hashCode();
        entries.put(entry.getId(), entry);
        tokenCounter.addAndGet(entry.getTokenCount() - (previousById == null ? 0 : previousById.getTokenCount()));
        if (previousById != null) {
            removeHashIfUnused(previousById.getContent().hashCode());
        }
        contentHashes.add(hash);
        try {
            onStoreHook.accept(entry);
        } catch (Exception e) {
            log.warn("LongTermMemory onStoreHook failed for {}: {}", entry.getId(), e.getMessage());
        }
    }

    @Override
    public Optional<MemoryEntry> retrieve(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    @Override
    public List<MemoryEntry> search(String query, int limit) {
        Set<String> queryTokens = MemoryQueryTokenizer.tokenize(query);
        return entries.values().stream()
                .filter(entry -> {
                    if (MemoryQueryTokenizer.matches(entry.getContent(), queryTokens)) {
                        return true;
                    }
                    return entry.getMetadata().values().stream()
                            .anyMatch(value -> MemoryQueryTokenizer.matches(value, queryTokens));
                })
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public List<MemoryEntry> getAll() {
        return new ArrayList<>(entries.values());
    }

    @Override
    public boolean delete(String id) {
        MemoryEntry toRemove = entries.get(id);
        if (toRemove == null) {
            return false;
        }
        // Bug #20 修复：先删 SQLite，成功后再删内存
        // store.delete() 返回 void，如果抛异常则表示失败
        try {
            store.delete(id);
        } catch (Exception e) {
            if (persistentStore) {
                log.warn("LongTermMemory delete failed in persistent store for {}: {}", id, e.getMessage());
                return false;
            }
            // 非持久化模式，忽略 store 错误
        }
        // SQLite 删除成功或非持久化模式，删除内存
        entries.remove(id);
        tokenCounter.addAndGet(-toRemove.getTokenCount());
        removeHashIfUnused(toRemove.getContent().hashCode());
        try {
            onDeleteHook.accept(id);
        } catch (Exception e) {
            log.warn("LongTermMemory onDeleteHook failed for {}: {}", id, e.getMessage());
        }
        return true;
    }

    private MemoryEntry findDuplicateContent(MemoryEntry entry) {
        int hash = entry.getContent().hashCode();
        if (!contentHashes.contains(hash)) {
            return null;
        }
        for (MemoryEntry existing : entries.values()) {
            if (!existing.getId().equals(entry.getId()) && existing.getContent().equals(entry.getContent())) {
                return existing;
            }
        }
        return null;
    }

    private void removeHashIfUnused(int hash) {
        boolean stillUsed = entries.values().stream()
                .anyMatch(e -> e.getContent().hashCode() == hash);
        if (!stillUsed) {
            contentHashes.remove(hash);
        }
    }

    @Override
    public void clear() {
        entries.clear();
        contentHashes.clear();
        tokenCounter.set(0);
        store.clear();
        try {
            onClearHook.run();
        } catch (Exception e) {
            log.warn("LongTermMemory onClearHook failed: {}", e.getMessage());
        }
    }

    /**
     * 注入向量索引钩子（PR-C）。Main 启动时把 EmbeddingClient + MemoryVectorStore 包成
     * 三个 Consumer/Runnable 接进来，让 store/delete/clear 同步更新向量。
     * 不调用此方法时三个钩子都是 no-op。
     */
    public void setVectorIndex(java.util.function.Consumer<MemoryEntry> onStore,
                                java.util.function.Consumer<String> onDelete,
                                Runnable onClear) {
        this.onStoreHook = onStore == null ? entry -> {} : onStore;
        this.onDeleteHook = onDelete == null ? id -> {} : onDelete;
        this.onClearHook = onClear == null ? () -> {} : onClear;
    }

    @Override
    public int getTokenCount() {
        return tokenCounter.get();
    }

    @Override
    public int size() {
        return entries.size();
    }

    /** 按类型筛选记忆 */
    public List<MemoryEntry> getByType(MemoryEntry.MemoryType type) {
        return entries.values().stream()
                .filter(entry -> entry.getType() == type)
                .collect(Collectors.toList());
    }

    /**
     * 解析 PaiCLI 记忆目录（共享给 StickyMemory / SqliteLongTermMemoryStore 等同生态组件，
     * 保持目录约定一致）。
     * 优先级：{@code -Dpaicli.memory.dir} > {@code PAICLI_MEMORY_DIR} 环境变量 > {@code ~/.paicli/memory}
     */
    public static Path resolveMemoryDir() {
        String configuredDir = System.getProperty(STORAGE_DIR_PROPERTY);
        if (configuredDir == null || configuredDir.isBlank()) {
            configuredDir = System.getenv(STORAGE_DIR_ENV);
        }
        if (configuredDir != null && !configuredDir.isBlank()) {
            return Path.of(configuredDir);
        }
        return Path.of(System.getProperty("user.home"), ".paicli", "memory");
    }

    /**
     * 启动时一次性迁移旧 JSON 到 SQLite。迁移完成后把 JSON 重命名为 .bak 保留备份，
     * 失败 / 已无 JSON 时静默跳过，主路径不阻塞。
     */
    private void migrateLegacyJsonIfNeeded(Path memoryDir) {
        Path legacyJson = memoryDir.resolve(LEGACY_JSON_FILE);
        if (!Files.exists(legacyJson)) return;
        log.info("Detected legacy long_term_memory.json; migrating to SQLite store");
        try {
            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> dataList = mapper.readValue(legacyJson.toFile(), List.class);
            int migrated = 0;
            for (Map<String, Object> data : dataList) {
                MemoryEntry entry = parseLegacyEntry(data);
                if (entry != null) {
                    if (!store.upsert(entry)) {
                        throw new IOException("SQLite store did not confirm migration for entry " + entry.getId());
                    }
                    migrated++;
                }
            }
            // 备份原 JSON（不删除——给用户一份后悔药）
            Path backup = memoryDir.resolve(LEGACY_JSON_BACKUP);
            Files.move(legacyJson, backup, StandardCopyOption.REPLACE_EXISTING);
            log.info("Migrated {} entries from {} to SQLite; original JSON backed up as {}",
                    migrated, legacyJson, backup.getFileName());
        } catch (IOException e) {
            log.warn("Migration from legacy JSON failed; keeping JSON in place: {}", e.getMessage());
        }
    }

    private void loadFromStore() {
        for (MemoryEntry entry : store.loadAll()) {
            entries.put(entry.getId(), entry);
            contentHashes.add(entry.getContent().hashCode());
            tokenCounter.addAndGet(entry.getTokenCount());
        }
        if (!entries.isEmpty()) {
            log.info("Loaded {} long-term memory entries from store", entries.size());
        }
    }

    private static void ensureDir(Path dir) {
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("Failed to create memory dir {}: {}", dir, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private MemoryEntry parseLegacyEntry(Map<String, Object> map) {
        try {
            String id = (String) map.get("id");
            String content = (String) map.get("content");
            MemoryEntry.MemoryType type = MemoryEntry.MemoryType.valueOf((String) map.get("type"));
            Instant timestamp = null;
            Object timestampObj = map.get("timestamp");
            if (timestampObj instanceof String timestampValue && !timestampValue.isBlank()) {
                timestamp = Instant.parse(timestampValue);
            }
            Map<String, String> metadata = new HashMap<>();
            Object metaObj = map.get("metadata");
            if (metaObj instanceof Map) {
                ((Map<String, Object>) metaObj).forEach((k, v) -> metadata.put(k, String.valueOf(v)));
            }
            int tokenCount = map.get("tokenCount") instanceof Number n
                    ? n.intValue()
                    : MemoryEntry.estimateTokens(content);
            return new MemoryEntry(id, content, type, timestamp, metadata, tokenCount);
        } catch (Exception e) {
            log.warn("Skip corrupted legacy entry: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 生成记忆状态摘要
     */
    public String getStatusSummary() {
        Map<MemoryEntry.MemoryType, Long> typeCounts = entries.values().stream()
                .collect(Collectors.groupingBy(MemoryEntry::getType, Collectors.counting()));

        return String.format("长期记忆: %d条 / %d tokens (事实: %d, 摘要: %d, 工具结果: %d)",
                entries.size(), tokenCounter.get(),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.FACT, 0L),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.SUMMARY, 0L),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.TOOL_RESULT, 0L));
    }

    /**
     * 关闭底层 store。Main 长进程不需要主动调（JVM 退出时连接自然释放）；
     * 主要给单元测试 / 短生命周期场景用，避免 SQLite 文件锁阻碍 @TempDir 清理。
     */
    @Override
    public void close() {
        try {
            store.close();
        } catch (Exception e) {
            log.warn("LongTermMemory.close failed: {}", e.getMessage());
        }
    }
}
