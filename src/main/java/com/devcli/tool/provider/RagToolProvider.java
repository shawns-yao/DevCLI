package com.devcli.tool.provider;

import com.devcli.rag.CodeRetriever;
import com.devcli.rag.RagEvidencePayload;
import com.devcli.rag.RagEvidenceSideChannel;
import com.devcli.rag.SearchResultFormatter;
import com.devcli.rag.SymbolInvalidation;
import com.devcli.rag.VectorStore;
import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class RagToolProvider implements ToolProvider, AutoCloseable {
    private CodeRetriever cachedCodeRetriever;
    private String cachedCodeRetrieverProjectPath = "";

    @Override
    public void register(ToolContext context) {
        context.registerTool(ToolRegistry.Tool.structured(
                "search_code",
                "检索代码库。mode 可选：auto/general/call_chain/definition/error_trace/config；调用链场景可用 graph_depth 0-3 控制图谱扩展。",
                context.createToolParameters(
                        new ToolParameter("query", "string", "自然语言查询描述，例如'用户登录的实现'", true),
                        new ToolParameter("top_k", "integer", "返回结果数量（默认 5，上限 30）", false),
                        new ToolParameter("mode", "string", "检索意图，可选 auto/general/call_chain/definition/error_trace/config；非法值自动降级", false),
                        new ToolParameter("graph_depth", "integer", "调用链图谱扩展深度，范围 0-3；非调用链模式会自动收窄", false)
                ),
                args -> searchCode(context, args)
        ));
    }

    public synchronized void closeCachedCodeRetriever() {
        if (cachedCodeRetriever == null) {
            cachedCodeRetrieverProjectPath = "";
            return;
        }
        try {
            cachedCodeRetriever.close();
        } catch (Exception ignored) {
        } finally {
            cachedCodeRetriever = null;
            cachedCodeRetrieverProjectPath = "";
        }
    }

    @Override
    public void close() {
        closeCachedCodeRetriever();
    }

    private ToolOutput searchCode(ToolContext context, Map<String, String> args) {
        String query = args.get("query");
        int topK = 5;
        try {
            if (args.containsKey("top_k")) {
                topK = Integer.parseInt(args.get("top_k"));
            }
        } catch (NumberFormatException ignored) {
        }
        topK = Math.max(1, Math.min(topK, 30));
        Integer graphDepth = null;
        try {
            if (args.containsKey("graph_depth")) {
                graphDepth = Integer.parseInt(args.get("graph_depth"));
            }
        } catch (NumberFormatException ignored) {
        }

        try {
            CodeRetriever retriever = getCodeRetriever(context.projectPath());
            synchronized (retriever) {
                var stats = retriever.getStats();
                if (stats.chunkCount() == 0) {
                    return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                            "代码库尚未索引，请先使用 /index 命令索引当前项目。", false);
                }

                List<VectorStore.SearchResult> results = retriever.search(query, topK, args.get("mode"), graphDepth);
                if (results.isEmpty()) {
                    results = retriever.search(query, topK, "general", 1);
                }
                List<SymbolInvalidation> invalidations =
                        retriever.relevantInvalidations(query, Math.min(topK, 10));
                return formatSearchResult(query, results, invalidations, retriever.lastSemanticDegraded());
            }
        } catch (Exception e) {
            closeCachedCodeRetriever();
            return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                    "代码检索失败: " + e.getMessage(), true);
        }
    }

    static ToolOutput formatSearchResult(String query,
                                         List<VectorStore.SearchResult> results,
                                         List<SymbolInvalidation> invalidations,
                                         boolean semanticDegraded) {
        List<VectorStore.SearchResult> safeResults = results == null ? List.of() : results;
        List<SymbolInvalidation> safeInvalidations = invalidations == null ? List.of() : invalidations;
        String invalidationFacts = SearchResultFormatter.formatInvalidations(safeInvalidations);
        RagEvidenceSideChannel evidence = new RagEvidenceSideChannel(
                RagEvidencePayload.fromSearchResults(query, safeResults, safeInvalidations));
        if (safeResults.isEmpty()) {
            if (!invalidationFacts.isBlank()) {
                return ToolOutput.success(invalidationFacts).withSideChannel(evidence);
            }
            return ToolOutput.success("未找到与查询相关的代码。");
        }

        String formatted = SearchResultFormatter.formatForTool(query, safeResults);
        if (!invalidationFacts.isBlank()) {
            formatted = formatted + "\n\n" + invalidationFacts;
        }
        if (semanticDegraded) {
            formatted = "（注意：语义检索服务不可用，本次已降级为关键词+结构化检索，结果可能不完整）\n\n"
                    + formatted;
        }
        return ToolOutput.success(formatted).withSideChannel(evidence);
    }

    private synchronized CodeRetriever getCodeRetriever(String projectPath) throws Exception {
        String normalizedProjectPath = Path.of(projectPath).toAbsolutePath().normalize().toString();
        if (cachedCodeRetriever == null || !normalizedProjectPath.equals(cachedCodeRetrieverProjectPath)) {
            closeCachedCodeRetriever();
            cachedCodeRetriever = new CodeRetriever(normalizedProjectPath);
            cachedCodeRetrieverProjectPath = normalizedProjectPath;
        }
        return cachedCodeRetriever;
    }
}
