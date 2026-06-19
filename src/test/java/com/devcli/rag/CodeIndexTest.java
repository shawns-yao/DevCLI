package com.devcli.rag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    private static final class DeterministicEmbeddingClient extends EmbeddingClient {
        private DeterministicEmbeddingClient() {
            super("ollama", "test-embedding", "http://localhost:11434", "");
        }

        @Override
        public float[] embed(String text) {
            return new float[]{1.0f, text == null ? 0.0f : text.length()};
        }
    }
}
