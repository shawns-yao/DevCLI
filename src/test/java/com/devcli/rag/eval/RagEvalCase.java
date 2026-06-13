package com.devcli.rag.eval;

import java.util.List;

/**
 * RAG 评测数据集 - 单条标注数据
 */
public record RagEvalCase(
    String query,              // 查询文本（自然语言问题）
    List<String> groundTruth,  // 标注的相关代码片段标识符（symbolKey）
    String category,           // 类别（class-lookup / method-lookup / cross-file / integration）
    String difficulty          // 难度（easy / medium / hard）
) {
    /**
     * 标注格式说明：
     * - query: 用户的自然语言问题
     * - groundTruth: 应该被召回的 symbolKey 列表（格式：filePath#chunkType#name）
     * - category:
     *   - class-lookup: 类查找
     *   - method-lookup: 方法查找
     *   - cross-file: 跨文件关系
     *   - integration: 集成场景（需要多个文件配合理解）
     * - difficulty:
     *   - easy: 直接关键词匹配
     *   - medium: 需要语义理解
     *   - hard: 需要代码关系图谱或跨文件推理
     */
}
