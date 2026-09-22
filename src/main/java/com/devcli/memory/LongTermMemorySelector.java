package com.devcli.memory;

import com.devcli.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 长期记忆选择器：用一次无工具的 LLM 调用，从候选记忆里挑出与当前问题相关的那几条。
 *
 * <p>为什么不用关键词打分：旧版用「token 命中 +1、整串命中 +100」的粗粒度分数，
 * 再用绝对阈值 {@code minScore=0.25} 与相对阈值 {@code maxScoreGap=0.60} 双重卡，
 * 三个魔数互相耦合，换一个领域就失准，而且阈值只对当时那批数据调过参。
 * 判断「这条记忆和这个问题有关吗」本来就是语义判断，交给模型比交给公式诚实。
 *
 * <p>选择器默认开启，与 CodeBuddy 当前公开设置保持一致；
 * 可用 {@code -Ddevcli.memory.relevanceSelection=false} 或同名环境变量显式关闭。
 *
 * <p>任何失败（无 LLM、超时、非 JSON、字段缺失、文件名不在白名单）都返回空列表，
 * 不做猜测性回退。宁可这次不注入，也不要注入一条编造的记忆。
 */
public final class LongTermMemorySelector {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemorySelector.class);
    private static final ObjectMapper JSON = MemoryJson.mapper();

    /** 开启相关性选择的系统属性键。 */
    public static final String ENABLED_PROPERTY = "devcli.memory.relevanceSelection";
    /** 开启相关性选择的环境变量键。 */
    public static final String ENABLED_ENV = "DEVCLI_MEMORY_RELEVANCE_SELECTION";
    /** 单次最多选中的记忆条数。 */
    public static final int MAX_SELECTED = 8;
    /** 发送给选择器的候选清单总预算，避免候选数量增长时额外请求失控。 */
    public static final int MAX_MANIFEST_TOKENS = 4_096;
    private static final String MANIFEST_TRUNCATION_NOTICE =
            "[候选记忆清单已按选择器输入预算截断]";

    private static final String SYSTEM_PROMPT = """
            你是一次性长期记忆选择器。用户即将提出一个问题，你会看到一份记忆清单，
            每行形如 `- [类型] 文件名 (时间): 名称: 描述`。
            请选出**确实与这个问题相关**的记忆；不相关、只是碰巧含相同词、或只是同一项目的都要排除。
            只输出一个 JSON 对象，不要 Markdown、不要解释：
            {"selected_memories":["文件名1","文件名2"]}
            没有任何相关记忆时输出 {"selected_memories":[]}。
            文件名必须逐字取自清单，不得编造。
            """;

    private LongTermMemorySelector() {
    }

    /**
     * 是否开启相关性选择。
     */
    public static boolean enabled() {
        String value = System.getProperty(ENABLED_PROPERTY);
        if (value == null || value.isBlank()) value = System.getenv(ENABLED_ENV);
        return value == null || value.isBlank() || "true".equalsIgnoreCase(value.trim());
    }

    /**
     * 从候选记忆中选出相关条目并读取全文。
     *
     * @param llm        LLM 客户端；为 {@code null} 时返回空列表
     * @param query      当前用户问题
     * @param candidates 候选记忆（仅头部已解析，正文为空）
     * @return 选中的记忆全文；任何失败或未开启时返回空列表
     */
    public static List<TopicMemory> select(LlmClient llm, String query, List<TopicMemory> candidates) {
        if (llm == null || candidates == null || candidates.isEmpty()) return List.of();
        if (query == null || query.isBlank()) return List.of();

        Manifest manifest = boundedManifest(candidates);
        if (manifest.text().isBlank()) return List.of();

        String content;
        try {
            LlmClient.ChatResponse response = llm.chat(List.of(
                    LlmClient.Message.system(SYSTEM_PROMPT),
                    LlmClient.Message.user("Query: " + query
                            + "\n\nAvailable memories:\n" + manifest.text())),
                    List.of());
            content = response == null ? "" : response.content();
        } catch (Exception e) {
            log.warn("记忆选择器调用失败，本次不注入长期记忆: {}", e.getMessage());
            return List.of();
        }
        if (content == null || content.isBlank()) return List.of();

        Set<String> selected = parseSelection(content);
        if (selected.isEmpty()) return List.of();

        List<TopicMemory> result = new ArrayList<>();
        for (TopicMemory candidate : manifest.candidates()) {
            if (!selected.contains(candidate.fileName())) continue;
            TopicMemory full = TopicMemory.read(candidate.file());
            if (full != null) result.add(full);
            if (result.size() >= MAX_SELECTED) break;
        }
        return result;
    }

    /**
     * 渲染选择器输入清单。
     */
    public static String formatManifest(List<TopicMemory> candidates) {
        return boundedManifest(candidates).text();
    }

    private static Manifest boundedManifest(List<TopicMemory> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new Manifest("", List.of());
        }
        int contentBudget = MAX_MANIFEST_TOKENS
                - MemoryEntry.estimateTokens("\n" + MANIFEST_TRUNCATION_NOTICE);
        StringBuilder builder = new StringBuilder();
        List<TopicMemory> included = new ArrayList<>();
        int usedTokens = 0;
        for (TopicMemory candidate : candidates) {
            if (candidate == null) continue;
            String addition = (builder.length() == 0 ? "" : "\n") + candidate.manifestLine();
            int lineTokens = MemoryEntry.estimateTokens(addition);
            if (usedTokens + lineTokens > contentBudget) break;
            builder.append(addition);
            included.add(candidate);
            usedTokens += lineTokens;
        }
        long nonNullCandidates = candidates.stream().filter(java.util.Objects::nonNull).count();
        if (included.size() < nonNullCandidates && builder.length() > 0) {
            builder.append('\n').append(MANIFEST_TRUNCATION_NOTICE);
        }
        return new Manifest(builder.toString().strip(), List.copyOf(included));
    }

    private record Manifest(String text, List<TopicMemory> candidates) {
    }

    /**
     * 从模型输出中提取 {@code selected_memories}，并做白名单过滤交给调用方。
     */
    static Set<String> parseSelection(String content) {
        String text = content == null ? "" : content.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return Set.of();
        JsonNode root;
        try {
            root = JSON.readTree(text.substring(start, end + 1));
        } catch (Exception ignored) {
            return Set.of();
        }
        if (root == null || !root.isObject()) return Set.of();
        JsonNode selected = root.path("selected_memories");
        if (!selected.isArray()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        selected.forEach(node -> {
            if (!node.isTextual()) return;
            String fileName = node.asText("").trim();
            if (fileName.isEmpty()) return;
            if (fileName.toLowerCase(Locale.ROOT).endsWith(".md")
                    && !fileName.equalsIgnoreCase(MemoryIndex.INDEX_FILE_NAME)) {
                result.add(fileName);
            }
        });
        return result;
    }
}
