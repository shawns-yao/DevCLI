package com.devcli.rag;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddingClientTest {

    @Test
    void testDefaultConfiguration() throws Exception {
        withoutDotEnv(() -> {
            EmbeddingClient client = new EmbeddingClient();
            assertEquals("ollama", client.getProvider());
            assertEquals("nomic-embed-text:latest", client.getModel());
        });
    }

    @Test
    void testCustomConfiguration() {
        EmbeddingClient client = new EmbeddingClient("zhipu", "embedding-3",
                "https://open.bigmodel.cn/api/paas/v4", "test-key");
        assertEquals("zhipu", client.getProvider());
        assertEquals("embedding-3", client.getModel());
    }

    @Test
    void testCustomConfigurationGlmAliasNormalizedToZhipu() {
        // glm 是 zhipu 的别名，构造时应归一化
        EmbeddingClient client = new EmbeddingClient("GLM", "embedding-3",
                "https://example.com", "test-key");
        assertEquals("zhipu", client.getProvider(), "glm 应归一化为 zhipu");
    }

    @Test
    void emptyInputThrowsForFailFast() throws Exception {
        // 新行为（Fail-Fast）：空输入直接抛 IOException 而不是返回空数组
        // 旧行为返回零向量后被 MemoryVectorStore 写入会污染索引（NaN 余弦），非常难诊断
        withoutDotEnv(() -> {
            EmbeddingClient client = new EmbeddingClient();
            assertThrows(java.io.IOException.class, () -> client.embed(""));
            assertThrows(java.io.IOException.class, () -> client.embed(null));
            assertThrows(java.io.IOException.class, () -> client.embed("   "));
        });
    }

    @Test
    void unknownProviderFailsFast() {
        // 旧行为：拼错 provider 静默 fallback 到 ollama，连本地 11434 报 connection refused
        // 新行为：构造时直接抛 IllegalArgumentException
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new EmbeddingClient("deepseek", "model", "url", "key"));
        assertTrue(ex.getMessage().contains("ollama"), "错误提示应列出支持值");
        assertTrue(ex.getMessage().contains("deepseek"), "错误提示应包含用户传入的非法值");
    }

    @Test
    void emptyProviderRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new EmbeddingClient("", "m", "u", "k"));
        assertThrows(IllegalArgumentException.class,
                () -> new EmbeddingClient(null, "m", "u", "k"));
        assertThrows(IllegalArgumentException.class,
                () -> new EmbeddingClient("   ", "m", "u", "k"));
    }

    @Test
    void maxInputCharsDefaultsTo6000() throws Exception {
        withoutDotEnv(() -> {
            EmbeddingClient client = new EmbeddingClient();
            assertEquals(6000, client.getMaxInputChars(),
                    "默认应为 6000 字符（兼容 ~8K token 嵌入模型）");
        });
    }

    @Test
    void maxInputCharsConfigurableViaEnv() throws Exception {
        String previous = System.getProperty("EMBEDDING_MAX_INPUT_CHARS");
        try {
            System.setProperty("EMBEDDING_MAX_INPUT_CHARS", "8000");
            withoutDotEnv(() -> {
                EmbeddingClient client = new EmbeddingClient();
                assertEquals(8000, client.getMaxInputChars());
            });
        } finally {
            restoreProperty("EMBEDDING_MAX_INPUT_CHARS", previous);
        }
    }

    @Test
    void maxInputCharsInvalidValueFallsBackToDefault() throws Exception {
        String previous = System.getProperty("EMBEDDING_MAX_INPUT_CHARS");
        try {
            System.setProperty("EMBEDDING_MAX_INPUT_CHARS", "not-a-number");
            withoutDotEnv(() -> {
                EmbeddingClient client = new EmbeddingClient();
                assertEquals(6000, client.getMaxInputChars(),
                        "非数字应 fallback 到默认 6000");
            });
            // 0 / 负数也应回退
            System.setProperty("EMBEDDING_MAX_INPUT_CHARS", "0");
            withoutDotEnv(() -> {
                EmbeddingClient client = new EmbeddingClient();
                assertEquals(6000, client.getMaxInputChars());
            });
            System.setProperty("EMBEDDING_MAX_INPUT_CHARS", "-100");
            withoutDotEnv(() -> {
                EmbeddingClient client = new EmbeddingClient();
                assertEquals(6000, client.getMaxInputChars());
            });
        } finally {
            restoreProperty("EMBEDDING_MAX_INPUT_CHARS", previous);
        }
    }

    @Test
    void readsEmbeddingConfigurationFromDotEnv() throws Exception {
        Path original = Path.of(".env");
        Path backup = Path.of(".env.embedding-client-test-backup");
        boolean hadOriginal = Files.exists(original);
        if (hadOriginal) {
            Files.move(original, backup);
        }

        try {
            Files.writeString(original, """
                    EMBEDDING_PROVIDER=openai
                    EMBEDDING_MODEL=Qwen/Qwen3-Embedding-4B
                    EMBEDDING_BASE_URL="https://router.tumuer.me/v1"
                    """);

            EmbeddingClient client = new EmbeddingClient();

            assertEquals("openai", client.getProvider());
            assertEquals("Qwen/Qwen3-Embedding-4B", client.getModel());
        } finally {
            Files.deleteIfExists(original);
            if (hadOriginal) {
                Files.move(backup, original);
            }
        }
    }

    @Test
    void systemPropertyTakesPrecedenceOverDotEnv() throws Exception {
        Path original = Path.of(".env");
        Path backup = Path.of(".env.embedding-client-test-backup");
        boolean hadOriginal = Files.exists(original);
        if (hadOriginal) {
            Files.move(original, backup);
        }

        String previousProvider = System.getProperty("EMBEDDING_PROVIDER");
        String previousModel = System.getProperty("EMBEDDING_MODEL");
        try {
            Files.writeString(original, """
                    EMBEDDING_PROVIDER=openai
                    EMBEDDING_MODEL=from-dot-env
                    """);
            System.setProperty("EMBEDDING_PROVIDER", "zhipu");
            System.setProperty("EMBEDDING_MODEL", "from-system-property");

            EmbeddingClient client = new EmbeddingClient();

            assertEquals("zhipu", client.getProvider());
            assertEquals("from-system-property", client.getModel());
        } finally {
            restoreProperty("EMBEDDING_PROVIDER", previousProvider);
            restoreProperty("EMBEDDING_MODEL", previousModel);
            Files.deleteIfExists(original);
            if (hadOriginal) {
                Files.move(backup, original);
            }
        }
    }

    private static void restoreProperty(String key, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previousValue);
        }
    }

    private static void withoutDotEnv(ThrowingRunnable runnable) throws Exception {
        Path original = Path.of(".env");
        Path backup = Path.of(".env.embedding-client-test-backup");
        boolean hadOriginal = Files.exists(original);
        if (hadOriginal) {
            Files.move(original, backup);
        }

        try {
            runnable.run();
        } finally {
            Files.deleteIfExists(original);
            if (hadOriginal) {
                Files.move(backup, original);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
