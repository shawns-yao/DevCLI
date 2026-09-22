package com.devcli.rag;

/** 可审计的检索通道权重配置，避免散落在调用点的魔法数字。 */
public record RetrievalScoringProfile(double semanticWeight,
                                      double keywordWeight,
                                      double graphWeight) {
    public RetrievalScoringProfile {
        semanticWeight = nonNegative(semanticWeight);
        keywordWeight = nonNegative(keywordWeight);
        graphWeight = nonNegative(graphWeight);
    }

    public static RetrievalScoringProfile forMode(CodeSearchMode mode) {
        return switch (mode == null ? CodeSearchMode.GENERAL : mode) {
            case ERROR_TRACE -> new RetrievalScoringProfile(0.90, 1.30, 0.85);
            case CALL_CHAIN -> new RetrievalScoringProfile(1.00, 1.20, 0.85);
            case DEFINITION, CONFIG -> new RetrievalScoringProfile(0.75, 1.35, 0.0);
            case AUTO, GENERAL -> new RetrievalScoringProfile(1.00, 1.15, 0.85);
        };
    }

    private static double nonNegative(double value) {
        return Double.isFinite(value) ? Math.max(0.0, value) : 0.0;
    }
}
