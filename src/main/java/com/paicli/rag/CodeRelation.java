package com.paicli.rag;

/**
 * 代码关系数据模型（用于构建代码关系图谱）
 *
 * @param fromFile     源文件路径
 * @param fromName     源名称（类名或方法名）
 * @param toFile       目标文件路径
 * @param toName       目标名称
 * @param relationType 关系类型：extends / implements / imports / calls / contains
 */
public record CodeRelation(String fromFile, String fromName,
                           String toFile, String toName, String relationType,
                           String resolutionSource, double confidence, String classpathEpoch) {

    public static final String AST_INFERRED = "AST_INFERRED";
    public static final String SOURCE_RESOLVED = "SOURCE_RESOLVED";
    public static final String SYMBOL_SOLVER = "SYMBOL_SOLVER";

    public CodeRelation(String fromFile, String fromName,
                        String toFile, String toName, String relationType) {
        this(fromFile, fromName, toFile, toName, relationType, AST_INFERRED, 0.50, "none");
    }

    public CodeRelation {
        resolutionSource = resolutionSource == null || resolutionSource.isBlank() ? AST_INFERRED : resolutionSource;
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        classpathEpoch = classpathEpoch == null || classpathEpoch.isBlank() ? "none" : classpathEpoch;
    }
}
