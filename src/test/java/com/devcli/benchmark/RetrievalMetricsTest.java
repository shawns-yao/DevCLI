package com.devcli.benchmark;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RetrievalMetricsTest {
    private static final java.util.function.BiPredicate<String, String> EXACT = String::equalsIgnoreCase;

    @Test
    void computesRecallReciprocalRankAndNdcg() {
        List<String> ranked = List.of("noise", "A", "B");
        List<String> gold = List.of("A", "B");

        assertEquals(1.0, RetrievalMetrics.recallAtK(ranked, gold, 3, EXACT));
        assertEquals(0.5, RetrievalMetrics.reciprocalRankAtK(ranked, gold, 3, EXACT));
        assertEquals(0.6934, RetrievalMetrics.ndcgAtK(ranked, gold, 3, EXACT));
    }

    @Test
    void duplicateResultDoesNotReceiveDuplicateNdcgCredit() {
        List<String> ranked = List.of("A", "A", "B");
        List<String> gold = List.of("A", "B");

        assertEquals(0.9197, RetrievalMetrics.ndcgAtK(ranked, gold, 3, EXACT));
    }

    @Test
    void emptyGoldProducesZeroMetrics() {
        assertEquals(0.0, RetrievalMetrics.recallAtK(List.of("A"), List.of(), 5, EXACT));
        assertEquals(0.0, RetrievalMetrics.reciprocalRankAtK(List.of("A"), List.of(), 5, EXACT));
        assertEquals(0.0, RetrievalMetrics.ndcgAtK(List.of("A"), List.of(), 5, EXACT));
    }
}
