package com.paicli.memory;

import com.paicli.rag.SymbolInvalidation;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 WorkingMemory 的 RAG 证据清理功能
 */
class WorkingMemoryPruneTest {

    @Test
    void pruneInvalidEvidence_shouldRemoveStaleEvidence() {
        // Arrange
        WorkingMemory memory = new WorkingMemory();

        // 模拟添加 RAG 证据（通过 recordToolResult 触发）
        String searchResult = """
            1. [class:UserService] (相似度: 0.95) com/example/UserService.java
               evidence: symbolVersion=abc123, indexEpoch=epoch-001, classpathEpoch=cp-001
            2. [method:findById] (相似度: 0.88) com/example/UserService.java
               evidence: symbolVersion=def456, indexEpoch=epoch-001, classpathEpoch=cp-001
            """;

        memory.recordToolResult("search_code", "{\"query\":\"user service\"}", searchResult);

        assertEquals(2, memory.getRagEvidenceCount(), "应该有 2 条 RAG 证据");

        // Act: 清理旧 epoch 的证据
        SymbolInvalidation invalidation = new SymbolInvalidation(
            "UserService#class#UserService",  // symbolKey
            "com/example/UserService.java",   // filePath
            "class",                           // chunkType
            "UserService",                     // name
            "abc123",                          // oldSymbolVersion
            "xyz789",                          // newSymbolVersion
            "epoch-001",                       // oldIndexEpoch
            "epoch-002",                       // newIndexEpoch
            "cp-001",                          // classpathEpoch
            "Refresh symbol"                   // negativeFact
        );

        int removed = memory.pruneInvalidEvidence(invalidation);

        // Assert
        assertEquals(2, removed, "应该清理 2 条旧证据");
        assertEquals(0, memory.getRagEvidenceCount(), "清理后应该没有证据");
    }

    @Test
    void pruneInvalidEvidence_shouldHandleNullEpoch() {
        // Arrange
        WorkingMemory memory = new WorkingMemory();

        String searchResult = """
            1. [class:UserService] (相似度: 0.95) com/example/UserService.java
               evidence: symbolVersion=abc123, indexEpoch=epoch-001, classpathEpoch=cp-001
            """;

        memory.recordToolResult("search_code", "{}", searchResult);
        assertEquals(1, memory.getRagEvidenceCount());

        // Act: 传入 null oldIndexEpoch
        SymbolInvalidation invalidation = new SymbolInvalidation(
            "UserService#class#UserService",
            "com/example/UserService.java",
            "class",
            "UserService",
            "abc123",
            "xyz789",
            null,         // oldIndexEpoch = null
            "epoch-002",
            "cp-001",
            "Refresh symbol"
        );

        int removed = memory.pruneInvalidEvidence(invalidation);

        // Assert: 不应该删除任何证据（null epoch 不匹配）
        assertEquals(0, removed, "null epoch 不应该删除任何证据");
        assertEquals(1, memory.getRagEvidenceCount(), "证据应该保留");
    }

    @Test
    void pruneInvalidEvidence_shouldNotRemoveNewEpochEvidence() {
        // Arrange
        WorkingMemory memory = new WorkingMemory();

        // 旧 epoch 证据
        String oldResult = """
            1. [class:OldClass] (相似度: 0.9) old/OldClass.java
               evidence: symbolVersion=old123, indexEpoch=epoch-001, classpathEpoch=cp-001
            """;

        // 新 epoch 证据
        String newResult = """
            1. [class:NewClass] (相似度: 0.9) new/NewClass.java
               evidence: symbolVersion=new456, indexEpoch=epoch-002, classpathEpoch=cp-001
            """;

        memory.recordToolResult("search_code", "{}", oldResult);
        memory.recordToolResult("search_code", "{}", newResult);

        assertEquals(2, memory.getRagEvidenceCount());

        // Act: 清理旧 epoch
        SymbolInvalidation invalidation = new SymbolInvalidation(
            "OldClass#class#OldClass",
            "old/OldClass.java",
            "class",
            "OldClass",
            "old123",
            "new456",
            "epoch-001",
            "epoch-002",
            "cp-001",
            "Refresh symbol"
        );

        int removed = memory.pruneInvalidEvidence(invalidation);

        // Assert: 只删除旧 epoch
        assertEquals(1, removed, "应该只删除 1 条旧证据");
        assertEquals(1, memory.getRagEvidenceCount(), "应该保留新 epoch 证据");
    }

    @Test
    void pruneInvalidEvidence_shouldHandleNullInput() {
        // Arrange
        WorkingMemory memory = new WorkingMemory();
        String searchResult = """
            1. [class:Test] (相似度: 0.9) test.java
               evidence: symbolVersion=v1, indexEpoch=e1, classpathEpoch=cp1
            """;
        memory.recordToolResult("search_code", "{}", searchResult);

        assertEquals(1, memory.getRagEvidenceCount(), "应该有 1 条证据");

        // Act: 传入 null
        int removed = memory.pruneInvalidEvidence(null);

        // Assert
        assertEquals(0, removed, "null 输入应该返回 0");
        assertEquals(1, memory.getRagEvidenceCount(), "证据应该保留");
    }

    @Test
    void negativeFactWithOldSymbolVersion_shouldPruneStaleEvidenceImmediately() {
        // Arrange: 先建立一条旧版本证据
        WorkingMemory memory = new WorkingMemory();
        String firstResult = """
            1. [class:UserService] (相似度: 0.95) com/example/UserService.java
               evidence: symbolVersion=sv_old, indexEpoch=epoch-001, classpathEpoch=cp-001
            """;
        memory.recordToolResult("search_code", "{}", firstResult);
        assertEquals(1, memory.getRagEvidenceCount());

        // Act: 新一轮检索结果携带结构化 negativeFact，声明 sv_old 已失效
        String secondResult = """
            1. [class:UserService] (相似度: 0.95) com/example/UserService.java
               evidence: symbolVersion=sv_new, indexEpoch=epoch-002, classpathEpoch=cp-001
               negativeFact: UserService 已变更 oldSymbolVersion=sv_old, newSymbolVersion=sv_new, oldIndexEpoch=epoch-001, newIndexEpoch=epoch-002
            """;
        memory.recordToolResult("search_code", "{}", secondResult);

        // Assert: sv_old 证据被即时清理，只剩 sv_new
        assertEquals(1, memory.getRagEvidenceCount(), "旧版本证据应被即时清理");
        assertEquals("sv_new", memory.getRagEvidenceMemory().get(0).symbolVersion());
    }

    @Test
    void negativeFactWithoutStructuredFields_shouldNotPruneAnything() {
        // Arrange
        WorkingMemory memory = new WorkingMemory();
        String firstResult = """
            1. [class:UserService] (相似度: 0.95) com/example/UserService.java
               evidence: symbolVersion=sv_old, indexEpoch=epoch-001, classpathEpoch=cp-001
            """;
        memory.recordToolResult("search_code", "{}", firstResult);

        // Act: 自由文本 negativeFact，没有 oldSymbolVersion= 结构化字段
        String secondResult = """
            1. [method:findById] (相似度: 0.88) com/example/UserService.java
               evidence: symbolVersion=sv_other, indexEpoch=epoch-001, classpathEpoch=cp-001
               negativeFact: Do not rely on stale data.
            """;
        memory.recordToolResult("search_code", "{}", secondResult);

        // Assert: 无结构化字段时不做清理
        assertEquals(2, memory.getRagEvidenceCount(), "自由文本 negativeFact 不应触发清理");
    }
}
