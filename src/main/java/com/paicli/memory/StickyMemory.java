package com.paicli.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Sticky 区记忆 —— 永远全量注入 system prompt 的稳定事实。
 *
 * <p>设计参考 Claude Code 的 CLAUDE.md 三层文件式记忆，并叠加用户显式 pin 的事实。
 * 两条来源协作：
 * <ol>
 *   <li><b>文件层</b>（仿 Claude Code）：三个优先级递增的 Markdown 文件
 *     <ul>
 *       <li>{@code ~/.paicli/PAICLI.md}（用户全局，所有项目共享）</li>
 *       <li>{@code <project>/PAICLI.md}（项目级，进 git，团队共享）</li>
 *       <li>{@code <project>/.paicli/PAICLI.local.md}（项目本地，gitignore，个人偏好）</li>
 *     </ul>
 *   </li>
 *   <li><b>pinned facts 层</b>：用户通过 {@code /save --pin} 或 LLM 显式 {@code pin_fact} 写入；
 *       存到 {@code ~/.paicli/memory/pinned_facts.json}，按 added_at 倒序保留</li>
 * </ol>
 *
 * <p>组装 system prompt 时调 {@link #renderForPrompt()} 拿到一段 Markdown 整体注入。
 * 整段超过 {@link #MAX_STICKY_TOKENS} 时 stderr 打 warning，但不阻断（让用户自己清理）。
 *
 * <p>线程安全：内部读写锁保护 {@code pinned} 列表；文件层是启动时一次性读，运行时不重读。
 * 调用 {@link #reloadFiles(Path)} 可主动重读文件层（比如 {@code /memory reload}）。
 */
public class StickyMemory {

    private static final Logger log = LoggerFactory.getLogger(StickyMemory.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** 文件层文件名（用户 / 项目级共用）。 */
    public static final String STICKY_FILE_NAME = "PAICLI.md";
    /** 项目本地级文件名（在 .paicli/ 子目录里）。 */
    public static final String STICKY_LOCAL_FILE_NAME = "PAICLI.local.md";
    /** Sticky 区整体软上限（token 估算）。超过这个值打 warning。 */
    public static final int MAX_STICKY_TOKENS = 8_000;

    private final Path pinnedFactsFile;
    private final List<PinnedFact> pinned;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private volatile String userMd = "";
    private volatile String projectMd = "";
    private volatile String localMd = "";

    public StickyMemory(Path memoryDir) {
        this.pinnedFactsFile = memoryDir.resolve("pinned_facts.json");
        this.pinned = new ArrayList<>();
        ensureMemoryDir(memoryDir);
        loadPinnedFromDisk();
    }

    /**
     * 加载 / 重新加载文件层的三个 Markdown 文件。
     * 启动时调一次；用户编辑文件后调 {@code /memory reload} 重新加载。
     *
     * @param projectRoot 当前项目根目录
     */
    public void reloadFiles(Path projectRoot) {
        Path home = Path.of(System.getProperty("user.home"));
        userMd = readSafely(home.resolve(".paicli").resolve(STICKY_FILE_NAME));
        if (projectRoot != null) {
            projectMd = readSafely(projectRoot.resolve(STICKY_FILE_NAME));
            localMd = readSafely(projectRoot.resolve(".paicli").resolve(STICKY_LOCAL_FILE_NAME));
        } else {
            projectMd = "";
            localMd = "";
        }
    }

    /** 用户显式 pin 一条事实。重复（content 完全相同）的直接覆盖时间戳。 */
    public PinnedFact pin(String content, String source) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("pinned fact content cannot be blank");
        }
        String normalized = content.trim();
        lock.writeLock().lock();
        try {
            // 去重：content 相同的更新 timestamp + source，不新增
            for (int i = 0; i < pinned.size(); i++) {
                if (pinned.get(i).content.equals(normalized)) {
                    PinnedFact updated = new PinnedFact(pinned.get(i).id, normalized,
                            source == null ? "user" : source, Instant.now().toEpochMilli());
                    pinned.set(i, updated);
                    savePinnedToDisk();
                    return updated;
                }
            }
            PinnedFact fact = new PinnedFact(
                    "pin-" + UUID.randomUUID().toString().substring(0, 8),
                    normalized,
                    source == null ? "user" : source,
                    Instant.now().toEpochMilli());
            pinned.add(fact);
            savePinnedToDisk();
            return fact;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 删除 pin。返回是否真的删掉了。 */
    public boolean unpin(String factId) {
        lock.writeLock().lock();
        try {
            boolean removed = pinned.removeIf(f -> f.id.equals(factId));
            if (removed) savePinnedToDisk();
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 返回当前所有 pinned facts 的快照（按 added_at 升序）。 */
    public List<PinnedFact> listPinned() {
        lock.readLock().lock();
        try {
            return List.copyOf(pinned);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 渲染整个 Sticky 区为一段可注入 system prompt 的 Markdown。
     * 空内容时返回空字符串（PromptAssembler 会跳过空段）。
     */
    public String renderForPrompt() {
        StringBuilder sb = new StringBuilder();
        if (!userMd.isBlank()) {
            sb.append("### 用户全局约定（~/.paicli/PAICLI.md）\n\n");
            sb.append(userMd.trim()).append("\n\n");
        }
        if (!projectMd.isBlank()) {
            sb.append("### 项目约定（PAICLI.md）\n\n");
            sb.append(projectMd.trim()).append("\n\n");
        }
        if (!localMd.isBlank()) {
            sb.append("### 项目本地补充（.paicli/PAICLI.local.md）\n\n");
            sb.append(localMd.trim()).append("\n\n");
        }
        List<PinnedFact> snapshot = listPinned();
        if (!snapshot.isEmpty()) {
            sb.append("### 用户偏好与稳定事实（pinned）\n\n");
            for (PinnedFact f : snapshot) {
                sb.append("- ").append(f.content).append("\n");
            }
            sb.append("\n");
        }
        String rendered = sb.toString().trim();
        // 软上限检查
        if (!rendered.isEmpty()) {
            int tokens = MemoryEntry.estimateTokens(rendered);
            if (tokens > MAX_STICKY_TOKENS) {
                log.warn("Sticky memory size {} tokens exceeds soft cap {}; consider clean up PAICLI.md or unpin facts",
                        tokens, MAX_STICKY_TOKENS);
            }
        }
        return rendered;
    }

    /** 状态摘要，给 /memory 命令用。 */
    public String getStatusSummary() {
        List<PinnedFact> snapshot = listPinned();
        int totalTokens = MemoryEntry.estimateTokens(renderForPrompt());
        String usage = totalTokens > MAX_STICKY_TOKENS
                ? String.format("%d tokens / cap %d (超限，建议清理 PAICLI.md 或 unpin facts)",
                totalTokens, MAX_STICKY_TOKENS)
                : String.format("%d tokens / cap %d", totalTokens, MAX_STICKY_TOKENS);
        return String.format(
                "📌 Sticky 区: %d pinned facts | files: %s%s%s | %s",
                snapshot.size(),
                userMd.isBlank() ? "" : "U ",
                projectMd.isBlank() ? "" : "P ",
                localMd.isBlank() ? "" : "L",
                usage);
    }

    private void ensureMemoryDir(Path memoryDir) {
        try {
            Files.createDirectories(memoryDir);
        } catch (IOException e) {
            log.warn("Failed to create memory dir {}: {}", memoryDir, e.getMessage());
        }
    }

    private String readSafely(Path file) {
        try {
            if (Files.exists(file) && Files.isRegularFile(file)) {
                return Files.readString(file, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("Failed to read sticky file {}: {}", file, e.getMessage());
        }
        return "";
    }

    private void loadPinnedFromDisk() {
        if (!Files.exists(pinnedFactsFile)) return;
        try {
            JsonNode root = MAPPER.readTree(Files.readString(pinnedFactsFile, StandardCharsets.UTF_8));
            if (!root.isArray()) return;
            lock.writeLock().lock();
            try {
                pinned.clear();
                for (JsonNode node : root) {
                    String id = node.path("id").asText("");
                    String content = node.path("content").asText("");
                    String source = node.path("source").asText("user");
                    long addedAt = node.path("added_at").asLong(0);
                    if (!id.isBlank() && !content.isBlank()) {
                        pinned.add(new PinnedFact(id, content, source, addedAt));
                    }
                }
            } finally {
                lock.writeLock().unlock();
            }
        } catch (IOException e) {
            log.warn("Failed to load pinned_facts.json: {}", e.getMessage());
        }
    }

    private void savePinnedToDisk() {
        try {
            ArrayNode arr = MAPPER.createArrayNode();
            for (PinnedFact f : pinned) {
                ObjectNode n = arr.addObject();
                n.put("id", f.id);
                n.put("content", f.content);
                n.put("source", f.source);
                n.put("added_at", f.addedAt);
            }
            Files.createDirectories(pinnedFactsFile.getParent());
            Files.writeString(pinnedFactsFile,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(arr),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to save pinned_facts.json: {}", e.getMessage());
        }
    }

    /** 一条 pinned fact 的不可变快照。 */
    public static final class PinnedFact {
        public final String id;
        public final String content;
        public final String source;
        public final long addedAt;

        PinnedFact(String id, String content, String source, long addedAt) {
            this.id = id;
            this.content = content;
            this.source = source;
            this.addedAt = addedAt;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("content", content);
            m.put("source", source);
            m.put("added_at", addedAt);
            return m;
        }
    }
}
