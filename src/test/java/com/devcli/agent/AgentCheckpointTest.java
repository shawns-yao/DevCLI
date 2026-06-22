package com.devcli.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentCheckpointTest {

    @TempDir
    Path tempDir;

    private String previousDir;

    @BeforeEach
    void redirectCheckpointDir() {
        previousDir = System.getProperty("devcli.checkpoint.dir");
        System.setProperty("devcli.checkpoint.dir", tempDir.toString());
    }

    @AfterEach
    void restoreCheckpointDir() {
        if (previousDir == null) {
            System.clearProperty("devcli.checkpoint.dir");
        } else {
            System.setProperty("devcli.checkpoint.dir", previousDir);
        }
    }

    @Test
    void roundTripsPlanAndProgress() {
        AgentCheckpoint checkpoint = new AgentCheckpoint("orch-test1", "重构订单模块并补充测试");
        checkpoint.setPlanSteps(List.of(
                new AgentCheckpoint.PlanStep("step-1", "拆分校验逻辑", "code", List.of()),
                new AgentCheckpoint.PlanStep("step-2", "补充单元测试", "test", List.of("step-1"))));
        checkpoint.setAcceptanceCriteria(List.of(
                new AgentCheckpoint.CriterionRecord("ac-1", "critical", "编译通过", "mvn compile", "critical")));
        checkpoint.addCompletedStep("step-1", List.of("src/Order.java"), "校验逻辑已下沉");
        checkpoint.setSupersededSteps(List.of("step-x"));
        checkpoint.save();

        AgentCheckpoint loaded = AgentCheckpoint.load("orch-test1");

        assertNotNull(loaded);
        assertEquals("重构订单模块并补充测试", loaded.getGoal());
        assertEquals(2, loaded.getPlanSteps().size());
        assertEquals(List.of("step-1"), loaded.getPlanSteps().get(1).dependencies());
        assertEquals(1, loaded.getAcceptanceCriteria().size());
        assertTrue(loaded.isStepCompleted("step-1"));
        assertFalse(loaded.isStepCompleted("step-2"));
        assertTrue(loaded.isStepSuperseded("step-x"));
        assertEquals(List.of("src/Order.java"), loaded.getArtifacts().get("step-1").modifiedFiles());
        assertEquals("校验逻辑已下沉", loaded.getArtifacts().get("step-1").summary());
    }

    @Test
    void truncatesOversizedStepResult() {
        AgentCheckpoint checkpoint = new AgentCheckpoint("orch-test2", "goal");
        String huge = "x".repeat(AgentCheckpoint.MAX_SUMMARY_LENGTH + 100);

        checkpoint.addCompletedStep("step-1", List.of(), huge);

        String stored = checkpoint.getArtifacts().get("step-1").summary();
        assertTrue(stored.length() <= AgentCheckpoint.MAX_SUMMARY_LENGTH + 10);
        assertTrue(stored.endsWith("...(截断)"));
    }

    @Test
    void atomicSaveLeavesNoTempFile() throws Exception {
        AgentCheckpoint checkpoint = new AgentCheckpoint("orch-test3", "goal");
        checkpoint.save();

        assertTrue(Files.exists(tempDir.resolve("orch-test3.json")));
        assertFalse(Files.exists(tempDir.resolve("orch-test3.json.tmp")));
    }

    @Test
    void loadLatestPicksMostRecentTimestamp() {
        AgentCheckpoint older = new AgentCheckpoint("orch-old", "older goal");
        older.setTimestamp(1_000L);
        older.save();
        AgentCheckpoint newer = new AgentCheckpoint("orch-new", "newer goal");
        newer.setTimestamp(2_000L);
        newer.save();

        AgentCheckpoint latest = AgentCheckpoint.loadLatest();

        assertNotNull(latest);
        assertEquals("orch-new", latest.getOrchestrationId());
    }

    @Test
    void loadLatestReturnsNullWhenEmpty() {
        assertNull(AgentCheckpoint.loadLatest());
    }

    @Test
    void addFailedStepPersistsModifiedFilesForResume() {
        AgentCheckpoint checkpoint = new AgentCheckpoint("orch-failed", "目标");
        checkpoint.setPlanSteps(List.of(
                new AgentCheckpoint.PlanStep("step-1", "重构接口", "code", List.of())));
        // 失败步骤已写入文件（副作用不可逆），其 modifiedFiles 应进 checkpoint
        checkpoint.addFailedStep("step-1", List.of("src/UserService.java"), "编译失败：签名不匹配");
        checkpoint.save();

        AgentCheckpoint loaded = AgentCheckpoint.load("orch-failed");

        assertNotNull(loaded);
        assertEquals(1, loaded.getFailedArtifacts().size());
        assertEquals(List.of("src/UserService.java"),
                loaded.getFailedArtifacts().get("step-1").modifiedFiles());
        assertTrue(loaded.getFailedArtifacts().get("step-1").summary().contains("签名不匹配"));
        // 失败步骤未进 completed，resume 时会重置 PENDING 重做
        assertFalse(loaded.isStepCompleted("step-1"));
        // addFailedStep 内部调 recordFailure，failedSteps 计数 +1（不重复）
        assertEquals(1, loaded.getFailedSteps());
    }

    @Test
    void completingStepClearsItsStaleFailedArtifact() {
        AgentCheckpoint checkpoint = new AgentCheckpoint("orch-redo", "目标");
        checkpoint.setPlanSteps(List.of(
                new AgentCheckpoint.PlanStep("step-1", "实现", "code", List.of())));
        // 先失败（留下失败 artifact），重做成功后应清理旧失败 artifact，避免成功与失败记录并存
        checkpoint.addFailedStep("step-1", List.of("src/A.java"), "第一次失败");
        checkpoint.addCompletedStep("step-1", List.of("src/A.java"), "重做成功");

        assertTrue(checkpoint.isStepCompleted("step-1"));
        assertFalse(checkpoint.getFailedArtifacts().containsKey("step-1"),
                "重做成功后同 step 的失败 artifact 应被清理");
        assertEquals("重做成功", checkpoint.getArtifacts().get("step-1").summary());
    }
}
