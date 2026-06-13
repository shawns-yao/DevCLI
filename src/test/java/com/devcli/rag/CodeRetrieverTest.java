package com.devcli.rag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.List;
import java.nio.file.Path;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeRetrieverTest {

    @TempDir
    Path tempDir;

    private VectorStore store;
    private String testProject;
    private String previousRagDir;
    private int embeddingCalls;

    @BeforeEach
    void setUp() throws Exception {
        testProject = tempDir.resolve("project").toAbsolutePath().normalize().toString();
        previousRagDir = System.getProperty("devcli.rag.dir");
        System.setProperty("devcli.rag.dir", tempDir.resolve("rag").toString());
        store = new VectorStore(testProject);
        store.clearProject();
        embeddingCalls = 0;
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
    void hybridSearchBoostsCodeKeywordsFromNaturalLanguageQuery() throws Exception {
        CodeChunk getterChunk = CodeChunk.methodChunk(
                "src/main/java/com/example/Task.java",
                "Task.getId()",
                "public String getId() { return id; }",
                10, 12
        );
        CodeChunk agentChunk = CodeChunk.methodChunk(
                "src/main/java/com/example/Agent.java",
                "Agent.run(String userInput)",
                "ReAct 循环：读取用户输入，思考，必要时调用工具，再继续下一轮。",
                20, 40
        );

        store.insertChunks(List.of(
                new VectorStore.CodeChunkEntry(getterChunk, new float[]{1.0f, 0.0f}),
                new VectorStore.CodeChunkEntry(agentChunk, new float[]{0.80f, 0.20f})
        ));

        EmbeddingClient stubClient = new EmbeddingClient("ollama", "stub", "http://localhost", "") {
            @Override
            public float[] embed(String text) {
                return new float[]{1.0f, 0.0f};
            }
        };

        try (CodeRetriever retriever = new CodeRetriever(testProject, stubClient)) {
            List<VectorStore.SearchResult> results = retriever.hybridSearch("Agent的ReAct循环是怎么实现的", 5);

            assertFalse(results.isEmpty());
            assertEquals("Agent.run(String userInput)", results.get(0).name());
        }
    }

    @Test
    void hybridSearchExpandsCallGraphUpToThreeHops() throws Exception {
        CodeChunk controller = CodeChunk.methodChunk(
                "src/main/java/com/example/UserController.java",
                "UserController.detail()",
                "public UserVO detail(Long id) { return userService.detail(id); }",
                10, 12
        );
        CodeChunk serviceInterface = CodeChunk.methodChunk(
                "src/main/java/com/example/UserService.java",
                "UserService.detail(Long id)",
                "UserVO detail(Long id);",
                5, 5
        );
        CodeChunk serviceImpl = CodeChunk.methodChunk(
                "src/main/java/com/example/UserServiceImpl.java",
                "UserServiceImpl.detail(Long id)",
                "public UserVO detail(Long id) { return userMapper.selectById(id); }",
                20, 24
        );
        CodeChunk mapper = CodeChunk.methodChunk(
                "src/main/java/com/example/UserMapper.java",
                "UserMapper.selectById(Long id)",
                "UserDO selectById(Long id);",
                8, 8
        );

        store.insertChunks(List.of(
                new VectorStore.CodeChunkEntry(controller, new float[]{1.0f, 0.0f}),
                new VectorStore.CodeChunkEntry(serviceInterface, new float[]{0.0f, 1.0f}),
                new VectorStore.CodeChunkEntry(serviceImpl, new float[]{0.0f, 0.9f}),
                new VectorStore.CodeChunkEntry(mapper, new float[]{0.0f, 0.8f})
        ));
        store.insertRelations(List.of(
                new CodeRelation(controller.filePath(), "UserController.detail", null, "UserService.detail", "calls"),
                new CodeRelation(serviceImpl.filePath(), "UserServiceImpl.detail", null, "UserService.detail", "implements"),
                new CodeRelation(serviceImpl.filePath(), "UserServiceImpl.detail", null, "UserMapper.selectById", "calls")
        ));

        EmbeddingClient stubClient = new EmbeddingClient("ollama", "stub", "http://localhost", "") {
            @Override
            public float[] embed(String text) {
                return new float[]{1.0f, 0.0f};
            }
        };

        try (CodeRetriever retriever = new CodeRetriever(testProject, stubClient)) {
            List<VectorStore.SearchResult> results = retriever.hybridSearch("UserController detail 调用链", 10);
            List<String> names = results.stream().map(VectorStore.SearchResult::name).toList();

            assertTrue(names.contains("UserController.detail()"));
            assertTrue(names.contains("UserService.detail(Long id)"));
            assertTrue(names.contains("UserServiceImpl.detail(Long id)"));
            assertTrue(names.contains("UserMapper.selectById(Long id)"));
        }
    }

    @Test
    void definitionModeDisablesDeepGraphExpansion() throws Exception {
        CodeChunk controller = CodeChunk.methodChunk(
                "src/main/java/com/example/UserController.java",
                "UserController.detail()",
                "public UserVO detail(Long id) { return userService.detail(id); }",
                10, 12
        );
        CodeChunk service = CodeChunk.methodChunk(
                "src/main/java/com/example/UserService.java",
                "UserService.detail(Long id)",
                "UserVO loadProfile(Long id);",
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
            List<VectorStore.SearchResult> results = retriever.search("UserController 在哪里定义", 1, "definition", 3);
            List<String> names = results.stream().map(VectorStore.SearchResult::name).toList();

            assertTrue(names.contains("UserController.detail()"));
            assertFalse(names.contains("UserService.detail(Long id)"));
        }
    }

    @Test
    void definitionModeUsesKeywordBeforeEmbedding() throws Exception {
        CodeChunk service = CodeChunk.classChunk(
                "src/main/java/com/example/UserService.java",
                "UserService",
                "public interface UserService { UserVO detail(Long id); }",
                1, 3
        );
        CodeChunk unrelated = CodeChunk.classChunk(
                "src/main/java/com/example/OrderService.java",
                "OrderService",
                "public interface OrderService { OrderVO detail(Long id); }",
                1, 3
        );
        store.insertChunks(List.of(
                new VectorStore.CodeChunkEntry(service, new float[]{0.0f, 1.0f}),
                new VectorStore.CodeChunkEntry(unrelated, new float[]{1.0f, 0.0f})
        ));

        EmbeddingClient stubClient = new EmbeddingClient("ollama", "stub", "http://localhost", "") {
            @Override
            public float[] embed(String text) {
                embeddingCalls++;
                return new float[]{1.0f, 0.0f};
            }
        };

        try (CodeRetriever retriever = new CodeRetriever(testProject, stubClient)) {
            List<VectorStore.SearchResult> results = retriever.search("UserService 在哪里定义", 5, "definition", null);

            assertFalse(results.isEmpty());
            assertEquals("UserService", results.get(0).name());
            assertEquals(1, embeddingCalls);
        }
    }

    @Test
    void preciseModeFallsBackToEmbeddingWhenKeywordMisses() throws Exception {
        CodeChunk service = CodeChunk.classChunk(
                "src/main/java/com/example/UserService.java",
                "UserService",
                "public interface UserService { UserVO detail(Long id); }",
                1, 3
        );
        store.insertChunks(List.of(
                new VectorStore.CodeChunkEntry(service, new float[]{1.0f, 0.0f})
        ));

        EmbeddingClient stubClient = new EmbeddingClient("ollama", "stub", "http://localhost", "") {
            @Override
            public float[] embed(String text) {
                embeddingCalls++;
                return new float[]{1.0f, 0.0f};
            }
        };

        try (CodeRetriever retriever = new CodeRetriever(testProject, stubClient)) {
            List<VectorStore.SearchResult> results = retriever.search("核心用户能力声明位置", 5, "definition", null);

            assertFalse(results.isEmpty());
            assertEquals("UserService", results.get(0).name());
            assertEquals(1, embeddingCalls);
        }
    }

    @Test
    void tokenizerSplitsCamelCaseAndRerankerPrefersExactSymbolMatches() throws Exception {
        CodeChunk promptAssembler = CodeChunk.classChunk(
                "src/main/java/com/devcli/prompt/PromptAssembler.java",
                "PromptAssembler",
                "public final class PromptAssembler { PromptContext assemble() { return null; } }",
                1, 20
        );
        CodeChunk promptRepository = CodeChunk.classChunk(
                "src/main/java/com/devcli/prompt/PromptRepository.java",
                "PromptRepository",
                "public final class PromptRepository { String systemPrompt() { return \"PromptAssembler\"; } }",
                1, 20
        );
        store.insertChunks(List.of(
                new VectorStore.CodeChunkEntry(promptAssembler, new float[]{0.0f, 1.0f}),
                new VectorStore.CodeChunkEntry(promptRepository, new float[]{1.0f, 0.0f})
        ));

        EmbeddingClient stubClient = new EmbeddingClient("ollama", "stub", "http://localhost", "") {
            @Override
            public float[] embed(String text) {
                return new float[]{1.0f, 0.0f};
            }
        };

        try (CodeRetriever retriever = new CodeRetriever(testProject, stubClient)) {
            List<VectorStore.SearchResult> results = retriever.search("PromptAssembler 如何组装 PromptContext", 2,
                    "definition", null);

            assertFalse(results.isEmpty());
            assertEquals("PromptAssembler", results.get(0).name());
        }
    }

    @Test
    void rrfFusionPrefersResultsConfirmedByKeywordAndSemanticChannels() throws Exception {
        CodeChunk noisySemantic = CodeChunk.classChunk(
                "src/main/java/com/example/DeleteHelper.java",
                "DeleteHelper",
                "public class DeleteHelper { void deleteEverything() {} }",
                1, 10
        );
        CodeChunk userService = CodeChunk.classChunk(
                "src/main/java/com/example/UserService.java",
                "UserService",
                "public interface UserService { UserVO detail(Long id); }",
                1, 3
        );
        store.insertChunks(List.of(
                new VectorStore.CodeChunkEntry(noisySemantic, new float[]{1.0f, 0.0f}),
                new VectorStore.CodeChunkEntry(userService, new float[]{0.98f, 0.02f})
        ));

        EmbeddingClient stubClient = new EmbeddingClient("ollama", "stub", "http://localhost", "") {
            @Override
            public float[] embed(String text) {
                return new float[]{1.0f, 0.0f};
            }
        };

        try (CodeRetriever retriever = new CodeRetriever(testProject, stubClient)) {
            List<VectorStore.SearchResult> results = retriever.search("UserService 在哪里定义", 2,
                    "definition", null);

            assertFalse(results.isEmpty());
            assertEquals("UserService", results.get(0).name());
        }
    }

    @Test
    void searchAppliesRerankerAfterRrfFusion() throws Exception {
        CodeChunk noise = CodeChunk.methodChunk(
                "src/main/java/com/example/NoiseService.java",
                "NoiseService.handle()",
                "public void handle() {}",
                1, 3
        );
        CodeChunk target = CodeChunk.methodChunk(
                "src/main/java/com/example/UserService.java",
                "UserService.detail(Long id)",
                "public UserVO detail(Long id) { return userMapper.selectById(id); }",
                5, 8
        );
        store.insertChunks(List.of(
                new VectorStore.CodeChunkEntry(noise, new float[]{1.0f, 0.0f}),
                new VectorStore.CodeChunkEntry(target, new float[]{0.9f, 0.1f})
        ));

        EmbeddingClient stubClient = new EmbeddingClient("ollama", "stub", "http://localhost", "") {
            @Override
            public float[] embed(String text) {
                return new float[]{1.0f, 0.0f};
            }
        };
        CodeReranker reranker = new CodeReranker() {
            @Override
            public List<VectorStore.SearchResult> rerank(String query, List<VectorStore.SearchResult> candidates, int limit) {
                return candidates.stream()
                        .sorted((left, right) -> Boolean.compare(
                                right.name().contains("UserService"),
                                left.name().contains("UserService")))
                        .limit(limit)
                        .toList();
            }

            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public String description() {
                return "stub-cross-encoder";
            }
        };

        try (CodeRetriever retriever = new CodeRetriever(testProject, stubClient, reranker)) {
            List<VectorStore.SearchResult> results = retriever.search("用户详情实现在哪里", 1, "general", null);

            assertEquals("UserService.detail(Long id)", results.get(0).name());
            assertEquals("cross_encoder:stub-cross-encoder", retriever.rerankStrategy());
        }
    }

    @Test
    void searchFallsBackToRrfWhenRerankerFails() throws Exception {
        CodeChunk target = CodeChunk.methodChunk(
                "src/main/java/com/example/UserService.java",
                "UserService.detail(Long id)",
                "public UserVO detail(Long id) { return userMapper.selectById(id); }",
                5, 8
        );
        store.insertChunks(List.of(new VectorStore.CodeChunkEntry(target, new float[]{1.0f, 0.0f})));

        EmbeddingClient stubClient = new EmbeddingClient("ollama", "stub", "http://localhost", "") {
            @Override
            public float[] embed(String text) {
                return new float[]{1.0f, 0.0f};
            }
        };
        CodeReranker failingReranker = new CodeReranker() {
            @Override
            public List<VectorStore.SearchResult> rerank(String query, List<VectorStore.SearchResult> candidates, int limit) {
                throw new IllegalStateException("rerank unavailable");
            }

            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public String description() {
                return "failing-cross-encoder";
            }
        };

        try (CodeRetriever retriever = new CodeRetriever(testProject, stubClient, failingReranker)) {
            List<VectorStore.SearchResult> results = retriever.search("UserService detail", 1, "general", null);

            assertFalse(results.isEmpty());
            assertEquals("UserService.detail(Long id)", results.get(0).name());
        }
    }

    @Test
    void searchDegradesToKeywordWhenEmbeddingUnavailable() throws Exception {
        CodeChunk service = CodeChunk.methodChunk(
                "src/main/java/com/example/UserService.java",
                "UserService.login(String name)",
                "public boolean login(String name) { return repo.exists(name); }",
                10, 14
        );
        store.insertChunks(List.of(
                new VectorStore.CodeChunkEntry(service, new float[]{1.0f, 0.0f})
        ));

        // embedding 服务不可用：embed() 抛 IOException
        EmbeddingClient failingEmbedding = new EmbeddingClient("ollama", "stub", "http://localhost", "") {
            @Override
            public float[] embed(String text) throws IOException {
                throw new IOException("embedding service down");
            }
        };

        try (CodeRetriever retriever = new CodeRetriever(testProject, failingEmbedding)) {
            // 不应抛错：语义通道失败应降级为关键词+结构化检索
            List<VectorStore.SearchResult> results = retriever.search("login", 5, "general", null);

            assertFalse(results.isEmpty(), "embedding 失败时关键词通道仍应返回结果");
            assertEquals("UserService.login(String name)", results.get(0).name());
            assertTrue(retriever.lastSemanticDegraded(), "应显式标记本次检索已降级");
        }
    }

    @Test
    void searchNotDegradedWhenEmbeddingHealthy() throws Exception {
        CodeChunk service = CodeChunk.methodChunk(
                "src/main/java/com/example/UserService.java",
                "UserService.login(String name)",
                "public boolean login(String name) { return repo.exists(name); }",
                10, 14
        );
        store.insertChunks(List.of(
                new VectorStore.CodeChunkEntry(service, new float[]{1.0f, 0.0f})
        ));

        EmbeddingClient stubClient = new EmbeddingClient("ollama", "stub", "http://localhost", "") {
            @Override
            public float[] embed(String text) {
                return new float[]{1.0f, 0.0f};
            }
        };

        try (CodeRetriever retriever = new CodeRetriever(testProject, stubClient)) {
            retriever.search("login", 5, "general", null);
            assertFalse(retriever.lastSemanticDegraded(), "embedding 正常时不应标记降级");
        }
    }
}
