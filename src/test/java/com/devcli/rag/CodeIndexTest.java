package com.devcli.rag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeIndexTest {

    @TempDir
    Path ragDir;

    @BeforeEach
    void configureRagDir() {
        System.setProperty("devcli.rag.dir", ragDir.toString());
    }

    @AfterEach
    void clearRagDir() {
        System.clearProperty("devcli.rag.dir");
    }

    @Test
    void testIndexNonExistentPath() {
        CodeIndex indexer = new CodeIndex();
        CodeIndex.IndexResult result = indexer.index("/non/existent/path");
        assertEquals(0, result.chunkCount());
        assertTrue(result.message().contains("路径不存在"));
    }

    @Test
    void testIndexCurrentProject() {
        CodeIndex indexer = new CodeIndex(new DeterministicEmbeddingClient());
        // 索引测试资源目录
        CodeIndex.IndexResult result = indexer.index("src/test/resources/rag");
        assertTrue(result.chunkCount() > 0, "应该至少索引一个代码块");
        assertTrue(result.message().contains("索引完成"));
        assertTrue(result.message().contains("indexEpoch=idx_"));
    }

    @Test
    void reportsProgressThroughListener() {
        List<String> messages = new ArrayList<>();
        CodeIndex indexer = new CodeIndex(new DeterministicEmbeddingClient(), messages::add);

        CodeIndex.IndexResult result = indexer.index("src/test/resources/rag");

        assertTrue(result.chunkCount() > 0, "应该至少索引一个代码块");
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("🔍 开始索引")));
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("🧭 IndexEpoch")));
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("📁 发现")));
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("✅ 索引完成")));
    }

    @Test
    void indexUsesBatchEmbeddingForChunks() throws Exception {
        Path project = Files.createDirectory(ragDir.resolve("batch-project"));
        Files.writeString(project.resolve("FirstService.java"), """
                class FirstService {
                    String name() { return "first"; }
                }
                """);
        Files.writeString(project.resolve("SecondService.java"), """
                class SecondService {
                    String name() { return "second"; }
                }
                """);
        BatchAwareEmbeddingClient client = new BatchAwareEmbeddingClient();
        CodeIndex indexer = new CodeIndex(client);

        CodeIndex.IndexResult result = indexer.index(project.toString());

        assertTrue(result.chunkCount() > 0);
        assertTrue(client.batchCalls > 0, "索引阶段应优先按文件批量生成 embedding");
        assertEquals(0, client.singleCalls, "批量成功时不应退回逐条 embedding");
    }

    @Test
    void indexFallsBackToSingleEmbeddingWhenBatchFails() throws Exception {
        Path project = Files.createDirectory(ragDir.resolve("fallback-project"));
        Files.writeString(project.resolve("FallbackService.java"), """
                class FallbackService {
                    String name() { return "fallback"; }
                    String value() { return "value"; }
                }
                """);
        BatchAwareEmbeddingClient client = new BatchAwareEmbeddingClient();
        client.failBatch = true;
        CodeIndex indexer = new CodeIndex(client);

        CodeIndex.IndexResult result = indexer.index(project.toString());

        assertTrue(result.chunkCount() > 0);
        assertTrue(client.batchCalls > 0, "应先尝试批量 embedding");
        assertEquals(result.chunkCount(), client.singleCalls, "批量失败后应逐条降级并保留成功 chunk");
    }

    private static final class DeterministicEmbeddingClient extends EmbeddingClient {
        private DeterministicEmbeddingClient() {
            super("ollama", "test-embedding", "http://localhost:11434", "");
        }

        @Override
        public float[] embed(String text) {
            return new float[]{1.0f, text == null ? 0.0f : text.length()};
        }
    }

    private static final class BatchAwareEmbeddingClient extends EmbeddingClient {
        private int batchCalls;
        private int singleCalls;
        private boolean failBatch;

        private BatchAwareEmbeddingClient() {
            super("ollama", "test-embedding", "http://localhost:11434", "");
        }

        @Override
        public List<float[]> embedAll(List<String> texts) throws IOException {
            batchCalls++;
            if (failBatch) {
                throw new IOException("batch failed");
            }
            return texts.stream()
                    .map(this::embeddingFor)
                    .toList();
        }

        @Override
        public float[] embed(String text) {
            singleCalls++;
            return embeddingFor(text);
        }

        private float[] embeddingFor(String text) {
            return new float[]{1.0f, text == null ? 0.0f : text.length()};
        }
    }
}
