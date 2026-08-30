package com.devcli.memory;

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
import java.util.Comparator;
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
    private static final String STORAGE_DIR_PROPERTY = "devcli.memory.dir";
    private static final String STORAGE_DIR_ENV = "DEVCLI_MEMORY_DIR";
    private static final String LEGACY_JSON_FILE = "long_term_memory.json";
    private static final String LEGACY_JSON_BACKUP = "long_term_memory.json.bak";

    /** 全量轻量目录；正文和完整证据只进入有界 Hot Working Set。 */
    private final Map<String, MemoryEntry> entries = new ConcurrentHashMap<>();
    /** memory id -> 正文 SHA-256，用于无需加载正文的 O(1) 去重预筛。 */
    private final Map<String, String> contentDigests = new ConcurrentHashMap<>();
    private final Set<String> contentDigestValues = ConcurrentHashMap.newKeySet();
    private final AtomicInteger tokenCounter = new AtomicInteger(0);
    private final LongTermMemoryStore store;
    private final boolean persistentStore;
    private final Path storageDir;
    private final MemoryHotCache hotCache;

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
        this(store, migrationDir, new MemoryHotCache());
    }

    LongTermMemory(LongTermMemoryStore store, Path migrationDir, MemoryHotCache hotCache) {
        this.store = store;
        this.persistentStore = store != null && store.isPersistent();
        this.storageDir = migrationDir.toAbsolutePath().normalize();
        this.hotCache = hotCache == null ? new MemoryHotCache() : hotCache;
        ensureDir(migrationDir);
        migrateLegacyJsonIfNeeded(migrationDir);
        loadFromStore();
    }

    @Override
    public synchronized void store(MemoryEntry entry) {
        // Bug #13 修复：整个方法加锁，确保去重检查和插入原子性
        if (entry == null) return;
        archiveExpired();
        entry = MemoryLifecyclePolicy.initializeExpiration(entry, Instant.now());
        MemoryEntry previousById = hydrate(entry.getId()).orElse(null);
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

        putLoaded(entry);
        tokenCounter.addAndGet(entry.getTokenCount() - (previousById == null ? 0 : previousById.getTokenCount()));
        try {
            onStoreHook.accept(entry);
        } catch (Exception e) {
            log.warn("LongTermMemory onStoreHook failed for {}: {}", entry.getId(), e.getMessage());
        }
    }

    /**
     * 带主题的写入：同 {@code subject} 的现存 active 事实先被标记为失效（{@code supersededBy}
     * 指向新条），再写入新事实，实现"同主题新事实覆盖旧事实"的冲突消解。旧条软删除保留审计，
     * 检索侧（{@link #search} / MemoryRetriever）按 active 过滤后不再召回。
     *
     * <p>顺序关键——先 supersede 旧条再 {@link #store} 新条：否则当新旧 content 相同时，
     * 新条会被 {@link #findDuplicateContent} 判为重复而跳过，导致该主题失去 active 条。
     *
     * <p>{@code entry.subject} 为空时退化为普通 {@link #store}（不参与主题归并）。
     */
    public synchronized void storeWithSubject(MemoryEntry entry) {
        if (entry == null) return;
        Map<String, String> metadata = new HashMap<>(entry.getMetadata());
        metadata.put(MemoryWriteProtocol.META_SUBJECT_SOURCE,
                MemoryWriteProtocol.SUBJECT_SOURCE_EXPLICIT);
        storeManaged(entry.copy(entry.getSubject(), entry.isActive(), entry.getSupersededBy(),
                entry.getRevision(), entry.getExpiresAt(), metadata, entry.getEvidence()));
    }

    private synchronized void storePrepared(MemoryEntry entry, List<String> explicitTargetIds) {
        if (entry == null) return;
        MemoryWriteProtocol.Prepared prepared = MemoryWriteProtocol.prepare(entry);
        entry = MemoryLifecyclePolicy.initializeExpiration(prepared.entry(), Instant.now());
        archiveExpired();
        List<MemoryEntry> existingEntries = getAll();
        boolean hasExplicitTargets = explicitTargetIds != null && !explicitTargetIds.isEmpty();
        if (!hasExplicitTargets && MemoryConflictDetector.findEquivalent(entry, existingEntries).isPresent()) {
            return;
        }
        Optional<MemoryConflictDetector.Conflict> conflict =
                MemoryConflictDetector.detect(entry, existingEntries);
        String subject = entry.getSubject();
        if ((subject == null || subject.isBlank()) && conflict.isPresent()) {
            subject = conflict.get().subject();
        }
        if ((subject == null || subject.isBlank())
                && explicitTargetIds != null && !explicitTargetIds.isEmpty()) {
            MemoryEntry target = hydrate(explicitTargetIds.getFirst()).orElse(null);
            subject = target == null || target.getSubject().isBlank()
                    ? "memory:" + explicitTargetIds.getFirst()
                    : target.getSubject();
            entry = entry.copy(subject, entry.isActive(), entry.getSupersededBy(),
                    entry.getRevision(), entry.getExpiresAt(), entry.getMetadata(), entry.getEvidence());
            prepared = MemoryWriteProtocol.prepare(entry);
            entry = prepared.entry();
        }
        if (subject == null || subject.isBlank()) {
            store(entry);
            return;
        }

        List<MemoryEntry> supersededTargets = new ArrayList<>();
        int nextRevision = 1;
        for (MemoryEntry existing : existingEntries) {
            String existingSubject = existing.getSubject().isBlank()
                    ? MemoryConflictDetector.inferSubject(existing.getContent())
                    : existing.getSubject();
            MemoryWriteProtocol.StableKey existingKey = MemoryWriteProtocol.stableKey(existing);
            boolean sameStableKey = prepared.stableKey() != null
                    && prepared.stableKey().equals(existingKey);
            boolean explicitTarget = explicitTargetIds != null
                    && explicitTargetIds.contains(existing.getId());
            if (sameStableKey || explicitTarget) {
                nextRevision = Math.max(nextRevision, existing.getRevision() + 1);
            }
            if (existing.isActive()
                    && (sameStableKey || explicitTarget)
                    && !existing.getId().equals(entry.getId())) {
                hydrate(existing.getId()).ifPresent(supersededTargets::add);
            }
        }

        Map<String, String> metadata = new HashMap<>(entry.getMetadata());
        MemoryEvidence evidence = entry.getEvidence();
        if (conflict.isPresent()) {
            MemoryConflictDetector.Conflict value = conflict.get();
            metadata.put("conflict_detected", "true");
            metadata.put("conflict_with", value.existingId());
            evidence = evidence.withConflict(value.existingId());
        }
        MemoryEntry managedEntry = entry.copy(
                subject, true, "", nextRevision, entry.getExpiresAt(), metadata, evidence);

        List<MemoryEntry> revisionWrites = new ArrayList<>();
        for (MemoryEntry old : supersededTargets) {
            revisionWrites.add(asSuperseded(old, managedEntry.getId()));
        }
        revisionWrites.add(managedEntry);
        boolean persisted = store.upsertAll(revisionWrites);
        if (!persisted && persistentStore) {
            log.warn("Atomic memory revision rejected for stable key {}; no in-memory state changed",
                    managedEntry.getStableKey());
            return;
        }
        if (!persisted) {
            log.warn("Memory store did not confirm atomic revision; using in-memory fallback for {}",
                    managedEntry.getId());
        }
        for (MemoryEntry persistedEntry : revisionWrites) {
            MemoryEntry previous = hydrate(persistedEntry.getId()).orElse(null);
            putLoaded(persistedEntry);
            tokenCounter.addAndGet(persistedEntry.getTokenCount()
                    - (previous == null ? 0 : previous.getTokenCount()));
        }
        try {
            onStoreHook.accept(managedEntry);
        } catch (Exception e) {
            log.warn("LongTermMemory onStoreHook failed for {}: {}",
                    managedEntry.getId(), e.getMessage());
        }
    }

    public synchronized void storeManaged(MemoryEntry entry) {
        if (entry == null) return;
        MemoryWriteProtocol.Prepared prepared = MemoryWriteProtocol.prepare(entry);
        if (prepared.state() == MemoryWriteProtocol.StructureState.PENDING_CONFIRMATION
                || prepared.entry().getEvidence().reviewState() == MemoryEvidence.ReviewState.REJECTED) {
            storeInactiveCandidate(prepared.entry());
            return;
        }
        storePrepared(entry, List.of());
    }

    private void storeInactiveCandidate(MemoryEntry entry) {
        entry = MemoryLifecyclePolicy.initializeExpiration(entry, Instant.now());
        MemoryEntry candidate = entry.copy(entry.getSubject(), false, "",
                entry.getRevision(), entry.getExpiresAt(), entry.getMetadata(), entry.getEvidence());
        boolean persisted = store.upsert(candidate);
        if (!persisted && persistentStore) {
            log.warn("Memory candidate persistence rejected for {}", candidate.getId());
            return;
        }
        MemoryEntry previous = hydrate(candidate.getId()).orElse(null);
        putLoaded(candidate);
        tokenCounter.addAndGet(candidate.getTokenCount()
                - (previous == null ? 0 : previous.getTokenCount()));
    }

    /**
     * 用当前工具观察产生的负向事实显式取代指定旧记忆。
     * 与普通主题写入不同，这里按 id 处理旧版无 subject 的条目，并保留完整软删除审计链。
     */
    public synchronized boolean storeObservationInvalidation(MemoryEntry entry, List<String> targetIds) {
        if (entry == null || targetIds == null || targetIds.isEmpty()) {
            return false;
        }
        archiveExpired();
        List<MemoryEntry> targets = targetIds.stream()
                .map(this::hydrate)
                .flatMap(Optional::stream)
                .filter(MemoryEntry::isRecallable)
                .toList();
        if (targets.isEmpty()) {
            return false;
        }

        Map<String, String> metadata = new HashMap<>(entry.getMetadata());
        String conflictIds = targets.stream().map(MemoryEntry::getId)
                .collect(java.util.stream.Collectors.joining(","));
        metadata.put("conflict_detected", "true");
        metadata.put("conflict_with", targets.getFirst().getId());
        metadata.put("invalidates_memory_ids", conflictIds);
        MemoryEvidence evidence = entry.getEvidence();
        for (MemoryEntry target : targets) {
            evidence = evidence.withConflict(target.getId());
        }
        MemoryEntry managedEntry = entry.copy(entry.getSubject(), true, "", entry.getRevision(),
                entry.getExpiresAt(), Map.copyOf(metadata), evidence);
        return storeSuperseding(managedEntry, targets.stream().map(MemoryEntry::getId).toList());
    }

    /**
     * 将新条目作为指定旧条目的显式替代版本原子写入。调用方必须已经确定替代关系；
     * 普通记忆写入仍只能依赖结构化稳定键自动 supersede。
     */
    public synchronized boolean storeSuperseding(MemoryEntry entry, List<String> targetIds) {
        if (entry == null || targetIds == null || targetIds.isEmpty()) return false;
        archiveExpired();
        List<String> effectiveTargets = targetIds.stream()
                .distinct()
                .filter(id -> {
                    MemoryEntry target = hydrate(id).orElse(null);
                    return target != null && target.isRecallable()
                            && !target.getId().equals(entry.getId());
                })
                .toList();
        if (effectiveTargets.isEmpty()) return false;
        storePrepared(entry, effectiveTargets);
        MemoryEntry stored = hydrate(entry.getId()).orElse(null);
        if (stored == null || !stored.isRecallable()) return false;
        return effectiveTargets.stream().allMatch(id -> {
            MemoryEntry target = hydrate(id).orElse(null);
            return target != null && !target.isActive()
                    && entry.getId().equals(target.getSupersededBy());
        });
    }

    /** 基于旧条派生一个被取代的失效副本：仅改 active=false 与 supersededBy，其余保持不变。 */
    private static MemoryEntry asSuperseded(MemoryEntry old, String newId) {
        return old.copy(old.getSubject(), false, newId, old.getRevision(),
                old.getExpiresAt(), old.getMetadata());
    }

    @Override
    public synchronized Optional<MemoryEntry> retrieve(String id) {
        archiveExpired();
        return hydrate(id);
    }

    @Override
    public synchronized List<MemoryEntry> search(String query, int limit) {
        List<MemoryEntry> results = searchCandidates(query, limit);
        for (int i = results.size() - 1; i >= 0; i--) {
            hotCache.put(results.get(i));
        }
        return results;
    }

    synchronized List<MemoryEntry> searchCandidates(String query, int limit) {
        archiveExpired();
        Set<String> queryTokens = MemoryQueryTokenizer.tokenize(query);
        int expandedLimit = limit > Integer.MAX_VALUE / 10 ? Integer.MAX_VALUE : limit * 10;
        int candidateLimit = Math.max(limit, Math.min(200, expandedLimit));
        return store.searchCandidateIds(query, candidateLimit).stream()
                .map(this::loadCandidate)
                .flatMap(Optional::stream)
                .filter(MemoryEntry::isRecallable)
                .filter(entry -> {
                    if (MemoryQueryTokenizer.matches(entry.getContent(), queryTokens)) {
                        return true;
                    }
                    return entry.getMetadata().values().stream()
                            .anyMatch(value -> MemoryQueryTokenizer.matches(value, queryTokens));
                })
                .sorted(Comparator.comparingDouble(
                        (MemoryEntry entry) -> keywordMatchScore(entry, query, queryTokens)).reversed())
                .limit(Math.max(0, limit))
                .collect(Collectors.toList());
    }

    @Override
    public synchronized List<MemoryEntry> getAll() {
        archiveExpired();
        return entries.keySet().stream()
                .map(this::hydrate)
                .flatMap(Optional::stream)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public synchronized boolean updateReviewState(
            String id, MemoryEvidence.ReviewState reviewState) {
        if (id == null || id.isBlank() || reviewState == null) return false;
        archiveExpired();
        MemoryEntry existing = hydrate(id).orElse(null);
        if (existing == null) return false;
        if (existing.getEvidence().reviewState() == reviewState) return true;
        MemoryEntry updated = existing.withEvidence(existing.getEvidence().withReviewState(reviewState));
        if (reviewState == MemoryEvidence.ReviewState.REVIEWED) {
            storePrepared(updated, List.of());
            MemoryEntry activated = hydrate(id).orElse(null);
            return activated != null && activated.isActive() && activated.isRecallable();
        }
        updated = MemoryWriteProtocol.prepare(updated).entry();
        updated = updated.copy(updated.getSubject(), false, "", updated.getRevision(),
                updated.getExpiresAt(), updated.getMetadata(), updated.getEvidence());
        boolean persisted = store.upsert(updated);
        if (!persisted && persistentStore) {
            log.warn("Failed to persist review state {} for {}", reviewState, id);
            return false;
        }
        putLoaded(updated);
        return true;
    }

    @Override
    public synchronized boolean delete(String id) {
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
        hotCache.remove(id);
        removeContentDigest(id);
        tokenCounter.addAndGet(-toRemove.getTokenCount());
        try {
            onDeleteHook.accept(id);
        } catch (Exception e) {
            log.warn("LongTermMemory onDeleteHook failed for {}: {}", id, e.getMessage());
        }
        return true;
    }

    private void archiveExpired() {
        Instant now = Instant.now();
        List<MemoryEntry> expiredEntries = entries.values().stream()
                .filter(MemoryEntry::isActive)
                .filter(entry -> entry.isExpired(now))
                .map(entry -> hydrate(entry.getId()).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
        for (MemoryEntry entry : expiredEntries) {
            Map<String, String> metadata = new HashMap<>(entry.getMetadata());
            metadata.put("lifecycle_state", "ARCHIVED");
            metadata.put("archived_at", now.toString());
            MemoryEntry archived = entry.copy(entry.getSubject(), false, entry.getSupersededBy(),
                    entry.getRevision(), entry.getExpiresAt(), Map.copyOf(metadata));
            if (store.upsert(archived) || !persistentStore) {
                putLoaded(archived);
            } else {
                log.warn("Failed to archive expired memory {}", entry.getId());
            }
        }
    }

    /** 记录真正进入 Turn Context 的条目；调用方必须传入本轮去重后的 id。 */
    public synchronized boolean recordRecalled(java.util.Collection<String> ids, Instant recalledAt) {
        if (ids == null || ids.isEmpty()) return true;
        Instant effectiveTime = recalledAt == null ? Instant.now() : recalledAt;
        List<MemoryEntry> updated = ids.stream().filter(java.util.Objects::nonNull).distinct()
                .map(this::hydrate)
                .flatMap(Optional::stream)
                .filter(MemoryEntry::isRecallable)
                .map(entry -> MemoryLifecyclePolicy.recordRecall(entry, effectiveTime))
                .toList();
        if (updated.isEmpty()) return true;
        boolean persisted = store.recordRecall(updated);
        if (!persisted && persistentStore) return false;
        updated.forEach(this::putLoaded);
        return true;
    }

    /** 记录强验证信号并按验证次数分级延长有效期；普通召回不得调用。 */
    public synchronized boolean recordValidated(String id, Instant validatedAt) {
        if (id == null || id.isBlank()) return false;
        archiveExpired();
        MemoryEntry existing = hydrate(id).orElse(null);
        if (existing == null || !existing.isRecallable()) return false;
        MemoryEntry updated = MemoryLifecyclePolicy.recordValidated(existing, validatedAt);
        boolean persisted = store.upsert(updated);
        if (!persisted && persistentStore) return false;
        putLoaded(updated);
        return true;
    }

    private MemoryEntry findDuplicateContent(MemoryEntry entry) {
        String digest = MemoryMarkdownRepository.contentDigest(entry.getContent());
        if (!contentDigestValues.contains(digest)) {
            return null;
        }
        for (Map.Entry<String, String> digestEntry : contentDigests.entrySet()) {
            if (!digest.equals(digestEntry.getValue())) continue;
            MemoryEntry existing = hydrate(digestEntry.getKey()).orElse(null);
            // 仅比对可召回条目：被 supersede 或已拒绝的旧条不应阻止同内容重新写入
            if (existing != null && existing.isRecallable()
                    && !existing.getId().equals(entry.getId())
                    && MemoryWriteProtocol.scopeOf(existing).equals(MemoryWriteProtocol.scopeOf(entry))
                    && existing.getContent().equals(entry.getContent())) {
                return existing;
            }
        }
        return null;
    }

    @Override
    public synchronized void clear() {
        entries.clear();
        contentDigests.clear();
        contentDigestValues.clear();
        hotCache.clear();
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
    public synchronized int getTokenCount() {
        archiveExpired();
        return tokenCounter.get();
    }

    @Override
    public synchronized int size() {
        archiveExpired();
        return entries.size();
    }

    /**
     * 当前长期记忆是否能跨进程持久化。SQLite 初始化失败时底层 store 降级为 no-op，
     * 此时返回 false——写入仅留在内存，进程退出即丢。调用方据此给出诚实提示，
     * 不把降级写入伪装成已持久化。
     */
    public boolean isPersistent() {
        return persistentStore;
    }

    Path storageDir() {
        return storageDir;
    }

    /** 按类型筛选记忆 */
    public synchronized List<MemoryEntry> getByType(MemoryEntry.MemoryType type) {
        archiveExpired();
        return entries.values().stream()
                .filter(entry -> entry.getType() == type)
                .map(entry -> hydrate(entry.getId()).orElse(null))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 解析 DevCLI 记忆目录（共享给 RuleContext / SqliteLongTermMemoryStore 等相邻 Module，
     * 保持目录约定一致）。
     * 优先级：{@code -Ddevcli.memory.dir} > {@code DEVCLI_MEMORY_DIR} 环境变量 > {@code ~/.devcli/memory}
     */
    public static Path resolveMemoryDir() {
        String configuredDir = System.getProperty(STORAGE_DIR_PROPERTY);
        if (configuredDir == null || configuredDir.isBlank()) {
            configuredDir = System.getenv(STORAGE_DIR_ENV);
        }
        if (configuredDir != null && !configuredDir.isBlank()) {
            return Path.of(configuredDir);
        }
        return Path.of(System.getProperty("user.home"), ".devcli", "memory");
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
        List<MemoryEntry> catalog = store.loadCatalog();
        for (MemoryEntry entry : catalog) {
            entries.put(entry.getId(), catalogEntry(entry));
            tokenCounter.addAndGet(entry.getTokenCount());
        }
        contentDigests.putAll(store.loadContentDigests());
        contentDigestValues.addAll(contentDigests.values());
        catalog.stream()
                .sorted(Comparator
                        .comparing((MemoryEntry entry) -> !Boolean.parseBoolean(
                                entry.getMetadata().getOrDefault("pinned", "false")))
                        .thenComparing(Comparator.comparingDouble(
                                MemoryHotCache::importanceOf).reversed())
                        .thenComparing(Comparator.comparingLong(
                                MemoryEntry::getValidatedUseCount).reversed())
                        .thenComparing(Comparator.comparingLong(
                                MemoryEntry::getRecallCount).reversed())
                        .thenComparing(entry -> entry.getLastRecalledAt() == null
                                ? Instant.EPOCH : entry.getLastRecalledAt(), Comparator.reverseOrder()))
                .limit(hotCache.capacity())
                .map(MemoryEntry::getId)
                .map(store::loadById)
                .flatMap(Optional::stream)
                .forEach(hotCache::preload);
        if (!entries.isEmpty()) {
            log.info("Loaded {} long-term memory catalog entries; preheated {} Markdown memories",
                    entries.size(), hotCache.size());
        }
    }

    private Optional<MemoryEntry> hydrate(String id) {
        if (id == null || id.isBlank() || !entries.containsKey(id)) return Optional.empty();
        Optional<MemoryEntry> cached = hotCache.get(id);
        if (cached.isPresent()) return cached;
        Optional<MemoryEntry> loaded = store.loadById(id);
        loaded.ifPresent(hotCache::put);
        return loaded;
    }

    Optional<MemoryEntry> loadCandidate(String id) {
        if (id == null || id.isBlank() || !entries.containsKey(id)) return Optional.empty();
        Optional<MemoryEntry> cached = hotCache.peek(id);
        return cached.isPresent() ? cached : store.loadById(id);
    }

    void promoteToHot(MemoryEntry entry) {
        hotCache.put(entry);
    }

    private static double keywordMatchScore(MemoryEntry entry, String query, Set<String> queryTokens) {
        if (entry == null) return 0;
        String content = entry.getContent().toLowerCase(java.util.Locale.ROOT);
        String normalizedQuery = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT);
        double score = !normalizedQuery.isBlank() && content.contains(normalizedQuery) ? 100 : 0;
        for (String token : queryTokens) {
            if (!token.isBlank() && content.contains(token.toLowerCase(java.util.Locale.ROOT))) score++;
        }
        return score;
    }

    private void putLoaded(MemoryEntry entry) {
        entries.put(entry.getId(), catalogEntry(entry));
        String digest = MemoryMarkdownRepository.contentDigest(entry.getContent());
        String previous = contentDigests.put(entry.getId(), digest);
        contentDigestValues.add(digest);
        if (previous != null && !previous.equals(digest)
                && !contentDigests.containsValue(previous)) {
            contentDigestValues.remove(previous);
        }
        hotCache.put(entry);
    }

    private void removeContentDigest(String id) {
        String removed = contentDigests.remove(id);
        if (removed != null && !contentDigests.containsValue(removed)) {
            contentDigestValues.remove(removed);
        }
    }

    private static MemoryEntry catalogEntry(MemoryEntry entry) {
        MemoryEvidence evidence = entry.getEvidence();
        MemoryEvidence catalogEvidence = new MemoryEvidence(evidence.confidence(), "", "",
                evidence.reviewState(), evidence.conflictsWith());
        return new MemoryEntry(entry.getId(), "", entry.getType(), entry.getTimestamp(),
                entry.getMetadata(), entry.getTokenCount(), entry.getSubject(), entry.isActive(),
                entry.getSupersededBy(), entry.getSchemaVersion(), entry.getRevision(),
                entry.getExpiresAt(), catalogEvidence, entry.getRecallCount(),
                entry.getLastRecalledAt(), entry.getKind(), entry.getValidatedUseCount(),
                entry.getLastValidatedAt());
    }

    int hotCacheSize() {
        return hotCache.size();
    }

    boolean isHot(String id) {
        return hotCache.contains(id);
    }

    Set<String> hotIds() {
        return hotCache.ids();
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
        long activeCount = entries.values().stream().filter(MemoryEntry::isActive).count();
        long supersededCount = entries.size() - activeCount;
        long conflictAuditCount = entries.values().stream()
                .filter(entry -> "true".equals(entry.getMetadata().get("conflict_detected")))
                .count();
        long currentStateConflictCount = entries.values().stream()
                .filter(entry -> "CURRENT_STATE_CONFLICT".equals(
                        entry.getMetadata().get("reason_code")))
                .count();

        return String.format("长期记忆: %d条 / %d tokens "
                        + "(事实: %d, 摘要: %d, 工具结果: %d, 有效: %d, 已取代: %d, 冲突审计: %d, 状态推翻: %d)",
                entries.size(), tokenCounter.get(),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.FACT, 0L),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.SUMMARY, 0L),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.TOOL_RESULT, 0L),
                activeCount, supersededCount, conflictAuditCount, currentStateConflictCount);
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
