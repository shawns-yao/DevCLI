package com.devcli.plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResourceConflictDetectorTest {

    @Test
    void keepsReadOnlyFileTasksInSameWave() {
        List<Item> items = List.of(
                new Item("a", "读取 src/main/A.java", "FILE_READ"),
                new Item("b", "读取 src/main/A.java", "FILE_READ")
        );

        List<List<Item>> waves = split(items);

        assertEquals(1, waves.size());
        assertEquals(List.of("a", "b"), ids(waves.get(0)));
    }

    @Test
    void splitsWritesToSameFile() {
        List<Item> items = List.of(
                new Item("a", "修改 src/main/A.java", "FILE_WRITE"),
                new Item("b", "读取 src/main/A.java", "FILE_READ")
        );

        List<List<Item>> waves = split(items);

        assertEquals(2, waves.size());
        assertEquals(List.of("a"), ids(waves.get(0)));
        assertEquals(List.of("b"), ids(waves.get(1)));
    }

    @Test
    void commandTasksAreExclusive() {
        List<Item> items = List.of(
                new Item("a", "读取 README.md", "FILE_READ"),
                new Item("b", "执行命令 mvn test", "COMMAND"),
                new Item("c", "读取 pom.xml", "FILE_READ")
        );

        List<List<Item>> waves = split(items);

        assertEquals(3, waves.size());
        assertEquals(List.of("a"), ids(waves.get(0)));
        assertEquals(List.of("b"), ids(waves.get(1)));
        assertEquals(List.of("c"), ids(waves.get(2)));
    }

    @Test
    void allowsWritesToDifferentFilesInSameWave() {
        List<Item> items = List.of(
                new Item("a", "修改 src/main/java/A.java", "FILE_WRITE"),
                new Item("b", "修改 src/main/java/B.java", "FILE_WRITE")
        );

        List<List<Item>> waves = split(items);

        assertEquals(1, waves.size());
        assertEquals(List.of("a", "b"), ids(waves.get(0)));
    }

    @Test
    void normalizesPathAndBasenameForSameJavaFileConflict() {
        List<Item> items = List.of(
                new Item("a", "修改 src/main/java/com/acme/LogCli.java", "FILE_WRITE"),
                new Item("b", "读取 LogCli.java", "FILE_READ")
        );

        List<List<Item>> waves = split(items);

        assertEquals(2, waves.size());
        assertEquals(List.of("a"), ids(waves.get(0)));
        assertEquals(List.of("b"), ids(waves.get(1)));
    }

    @Test
    void allowsWritesToDifferentMethodsInSameJavaFile(@TempDir Path project) throws Exception {
        Path source = project.resolve("src/main/java/A.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                class A {
                    int alpha() { return 1; }
                    int beta() { return 2; }
                }
                """);
        List<Item> items = List.of(
                new Item("a", "修改 src/main/java/A.java 的 A.alpha() 方法", "FILE_WRITE"),
                new Item("b", "修改 src/main/java/A.java 的 A.beta() 方法", "FILE_WRITE")
        );

        List<List<Item>> waves = split(project, items);

        assertEquals(1, waves.size());
        assertEquals(List.of("a", "b"), ids(waves.get(0)));
    }

    @Test
    void splitsWritesToSameMethodInSameJavaFile(@TempDir Path project) throws Exception {
        Path source = project.resolve("src/main/java/A.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                class A {
                    int alpha() { return 1; }
                }
                """);
        List<Item> items = List.of(
                new Item("a", "修改 src/main/java/A.java 的 A.alpha() 方法", "FILE_WRITE"),
                new Item("b", "修改 src/main/java/A.java 的 A.alpha() 返回值", "FILE_WRITE")
        );

        List<List<Item>> waves = split(project, items);

        assertEquals(2, waves.size());
    }

    @Test
    void keepsStructuralJavaChangesAtFileScope(@TempDir Path project) throws Exception {
        Path source = project.resolve("src/main/java/A.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                class A {
                    int alpha() { return 1; }
                    int beta() { return 2; }
                }
                """);
        List<Item> items = List.of(
                new Item("a", "修改 src/main/java/A.java 的 import 和 A.alpha()", "FILE_WRITE"),
                new Item("b", "修改 src/main/java/A.java 的 A.beta() 方法", "FILE_WRITE")
        );

        List<List<Item>> waves = split(project, items);

        assertEquals(2, waves.size());
    }

    @Test
    void distinguishesOverloadedMethodsByAstSignature(@TempDir Path project) throws Exception {
        Path source = project.resolve("src/main/java/A.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                class A {
                    int load(String value) { return value.length(); }
                    int load(int value) { return value; }
                }
                """);
        List<Item> items = List.of(
                new Item("a", "修改 src/main/java/A.java 的 A.load(String)", "FILE_WRITE"),
                new Item("b", "修改 src/main/java/A.java 的 A.load(int)", "FILE_WRITE")
        );

        List<List<Item>> waves = split(project, items);

        assertEquals(1, waves.size());
        assertEquals(List.of("a", "b"), ids(waves.get(0)));
    }

    private static List<List<Item>> split(List<Item> items) {
        return ResourceConflictDetector.splitConflictFree(items, Item::id, Item::description, Item::type);
    }

    private static List<List<Item>> split(Path project, List<Item> items) {
        return ResourceConflictDetector.splitConflictFree(
                items, Item::id, Item::description, Item::type, project);
    }

    private static List<String> ids(List<Item> items) {
        return items.stream().map(Item::id).toList();
    }

    private record Item(String id, String description, String type) {
    }
}
