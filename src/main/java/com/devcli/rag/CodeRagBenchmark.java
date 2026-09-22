package com.devcli.rag;

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
        double dcg = 0.0;
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

        List<VectorStore.SearchResult> ranked = retrieved.stream().limit(Math.max(0, topK)).toList();
        for (int i = 0; i < ranked.size(); i++) {
            int gain = relevanceGrade(ranked.get(i), benchmarkCase);
            if (gain > 0) {
                relevantHits++;
                dcg += (Math.pow(2, gain) - 1.0) / log2(i + 2);
            }
        }

        double recall = required.isEmpty() ? 1.0 : requiredHits / (double) required.size();
        double precision = topK <= 0 ? 0.0 : relevantHits / (double) Math.min(topK, Math.max(retrieved.size(), 1));
        double idealDcg = idealDcg(benchmarkCase, topK);
        double ndcg = idealDcg == 0.0 ? 0.0 : dcg / idealDcg;
        return new CaseResult(benchmarkCase.name(), recall, precision, reciprocalRank, ndcg,
                hitTargets, missingTargets);
    }

    private int relevanceGrade(VectorStore.SearchResult result, BenchmarkCase benchmarkCase) {
        if (benchmarkCase.mustHave().stream().anyMatch(target -> target.matches(result))) return 3;
        if (benchmarkCase.shouldHave().stream().anyMatch(target -> target.matches(result))) return 2;
        if (benchmarkCase.niceToHave().stream().anyMatch(target -> target.matches(result))) return 1;
        return 0;
    }

    private double idealDcg(BenchmarkCase benchmarkCase, int topK) {
        int slots = Math.min(Math.max(0, topK), benchmarkCase.allTargets().size());
        List<Integer> gains = new ArrayList<>();
        gains.addAll(benchmarkCase.mustHave().stream().map(ignored -> 3).toList());
        gains.addAll(benchmarkCase.shouldHave().stream().map(ignored -> 2).toList());
        gains.addAll(benchmarkCase.niceToHave().stream().map(ignored -> 1).toList());
        gains.sort(java.util.Comparator.reverseOrder());
        double ideal = 0.0;
        for (int i = 0; i < Math.min(slots, gains.size()); i++) {
            ideal += (Math.pow(2, gains.get(i)) - 1.0) / log2(i + 2);
        }
        return ideal;
    }

    private double log2(int value) {
        return Math.log(value) / Math.log(2.0);
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
            double ndcgAtK,
            List<String> hitTargets,
            List<String> missingTargets
    ) {
        public CaseResult(String name, double recallAtK, double precisionAtK, double reciprocalRank,
                          List<String> hitTargets, List<String> missingTargets) {
            this(name, recallAtK, precisionAtK, reciprocalRank, 0.0, hitTargets, missingTargets);
        }
    }

    public record BenchmarkReport(
            int caseCount,
            double recallAtK,
            double precisionAtK,
            double mrr,
            double ndcgAtK,
            List<CaseResult> cases
    ) {
        public BenchmarkReport(int caseCount, double recallAtK, double precisionAtK, double mrr,
                               List<CaseResult> cases) {
            this(caseCount, recallAtK, precisionAtK, mrr, 0.0, cases);
        }

        private static BenchmarkReport from(List<CaseResult> cases) {
            if (cases == null || cases.isEmpty()) {
                return new BenchmarkReport(0, 0.0, 0.0, 0.0, 0.0, List.of());
            }
            double recall = cases.stream().mapToDouble(CaseResult::recallAtK).average().orElse(0.0);
            double precision = cases.stream().mapToDouble(CaseResult::precisionAtK).average().orElse(0.0);
            double mrr = cases.stream().mapToDouble(CaseResult::reciprocalRank).average().orElse(0.0);
            double ndcg = cases.stream().mapToDouble(CaseResult::ndcgAtK).average().orElse(0.0);
            return new BenchmarkReport(cases.size(), recall, precision, mrr, ndcg, List.copyOf(cases));
        }
    }
}
