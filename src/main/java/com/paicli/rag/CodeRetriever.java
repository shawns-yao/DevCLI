package com.paicli.rag;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 代码检索器：语义检索 + 图谱检索的统一入口
 */
public class CodeRetriever implements AutoCloseable {
    private static final int GRAPH_MAX_DEPTH = 3;
    private static final int GRAPH_SEED_LIMIT = 5;
    private static final int GRAPH_RELATIONS_PER_NODE = 5;
    private static final int GRAPH_TOTAL_CHUNK_LIMIT = 12;
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;

    public CodeRetriever(String projectPath) throws SQLException {
        this.embeddingClient = new EmbeddingClient();
        this.vectorStore = new VectorStore(Paths.get(projectPath).toAbsolutePath().normalize().toString());
    }

    public CodeRetriever(String projectPath, EmbeddingClient embeddingClient) throws SQLException {
        this.embeddingClient = embeddingClient;
        this.vectorStore = new VectorStore(Paths.get(projectPath).toAbsolutePath().normalize().toString());
    }

    /**
     * 语义检索：用自然语言查询最相关的代码块
     */
    public List<VectorStore.SearchResult> semanticSearch(String query, int topK) throws Exception {
        float[] queryEmbedding = embeddingClient.embed(query);
        return vectorStore.search(queryEmbedding, topK);
    }

    /**
     * 关键词检索：按类名/方法名/内容精确匹配
     */
    public List<VectorStore.SearchResult> keywordSearch(String keyword) throws SQLException {
        return vectorStore.searchByKeyword(keyword);
    }

    /**
     * 混合检索：同时进行语义检索和关键词检索，合并去重
     */
    public List<VectorStore.SearchResult> hybridSearch(String query, int topK) throws Exception {
        return search(query, topK, CodeSearchOptions.resolve("call_chain", query, GRAPH_MAX_DEPTH));
    }

    public List<VectorStore.SearchResult> search(String query, int topK, String mode, Integer graphDepth) throws Exception {
        return search(query, topK, CodeSearchOptions.resolve(mode, query, graphDepth));
    }

    public List<VectorStore.SearchResult> search(String query, int topK, CodeSearchOptions options) throws Exception {
        Map<String, VectorStore.SearchResult> merged = new LinkedHashMap<>();
        Set<String> dualMatchBonused = new HashSet<>();

        switch (options.mode()) {
            case DEFINITION, CONFIG -> searchPreciseFirst(query, topK, merged, dualMatchBonused);
            case ERROR_TRACE -> searchErrorTrace(query, topK, options, merged, dualMatchBonused);
            case CALL_CHAIN -> searchCallChain(query, topK, options, merged, dualMatchBonused);
            case AUTO, GENERAL -> searchGeneral(query, topK, options, merged, dualMatchBonused);
        }

        return rankAndLimit(merged, query, topK);
    }

    private void searchGeneral(String query, int topK, CodeSearchOptions options,
                               Map<String, VectorStore.SearchResult> merged,
                               Set<String> dualMatchBonused) throws Exception {
        addSemanticResults(query, topK, merged, dualMatchBonused);
        addKeywordResults(query, merged, dualMatchBonused);
        expandIfNeeded(merged, options.graphDepth());
    }

    private void searchCallChain(String query, int topK, CodeSearchOptions options,
                                 Map<String, VectorStore.SearchResult> merged,
                                 Set<String> dualMatchBonused) throws Exception {
        addSemanticResults(query, topK, merged, dualMatchBonused);
        addKeywordResults(query, merged, dualMatchBonused);
        expandIfNeeded(merged, options.graphDepth());
    }

    private void searchErrorTrace(String query, int topK, CodeSearchOptions options,
                                  Map<String, VectorStore.SearchResult> merged,
                                  Set<String> dualMatchBonused) throws Exception {
        addKeywordResults(query, merged, dualMatchBonused);
        addSemanticResults(query, topK, merged, dualMatchBonused);
        expandIfNeeded(merged, options.graphDepth());
    }

    private void searchPreciseFirst(String query, int topK,
                                    Map<String, VectorStore.SearchResult> merged,
                                    Set<String> dualMatchBonused) throws Exception {
        addKeywordResults(query, merged, dualMatchBonused);
        if (merged.size() < Math.max(topK, 5)) {
            addSemanticResults(query, topK, merged, dualMatchBonused);
        }
    }

    private void addSemanticResults(String query, int topK, Map<String, VectorStore.SearchResult> merged,
                                    Set<String> dualMatchBonused) throws Exception {
        int semanticLimit = Math.max(topK * 2, 10);
        for (VectorStore.SearchResult result : semanticSearch(query, semanticLimit)) {
            mergeResult(merged, result, dualMatchBonused);
        }
    }

