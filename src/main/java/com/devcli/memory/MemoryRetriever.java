package com.devcli.memory;

import com.devcli.policy.SensitiveDataRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.time.Instant;

/**
 * 长期记忆检索器。
 *
 * <p>v2 重构（路径 B）：
 * <ul>
 *   <li>不再处理短期记忆——会话记忆走 {@link SessionMemory#render(SessionMemory.SessionView, int)} 直接注入，
 *       不参与 query-based 检索</li>
 *   <li>仅检索 {@link LongTermMemory}，由 {@code MemoryRetriever.retrieveLongTerm} 提供</li>
 *   <li>语义检索（PR-C）+ 关键词分词（PR 之前）合并打分</li>
 * </ul>
 *
 * <p>检索策略：
 * <ol>
 *   <li>语义路径（向量余弦相似度 top-k）扩大召回</li>
 *   <li>关键词路径（jieba 分词 + 词频 + 时间衰减）保留精确命中</li>
 *   <li>合并分数 + 去重 + top-k 返回</li>
 * </ol>
 */
public class MemoryRetriever {
    private static final Logger log = LoggerFactory.getLogger(MemoryRetriever.class);
    private static final double DEFAULT_MIN_SCORE = 0.25;
    private static final double DEFAULT_MAX_SCORE_GAP = 0.60;
    private static final int DEFAULT_MAX_INJECTED = 5;
    private static final int SEMANTIC_CANDIDATE_MULTIPLIER = 5;
    private static final int MIN_SEMANTIC_CANDIDATES = 20;

    private final LongTermMemory longTermMemory;
    /**
     * 语义检索通道（PR-C）。Main 启动时把 EmbeddingClient + MemoryVectorStore 包成
     * {@code (query, topK) -> List<SemanticHit>} 函数接进来；不接时返回空，自动 fallback 关键词。
     */
    private SemanticSearch semanticSearch = (query, topK) -> List.of();
    private final double minScore;
    private final double maxScoreGap;
    private final int maxInjected;

    public MemoryRetriever(LongTermMemory longTermMemory) {
        this(longTermMemory,
                readDouble("devcli.memory.retrieval.min.score", "DEVCLI_MEMORY_RETRIEVAL_MIN_SCORE", DEFAULT_MIN_SCORE),
                readDouble("devcli.memory.retrieval.max.score.gap", "DEVCLI_MEMORY_RETRIEVAL_MAX_SCORE_GAP", DEFAULT_MAX_SCORE_GAP),
                readInt("devcli.memory.retrieval.max.injected", "DEVCLI_MEMORY_RETRIEVAL_MAX_INJECTED", DEFAULT_MAX_INJECTED));
    }

    MemoryRetriever(LongTermMemory longTermMemory, double minScore, double maxScoreGap, int maxInjected) {
        this.longTermMemory = longTermMemory;
        this.minScore = Math.max(0, minScore);
        this.maxScoreGap = Math.max(0, maxScoreGap);
        this.maxInjected = Math.max(1, maxInjected);
    }

    /**
     * 注入语义检索通道（PR-C）。不调用时仅走关键词检索，与 PR-C 之前行为一致。
     */
    public void setSemanticSearch(SemanticSearch semanticSearch) {
        this.semanticSearch = semanticSearch == null ? (q, k) -> List.of() : semanticSearch;
    }

    /**
     * 仅从长期记忆中检索稳定事实，用于 system prompt 注入。
     *
     * <p>当前轮用户输入和短期对话已经在 message history 里，不应再次以"相关记忆"身份
     * 注入给模型，否则容易让模型把当前请求误读成历史事实。
     *
     * <p>PR-C：语义检索（向量余弦相似度）和关键词分词同时参与排序。
     * 语义路径扩展召回，关键词路径保留精确命中，最终按合并分数去重返回。
     */
    public List<MemoryEntry> retrieveLongTerm(String query, int limit) {
        return retrieveLongTermRanked(query, limit).stream().map(RankedMemory::entry).toList();
    }

    public List<RankedMemory> retrieveLongTermRanked(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        Map<String, MemoryEntry> byId = new HashMap<>();
        for (MemoryEntry entry : longTermMemory.getAll()) {
            // 只把可召回事实纳入候选：被 supersede 或明确拒绝的条目不会注入 prompt
            if (entry.isRecallable()) {
                byId.put(entry.getId(), entry);
            }
        }

        Map<String, ScoredEntry> scoredById = new HashMap<>();

        // 1. 语义检索（PR-C）：按 fact_id 命中向量，再与关键词分数合并
        int semanticCandidateLimit = Math.max(MIN_SEMANTIC_CANDIDATES,
                limit > Integer.MAX_VALUE / SEMANTIC_CANDIDATE_MULTIPLIER
                        ? Integer.MAX_VALUE
                        : limit * SEMANTIC_CANDIDATE_MULTIPLIER);
        List<SemanticHit> semanticHits = semanticSearch.search(query, semanticCandidateLimit);
        if (!semanticHits.isEmpty()) {
            for (SemanticHit hit : semanticHits) {
                MemoryEntry entry = byId.get(hit.factId());
                if (entry != null) {
                    double semanticScore = Math.max(0, hit.similarity())
                            * entry.getEvidence().retrievalWeight()
                            * MemoryFreshnessPolicy.weight(entry, Instant.now());
                    mergeScore(scoredById, entry, semanticScore, 0);
                }
            }
            if (log.isDebugEnabled() && !scoredById.isEmpty()) {
                log.debug("Retrieved {} long-term candidates via semantic search (top sim={})",
                        scoredById.size(), semanticHits.get(0).similarity());
            }
        }

        // 2. 关键词检索：与语义召回合并，避免语义命中覆盖精确关键词事实
        for (MemoryEntry entry : byId.values()) {
            double keywordScore = computeRelevanceScore(entry, query) * 1.2
                    * entry.getEvidence().retrievalWeight()
                    * MemoryFreshnessPolicy.weight(entry, Instant.now());
            if (keywordScore > 0) {
                mergeScore(scoredById, entry, 0, keywordScore);
            }
        }

        List<RankedMemory> ranked = scoredById.values().stream()
                .sorted(Comparator.comparingDouble(ScoredEntry::score).reversed())
                .map(entry -> new RankedMemory(entry.entry(), entry.score(), entry.semanticScore(), entry.keywordScore()))
                .toList();
        if (ranked.isEmpty()) {
            return List.of();
        }
        double topScore = ranked.get(0).score();
        return ranked.stream()
                .filter(result -> result.score() >= minScore)
                .filter(result -> topScore - result.score() <= maxScoreGap)
                .limit(Math.min(limit, maxInjected))
                .toList();
    }

