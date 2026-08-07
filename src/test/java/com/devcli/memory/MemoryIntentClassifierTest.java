package com.devcli.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MemoryIntentClassifierTest {
    @Test
    void classifiesMemoryOperationsAndHistoryDependency() {
        assertEquals(MemoryIntentClassifier.Intent.SAVE,
                MemoryIntentClassifier.classify("请记住我默认使用 Java 17"));
        assertEquals(MemoryIntentClassifier.Intent.INVENTORY,
                MemoryIntentClassifier.classify("把你以前记住的内容给我看看"));
        assertEquals(MemoryIntentClassifier.Intent.DELETE,
                MemoryIntentClassifier.classify("删除记忆中的旧项目路径"));
        assertEquals(MemoryIntentClassifier.Intent.IGNORE,
                MemoryIntentClassifier.classify("这次不要使用记忆"));
        assertEquals(MemoryIntentClassifier.Intent.HISTORY_DEPENDENT,
                MemoryIntentClassifier.classify("按照我的习惯生成配置"));
    }
}