    private void addKeywordResults(String query, Map<String, VectorStore.SearchResult> merged,
                                   Set<String> dualMatchBonused) throws SQLException {
        Set<String> keywords = RagQueryTokenizer.tokenize(query);
        for (String keyword : keywords) {
            for (VectorStore.SearchResult result : keywordSearch(keyword)) {
                mergeResult(merged, boostKeywordMatch(result, keyword), dualMatchBonused);
            }
        }
    }

    private void expandIfNeeded(Map<String, VectorStore.SearchResult> merged, int graphDepth) throws SQLException {
        if (graphDepth > 0) {
            expandGraphNeighbors(merged, graphDepth);
        }
    }

    private List<VectorStore.SearchResult> rankAndLimit(Map<String, VectorStore.SearchResult> merged, String query, int topK) {
        Set<String> queryTokens = RagQueryTokenizer.tokenize(query);
        List<VectorStore.SearchResult> ranked = new ArrayList<>();
        for (VectorStore.SearchResult r : merged.values()) {
            double typeBoost = switch (r.chunkType()) {
                case "method" -> 0.15;
                case "class" -> 0.10;
                default -> 0.0;
            };
            double rerankBoost = typeBoost + queryMatchBoost(r, queryTokens) - noisePenalty(r, queryTokens);
            ranked.add(rerankBoost == 0.0 ? r : new VectorStore.SearchResult(
                    r.filePath(), r.chunkType(), r.name(), r.content(), r.similarity() + rerankBoost));
        }

        ranked.sort(Comparator.comparingDouble(VectorStore.SearchResult::similarity).reversed());
        return limitPerFile(ranked, topK, 2);
    }

    private double queryMatchBoost(VectorStore.SearchResult result, Set<String> queryTokens) {
        if (queryTokens.isEmpty()) {
            return 0.0;
        }
        String nameLower = result.name().toLowerCase();
        String fileLower = result.filePath().replace('\\', '/').toLowerCase();
        String contentLower = result.content().toLowerCase();
        double boost = 0.0;
        int covered = 0;
        for (String token : queryTokens) {
            String tokenLower = token.toLowerCase();
            if (tokenLower.length() < 2) {
                continue;
            }
            boolean matched = false;
            if (nameLower.equals(tokenLower)) {
                boost += 0.70;
                matched = true;
            } else if (nameLower.startsWith(tokenLower + ".")
                    || nameLower.contains("." + tokenLower) || nameLower.contains(tokenLower + "(")) {
                boost += 0.18;
                matched = true;
            } else if (nameLower.contains(tokenLower)) {
                boost += 0.10;
                matched = true;
            }
            if (fileLower.endsWith("/" + tokenLower + ".java") || fileLower.contains("/" + tokenLower + "/")) {
                boost += 0.16;
                matched = true;
            } else if (fileLower.contains(tokenLower)) {
                boost += 0.06;
                matched = true;
            }
            if (!matched && contentLower.contains(tokenLower)) {
                boost += 0.02;
                matched = true;
            }
            if (matched) {
                covered++;
            }
        }
        if (covered >= 2) {
            boost += Math.min(0.20, covered * 0.03);
        }
        return Math.min(boost, 1.20);
    }

