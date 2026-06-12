package com.paicli.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchResultFormatterTest {

    @Test
    void cliFormatIncludesReadableSummaryBeforeResults() {
        List<VectorStore.SearchResult> results = List.of(
                new VectorStore.SearchResult(
                        "/Users/itwanger/Documents/GitHub/paicli/src/main/java/com/paicli/agent/Agent.java",
                        "method",
                        "Agent.run(String userInput)",
                        "ReAct 循环：读取用户输入，思考，调用工具，再继续下一轮。",
                        1.42
                )
        );

        String output = SearchResultFormatter.formatForCli("Agent的ReAct循环是怎么实现的", results);

        assertTrue(output.contains("搜索摘要:"));
        assertTrue(output.contains("最相关的入口是 [method:Agent.run(String userInput)]"));
        assertTrue(output.contains("1. [method:Agent.run(String userInput)]"));
    }

    @Test
    void toolFormatIncludesSymbolVersionEvidence() {
        List<VectorStore.SearchResult> results = List.of(
                new VectorStore.SearchResult(
                        "src/main/java/com/paicli/rag/CodeRetriever.java",
                        "method",
                        "CodeRetriever.search(String,int,String,int)",
                        "public List<SearchResult> search(...) { return List.of(); }",
                        0.91,
                        "sv_test123",
                        "cp-1",
                        "idx-1",
                        List.of()
                )
        );

        String output = SearchResultFormatter.formatForTool("CodeRetriever search", results);

        assertTrue(output.contains("evidence: symbolVersion=sv_test123, indexEpoch=idx-1, classpathEpoch=cp-1"));
    }

    @Test
    void toolFormatIncludesNegativeFactsForInvalidatedSymbols() {
        SymbolInvalidation invalidation = new SymbolInvalidation(
                "CodeRetriever.java#method#CodeRetriever.search",
                "src/main/java/com/paicli/rag/CodeRetriever.java",
                "method",
                "CodeRetriever.search",
                "sv_old",
                "sv_new",
                "idx_old",
                "idx_new",
                "cp-1",
                "Do not rely on CodeRetriever.search from symbolVersion sv_old.");
        List<VectorStore.SearchResult> results = List.of(
                new VectorStore.SearchResult(
                        "src/main/java/com/paicli/rag/CodeRetriever.java",
                        "method",
                        "CodeRetriever.search",
                        "public List<SearchResult> search(...) { return List.of(); }",
                        0.91,
                        "sv_new",
                        "cp-1",
                        "idx_new",
                        List.of(invalidation)
                )
        );

        String output = SearchResultFormatter.formatForTool("CodeRetriever search", results);

        assertTrue(output.contains("negativeFact: Do not rely on CodeRetriever.search from symbolVersion sv_old."));
        assertTrue(output.contains("oldSymbolVersion=sv_old"));
    }
}
