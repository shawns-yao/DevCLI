package com.devcli.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.devcli.plan.ExecutionArtifact;
import com.devcli.plan.ExecutionGraph;
import com.devcli.workspace.PatchSet;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

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
                new AgentCheckpoint.CriterionRecord(
                        "ac-1", "critical", "编译通过", "mvn compile", "critical",
                        "TOOL", "execute_command", List.of("step-2", "FINAL"))));
        checkpoint.addCompletedStep("step-1", List.of("src/Order.java"), "校验逻辑已下沉");
        checkpoint.setSupersededSteps(List.of("step-x"));
        checkpoint.save();

        AgentCheckpoint loaded = AgentCheckpoint.load("orch-test1");

        assertNotNull(loaded);
        assertEquals("重构订单模块并补充测试", loaded.getGoal());
        assertEquals(2, loaded.getPlanSteps().size());
        assertEquals(List.of("step-1"), loaded.getPlanSteps().get(1).dependencies());
        assertEquals(1, loaded.getAcceptanceCriteria().size());
        assertEquals("TOOL", loaded.getAcceptanceCriteria().get(0).verificationMethod());
        assertEquals("execute_command", loaded.getAcceptanceCriteria().get(0).verifier());
        assertEquals(List.of("step-2", "FINAL"), loaded.getAcceptanceCriteria().get(0).appliesTo());
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

    @Test
    void roundTripsRedoBudgetAndAttemptEvidence() {
        AgentCheckpoint checkpoint = new AgentCheckpoint("orch-redo-history", "目标");
        checkpoint.recordRedoAttempt(
                "step-1", 1, "编译失败：签名不匹配", List.of("src/A.java"));
        checkpoint.save();

        AgentCheckpoint loaded = AgentCheckpoint.load("orch-redo-history");

        assertEquals(1, loaded.getRedoCounts().get("step-1"));
        assertEquals(1, loaded.getRedoAttempts().size());
        AgentCheckpoint.RedoAttemptRecord attempt = loaded.getRedoAttempts().get(0);
        assertEquals("step-1", attempt.stepId());
        assertEquals(1, attempt.attempt());
        assertEquals("编译失败：签名不匹配", attempt.failureReason());
        assertEquals(List.of("src/A.java"), attempt.modifiedFiles());
        assertEquals(1, loaded.recoveryState().redoCounts().get("step-1"));
        assertEquals(1, loaded.recoveryState().redoAttempts().size());
        assertEquals(Set.of("step-1"), loaded.recoveryState().redoPendingSteps());
    }

    @Test
    void clearsRedoPendingMarkerWhenAttemptReachesTerminalState() {
        AgentCheckpoint checkpoint = new AgentCheckpoint("orch-redo-terminal", "目标");
        checkpoint.recordRedoAttempt("step-1", 1, "首次失败", List.of("src/A.java"));

        checkpoint.addFailedStep("step-1", List.of("src/A.java"), "重做仍失败");

        assertTrue(checkpoint.getRedoPendingSteps().isEmpty());
        assertEquals(1, checkpoint.getRedoCounts().get("step-1"));
    }

    @Test
    void reconcilesAppliedPatchJournalAsCompleted(@TempDir Path project) throws Exception {
        Path target = project.resolve("shared.txt");
        Files.writeString(target, "before");
        byte[] before = "before".getBytes(StandardCharsets.UTF_8);
        byte[] after = "after".getBytes(StandardCharsets.UTF_8);
        PatchSet patchSet = new PatchSet(List.of(new PatchSet.FileChange(
                "shared.txt", PatchSet.ChangeType.MODIFY,
                PatchSet.hash(before), PatchSet.hash(after), after)));
        ExecutionArtifact intended = ExecutionArtifact.pending("step-1")
                .start(10L)
                .complete("done", "done", List.of("shared.txt"), 20L);
        AgentCheckpoint checkpoint = new AgentCheckpoint("orch-journal-applied", "goal");
        checkpoint.preparePatchCommit("step-1", project, patchSet, intended);
        assertTrue(patchSet.apply(project).applied());

        AgentCheckpoint loaded = AgentCheckpoint.load("orch-journal-applied");
        assertNotNull(loaded);
        AgentCheckpoint.PatchReconcileResult result = loaded.reconcilePendingPatchCommits(project);

        assertEquals(AgentCheckpoint.PatchReconcileAction.PROMOTED_COMPLETED,
                result.actions().get("step-1"));
        assertTrue(loaded.recoveryState().artifacts().get("step-1").successful());
        assertEquals("after", Files.readString(target));
        assertTrue(loaded.getPendingPatchCommits().isEmpty());
    }

    @Test
    void reconcilesMixedPatchJournalByRollingBack(@TempDir Path project) throws Exception {
        byte[] aBefore = "a-before".getBytes(StandardCharsets.UTF_8);
        byte[] bBefore = "b-before".getBytes(StandardCharsets.UTF_8);
        Files.write(project.resolve("a.txt"), aBefore);
        Files.write(project.resolve("b.txt"), bBefore);
        byte[] aAfter = "a-after".getBytes(StandardCharsets.UTF_8);
        byte[] bAfter = "b-after".getBytes(StandardCharsets.UTF_8);
        PatchSet patchSet = new PatchSet(List.of(
                new PatchSet.FileChange("a.txt", PatchSet.ChangeType.MODIFY,
                        PatchSet.hash(aBefore), PatchSet.hash(aAfter), aAfter),
                new PatchSet.FileChange("b.txt", PatchSet.ChangeType.MODIFY,
                        PatchSet.hash(bBefore), PatchSet.hash(bAfter), bAfter)
        ));
        AgentCheckpoint checkpoint = new AgentCheckpoint("orch-journal-mixed", "goal");
        checkpoint.preparePatchCommit("step-1", project, patchSet,
                ExecutionArtifact.completed("step-1", "done", "done", List.of("a.txt", "b.txt")));
        Files.write(project.resolve("a.txt"), aAfter);

        AgentCheckpoint loaded = AgentCheckpoint.load("orch-journal-mixed");
        assertNotNull(loaded);
        AgentCheckpoint.PatchReconcileResult result = loaded.reconcilePendingPatchCommits(project);

        assertEquals(AgentCheckpoint.PatchReconcileAction.ROLLED_BACK,
                result.actions().get("step-1"), result.failures().toString());
        assertEquals("a-before", Files.readString(project.resolve("a.txt")));
        assertEquals("b-before", Files.readString(project.resolve("b.txt")));
        assertFalse(loaded.recoveryState().artifacts().containsKey("step-1"));
        assertTrue(loaded.getPendingPatchCommits().isEmpty());
    }

    @Test
    void patchJournalStepIdCannotEscapeJournalRoot(@TempDir Path project) throws Exception {
        Path marker = tempDir.resolve("keep.txt");
        Files.writeString(marker, "keep");
        AgentCheckpoint checkpoint = new AgentCheckpoint("orch-safe-journal", "goal");

        checkpoint.preparePatchCommit("..", project, new PatchSet(List.of()),
                ExecutionArtifact.pending(".."));

        assertTrue(Files.exists(marker), "步骤标识不能删除写前日志根目录中的其他文件");
    }

    @Test
    void recoveryStateNormalizesCompletedAndFailedArtifacts() {
        AgentCheckpoint checkpoint = new AgentCheckpoint("orch-v2", "目标");
        checkpoint.setPlanSteps(List.of(
                new AgentCheckpoint.PlanStep("step-1", "完成步骤", "code", List.of()),
                new AgentCheckpoint.PlanStep("step-2", "失败步骤", "test", List.of("step-1"))));
        checkpoint.addCompletedStep("step-1", List.of("src/A.java"), "完成");
        checkpoint.addFailedStep("step-2", List.of("src/B.java"), "测试失败");

        AgentCheckpoint.RecoveryState recovery = checkpoint.recoveryState();
        ExecutionArtifact completed = recovery.artifacts().get("step-1");
        ExecutionArtifact failed = recovery.artifacts().get("step-2");

        assertEquals(AgentCheckpoint.CURRENT_PROTOCOL_VERSION, recovery.protocolVersion());
        assertEquals(ExecutionGraph.NodeState.COMPLETED, completed.state());
        assertEquals(ExecutionGraph.NodeState.FAILED, failed.state());
        assertEquals(List.of("src/A.java"), completed.modifiedResources());
        assertEquals("测试失败", failed.error());
    }

    @Test
    void rejectsCheckpointFromFutureProtocolVersion() {
        AgentCheckpoint checkpoint = new AgentCheckpoint("orch-future", "目标");
        checkpoint.setProtocolVersion(AgentCheckpoint.CURRENT_PROTOCOL_VERSION + 1);
        checkpoint.save();

        AgentCheckpoint.LoadResult result = AgentCheckpoint.loadResult("orch-future");

        assertEquals(AgentCheckpoint.LoadStatus.INCOMPATIBLE, result.status());
        assertNull(result.checkpoint());
        assertTrue(result.message().contains("不兼容"));
    }

    @Test
    void roundTripsStableAgentIdentityCursorAndAssignment() throws Exception {
        AgentCheckpoint checkpoint = new AgentCheckpoint("orch-agents", "目标");
        checkpoint.ensureAgentIdentities(agentIdentities());
        checkpoint.assignStep("step-1", "worker-2", "reviewer");
        assertTrue(checkpoint.advanceAgentCursor("worker-2", "step-1", "完成接口重构"));
        assertTrue(checkpoint.advanceAgentCursor("reviewer", "step-1", "审查通过"));
        checkpoint.saveStrict();

        AgentCheckpoint loaded = AgentCheckpoint.load("orch-agents");
        AgentCheckpoint.RecoveryState recovery = loaded.recoveryState();

        assertEquals(4, recovery.agentIdentities().size());
        assertEquals("worker-2", recovery.stepAssignments().get("step-1").workerAgentId());
        assertEquals(1, recovery.agentCursors().get("worker-2").lastMessageSeq());
        assertEquals(2, recovery.agentCursors().get("reviewer").lastMessageSeq());
        assertEquals(2, recovery.messageSequence());
        assertFalse(Files.readString(tempDir.resolve("orch-agents.json"))
                .contains("conversationHistory"));
    }

    @Test
    void duplicateMessageBoundaryDoesNotAdvanceCursor() {
        AgentCheckpoint checkpoint = new AgentCheckpoint("orch-cursor", "目标");
        checkpoint.ensureAgentIdentities(agentIdentities());

        assertTrue(checkpoint.advanceAgentCursor("worker-1", "step-1", "执行完成"));
        assertFalse(checkpoint.advanceAgentCursor("worker-1", "step-1", "执行完成"));

        assertEquals(1, checkpoint.getMessageSequence());
        assertEquals(1, checkpoint.getAgentCursors().get("worker-1").lastMessageSeq());
    }

    @Test
    void rejectsDuplicateAgentIdentityDuringLoad() throws Exception {
        String invalid = """
                {
                  "protocolVersion": 4,
                  "orchestrationId": "orch-invalid-agent",
                  "goal": "目标",
                  "agentIdentities": [
                    {"agentId":"worker-1","role":"WORKER","displayName":"worker-1","contextSchemaVersion":1,"createdAt":1,"updatedAt":1},
                    {"agentId":"worker-1","role":"WORKER","displayName":"worker-1","contextSchemaVersion":1,"createdAt":1,"updatedAt":1}
                  ],
                  "agentCursors": {},
                  "stepAssignments": {}
                }
                """;
        Files.writeString(tempDir.resolve("orch-invalid-agent.json"), invalid, StandardCharsets.UTF_8);

        AgentCheckpoint.LoadResult result = AgentCheckpoint.loadResult("orch-invalid-agent");

        assertEquals(AgentCheckpoint.LoadStatus.INVALID, result.status());
        assertNull(result.checkpoint());
    }

    @Test
    void loadsCheckpointFromLegacyProtocolVersion() {
        for (int version : List.of(1, 2, 3)) {
            String id = "orch-legacy-v" + version;
            AgentCheckpoint checkpoint = new AgentCheckpoint(id, "目标");
            checkpoint.setProtocolVersion(version);
            checkpoint.save();

            AgentCheckpoint loaded = AgentCheckpoint.load(id);

            assertNotNull(loaded);
            assertEquals(version, loaded.recoveryState().protocolVersion());
            assertTrue(loaded.recoveryState().agentIdentities().isEmpty());
        }
    }

    private static List<AgentCheckpoint.AgentIdentityRecord> agentIdentities() {
        return List.of(
                new AgentCheckpoint.AgentIdentityRecord("planner", "PLANNER", "planner", 1, 1, 1),
                new AgentCheckpoint.AgentIdentityRecord("worker-1", "WORKER", "worker-1", 1, 1, 1),
                new AgentCheckpoint.AgentIdentityRecord("worker-2", "WORKER", "worker-2", 1, 1, 1),
                new AgentCheckpoint.AgentIdentityRecord("reviewer", "REVIEWER", "reviewer", 1, 1, 1));
    }
}
