package com.devcli.memory;

import com.devcli.policy.SensitiveDataRedactor;
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
 * 强约束规则上下文。它不是记忆层，只负责每轮必须执行的规则。
 *
 * <p>规则来源包括三层文件和用户显式添加的规则：
 * 两条来源协作：
 * <ol>
 *   <li><b>文件层</b>（仿 Claude Code）：三个优先级递增的 Markdown 文件
 *     <ul>
 *       <li>{@code ~/.devcli/DEVCLI.md}（用户全局，所有项目共享）</li>
 *       <li>{@code <project>/DEVCLI.md}（项目级，进 git，团队共享）</li>
 *       <li>{@code <project>/.devcli/DEVCLI.local.md}（项目本地，gitignore，个人偏好）</li>
 *     </ul>
 *   </li>
 *   <li><b>显式规则层</b>：用户通过 {@code /rule add} 写入，存到
 *       {@code ~/.devcli/memory/rules.json}</li>
 * </ol>
 *
 * <p>组装 system prompt 时调 {@link #renderForPrompt()} 拿到一段 Markdown 整体注入。
 * 整段超过 {@link #MAX_RULE_TOKENS} 时记录 warning，但不阻断。
 *
 * <p>线程安全：内部读写锁保护规则列表；文件层是启动时一次性读，运行时不重读。
 * 调用 {@link #reloadFiles(Path)} 可主动重读文件层（比如 {@code /memory reload}）。
 */
public class RuleContext {

    private static final Logger log = LoggerFactory.getLogger(RuleContext.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** 文件层文件名（用户 / 项目级共用）。 */
    public static final String STICKY_FILE_NAME = "DEVCLI.md";
    /** 项目本地级文件名（在 .devcli/ 子目录里）。 */
    public static final String STICKY_LOCAL_FILE_NAME = "DEVCLI.local.md";
    /** 规则上下文整体软上限。 */
    public static final int MAX_RULE_TOKENS = 8_000;

    private final Path rulesFile;
    private final Path legacyPinnedFactsFile;
    private final List<Rule> rules;
    private final List<LegacyPinnedCandidate> legacyPinnedCandidates = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private volatile String userMd = "";
    private volatile String projectMd = "";
    private volatile String localMd = "";

    public RuleContext(Path memoryDir) {
        this.rulesFile = memoryDir.resolve("rules.json");
        this.legacyPinnedFactsFile = memoryDir.resolve("pinned_facts.json");
        this.rules = new ArrayList<>();
        ensureMemoryDir(memoryDir);
        loadRulesFromDisk();
        loadLegacyPinnedCandidates();
    }

    /**
     * 加载 / 重新加载文件层的三个 Markdown 文件。
     * 启动时调一次；用户编辑文件后调 {@code /memory reload} 重新加载。
     *
     * @param projectRoot 当前项目根目录
     */
    public void reloadFiles(Path projectRoot) {
        Path home = Path.of(System.getProperty("user.home"));
        userMd = readSafely(home.resolve(".devcli").resolve(STICKY_FILE_NAME));
        if (projectRoot != null) {
            projectMd = readSafely(projectRoot.resolve(STICKY_FILE_NAME));
            localMd = readSafely(projectRoot.resolve(".devcli").resolve(STICKY_LOCAL_FILE_NAME));
        } else {
            projectMd = "";
            localMd = "";
        }
    }

    /** 用户显式添加一条强约束。稳定事实应写入 LongTermMemory，不进入本类。 */
    public Rule addRule(String content, String source) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("rule content cannot be blank");
        }
        String normalized = content.trim();
        if (SensitiveDataRedactor.inspect(normalized).changed()) {
            throw new IllegalArgumentException("强约束不能保存敏感值；请删除凭据或个人敏感信息后重试");
        }
        lock.writeLock().lock();
        try {
            // 去重：content 相同的更新 timestamp + source，不新增
            for (int i = 0; i < rules.size(); i++) {
                if (rules.get(i).content.equals(normalized)) {
                    Rule updated = new Rule(rules.get(i).id, normalized,
                            source == null ? "user" : source, Instant.now().toEpochMilli());
                    rules.set(i, updated);
                    saveRulesToDisk();
                    return updated;
                }
            }
            Rule fact = new Rule(
                    "rule-" + UUID.randomUUID().toString().substring(0, 8),
                    normalized,
                    source == null ? "user" : source,
                    Instant.now().toEpochMilli());
            rules.add(fact);
            saveRulesToDisk();
            return fact;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 删除 pin。返回是否真的删掉了。 */
    public boolean removeRule(String ruleId) {
        lock.writeLock().lock();
        try {
            boolean removed = rules.removeIf(f -> f.id.equals(ruleId));
            if (removed) saveRulesToDisk();
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 返回当前所有显式规则的快照。 */
    public List<Rule> listRules() {
        lock.readLock().lock();
        try {
            return List.copyOf(rules);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<LegacyPinnedCandidate> listLegacyPinnedCandidates() {
        lock.readLock().lock();
        try {
            return List.copyOf(legacyPinnedCandidates);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** CLI 管理视图。旧 pinned facts 只列为待分类候选，不自动升级成规则。 */
    public String renderManagementReport() {
        StringBuilder out = new StringBuilder();
        List<Rule> activeRules = listRules();
        out.append("显式规则: ").append(activeRules.size()).append(" 条\n");
        for (Rule rule : activeRules) {
            out.append("- ").append(rule.id).append(": ")
                    .append(SensitiveDataRedactor.redact(rule.content)).append('\n');
        }
        List<LegacyPinnedCandidate> legacy = listLegacyPinnedCandidates();
        if (!legacy.isEmpty()) {
            out.append("\n旧 pinned facts 待分类: ").append(legacy.size()).append(" 条\n")
                    .append("请将强约束改用 /rule add，将稳定事实改用 /save；系统不会自动误分类。\n");
            for (LegacyPinnedCandidate candidate : legacy) {
                out.append("- ").append(candidate.id()).append(": ")
                        .append(SensitiveDataRedactor.redact(candidate.content())).append('\n');
            }
        }
        return out.toString().trim();
    }

    /**
     * 渲染规则上下文为一段可注入 system prompt 的 Markdown。
     * 空内容时返回空字符串（PromptAssembler 会跳过空段）。
     */
    public String renderForPrompt() {
        StringBuilder sb = new StringBuilder();
        if (!userMd.isBlank()) {
            sb.append("### 用户全局约定（~/.devcli/DEVCLI.md）\n\n");
            sb.append(SensitiveDataRedactor.redact(userMd.trim())).append("\n\n");
        }
        if (!projectMd.isBlank()) {
            sb.append("### 项目约定（DEVCLI.md）\n\n");
            sb.append(SensitiveDataRedactor.redact(projectMd.trim())).append("\n\n");
        }
        if (!localMd.isBlank()) {
            sb.append("### 项目本地补充（.devcli/DEVCLI.local.md）\n\n");
            sb.append(SensitiveDataRedactor.redact(localMd.trim())).append("\n\n");
        }
        List<Rule> snapshot = listRules();
        if (!snapshot.isEmpty()) {
            sb.append("### 用户显式强约束\n\n");
            for (Rule f : snapshot) {
                sb.append("- ").append(SensitiveDataRedactor.redact(f.content)).append("\n");
            }
            sb.append("\n");
        }
        String rendered = sb.toString().trim();
        // 软上限检查
        if (!rendered.isEmpty()) {
            int tokens = MemoryEntry.estimateTokens(rendered);
            if (tokens > MAX_RULE_TOKENS) {
                log.warn("Rule context size {} tokens exceeds soft cap {}; clean up DEVCLI.md or rules",
                        tokens, MAX_RULE_TOKENS);
            }
        }
        return rendered;
    }

    /** 状态摘要，给 /memory 命令用。 */
    public String getStatusSummary() {
        List<Rule> snapshot = listRules();
        int totalTokens = MemoryEntry.estimateTokens(renderForPrompt());
        String usage = totalTokens > MAX_RULE_TOKENS
                ? String.format("%d tokens / cap %d (超限，建议清理规则)",
                totalTokens, MAX_RULE_TOKENS)
                : String.format("%d tokens / cap %d", totalTokens, MAX_RULE_TOKENS);
        return String.format(
                "规则上下文: %d 条显式规则 / %d 条旧 pinned 待分类 | files: %s%s%s | %s",
                snapshot.size(),
                listLegacyPinnedCandidates().size(),
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
            log.warn("Failed to read rule file {}: {}", file, e.getMessage());
        }
        return "";
    }

    private void loadRulesFromDisk() {
        if (!Files.exists(rulesFile)) return;
        try {
            JsonNode root = MAPPER.readTree(Files.readString(rulesFile, StandardCharsets.UTF_8));
            if (!root.isArray()) return;
            lock.writeLock().lock();
            try {
                rules.clear();
                for (JsonNode node : root) {
                    String id = node.path("id").asText("");
                    String content = node.path("content").asText("");
                    String source = node.path("source").asText("user");
                    long addedAt = node.path("added_at").asLong(0);
                    if (!id.isBlank() && !content.isBlank()) {
                        rules.add(new Rule(id, content, source, addedAt));
                    }
                }
            } finally {
                lock.writeLock().unlock();
            }
        } catch (IOException e) {
            log.warn("Failed to load rules.json: {}", e.getMessage());
        }
    }

    private void loadLegacyPinnedCandidates() {
        if (!Files.exists(legacyPinnedFactsFile)) return;
        try {
            JsonNode root = MAPPER.readTree(Files.readString(legacyPinnedFactsFile, StandardCharsets.UTF_8));
            if (!root.isArray()) return;
            lock.writeLock().lock();
            try {
                legacyPinnedCandidates.clear();
                for (JsonNode node : root) {
                    String id = node.path("id").asText("");
                    String content = node.path("content").asText("");
                    String source = node.path("source").asText("legacy-pin");
                    if (!id.isBlank() && !content.isBlank()) {
                        legacyPinnedCandidates.add(new LegacyPinnedCandidate(id, content, source));
                    }
                }
            } finally {
                lock.writeLock().unlock();
            }
        } catch (IOException e) {
            log.warn("Failed to inspect legacy pinned_facts.json: {}", e.getMessage());
        }
    }

    private void saveRulesToDisk() {
        try {
            ArrayNode arr = MAPPER.createArrayNode();
            for (Rule f : rules) {
                ObjectNode n = arr.addObject();
                n.put("id", f.id);
                n.put("content", f.content);
                n.put("source", f.source);
                n.put("added_at", f.addedAt);
            }
            Files.createDirectories(rulesFile.getParent());
            Files.writeString(rulesFile,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(arr),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to save rules.json: {}", e.getMessage());
        }
    }

    /** 一条显式规则的不可变快照。 */
    public static final class Rule {
        public final String id;
        public final String content;
        public final String source;
        public final long addedAt;

        Rule(String id, String content, String source, long addedAt) {
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

    public record LegacyPinnedCandidate(String id, String content, String source) {}
}
