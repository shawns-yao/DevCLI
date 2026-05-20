package com.paicli.plan;

import org.junit.jupiter.api.Test;

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

    private static List<List<Item>> split(List<Item> items) {
        return ResourceConflictDetector.splitConflictFree(items, Item::id, Item::description, Item::type);
    }

    private static List<String> ids(List<Item> items) {
        return items.stream().map(Item::id).toList();
    }

    private record Item(String id, String description, String type) {
    }
}
