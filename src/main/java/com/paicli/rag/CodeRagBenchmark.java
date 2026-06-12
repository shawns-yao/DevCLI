package com.paicli.rag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Small deterministic benchmark harness for code RAG retrieval quality.
 */
public class CodeRagBenchmark {
    private final CodeRetriever retriever;

    public CodeRagBenchmark(CodeRetriever retriever) {
        this.retriever = retriever;
    }

    public BenchmarkReport run(List<BenchmarkCase> cases, int topK) throws Exception {
        List<CaseResult> results = new ArrayList<>();
        for (BenchmarkCase benchmarkCase : cases) {
            List<VectorStore.SearchResult> retrieved = retriever.search(
                    benchmarkCase.query(),
                    topK,
                    CodeSearchOptions.resolve(benchmarkCase.mode(), benchmarkCase.query(), benchmarkCase.graphDepth())
            );
            results.add(evaluateCase(benchmarkCase, retrieved, topK));
        }
        return BenchmarkReport.from(results);
    }

    private CaseResult evaluateCase(BenchmarkCase benchmarkCase, List<VectorStore.SearchResult> retrieved, int topK) {
        Set<ExpectedTarget> required = new LinkedHashSet<>();
        required.addAll(benchmarkCase.mustHave());
        required.addAll(benchmarkCase.shouldHave());

        int requiredHits = 0;
        int relevantHits = 0;
        double reciprocalRank = 0.0;
        List<String> hitTargets = new ArrayList<>();
        List<String> missingTargets = new ArrayList<>();

        for (ExpectedTarget target : required) {
            int rank = rankOf(target, retrieved);
            if (rank > 0) {
                requiredHits++;
                hitTargets.add(target.label());
                if (reciprocalRank == 0.0) {
                    reciprocalRank = 1.0 / rank;
                }
            } else {
                missingTargets.add(target.label());
            }
        }

        for (VectorStore.SearchResult result : retrieved.stream().limit(topK).toList()) {
            if (matchesAny(result, benchmarkCase.allTargets())) {
                relevantHits++;
            }
        }

        double recall = required.isEmpty() ? 1.0 : requiredHits / (double) required.size();
        double precision = topK <= 0 ? 0.0 : relevantHits / (double) Math.min(topK, Math.max(retrieved.size(), 1));
        return new CaseResult(benchmarkCase.name(), recall, precision, reciprocalRank, hitTargets, missingTargets);
    }

    private int rankOf(ExpectedTarget target, List<VectorStore.SearchResult> retrieved) {
        for (int i = 0; i < retrieved.size(); i++) {
            if (target.matches(retrieved.get(i))) {
                return i + 1;
            }
        }
        return -1;
    }

    private boolean matchesAny(VectorStore.SearchResult result, List<ExpectedTarget> targets) {
        return targets.stream().anyMatch(target -> target.matches(result));
    }

    public record BenchmarkCase(
            String name,
            String query,
            String mode,
            Integer graphDepth,
            List<ExpectedTarget> mustHave,
            List<ExpectedTarget> shouldHave,
            List<ExpectedTarget> niceToHave
    ) {
        public BenchmarkCase {
            mustHave = mustHave == null ? List.of() : List.copyOf(mustHave);
            shouldHave = shouldHave == null ? List.of() : List.copyOf(shouldHave);
            niceToHave = niceToHave == null ? List.of() : List.copyOf(niceToHave);
        }

        private List<ExpectedTarget> allTargets() {
            List<ExpectedTarget> targets = new ArrayList<>();
            targets.addAll(mustHave);
            targets.addAll(shouldHave);
            targets.addAll(niceToHave);
            return targets;
        }
    }

    public record ExpectedTarget(String label, String filePathContains, String nameContains) {
        public boolean matches(VectorStore.SearchResult result) {
            boolean fileMatches = filePathContains == null || filePathContains.isBlank()
                    || result.filePath().replace('\\', '/').contains(filePathContains.replace('\\', '/'));
            boolean nameMatches = nameContains == null || nameContains.isBlank()
                    || result.name().contains(nameContains);
            return fileMatches && nameMatches;
        }
    }

    public record CaseResult(
            String name,
            double recallAtK,
            double precisionAtK,
            double reciprocalRank,
            List<String> hitTargets,
            List<String> missingTargets
    ) {
    }

    public record BenchmarkReport(
            int caseCount,
            double recallAtK,
            double precisionAtK,
            double mrr,
            List<CaseResult> cases
    ) {
        private static BenchmarkReport from(List<CaseResult> cases) {
            if (cases == null || cases.isEmpty()) {
                return new BenchmarkReport(0, 0.0, 0.0, 0.0, List.of());
            }
            double recall = cases.stream().mapToDouble(CaseResult::recallAtK).average().orElse(0.0);
            double precision = cases.stream().mapToDouble(CaseResult::precisionAtK).average().orElse(0.0);
            double mrr = cases.stream().mapToDouble(CaseResult::reciprocalRank).average().orElse(0.0);
            return new BenchmarkReport(cases.size(), recall, precision, mrr, List.copyOf(cases));
        }
    }
}
