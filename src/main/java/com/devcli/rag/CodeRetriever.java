package com.devcli.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger log = LoggerFactory.getLogger(CodeRetriever.class);
    private static final int GRAPH_MAX_DEPTH = 3;
    private static final int GRAPH_SEED_LIMIT = 5;
    private static final int GRAPH_RELATIONS_PER_NODE = 5;
    private static final int GRAPH_TOTAL_CHUNK_LIMIT = 12;
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final CodeReranker reranker;
    /**
     * 最近一次 {@link #search} 是否因 embedding/语义检索不可用而降级为关键词+结构化检索。
     * 每次 search 入口重置，调用方据此向用户显式标记降级（不把降级结果伪装成完整 RAG）。
     */
    private boolean lastSemanticDegraded = false;

    public CodeRetriever(String projectPath) throws SQLException {
        this.embeddingClient = new EmbeddingClient();
        this.vectorStore = new VectorStore(Paths.get(projectPath).toAbsolutePath().normalize().toString());
        this.reranker = new CrossEncoderReranker();
    }

    public CodeRetriever(String projectPath, EmbeddingClient embeddingClient) throws SQLException {
        this(projectPath, embeddingClient, new CrossEncoderReranker());
    }

    public CodeRetriever(String projectPath, EmbeddingClient embeddingClient, CodeReranker reranker) throws SQLException {
        this.embeddingClient = embeddingClient;
        this.vectorStore = new VectorStore(Paths.get(projectPath).toAbsolutePath().normalize().toString());
        this.reranker = reranker == null ? new NoopCodeReranker() : reranker;
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
        lastSemanticDegraded = false;
        RetrievalFusion fusion = new RetrievalFusion();

        switch (options.mode()) {
            case DEFINITION, CONFIG -> searchPreciseFirst(query, topK, fusion);
            case ERROR_TRACE -> searchErrorTrace(query, topK, options, fusion);
            case CALL_CHAIN -> searchCallChain(query, topK, options, fusion);
            case AUTO, GENERAL -> searchGeneral(query, topK, options, fusion);
        }

        List<VectorStore.SearchResult> fused = fusion.rank(query, Math.max(topK * 3, topK));
        List<VectorStore.SearchResult> reranked = rerankOrFallback(query, fused, Math.max(topK * 3, topK));
        return limitPerFile(reranked, topK, 2);
    }

    /**
     * 最近一次 {@link #search} 是否降级（语义检索不可用，仅用关键词+结构化）。
     */
    public boolean lastSemanticDegraded() {
        return lastSemanticDegraded;
    }

    public String rerankStrategy() {
        return reranker.enabled() ? "cross_encoder:" + reranker.description() : "disabled";
    }

    private void searchGeneral(String query, int topK, CodeSearchOptions options, RetrievalFusion fusion) throws Exception {
        List<VectorStore.SearchResult> semantic = safeSemanticResults(query, topK);
        List<VectorStore.SearchResult> keyword = keywordResults(query);
        fusion.addChannel("semantic", semantic, 1.0);
        fusion.addChannel("keyword", keyword, 1.15);
        addGraphResults(options.graphDepth(), fusion, semantic, keyword);
    }

    private void searchCallChain(String query, int topK, CodeSearchOptions options, RetrievalFusion fusion) throws Exception {
        List<VectorStore.SearchResult> semantic = safeSemanticResults(query, topK);
        List<VectorStore.SearchResult> keyword = keywordResults(query);
        fusion.addChannel("semantic", semantic, 1.0);
        fusion.addChannel("keyword", keyword, 1.20);
        addGraphResults(options.graphDepth(), fusion, semantic, keyword);
    }

    private void searchErrorTrace(String query, int topK, CodeSearchOptions options, RetrievalFusion fusion) throws Exception {
        List<VectorStore.SearchResult> keyword = keywordResults(query);
        List<VectorStore.SearchResult> semantic = safeSemanticResults(query, topK);
        fusion.addChannel("keyword", keyword, 1.30);
        fusion.addChannel("semantic", semantic, 0.90);
        addGraphResults(options.graphDepth(), fusion, keyword, semantic);
    }

    private void searchPreciseFirst(String query, int topK, RetrievalFusion fusion) throws Exception {
        List<VectorStore.SearchResult> keyword = keywordResults(query);
        fusion.addChannel("keyword", keyword, 1.35);
        if (keyword.size() < Math.max(topK, 5)) {
            fusion.addChannel("semantic", safeSemanticResults(query, topK), 0.75);
        }
    }

    private List<VectorStore.SearchResult> semanticResults(String query, int topK) throws Exception {
        int semanticLimit = Math.max(topK * 2, 10);
        return semanticSearch(query, semanticLimit);
    }

    /**
     * 语义检索的容错包装：embedding 服务不可用时记 warn、标记降级并返回空召回，
     * 让 keyword/graph 通道照常融合，而不是让整条检索抛错。降级标记由调用方显式呈现给用户。
     */
    private List<VectorStore.SearchResult> safeSemanticResults(String query, int topK) {
        try {
            return semanticResults(query, topK);
        } catch (Exception e) {
            lastSemanticDegraded = true;
            log.warn("语义检索不可用，降级为关键词+结构化检索: {}", e.getMessage());
            return List.of();
        }
    }

    private List<VectorStore.SearchResult> rerankOrFallback(String query,
                                                            List<VectorStore.SearchResult> fused,
                                                            int limit) {
        if (!reranker.enabled()) {
            return fused;
        }
        try {
            return reranker.rerank(query, fused, limit);
        } catch (Exception e) {
            log.warn("rerank 失败，回退到融合排序结果: {}", e.getMessage());
            return fused;
        }
    }

    private List<VectorStore.SearchResult> keywordResults(String query) throws SQLException {
        Map<String, VectorStore.SearchResult> keywordResults = new LinkedHashMap<>();
        Set<String> keywords = RagQueryTokenizer.tokenize(query);
        for (String keyword : keywords) {
            for (VectorStore.SearchResult result : keywordSearch(keyword)) {
                mergeKeywordResult(keywordResults, boostKeywordMatch(result, keyword));
            }
        }
        return keywordResults.values().stream()
                .sorted(Comparator.comparingDouble(VectorStore.SearchResult::similarity).reversed())
                .toList();
    }

    private void addGraphResults(int graphDepth, RetrievalFusion fusion,
                                 List<VectorStore.SearchResult> first,
                                 List<VectorStore.SearchResult> second) throws SQLException {
        if (graphDepth > 0) {
            List<VectorStore.SearchResult> graph = expandGraphNeighbors(seedResults(first, second), graphDepth);
            fusion.addChannel("graph", graph, 0.85);
        }
    }

    private List<VectorStore.SearchResult> seedResults(List<VectorStore.SearchResult> first,
                                                       List<VectorStore.SearchResult> second) {
        Map<String, VectorStore.SearchResult> seeds = new LinkedHashMap<>();
        for (VectorStore.SearchResult result : first) {
            mergeKeywordResult(seeds, result);
        }
        for (VectorStore.SearchResult result : second) {
            mergeKeywordResult(seeds, result);
        }
        return seeds.values().stream()
                .sorted(Comparator.comparingDouble(VectorStore.SearchResult::similarity).reversed())
                .toList();
    }

    // Bug #14 修复：返回 true 表示实际新增了条目
    private boolean mergeKeywordResult(Map<String, VectorStore.SearchResult> merged, VectorStore.SearchResult candidate) {
        String key = candidate.filePath() + "#" + candidate.name();
        VectorStore.SearchResult existing = merged.get(key);
        if (existing == null || candidate.similarity() > existing.similarity()) {
            merged.put(key, candidate);
            return existing == null; // 只有从无到有才算新增
        }
        return false;
    }

    private List<VectorStore.SearchResult> expandGraphNeighbors(List<VectorStore.SearchResult> merged, int maxDepth) throws SQLException {
        List<VectorStore.SearchResult> seeds = merged.stream()
                .sorted(Comparator.comparingDouble(VectorStore.SearchResult::similarity).reversed())
                .limit(GRAPH_SEED_LIMIT)
                .toList();
        Map<String, VectorStore.SearchResult> graphResults = new LinkedHashMap<>();
        Set<String> visitedNodes = new HashSet<>();
        int[] added = {0};
        for (VectorStore.SearchResult seed : seeds) {
            expandFrom(seed.name(), seed.similarity(), 1, maxDepth, visitedNodes, graphResults, added);
            if (added[0] >= GRAPH_TOTAL_CHUNK_LIMIT) {
                break;
            }
        }
        return graphResults.values().stream()
                .sorted(Comparator.comparingDouble(VectorStore.SearchResult::similarity).reversed())
                .toList();
    }

    private void expandFrom(String name, double parentScore, int depth, int maxDepth, Set<String> visitedNodes,
                            Map<String, VectorStore.SearchResult> merged, int[] added) throws SQLException {
        if (name == null || name.isBlank() || depth > maxDepth || added[0] >= GRAPH_TOTAL_CHUNK_LIMIT) {
            return;
        }
        // Bug #5 修复：visitedNodes 使用完整签名（含参数），避免重载方法互相覆盖
        if (!visitedNodes.add(name)) {
            return;
        }

        // 查询关系时标准化（去参数），因为存储的关系 key 是标准化后的
        String normalizedName = normalizeMethodName(name);
        List<CodeRelation> relations = vectorStore.getRelations(normalizedName).stream()
                .filter(this::isGraphExpansionRelation)
                .limit(GRAPH_RELATIONS_PER_NODE)
                .toList();
        for (CodeRelation relation : relations) {
            String target = relatedTarget(normalizedName, relation);
            if (target == null || target.isBlank()) {
                continue;
            }
            double score = parentScore - (0.12 * depth) + relationBoost(relation);
            for (VectorStore.SearchResult chunk : vectorStore.findChunksByName(target, 3)) {
                // Bug #14 修复：只有实际新增时才计数
                boolean isNewEntry = mergeKeywordResult(merged, new VectorStore.SearchResult(
                        chunk.filePath(),
                        chunk.chunkType(),
                        chunk.name(),
                        chunk.content(),
                        score,
                        chunk.symbolVersion(),
                        chunk.classpathEpoch(),
                        chunk.indexEpoch(),
                        chunk.invalidations()));
                if (isNewEntry) {
                    added[0]++;
                }
                if (added[0] >= GRAPH_TOTAL_CHUNK_LIMIT) {
                    return;
                }
            }
            expandFrom(target, score, depth + 1, maxDepth, visitedNodes, merged, added);
        }
    }

    private String relatedTarget(String current, CodeRelation relation) {
        // 正向遍历：current 是 from，返回 to
        if (current.equals(relation.fromName())) {
            return relation.toName();
        }
        // Bug #6 修复：反向遍历 calls/contains/implements/extends
        // 搜索"谁调用了 X"时，从 X (toName) 找到 caller (fromName)
        if (current.equals(relation.toName())) {
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

    private double relationBoost(CodeRelation relation) {
        double base = switch (relation.relationType()) {
            case "calls" -> 0.12;
            case "implements", "extends" -> 0.08;
            case "contains" -> 0.04;
            default -> 0.0;
        };
        return base + ((relation.confidence() - 0.5) * 0.06);
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
                result.similarity() + bonus,
                result.symbolVersion(),
                result.classpathEpoch(),
                result.indexEpoch(),
                result.invalidations()
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
