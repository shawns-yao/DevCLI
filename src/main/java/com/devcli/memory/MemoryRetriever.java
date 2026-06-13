package com.devcli.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 长期记忆检索器。
 *
 * <p>v2 重构（路径 B）：
 * <ul>
 *   <li>不再处理短期记忆——工作记忆走 {@link WorkingMemory#renderForPrompt()} 直接注入，
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

    private final LongTermMemory longTermMemory;
    /**
     * 语义检索通道（PR-C）。Main 启动时把 EmbeddingClient + MemoryVectorStore 包成
     * {@code (query, topK) -> List<SemanticHit>} 函数接进来；不接时返回空，自动 fallback 关键词。
     */
    private SemanticSearch semanticSearch = (query, topK) -> List.of();

    public MemoryRetriever(LongTermMemory longTermMemory) {
        this.longTermMemory = longTermMemory;
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
        Map<String, MemoryEntry> byId = new HashMap<>();
        for (MemoryEntry entry : longTermMemory.getAll()) {
            byId.put(entry.getId(), entry);
        }

        Map<String, ScoredEntry> scoredById = new HashMap<>();

        // 1. 语义检索（PR-C）：按 fact_id 命中向量，再与关键词分数合并
        List<SemanticHit> semanticHits = semanticSearch.search(query, limit);
        if (!semanticHits.isEmpty()) {
            for (SemanticHit hit : semanticHits) {
                MemoryEntry entry = byId.get(hit.factId());
                if (entry != null) {
                    double semanticScore = Math.max(0, hit.similarity());
                    mergeScore(scoredById, entry, semanticScore);
                }
            }
            if (log.isDebugEnabled() && !scoredById.isEmpty()) {
                log.debug("Retrieved {} long-term candidates via semantic search (top sim={})",
                        scoredById.size(), semanticHits.get(0).similarity());
            }
        }

        // 2. 关键词检索：与语义召回合并，避免语义命中覆盖精确关键词事实
        for (MemoryEntry entry : byId.values()) {
            double keywordScore = computeRelevanceScore(entry, query) * 1.2;
            if (keywordScore > 0) {
                mergeScore(scoredById, entry, keywordScore);
            }
        }

        return scoredById.values().stream()
                .sorted(Comparator.comparingDouble(ScoredEntry::score).reversed())
                .limit(limit)
                .map(ScoredEntry::entry)
                .collect(Collectors.toList());
    }

    /**
     * 构建上下文：将相关长期记忆组装成文本，用于注入到 LLM 的 system prompt 中。
     */
    public String buildContextForQuery(String query, int maxTokens) {
        List<MemoryEntry> relevant = retrieveLongTerm(query, 10);
        if (relevant.isEmpty()) return "";

        StringBuilder context = new StringBuilder();
        context.append("## 相关长期记忆\n\n");

        int usedTokens = 0;
        for (MemoryEntry entry : relevant) {
            if (usedTokens + entry.getTokenCount() > maxTokens) break;

            context.append("- [").append(entry.getType()).append("] ")
                    .append(entry.getContent()).append("\n");
            usedTokens += entry.getTokenCount();
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

        // 3. 时间衰减（越近分数越高）
        long ageMs = System.currentTimeMillis() - entry.getTimestamp().toEpochMilli();
        double ageHours = ageMs / (1000.0 * 60 * 60);
        double timeDecay = Math.max(0.5, 1.0 - ageHours / 24.0); // 24小时内从1.0衰减到0.5

        return keywordScore * timeDecay;
    }

    private void mergeScore(Map<String, ScoredEntry> scoredById, MemoryEntry entry, double score) {
        ScoredEntry existing = scoredById.get(entry.getId());
        double mergedScore = score + (existing == null ? 0 : existing.score());
        scoredById.put(entry.getId(), new ScoredEntry(entry, mergedScore));
    }

    private record ScoredEntry(MemoryEntry entry, double score) {}

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