    private double noisePenalty(VectorStore.SearchResult result, Set<String> queryTokens) {
        String path = result.filePath().replace('\\', '/').toLowerCase();
        String tokenText = String.join(" ", queryTokens).toLowerCase();
        double penalty = 0.0;
        if ((path.contains("/src/test/") || path.contains("/test/"))
                && !containsAny(tokenText, "test", "测试", "单测")) {
            penalty += 0.12;
        }
        if ((path.contains("/docs/") || path.endsWith(".md"))
                && !containsAny(tokenText, "doc", "docs", "readme", "文档")) {
            penalty += 0.08;
        }
        if ("file".equals(result.chunkType())) {
            penalty += 0.08;
        }
        return penalty;
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private void mergeResult(Map<String, VectorStore.SearchResult> merged, VectorStore.SearchResult candidate,
                             Set<String> dualMatchBonused) {
        String key = candidate.filePath() + "#" + candidate.name();
        VectorStore.SearchResult existing = merged.get(key);
        if (existing == null) {
            merged.put(key, candidate);
        } else {
            double best = Math.max(existing.similarity(), candidate.similarity());
            // 双重命中奖励只给一次，不重复叠加
            if (!dualMatchBonused.contains(key)) {
                best += 0.1;
                dualMatchBonused.add(key);
            }
            merged.put(key, new VectorStore.SearchResult(
                    candidate.filePath(), candidate.chunkType(), candidate.name(),
                    candidate.content(), best));
        }
    }

    private void expandGraphNeighbors(Map<String, VectorStore.SearchResult> merged, int maxDepth) throws SQLException {
        List<VectorStore.SearchResult> seeds = merged.values().stream()
                .sorted(Comparator.comparingDouble(VectorStore.SearchResult::similarity).reversed())
                .limit(GRAPH_SEED_LIMIT)
                .toList();
        Set<String> visitedNodes = new HashSet<>();
        int[] added = {0};
        for (VectorStore.SearchResult seed : seeds) {
            expandFrom(seed.name(), seed.similarity(), 1, maxDepth, visitedNodes, merged, added);
            if (added[0] >= GRAPH_TOTAL_CHUNK_LIMIT) {
                break;
            }
        }
    }

    private void expandFrom(String name, double parentScore, int depth, int maxDepth, Set<String> visitedNodes,
                            Map<String, VectorStore.SearchResult> merged, int[] added) throws SQLException {
        if (name == null || name.isBlank() || depth > maxDepth || added[0] >= GRAPH_TOTAL_CHUNK_LIMIT) {
            return;
        }
        String normalizedName = normalizeMethodName(name);
        if (!visitedNodes.add(normalizedName)) {
            return;
        }

        List<CodeRelation> relations = vectorStore.getRelations(normalizedName).stream()
                .filter(this::isGraphExpansionRelation)
                .limit(GRAPH_RELATIONS_PER_NODE)
                .toList();
        for (CodeRelation relation : relations) {
            String target = relatedTarget(normalizedName, relation);
            if (target == null || target.isBlank()) {
                continue;
            }
            double score = parentScore - (0.12 * depth) + relationBoost(relation.relationType());
            for (VectorStore.SearchResult chunk : vectorStore.findChunksByName(target, 3)) {
                mergeResult(merged, new VectorStore.SearchResult(
                        chunk.filePath(), chunk.chunkType(), chunk.name(), chunk.content(), score), new HashSet<>());
                added[0]++;
                if (added[0] >= GRAPH_TOTAL_CHUNK_LIMIT) {
                    return;
                }
            }
            expandFrom(target, score, depth + 1, maxDepth, visitedNodes, merged, added);
        }
    }

    private String relatedTarget(String current, CodeRelation relation) {
        if (current.equals(relation.fromName())) {
            return relation.toName();
        }
        if (current.equals(relation.toName())
                && ("implements".equals(relation.relationType()) || "extends".equals(relation.relationType()))) {
            return relation.fromName();
        }
        return null;
    }

    private boolean isGraphExpansionRelation(CodeRelation relation) {
        return switch (relation.relationType()) {
            case "calls", "implements", "extends", "contains" -> true;
            default -> false;
        };
    }

    private double relationBoost(String relationType) {
        return switch (relationType) {
            case "calls" -> 0.12;
            case "implements", "extends" -> 0.08;
            case "contains" -> 0.04;
            default -> 0.0;
        };
    }

    private String normalizeMethodName(String name) {
        int paren = name.indexOf('(');
        if (paren < 0) {
            return name;
        }
        return name.substring(0, paren);
    }

    private VectorStore.SearchResult boostKeywordMatch(VectorStore.SearchResult result, String keyword) {
        String nameLower = result.name().toLowerCase();
        String fileLower = result.filePath().toLowerCase();
        String contentLower = result.content().toLowerCase();
        String keywordLower = keyword.toLowerCase();

        // 加分幅度控制在 0.1~0.5，确保关键词结果（base 0.3）最高到 ~0.8，不会压过语义结果（max 1.0）
        double bonus = 0.0;
        if (nameLower.contains(keywordLower)) {
            bonus += 0.3;  // 类名/方法名精确命中是最强信号
        }
        if (fileLower.contains(keywordLower)) {
            bonus += 0.1;
        }
        if (contentLower.contains(keywordLower)) {
            bonus += 0.1;
        }

        return new VectorStore.SearchResult(
                result.filePath(),
                result.chunkType(),
                result.name(),
                result.content(),
                result.similarity() + bonus
        );
    }

    /**
     * 同一文件最多保留 maxPerFile 个结果，总数不超过 topK
     */
    private List<VectorStore.SearchResult> limitPerFile(List<VectorStore.SearchResult> sorted, int topK, int maxPerFile) {
        List<VectorStore.SearchResult> result = new ArrayList<>();
        Map<String, Integer> fileCount = new HashMap<>();
        for (VectorStore.SearchResult r : sorted) {
            int count = fileCount.getOrDefault(r.filePath(), 0);
            if (count < maxPerFile) {
                result.add(r);
                fileCount.put(r.filePath(), count + 1);
                if (result.size() >= topK) {
                    break;
                }
            }
        }
        return result;
    }

    /**
     * 图谱检索：查询指定类/方法的关系图谱
     */
    public List<CodeRelation> getRelationGraph(String name) throws SQLException {
        return vectorStore.getRelations(name);
    }

    /**
     * 获取当前索引统计
     */
    public VectorStore.IndexStats getStats() throws SQLException {
        return vectorStore.getStats();
    }

    @Override
    public void close() throws Exception {
        vectorStore.close();
    }
}
