package com.paicli.rag;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeAnalyzerTest {

    private final CodeAnalyzer analyzer = new CodeAnalyzer();

    @Test
    void testAnalyzeSampleService() throws Exception {
        Path path = Paths.get("src/test/resources/rag/SampleService.java").toAbsolutePath();
        List<CodeRelation> relations = analyzer.analyzeFile(path);

        assertFalse(relations.isEmpty());

        // 检查 extends 关系
        assertTrue(relations.stream().anyMatch(r ->
                r.relationType().equals("extends") && r.fromName().equals("SampleService")
                        && r.toName().equals("BaseService")));

        // 检查 implements 关系
        assertTrue(relations.stream().anyMatch(r ->
                r.relationType().equals("implements") && r.fromName().equals("SampleService")
                        && r.toName().equals("ServiceInterface")));

        // 检查 contains 关系（类包含方法）
        assertTrue(relations.stream().anyMatch(r ->
                r.relationType().equals("contains") && r.fromName().equals("SampleService")));

        // 检查 imports 关系（非 JDK 导入）
        assertTrue(relations.stream().anyMatch(r ->
                r.relationType().equals("imports") && r.fromName().equals("file")));
    }

    @Test
    void resolvesFieldReceiverCallsToClassMethod(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("UserController.java");
        Files.writeString(file, """
                class UserController {
                    private UserService userService;
                    public void detail() {
                        userService.detail();
                    }
                }
                """);

        List<CodeRelation> relations = analyzer.analyzeFile(file);

        assertTrue(relations.stream().anyMatch(r ->
                r.relationType().equals("calls")
                        && r.fromName().equals("UserController.detail")
                        && r.toName().equals("UserService.detail")));
    }
}
