package com.paicli.rag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.List;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeRetrieverTest {

    @TempDir
    Path tempDir;

    private VectorStore store;
    private String testProject;
    private String previousRagDir;

    @BeforeEach
    void setUp() throws Exception {
        testProject = tempDir.resolve("project").toAbsolutePath().normalize().toString();
        previousRagDir = System.getProperty("paicli.rag.dir");
        System.setProperty("paicli.rag.dir", tempDir.resolve("rag").toString());
        store = new VectorStore(testProject);
        store.clearProject();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (store != null) {
            store.close();
        }
        if (previousRagDir == null) {
            System.clearProperty("paicli.rag.dir");
        } else {
            System.setProperty("paicli.rag.dir", previousRagDir);
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
}
