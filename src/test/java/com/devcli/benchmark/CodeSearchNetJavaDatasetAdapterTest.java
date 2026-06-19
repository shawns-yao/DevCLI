package com.devcli.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.devcli.rag.CodeChunk;
import com.devcli.rag.CodeChunker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeSearchNetJavaDatasetAdapterTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void convertsHuggingFaceRowsIntoParseableSyntheticJavaProject() throws Exception {
        JsonNode payload = JSON.readTree("""
                {
                  "rows": [
                    {
                      "row_idx": 0,
                      "row": {
                        "repository_name": "ReactiveX/RxJava",
                        "func_path_in_repository": "src/main/java/io/reactivex/internal/observers/QueueDrainObserver.java",
                        "func_name": "QueueDrainObserver.fastPathOrderedEmit",
                        "whole_func_string": "protected final void fastPathOrderedEmit(Object value, boolean delayError, Object disposable) {\\n    if (value == null) {\\n        return;\\n    }\\n    queue.offer(value);\\n}",
                        "language": "java",
                        "func_documentation_string": "Makes sure the fast-path emits in order."
                      }
                    }
                  ]
                }
                """);

        List<CodeSearchNetJavaDatasetAdapter.SourceCase> cases =
                CodeSearchNetJavaDatasetAdapter.fromHuggingFaceRows(payload, 10);

        assertEquals(1, cases.size());
        CodeSearchNetJavaDatasetAdapter.SourceCase sourceCase = cases.get(0);
        assertEquals("CodeSearchNetCase0000_QueueDrainObserver.fastPathOrderedEmit", sourceCase.goldName());
        assertTrue(sourceCase.query().contains("fast-path emits in order"));
        assertTrue(sourceCase.sourcePath().endsWith("CodeSearchNetCase0000_QueueDrainObserver.java"));
        assertTrue(sourceCase.source().contains("public class CodeSearchNetCase0000_QueueDrainObserver"));

        CodeSearchNetJavaDatasetAdapter.writeSyntheticProject(tempDir, cases);
        Path generated = tempDir.resolve(sourceCase.sourcePath());
        assertTrue(Files.exists(generated));

        List<CodeChunk> chunks = new CodeChunker().chunkFile(generated);
        assertTrue(chunks.stream().anyMatch(chunk ->
                "method".equals(chunk.chunkType()) && chunk.name().contains("fastPathOrderedEmit")));
    }

    @Test
    void skipsNonJavaAndIncompleteRowsWhileRespectingLimit() throws Exception {
        JsonNode payload = JSON.readTree("""
                {
                  "rows": [
                    {"row": {"language": "python", "func_name": "x", "whole_func_string": "def x(): pass"}},
                    {"row": {"language": "java", "func_name": "Missing.body"}},
                    {"row": {"language": "java", "func_name": "Sample.first", "whole_func_string": "public void first() {}"}},
                    {"row": {"language": "java", "func_name": "Sample.second", "whole_func_string": "public void second() {}"}}
                  ]
                }
                """);

        List<CodeSearchNetJavaDatasetAdapter.SourceCase> cases =
                CodeSearchNetJavaDatasetAdapter.fromHuggingFaceRows(payload, 1);

        assertEquals(1, cases.size());
        assertFalse(cases.get(0).source().contains("python"));
        assertTrue(cases.get(0).goldName().contains("first"));
    }
}