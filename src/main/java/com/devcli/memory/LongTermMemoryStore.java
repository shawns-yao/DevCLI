package com.devcli.memory;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 长期记忆持久化抽象。
 *
 * <p>把 LongTermMemory 跟具体存储介质解耦，方便：
 * <ul>
 *   <li>测试用 in-memory 实现，避开 SQLite IO</li>
 *   <li>未来切换底层（PostgreSQL / 远程 KV / S3 等）不用改 LongTermMemory 业务逻辑</li>
 * </ul>
 *
 * <p>当前实现：{@link SqliteLongTermMemoryStore}（复用 {@code memory_vectors.db}，加 memory_facts 表）。
 *
 * <p>语义约定：
 * <ul>
 *   <li>{@link #upsert(MemoryEntry)} 按 id 覆盖；返回是否确认写入成功，不做去重判断（去重由 LongTermMemory 在内存层面用 hash set 完成）</li>
 *   <li>{@link #delete(String)} 不存在的 id 静默成功，不抛异常</li>
 *   <li>{@link #clear()} 清空全部条目</li>
 *   <li>{@link #loadAll()} 启动时一次性读出全部条目；返回的 list 顺序由实现决定（建议 created_at 升序）</li>
 *   <li>所有方法对失败模式应当尽力降级：磁盘错误日志警告，返回空列表 / no-op，不阻塞 DevCLI 启动</li>
 * </ul>
 */
public interface LongTermMemoryStore extends AutoCloseable {

    /** 启动时一次性全量加载。 */
    List<MemoryEntry> loadAll();

    /** 启动目录加载。持久化实现可只返回不含正文的轻量条目。 */
    default List<MemoryEntry> loadCatalog() {
        return loadAll();
    }

    /** 按 id 回读完整正文；默认实现兼容旧的测试 Store。 */
    default Optional<MemoryEntry> loadById(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        return loadAll().stream().filter(entry -> id.equals(entry.getId())).findFirst();
    }

    /** 返回关键词候选 id；结果只是候选集合，最终相关性仍由 MemoryRetriever 计算。 */
    default List<String> searchCandidateIds(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) return List.of();
        var tokens = MemoryQueryTokenizer.tokenize(query);
        return loadAll().stream()
                .filter(entry -> MemoryQueryTokenizer.matches(entry.getContent(), tokens))
                .limit(limit)
                .map(MemoryEntry::getId)
                .toList();
    }

    /** 启动时加载正文去重摘要，不读取 Markdown 正文。 */
    default Map<String, String> loadContentDigests() {
        return loadAll().stream().collect(java.util.stream.Collectors.toMap(
                MemoryEntry::getId,
                entry -> MemoryMarkdownRepository.contentDigest(entry.getContent()),
                (left, right) -> left));
    }

    /** 按 id 写入或覆盖一条 entry。返回 false 表示底层 store 未确认持久化成功。 */
    boolean upsert(MemoryEntry entry);

    /** 同一修订链的失效旧条与 ACTIVE 新条必须作为一个原子批次提交。 */
    default boolean upsertAll(List<MemoryEntry> entries) {
        if (entries == null || entries.isEmpty()) return true;
        for (MemoryEntry entry : entries) {
            if (!upsert(entry)) return false;
        }
        return true;
    }

    /** 原子记录一批实际注入；同一轮调用方必须先去重。 */
    default boolean recordRecall(List<MemoryEntry> recalledEntries) {
        return upsertAll(recalledEntries);
    }

    /**
     * 当前 store 是否能确认跨进程持久化。
     * SQLite 初始化失败时会降级为 no-op store，此时返回 false，LongTermMemory 可选择
     * 明确进入 in-memory fallback；测试 store 默认视为持久化实现。
     */
    default boolean isPersistent() {
        return true;
    }

    /** 按 id 删除；不存在时静默。 */
    void delete(String id);

    /** 清空全部条目。 */
    void clear();

    /** 关闭底层资源；测试 / 多实例切换时调。 */
    @Override
    void close();
}
