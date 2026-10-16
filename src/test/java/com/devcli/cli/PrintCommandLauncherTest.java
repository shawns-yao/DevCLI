package com.devcli.cli;

import com.devcli.config.DevCliConfig;
import com.devcli.llm.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PrintCommandLauncherTest {
    @TempDir Path root;
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeEach
    void isolateJournal() { System.setProperty("devcli.runtime.dir", root.resolve("runtime").toString()); }

    @AfterEach
    void clearJournalDirectory() { System.clearProperty("devcli.runtime.dir"); }

    @Test
    void runsTheProductionSessionAndReturnsSingleJsonWithCacheUsage() throws Exception {
        var output = new ByteArrayOutputStream();
        LlmClient client = client(false);
        int exit = PrintCommandLauncher.run("hello", client, new DevCliConfig(), root,
                new PrintStream(output, true, StandardCharsets.UTF_8), new PrintStream(java.io.OutputStream.nullOutputStream()));
        assertEquals(0, exit);
        try (var parser = JSON.createParser(output.toByteArray())) {
            com.fasterxml.jackson.databind.JsonNode result = JSON.readTree(parser);
            assertEquals("completed", result.path("status").asText());
            assertEquals("完成", result.path("output").asText());
            assertEquals(0.5, result.path("usage").path("cache_hit_ratio").asDouble());
            assertNull(parser.nextToken(), "stdout 只允许一个 JSON 对象");
            try (var store = new com.devcli.runtime.api.RuntimeThreadStore(
                    com.devcli.runtime.api.RuntimeThreadStore.defaultDbPath())) {
                String thread = result.path("thread_id").asText();
                assertFalse(thread.isBlank());
                assertEquals("完成", store.contextView(thread).messages().getLast().content());
                assertTrue(store.events(thread, 0).stream().anyMatch(event -> event.type().equals("turn.completed")));
            }
        }
    }

    @Test
    void modelFailureReturnsNonzeroStatus() throws Exception {
        var output = new ByteArrayOutputStream();
        int exit = PrintCommandLauncher.run("fail", client(true), new DevCliConfig(), root,
                new PrintStream(output, true, StandardCharsets.UTF_8), new PrintStream(java.io.OutputStream.nullOutputStream()));
        assertEquals(1, exit);
        assertEquals("failed", JSON.readTree(output.toByteArray()).path("status").asText());
    }

    @Test
    void rejectsMissingPromptBeforeLoadingConfigOrOpeningTerminal() throws Exception {
        assertTrue(PrintCommandLauncher.isPrintCommand(new String[]{"-p"}));
        assertFalse(PrintCommandLauncher.isPrintCommand(new String[]{}));
        Path stdout = root.resolve("stdout.json");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Ddevcli.log.dir=" + root.resolve("logs"), "-cp", System.getProperty("java.class.path"),
                Main.class.getName(), "-p").directory(root.toFile())
                .redirectOutput(stdout.toFile()).redirectError(root.resolve("stderr.log").toFile()).start();
        try {
            assertTrue(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS), "入口不应等待 stdin");
            assertEquals(2, process.exitValue());
            assertEquals("failed", JSON.readTree(java.nio.file.Files.readString(stdout)).path("status").asText());
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static LlmClient client(boolean fail) {
        return new LlmClient() {
            public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
                if (fail) throw new IOException("model unavailable");
                return new ChatResponse("assistant", "完成", null, List.of(), 10, 2, 5);
            }
            public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
                return chat(messages, tools);
            }
            public String getModelName() { return "test"; }
            public String getProviderName() { return "test"; }
        };
    }
}
