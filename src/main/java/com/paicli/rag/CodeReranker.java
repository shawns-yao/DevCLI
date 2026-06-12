package com.paicli.rag;

import java.util.List;

/**
 * Optional second-stage reranker for RAG candidates after RRF fusion.
 */
public interface CodeReranker {
    List<VectorStore.SearchResult> rerank(String query,
                                          List<VectorStore.SearchResult> candidates,
                                          int limit) throws Exception;

    boolean enabled();

    String description();
}
