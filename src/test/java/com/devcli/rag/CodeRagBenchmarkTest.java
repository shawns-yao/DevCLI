package com.devcli.rag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeRagBenchmarkTest {

    @TempDir
    Path tempDir;

    private VectorStore store;
    private String testProject;
    private String previousRagDir;

    @BeforeEach
    void setUp() throws Exception {
        testProject = tempDir.resolve("project").toAbsolutePath().normalize().toString();
        previousRagDir = System.getProperty("devcli.rag.dir");
        System.setProperty("devcli.rag.dir", tempDir.resolve("rag").toString());
        store = new VectorStore(testProject);
        store.clearProject();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (store != null) {
            store.close();
        }
        if (previousRagDir == null) {
            System.clearProperty("devcli.rag.dir");
        } else {
            System.setProperty("devcli.rag.dir", previousRagDir);
        }
    }

    @Test
    void benchmarkReportsRecallPrecisionAndMrr() throws Exception {
        CodeChunk controller = CodeChunk.methodChunk(
                "src/main/java/com/example/UserController.java",
                "UserController.detail()",
                "public UserVO detail(Long id) { return userService.detail(id); }",
                10, 12
        );
        CodeChunk service = CodeChunk.methodChunk(
                "src/main/java/com/example/UserService.java",
                "UserService.detail(Long id)",
                "UserVO detail(Long id);",
                5, 5
        );
        store.insertChunks(List.of(
                new VectorStore.CodeChunkEntry(controller, new float[]{1.0f, 0.0f}),
                new VectorStore.CodeChunkEntry(service, new float[]{0.0f, 1.0f})
        ));
        store.insertRelations(List.of(
                new CodeRelation(controller.filePath(), "UserController.detail", null, "UserService.detail", "calls")
        ));

        EmbeddingClient stubClient = new EmbeddingClient("ollama", "stub", "http://localhost", "") {
            @Override
            public float[] embed(String text) {
                return new float[]{1.0f, 0.0f};
            }
        };

        try (CodeRetriever retriever = new CodeRetriever(testProject, stubClient)) {
            CodeRagBenchmark benchmark = new CodeRagBenchmark(retriever);
            CodeRagBenchmark.BenchmarkCase benchmarkCase = new CodeRagBenchmark.BenchmarkCase(
                    "user-detail-call-chain",
                    "UserController detail 调用链",
                    "call_chain",
                    2,
                    List.of(new CodeRagBenchmark.ExpectedTarget("controller", "UserController.java", "UserController.detail")),
                    List.of(new CodeRagBenchmark.ExpectedTarget("service", "UserService.java", "UserService.detail")),
                    List.of()
            );

            CodeRagBenchmark.BenchmarkReport report = benchmark.run(List.of(benchmarkCase), 5);

            assertEquals(1, report.caseCount());
            assertEquals(1.0, report.recallAtK());
            assertTrue(report.precisionAtK() > 0.0);
            assertTrue(report.mrr() > 0.0);
            assertTrue(report.cases().get(0).missingTargets().isEmpty());
        }
    }
}
