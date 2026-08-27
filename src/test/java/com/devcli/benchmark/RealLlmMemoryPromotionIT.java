package com.devcli.benchmark;

import com.devcli.config.DevCliConfig;
import com.devcli.llm.LlmClient;
import com.devcli.llm.LlmClientFactory;
import com.devcli.memory.IsolatedMemoryCurator;
import com.devcli.memory.LongTermMemory;
import com.devcli.memory.MemoryEntry;
import com.devcli.memory.MemoryPromotionPipeline;
import com.devcli.memory.MemoryPromotionQueue;
import com.devcli.memory.TaskMemorySnapshot;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealLlmMemoryPromotionIT {
    @TempDir
    Path tempDir;

    @Test
    void promotesReusableProjectKnowledgeWithConfiguredDeepSeek() {
        Assumptions.assumeTrue(Boolean.getBoolean("devcli.benchmark.memory.promotion"),
                "set -Ddevcli.benchmark.memory.promotion=true to run real promotion test");

        LlmClient client = LlmClientFactory.create("deepseek", DevCliConfig.load());
        assertNotNull(client, "DeepSeek credentials must be configured for the real promotion test");
        assertEquals("deepseek", client.getProviderName());
        assertEquals("deepseek-v4-flash", client.getModelName());

        TaskMemorySnapshot snapshot = new TaskMemorySnapshot(
                "real-promotion-1", "devcli-real-test-project",
                "请记住这个项目的固定构建约定：所有 Maven 命令必须追加 -Dfile.encoding=UTF-8。",
                "已经使用 mvn -Dfile.encoding=UTF-8 test 完成全量测试，结果通过。",
                Map.of("goal", "固化可跨任务复用的构建约定", "status", "completed"),
                List.of("execute_command:mvn -Dfile.encoding=UTF-8 test:SUCCESS"), Instant.now());

        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile());
             MemoryPromotionQueue queue = new MemoryPromotionQueue(tempDir)) {
            MemoryPromotionPipeline pipeline = new MemoryPromotionPipeline(
                    queue, new IsolatedMemoryCurator(client), memory);
            String jobId = pipeline.enqueue(snapshot);

            assertTrue(pipeline.processNext(), () -> "promotion failed: "
                    + queue.find(jobId).map(MemoryPromotionQueue.Job::detail).orElse("missing job"));
            MemoryPromotionQueue.Job job = queue.find(jobId).orElseThrow();
            if (job.state() == MemoryPromotionQueue.State.AWAITING_CONFIRMATION) {
                assertTrue(pipeline.confirm(jobId, true, ""), "confirmation must complete the durable job");
                job = queue.find(jobId).orElseThrow();
            }

            assertEquals(MemoryPromotionQueue.State.COMMITTED, job.state(),
                    "curator did not retain reusable knowledge: " + job.detail());
            MemoryEntry stored = memory.retrieve(job.resultRef()).orElseThrow();
            assertTrue(stored.getContent().contains("Maven")
                    || stored.getContent().contains("mvn"));
            assertEquals("PROJECT", stored.getMetadata().get("scope_type"));
            assertEquals("devcli-real-test-project", stored.getMetadata().get("scope_key"));
        }
    }
}
