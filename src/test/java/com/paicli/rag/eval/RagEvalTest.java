package com.paicli.rag.eval;

import com.paicli.rag.CodeRetriever;
import com.paicli.rag.SearchResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RAG 召回率评测
 *
 * 指标说明：
 * - Recall@K: Top-K 结果中包含多少个标注相关片段 / 总标注数
 * - MRR (Mean Reciprocal Rank): 第一个相关结果的排名倒数的平均值
 */
public class RagEvalTest {

    private static final int[] RECALL_AT = {5, 10, 20};

    @Test
    void evaluateRecallOnTestSet() throws IOException {
        // 加载评测数据集
        List<RagEvalCase> testCases = loadEvalDataset();

        assertTrue(testCases.size() >= 50, "评测数据集应至少有 50 条");

        // 初始化检索器
        CodeRetriever retriever = createRetriever();

        // 统计指标
        Map<Integer, List<Double>> recallScores = new HashMap<>();
        for (int k : RECALL_AT) {
            recallScores.put(k, new ArrayList<>());
        }
        List<Double> mrrScores = new ArrayList<>();

        // 逐条评测
        for (RagEvalCase testCase : testCases) {
            List<SearchResult> results = retriever.search(testCase.query(), 20);

            // 提取结果的 symbolKey
            Set<String> retrievedKeys = results.stream()
                .map(r -> r.symbolKey())
                .collect(Collectors.toSet());

            // 计算 Recall@K
            Set<String> groundTruth = new HashSet<>(testCase.groundTruth());
            for (int k : RECALL_AT) {
                Set<String> topK = results.stream()
                    .limit(k)
                    .map(r -> r.symbolKey())
                    .collect(Collectors.toSet());

                long hits = topK.stream().filter(groundTruth::contains).count();
                double recall = (double) hits / groundTruth.size();
                recallScores.get(k).add(recall);
            }

            // 计算 MRR
            double reciprocalRank = 0.0;
            for (int i = 0; i < results.size(); i++) {
                if (groundTruth.contains(results.get(i).symbolKey())) {
                    reciprocalRank = 1.0 / (i + 1);
                    break;
                }
            }
            mrrScores.add(reciprocalRank);
        }

        // 输出统计结果
        System.out.println("=== RAG Retrieval Evaluation Results ===");
        System.out.println("Dataset size: " + testCases.size());

        for (int k : RECALL_AT) {
            double avgRecall = recallScores.get(k).stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
            System.out.printf("Recall@%d: %.2f%%\n", k, avgRecall * 100);
        }

        double avgMRR = mrrScores.stream()
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0.0);
        System.out.printf("MRR: %.4f\n", avgMRR);

        // 断言基线（至少达到合理水平）
        double recall5 = recallScores.get(5).stream()
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0.0);

        assertTrue(recall5 >= 0.60, "Recall@5 应至少达到 60%（当前: " + (recall5 * 100) + "%）");
    }

    private List<RagEvalCase> loadEvalDataset() {
        // TODO: 从 JSON 文件加载标注数据
        // 当前返回示例数据
        return List.of(
            new RagEvalCase(
                "如何创建 Agent 实例",
                List.of("com/paicli/agent/Agent.java#class#Agent"),
                "class-lookup",
                "easy"
            ),
            new RagEvalCase(
                "ReAct 循环的主要执行逻辑在哪里",
                List.of("com/paicli/agent/Agent.java#method#run"),
                "method-lookup",
                "medium"
            )
            // ... 更多标注数据
        );
    }

    private CodeRetriever createRetriever() {
        // TODO: 初始化真实的 CodeRetriever
        // 需要先构建索引，然后返回检索器实例
        throw new UnsupportedOperationException("需要实现检索器初始化");
    }
}