    /**
     * 构建上下文：将相关长期记忆组装成文本，用于注入到 LLM 的 system prompt 中。
     */
    public String buildContextForQuery(String query, int maxTokens) {
        return buildContextForQuery(query, maxTokens, List.of());
    }

    public String buildContextForQuery(String query, int maxTokens, Collection<String> suppressedFacts) {
        List<RankedMemory> relevant = retrieveLongTermRanked(query, maxInjected);
        if (relevant.isEmpty()) return "";

        StringBuilder context = new StringBuilder();
        context.append("## 相关长期记忆\n\n");
        context.append("注意：记忆是过去某个时间点的记录，可能已过期。")
                .append("如果记忆提到了具体的文件路径、函数名或配置项，请先验证当前状态（读文件 / grep 确认），不要仅仅依赖记忆内容。")
                .append("如果记忆内容与当前观察冲突，以当前观察为准，并更新或删除过期记忆。\n\n");

        int preambleTokens = MemoryEntry.estimateTokens(context.toString());
        int usedTokens = preambleTokens;
        int appended = 0;
        for (RankedMemory ranked : relevant) {
            MemoryEntry entry = ranked.entry();
            if (MemoryFactDeduper.duplicatesAny(entry.getContent(), suppressedFacts)) {
                continue;
            }
            String safeContent = SensitiveDataRedactor.redact(entry.getContent());
            int safeTokens = MemoryEntry.estimateTokens(safeContent);
            if (usedTokens + safeTokens > maxTokens) break;

            context.append("- [").append(entry.getType())
                    .append("; score=").append(String.format(Locale.ROOT, "%.3f", ranked.score()))
                    .append("; confidence=").append(entry.getEvidence().confidence())
                    .append("; review=").append(entry.getEvidence().reviewState())
                    .append("] ").append(safeContent).append("\n");
            usedTokens += safeTokens;
            appended++;
        }

        if (appended == 0) {
            return "";
        }
        context.append("\n");
        return context.toString();
    }

    /**
     * 计算记忆条目与查询的相关度分数（关键词路径）。
     */
    private double computeRelevanceScore(MemoryEntry entry, String query) {
        String contentLower = entry.getContent().toLowerCase();
        String queryLower = query.toLowerCase();

        // 1. 精确匹配加分
        if (contentLower.contains(queryLower)) {
            return 1.0;
        }

        // 2. 关键词匹配
        Set<String> queryWords = MemoryQueryTokenizer.tokenize(queryLower);
        int matchedWords = 0;
        for (String word : queryWords) {
            if (!word.isEmpty() && contentLower.contains(word)) {
                matchedWords++;
            }
        }

        if (matchedWords == 0) return 0;

        double keywordScore = (double) matchedWords / queryWords.size();

        return keywordScore;
    }

    private void mergeScore(Map<String, ScoredEntry> scoredById, MemoryEntry entry,
                            double semanticScore, double keywordScore) {
        ScoredEntry existing = scoredById.get(entry.getId());
        double mergedSemantic = semanticScore + (existing == null ? 0 : existing.semanticScore());
        double mergedKeyword = keywordScore + (existing == null ? 0 : existing.keywordScore());
        scoredById.put(entry.getId(), new ScoredEntry(entry, mergedSemantic, mergedKeyword));
    }

    private static double readDouble(String property, String env, double fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) value = System.getenv(env);
        try {
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int readInt(String property, String env, int fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) value = System.getenv(env);
        try {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public record RankedMemory(MemoryEntry entry, double score, double semanticScore, double keywordScore) {}

    private record ScoredEntry(MemoryEntry entry, double semanticScore, double keywordScore) {
        double score() {
            return semanticScore + keywordScore;
        }
    }

    /**
     * PR-C 语义检索通道。Main 启动时把 EmbeddingClient + MemoryVectorStore 包成 lambda
     * 接进来；测试 / Ollama 不可用时使用默认空实现，自动 fallback 到关键词检索。
     */
    @FunctionalInterface
    public interface SemanticSearch {
        List<SemanticHit> search(String query, int topK);
    }

    /** 语义检索单条命中。 */
    public record SemanticHit(String factId, double similarity) {}
}
