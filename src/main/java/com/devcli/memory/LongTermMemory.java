package com.devcli.memory;

import com.devcli.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * CodeBuddy 风格的长期记忆：全局/项目目录、主题 Markdown、有界索引与按需召回。
 *
 * <p>Markdown 文件是唯一权威内容。过期、修订与类型都保存在 frontmatter；
 * 过期记忆仍可审计，但不进入索引、候选或检索。
 */
public class LongTermMemory implements Memory, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemory.class);
    private static final int MAX_SLUG_LENGTH = 48;
    private static final String FALLBACK_SLUG_PREFIX = "memory";

    private final Path memoryRoot;
    private final Clock clock;
    private final LegacyMemoryImporter.ImportResult legacyImportResult;
    private final Set<String> surfacedInSession = ConcurrentHashMap.newKeySet();
    private volatile String activeProjectPath = "";
    private volatile LegacyMemoryImporter.ProjectImportResult projectImportResult =
            LegacyMemoryImporter.ProjectImportResult.EMPTY;

    public enum SaveStatus {
        CREATED,
        UPDATED,
        UNCHANGED,
        CONFLICT,
        REJECTED
    }

    public record SaveResult(SaveStatus status, String fileName, String message,
                             TopicMemory memory) {
        public SaveResult {
            status = status == null ? SaveStatus.REJECTED : status;
            fileName = fileName == null ? "" : fileName;
            message = message == null ? "" : message;
        }

        public boolean stored() {
            return status == SaveStatus.CREATED || status == SaveStatus.UPDATED
                    || status == SaveStatus.UNCHANGED;
        }
    }

    /** 同名主题在项目与全局作用域中表达不同事实。 */
    public record ScopeConflict(String fileName, TopicMemory globalMemory,
                                TopicMemory projectMemory) {
    }

    public LongTermMemory() {
        this(MemoryPaths.memoryRoot(), Clock.systemUTC());
    }

    public LongTermMemory(File storageDir) {
        this(storageDir == null ? MemoryPaths.memoryRoot() : storageDir.toPath(),
                Clock.systemUTC());
    }

    public LongTermMemory(Path memoryRoot) {
        this(memoryRoot, Clock.systemUTC());
    }

    public LongTermMemory(Path memoryRoot, Clock clock) {
        this.memoryRoot = (memoryRoot == null ? MemoryPaths.memoryRoot() : memoryRoot)
                .toAbsolutePath().normalize();
        this.clock = clock == null ? Clock.systemUTC() : clock;
        ensureDir(this.memoryRoot);
        this.legacyImportResult = LegacyMemoryImporter.importRecords(
                this.memoryRoot, globalDir(), this.clock);
        if (legacyImportResult.imported() > 0) rebuildIndex(globalDir());
    }

    public static Path resolveMemoryDir() {
        return MemoryPaths.memoryRoot();
    }

    public void setActiveProjectPath(String projectPath) {
        String requested = projectPath == null ? "" : projectPath.trim();
        if (requested.isBlank()) {
            activeProjectPath = "";
            projectImportResult = LegacyMemoryImporter.ProjectImportResult.EMPTY;
            return;
        }
        Path projectRoot = MemoryPaths.resolveProjectRoot(requested);
        activeProjectPath = projectRoot.toString();
        Path source = MemoryPaths.pathProjectMemoryDir(memoryRoot, requested);
        projectImportResult = LegacyMemoryImporter.importProjectTopics(source, projectDir());
    }

    public String activeProjectPath() {
        return activeProjectPath;
    }

    public Path globalDir() {
        return MemoryPaths.globalMemoryDir(memoryRoot);
    }

    public Path projectDir() {
        return activeProjectPath.isBlank() ? null
                : MemoryPaths.pathProjectMemoryDir(memoryRoot, activeProjectPath);
    }

    public List<Path> visibleDirs() {
        List<Path> dirs = new ArrayList<>();
        Path project = projectDir();
        if (project != null) dirs.add(project);
        dirs.add(globalDir());
        return List.copyOf(dirs);
    }

    public MemoryScope defaultScope() {
        return projectDir() == null ? MemoryScope.GLOBAL : MemoryScope.PROJECT;
    }

    public Path writeTargetDir() {
        return defaultScope() == MemoryScope.PROJECT ? projectDir() : globalDir();
    }

    public String indexContext() {
        rebuildVisibleIndexes();
        StringBuilder builder = new StringBuilder();
        for (Path dir : visibleDirs()) {
            String index = MemoryIndex.read(dir);
            if (index.isBlank()) continue;
            if (builder.length() > 0) builder.append("\n\n");
            builder.append("### ").append(scopeLabel(dir)).append("记忆（")
                    .append(dir).append("）\n\n").append(index);
        }
        List<ScopeConflict> conflicts = detectScopeConflicts();
        if (!conflicts.isEmpty()) {
            if (builder.length() > 0) builder.append("\n\n");
            builder.append("### 作用域冲突\n\n");
            for (ScopeConflict conflict : conflicts) {
                builder.append("- 项目记忆覆盖同名全局记忆：")
                        .append(conflict.fileName())
                        .append("；召回使用项目版本，两份原文仍保留用于审计。\n");
            }
        }
        return builder.toString().strip();
    }

    /**
     * 索引段落，按本轮分配的 token 预算裁剪。
     *
     * <p>{@link #indexContext()} 只受索引自身上限（200 行 / 25000 字节）约束，
     * 那个上限可以远大于本轮记忆预算（默认 500–5000 tokens），因此注入前必须再过预算。
     */
    public String indexContext(int maxTokens) {
        return MemoryIndex.fitToTokens(indexContext(), maxTokens);
    }

    /** 可召回候选：过期项被过滤，项目同名项覆盖全局项。 */
    public List<TopicMemory> candidates() {
        Map<String, TopicMemory> byName = new LinkedHashMap<>();
        for (Path dir : visibleDirs()) {
            for (TopicMemory memory : activeHeads(dir)) {
                byName.putIfAbsent(memory.fileName(), memory);
            }
        }
        return List.copyOf(byName.values());
    }

    public List<TopicMemory> select(String query, LlmClient llmClient) {
        if (!LongTermMemorySelector.enabled()) return List.of();
        return LongTermMemorySelector.select(llmClient, query, candidates());
    }

    /**
     * 渲染选中记忆的全文段落，附新鲜度标注。
     *
     * <p>按整条取舍：放不下的记忆**整条跳过**，不切断正文——半句话的事实比没有事实更危险。
     * 只有真正写进段落的条目才计入「本会话已注入」，没放下的下一轮还有机会。
     *
     * @param maxTokens 本段可用的 token 预算
     */
    public String renderSelected(List<TopicMemory> selected, int maxTokens) {
        if (selected == null || selected.isEmpty() || maxTokens <= 0) return "";
        StringBuilder builder = new StringBuilder();
        int used = 0;
        for (TopicMemory memory : selected) {
            if (memory == null || memory.isExpired(clock.instant())) continue;
            String key = memory.file().toAbsolutePath().normalize().toString();
            if (surfacedInSession.contains(key)) continue;
            String block = renderOne(memory);
            int blockTokens = MemoryEntry.estimateTokens(block);
            if (used + blockTokens > maxTokens) continue;
            if (builder.length() > 0) builder.append("\n\n");
            builder.append(block);
            used += blockTokens;
            surfacedInSession.add(key);
        }
        return builder.toString().strip();
    }

    private String renderOne(TopicMemory memory) {
        StringBuilder builder = new StringBuilder();
        builder.append("#### ").append(memory.name().isBlank() ? memory.fileName() : memory.name())
                .append('\n');
        String freshness = MemoryFreshness.freshnessText(
                MemoryFreshness.ageDays(memory.updatedAt(), clock));
        if (!freshness.isBlank()) builder.append("> ").append(freshness).append('\n');
        builder.append("> 作用域：").append(scopeLabel(memory.file().getParent()))
                .append("；修订：r").append(memory.revision()).append('\n');
        builder.append("> 文件：").append(memory.file()).append('\n');
        builder.append(memory.body().isBlank() ? "（正文为空）" : memory.body());
        return builder.toString();
    }

    public int surfacedCount() {
        return surfacedInSession.size();
    }

    /**
     * 保存主题记忆。自动写入遇到同作用域不同内容时返回 CONFLICT，
     * 只有用户显式写入才能覆盖并把修订号加一。
     */
    public synchronized SaveResult save(MemoryScope scope, String name, String description,
                                        String type, String content, Instant expiresAt,
                                        boolean explicit) {
        if (content == null || content.isBlank()) {
            return rejected("记忆内容为空");
        }
        if (scope == null) {
            return rejected("记忆作用域为空");
        }
        if (!TopicMemory.isSupportedType(type)) {
            return rejected("不支持的记忆类型: " + type);
        }
        Instant now = clock.instant();
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            return rejected("有效期必须晚于当前时间");
        }
        Path dir = scope == MemoryScope.PROJECT ? projectDir() : globalDir();
        if (dir == null) {
            return rejected("写入项目记忆前必须绑定项目路径");
        }
        Path file = dir.resolve(slug(name));
        TopicMemory existing = TopicMemory.read(file);
        TopicMemory next;
        if (existing == null) {
            next = TopicMemory.of(file, name, description, type, content, now,
                    expiresAt, 1, now);
        } else {
            next = TopicMemory.of(file, name, description, type, content, now,
                    expiresAt, existing.revision() + 1, existing.createdAt());
            if (samePersistentContent(existing, next)) {
                return new SaveResult(SaveStatus.UNCHANGED, existing.fileName(),
                        "同主题记忆已是相同内容", existing);
            }
            if (!explicit) {
                return new SaveResult(SaveStatus.CONFLICT, existing.fileName(),
                        "自动写入与现有记忆冲突，已保留原内容；需要用户显式保存才能更新",
                        existing);
            }
        }
        try {
            writeAtomically(file, next.render());
            TopicMemory stored = TopicMemory.read(file);
            rebuildIndex(dir);
            SaveStatus status = existing == null ? SaveStatus.CREATED : SaveStatus.UPDATED;
            return new SaveResult(status, file.getFileName().toString(),
                    status == SaveStatus.CREATED ? "已创建长期记忆" : "已更新长期记忆",
                    stored == null ? next : stored);
        } catch (IOException e) {
            log.warn("写入长期记忆失败 {}: {}", file, e.getMessage());
            return rejected("写入长期记忆失败: " + e.getMessage());
        }
    }

    /** 兼容旧入口：按当前作用域显式保存为 reference。 */
    public String save(String name, String description, String content) {
        SaveResult result = save(defaultScope(), name, description,
                defaultScope() == MemoryScope.PROJECT ? "project" : "reference",
                content, null, true);
        return result.stored() ? result.fileName() : "";
    }

    @Override
    public boolean delete(String id) {
        if (id == null || id.isBlank()) return false;
        String target = id.trim();
        if (!target.toLowerCase(Locale.ROOT).endsWith(".md")) target += ".md";
        for (Path dir : visibleDirs()) {
            Path file = dir.resolve(target);
            if (!Files.isRegularFile(file)) continue;
            try {
                Files.delete(file);
                surfacedInSession.remove(file.toAbsolutePath().normalize().toString());
                rebuildIndex(dir);
                return true;
            } catch (IOException e) {
                log.warn("删除长期记忆失败 {}: {}", file, e.getMessage());
                return false;
            }
        }
        return false;
    }

    /**
     * 清空单个作用域目录，返回删除的文件数。
     *
     * <p>只动该作用域，不连带其他作用域——全局目录跨项目共享，
     * 在项目会话里执行「清空长期记忆」不应该抹掉其他项目也能看到的全局记忆。
     */
    public int clearScope(MemoryScope scope) {
        if (scope == null) return 0;
        Path dir = scope == MemoryScope.PROJECT ? projectDir() : globalDir();
        if (dir == null || !Files.isDirectory(dir)) return 0;
        int removed = 0;
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(".md"))
                    .toList()) {
                try {
                    Files.delete(file);
                    surfacedInSession.remove(file.toAbsolutePath().normalize().toString());
                    removed++;
                } catch (IOException e) {
                    log.warn("清空长期记忆时删除失败 {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("清空长期记忆失败 {}: {}", dir, e.getMessage());
        }
        return removed;
    }

    /** 清空全部可见作用域（项目 + 全局）。 */
    public int clearAndCount() {
        int removed = clearScope(MemoryScope.GLOBAL);
        if (projectDir() != null) {
            removed += clearScope(MemoryScope.PROJECT);
        }
        return removed;
    }

    @Override
    public void clear() {
        clearAndCount();
    }

    @Override
    public void store(MemoryEntry entry) {
        if (entry == null) return;
        MemoryScope scope;
        if (entry.getScope().isBlank()) {
            scope = defaultScope();
        } else {
            try {
                scope = MemoryScope.of(entry.getScope());
            } catch (IllegalArgumentException e) {
                // 与显式写入路径同口径：未知作用域拒绝，不回落为默认作用域。
                // 静默回落会把拼错的作用域变成一条归属错误、且用户完全看不见的记忆。
                log.warn("拒绝写入未知作用域的记忆 {}: {}", entry.getScope(), e.getMessage());
                return;
            }
        }
        save(scope, entry.getName(), entry.getDescription(), entry.getMemoryType(),
                entry.getContent(), entry.getExpiresAt().orElse(null), true);
    }

    @Override
    public Optional<MemoryEntry> retrieve(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        String target = id.trim();
        for (Path dir : visibleDirs()) {
            Path direct = dir.resolve(target.toLowerCase(Locale.ROOT).endsWith(".md")
                    ? target : target + ".md");
            TopicMemory memory = TopicMemory.read(direct);
            if (memory != null) return Optional.of(toEntry(memory, dir));
            for (TopicMemory candidate : MemoryScanner.scanHeads(dir)) {
                if (candidate.fileName().equals(target) || candidate.name().equals(target)) {
                    TopicMemory full = TopicMemory.read(candidate.file());
                    if (full != null) return Optional.of(toEntry(full, dir));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public List<MemoryEntry> search(String query, int limit) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<MemoryEntry> result = new ArrayList<>();
        for (TopicMemory head : candidates()) {
            if (needle.isEmpty()
                    || head.name().toLowerCase(Locale.ROOT).contains(needle)
                    || head.description().toLowerCase(Locale.ROOT).contains(needle)) {
                TopicMemory full = TopicMemory.read(head.file());
                if (full != null && !full.isExpired(clock.instant())) {
                    result.add(toEntry(full, head.file().getParent()));
                }
            }
            if (limit > 0 && result.size() >= limit) break;
        }
        return result;
    }

    /** 审计视图保留过期项和跨作用域同名项。 */
    @Override
    public List<MemoryEntry> getAll() {
        List<MemoryEntry> result = new ArrayList<>();
        for (Path dir : visibleDirs()) {
            for (TopicMemory head : MemoryScanner.scanHeads(dir)) {
                TopicMemory full = TopicMemory.read(head.file());
                if (full != null) result.add(toEntry(full, dir));
            }
        }
        result.sort(Comparator.comparing(MemoryEntry::getTimestamp).reversed());
        return result;
    }

    @Override
    public int getTokenCount() {
        return getAll().stream().mapToInt(MemoryEntry::getTokenCount).sum();
    }

    @Override
    public int size() {
        return getAll().size();
    }

    public boolean isPersistent() {
        Path dir = writeTargetDir();
        try {
            Files.createDirectories(dir);
            return Files.isWritable(dir);
        } catch (IOException e) {
            return false;
        }
    }

    public String getStatusSummary() {
        int globalCount = MemoryScanner.scan(globalDir()).size();
        Path project = projectDir();
        int projectCount = project == null ? 0 : MemoryScanner.scan(project).size();
        long expired = getAll().stream().filter(MemoryEntry::isExpired).count();
        String migration = legacyImportResult.imported() > 0
                ? " · 本次迁移旧记忆 " + legacyImportResult.imported() + " 条"
                : legacyImportResult.failed() > 0
                ? " · 旧记忆迁移失败 " + legacyImportResult.failed() + " 条"
                : "";
        if (projectImportResult.imported() > 0 || projectImportResult.conflicts() > 0
                || projectImportResult.failed() > 0) {
            migration += " · 旧项目记忆迁入 " + projectImportResult.imported()
                    + " 条 / 冲突保留 " + projectImportResult.conflicts()
                    + " 条 / 迁移失败 " + projectImportResult.failed() + " 条";
        }
        return "长期记忆: %d 条（全局 %d / 项目 %d / 已过期 %d / 作用域冲突 %d）· 根目录 %s · 本会话已注入全文 %d 条%s"
                .formatted(globalCount + projectCount, globalCount, projectCount, expired,
                        detectScopeConflicts().size(), memoryRoot, surfacedInSession.size(), migration);
    }

    public Path rootDir() {
        return memoryRoot;
    }

    /** 重建全局与已存在项目目录的索引，返回处理的作用域数。 */
    public int rebuildIndexes() {
        List<Path> dirs = allScopeDirs();
        for (Path dir : dirs) rebuildIndex(dir);
        return dirs.size();
    }

    public String maintenanceReport() {
        List<MemoryEntry> entries = getAll();
        long expired = entries.stream().filter(MemoryEntry::isExpired).count();
        return "长期记忆检查：共 " + entries.size() + " 条，已过期 " + expired
                + " 条，作用域冲突 " + detectScopeConflicts().size()
                + " 条。执行 /memory organize apply 可按当前文件重建索引。";
    }

    public String exportMarkdown() {
        StringBuilder out = new StringBuilder("# 长期记忆审计快照\n\n");
        out.append("导出时间：").append(clock.instant()).append("\n\n");
        for (MemoryEntry entry : getAll()) {
            out.append("## ").append(entry.getName()).append("\n\n")
                    .append("- 文件：").append(entry.getId()).append('\n')
                    .append("- 作用域：").append(entry.getScope()).append('\n')
                    .append("- 类型：").append(entry.getMemoryType()).append('\n')
                    .append("- 修订：r").append(entry.getRevision()).append('\n')
                    .append("- 状态：").append(entry.isExpired() ? "已过期" : "有效").append("\n\n")
                    .append(entry.getContent()).append("\n\n");
        }
        return out.toString();
    }

    public List<ScopeConflict> detectScopeConflicts() {
        Path project = projectDir();
        if (project == null) return List.of();
        Map<String, TopicMemory> globals = new LinkedHashMap<>();
        for (TopicMemory head : activeHeads(globalDir())) {
            TopicMemory full = TopicMemory.read(head.file());
            if (full != null) globals.put(full.fileName(), full);
        }
        List<ScopeConflict> conflicts = new ArrayList<>();
        for (TopicMemory head : activeHeads(project)) {
            TopicMemory global = globals.get(head.fileName());
            if (global == null) continue;
            TopicMemory local = TopicMemory.read(head.file());
            if (local != null && !samePersistentContent(global, local)) {
                conflicts.add(new ScopeConflict(head.fileName(), global, local));
            }
        }
        return List.copyOf(conflicts);
    }

    @Override
    public void close() {
        // Markdown 存储没有长连接资源。
    }

    private List<TopicMemory> activeHeads(Path dir) {
        List<TopicMemory> memories = new ArrayList<>();
        for (TopicMemory head : MemoryScanner.scanHeads(dir)) {
            if (!head.isExpired(clock.instant())) memories.add(head);
        }
        memories.sort(Comparator.comparing(TopicMemory::updatedAt).reversed());
        return memories;
    }

    private void rebuildVisibleIndexes() {
        for (Path dir : visibleDirs()) rebuildIndex(dir);
    }

    private void rebuildIndex(Path dir) {
        MemoryIndex.write(dir, activeHeads(dir));
    }

    private List<Path> allScopeDirs() {
        LinkedHashSet<Path> dirs = new LinkedHashSet<>();
        dirs.add(globalDir());
        Path projectsRoot = memoryRoot.resolve(MemoryPaths.PROJECTS_DIR_NAME);
        if (Files.isDirectory(projectsRoot)) {
            try (Stream<Path> stream = Files.list(projectsRoot)) {
                stream.filter(Files::isDirectory)
                        .map(path -> path.resolve(MemoryPaths.PROJECT_MEMORY_DIR_NAME))
                        .filter(Files::isDirectory)
                        .forEach(dirs::add);
            } catch (IOException e) {
                log.warn("扫描项目记忆目录失败 {}: {}", projectsRoot, e.getMessage());
            }
        }
        Path active = projectDir();
        if (active != null && Files.isDirectory(active)) dirs.add(active);
        return List.copyOf(dirs);
    }

    private MemoryEntry toEntry(TopicMemory memory, Path dir) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(MemoryEntry.META_NAME, memory.name());
        metadata.put(MemoryEntry.META_DESCRIPTION, memory.description());
        metadata.put(MemoryEntry.META_SCOPE, scopeLabel(dir));
        metadata.put(MemoryEntry.META_MEMORY_TYPE, memory.type());
        metadata.put(MemoryEntry.META_CREATED_AT, memory.createdAt().toString());
        metadata.put(MemoryEntry.META_UPDATED_AT, memory.updatedAt().toString());
        metadata.put(MemoryEntry.META_REVISION, Integer.toString(memory.revision()));
        metadata.put(MemoryEntry.META_EXPIRED,
                Boolean.toString(memory.isExpired(clock.instant())));
        if (memory.expiresAt() != null) {
            metadata.put(MemoryEntry.META_EXPIRES_AT, memory.expiresAt().toString());
        }
        return new MemoryEntry(memory.fileName(), memory.body(), MemoryEntry.MemoryType.FACT,
                memory.updatedAt(), metadata, MemoryEntry.estimateTokens(memory.body()));
    }

    private String scopeLabel(Path dir) {
        Path project = projectDir();
        return project != null && project.equals(dir) ? "project" : "global";
    }

    private static boolean samePersistentContent(TopicMemory left, TopicMemory right) {
        if (left == null || right == null) return false;
        return left.name().equals(right.name())
                && left.description().equals(right.description())
                && left.type().equals(right.type())
                && left.body().equals(right.body())
                && java.util.Objects.equals(left.expiresAt(), right.expiresAt());
    }

    private static SaveResult rejected(String message) {
        return new SaveResult(SaveStatus.REJECTED, "", message, null);
    }

    static String slug(String name) {
        String base = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        String normalized = base.replaceAll("[^\\p{IsHan}a-z0-9]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        if (normalized.isEmpty()) normalized = FALLBACK_SLUG_PREFIX;
        if (normalized.length() > MAX_SLUG_LENGTH) {
            normalized = normalized.substring(0, MAX_SLUG_LENGTH);
        }
        return normalized + ".md";
    }

    private static void writeAtomically(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), ".memory-topic-", ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("创建记忆目录失败 {}: {}", dir, e.getMessage());
        }
    }

    void resetSessionSurfaced() {
        surfacedInSession.clear();
    }

    Set<String> candidateNames() {
        Set<String> names = new LinkedHashSet<>();
        candidates().forEach(memory -> names.add(memory.fileName()));
        return names;
    }
}
