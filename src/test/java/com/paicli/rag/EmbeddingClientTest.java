package com.paicli.rag;

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
    void testEmptyInputReturnsEmptyArray() throws Exception {
        withoutDotEnv(() -> {
            EmbeddingClient client = new EmbeddingClient();
            assertEquals(0, client.embed("").length);
            assertEquals(0, client.embed(null).length);
        });
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
