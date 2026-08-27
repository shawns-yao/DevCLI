package com.devcli.memory;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionMemoryArchitectureTest {

    @Test
    void acceptsEventsAndKeepsWorkStateAsAnUpdateProjection() {
        SessionMemory memory = new SessionMemory();
        memory.accept(new SessionMemory.StateChanged("goal", "修复旧问题", "planner", "", 1));
        memory.accept(new SessionMemory.StateChanged("goal", "完成新方案", "planner", "", 3));
        memory.accept(new SessionMemory.StateChanged("goal", "迟到旧状态", "worker", "step-1", 2));

        assertEquals("完成新方案", memory.snapshot().workState().get("goal"));
    }

    @Test
    void stepStateMachineRejectsTerminalStateRollback() {
        SessionMemory memory = new SessionMemory();
        LinkedHashMap<String, String> steps = new LinkedHashMap<>();
        steps.put("step-1", "定位根因");
        memory.accept(new SessionMemory.PlanChanged("plan-v1", "修复问题", steps,
                "planner", "", 1));
        memory.accept(new SessionMemory.StepChanged("step-1", TaskLedger.StepStatus.RUNNING,
                "", "worker-1", 2));
        memory.accept(new SessionMemory.StepChanged("step-1", TaskLedger.StepStatus.DONE,
                "", "worker-1", 3));
        memory.accept(new SessionMemory.StepChanged("step-1", TaskLedger.StepStatus.RUNNING,
                "迟到事件", "worker-2", 4));

        assertTrue(memory.snapshot().taskLedger().contains("已完成: step-1"));
        assertFalse(memory.snapshot().taskLedger().contains("进行中"));
    }

    @Test
    void evidenceJournalClassifiesAndCompressesFailuresAndRegenerableReads() {
        SessionMemory memory = new SessionMemory();
        memory.accept(new SessionMemory.ToolResultObserved(
                "execute_command", "{\"command\":\"mvn test\"}",
                "BUILD FAILURE error: compilation failed", List.of(), "worker-1", "step-1", 1));
        memory.accept(new SessionMemory.ToolResultObserved(
                "read_file", "{\"path\":\"src/Main.java\"}",
                "class Main { }", List.of(), "worker-1", "step-1", 2));

        SessionMemory.SessionSnapshot snapshot = memory.snapshot();
        assertTrue(snapshot.evidenceJournal().stream()
                .anyMatch(item -> item.kind() == SessionMemory.EvidenceKind.FAILURE));
        assertTrue(snapshot.evidenceJournal().stream()
                .anyMatch(item -> item.kind() == SessionMemory.EvidenceKind.REGENERABLE
                        && item.reference().equals("src/Main.java")));
        assertTrue(snapshot.attemptDigests().getFirst().digest().contains("避免重复"));
        assertEquals("step-1", snapshot.attemptDigests().getFirst().stepId());
    }

    @Test
    void repeatedOrdinaryEvidenceFoldsIntoMilestone() {
        SessionMemory memory = new SessionMemory();
        for (int i = 0; i < 3; i++) {
            memory.accept(new SessionMemory.ToolResultObserved(
                    "list_dir", "{\"path\":\"src\"}", "目录结果 " + i,
                    List.of(), "worker-1", "step-1", i + 1));
        }

        SessionMemory.EvidenceSnapshot evidence = memory.snapshot().evidenceJournal().getFirst();
        assertEquals(SessionMemory.EvidenceKind.MILESTONE, evidence.kind());
        assertEquals(3, evidence.occurrences());
    }

    @Test
    void retryingTheSameLogicalEventIsIdempotent() {
        SessionMemory memory = new SessionMemory();
        SessionMemory.ToolResultObserved event = new SessionMemory.ToolResultObserved(
                "list_dir", "{\"path\":\"src\"}", "目录结果",
                List.of(), "worker-1", "step-1", 17);

        memory.accept(event);
        memory.accept(event);

        assertEquals(1, memory.snapshot().evidenceJournal().size());
        assertEquals(1, memory.snapshot().evidenceJournal().getFirst().occurrences());
    }

    @Test
    void roleViewsShareOneProjectionButExposeDifferentEvidence() {
        SessionMemory memory = new SessionMemory();
        memory.accept(new SessionMemory.StateChanged("goal", "分析项目", "planner", "", 1));
        memory.accept(new SessionMemory.ToolResultObserved(
                "write_file", "{\"path\":\"src/A.java\"}", "写入成功",
                List.of(), "worker-1", "step-1", 2));

        String planner = memory.render(SessionMemory.SessionView.PLANNER, 2_000);
        String reviewer = memory.render(SessionMemory.SessionView.REVIEWER, 2_000);

        assertTrue(planner.contains("goal: 分析项目"));
        assertFalse(planner.contains("write_file"));
        assertTrue(reviewer.contains("write_file"));
        assertTrue(memory.snapshot().modifiedFiles().contains("src/A.java"));
    }

    @Test
    void taskRotationClearsPreviousProjectionButKeepsCompletedTaskUntilNextBegin() {
        SessionMemory memory = new SessionMemory();
        memory.beginTask("task-1");
        memory.accept(new SessionMemory.StateChanged("goal", "旧任务", "planner", "", 1));
        memory.endTask("task-1");

        assertTrue(memory.snapshot().taskEnded());
        assertEquals("旧任务", memory.snapshot().workState().get("goal"));

        memory.beginTask("task-2");
        assertEquals("task-2", memory.snapshot().taskId());
        assertFalse(memory.snapshot().workState().containsKey("goal"));
    }

    @Test
    void firstExplicitTaskClearsLegacyProjectionWithoutTaskId() {
        SessionMemory memory = new SessionMemory();
        memory.accept(new SessionMemory.StateChanged("goal", "旧 ReAct 任务", "react", "", 1));

        memory.beginTask("plan-task");

        assertEquals("plan-task", memory.snapshot().taskId());
        assertFalse(memory.snapshot().workState().containsKey("goal"));
    }

    @Test
    void stalePlanEventsAndToolAttributionArePreservedDeterministically() {
        SessionMemory memory = new SessionMemory();
        memory.accept(new SessionMemory.PlanChanged("plan-new", "新目标", Map.of("new", "新步骤"),
                "planner-1", "", 5));
        memory.accept(new SessionMemory.PlanChanged("plan-old", "旧目标", Map.of("old", "旧步骤"),
                "planner-2", "", 4));
        memory.accept(new SessionMemory.ToolResultObserved(
                "read_file", "{\"path\":\"README.md\"}", "内容", List.of(),
                "worker-7", "step-9", 6));

        SessionMemory.SessionSnapshot snapshot = memory.snapshot();
        assertTrue(snapshot.taskLedger().contains("新目标"));
        assertFalse(snapshot.taskLedger().contains("旧目标"));
        assertEquals("worker-7", snapshot.evidenceJournal().getFirst().agentId());
        assertEquals("step-9", snapshot.evidenceJournal().getFirst().stepId());
    }

    @Test
    void rejectsEvidenceFromSupersededLogicalOriginAndRendersFreshness() {
        SessionMemory memory = new SessionMemory();
        memory.accept(new SessionMemory.EvidenceScopeStarted(
                "worker-1", "step-1", 10, 7, 1));
        memory.accept(new SessionMemory.ToolResultObserved(
                "read_file", "{\"path\":\"fresh.txt\"}", "fresh", List.of(),
                "worker-1", "step-1", 10, 7, 2));
        memory.accept(new SessionMemory.EvidenceScopeStarted(
                "worker-1", "step-1", 20, 9, 3));
        memory.accept(new SessionMemory.ToolResultObserved(
                "read_file", "{\"path\":\"late.txt\"}", "late", List.of(),
                "worker-1", "step-1", 10, 7, 4));
        memory.accept(new SessionMemory.ToolResultObserved(
                "read_file", "{\"path\":\"current.txt\"}", "current", List.of(),
                "worker-1", "step-1", 20, 9, 5));

        String rendered = memory.render(SessionMemory.SessionView.WORKER, 2_000);
        SessionMemory.SessionSnapshot snapshot = memory.snapshot();

        assertEquals(2, snapshot.evidenceJournal().size());
        assertFalse(rendered.contains("late.txt"), rendered);
        assertTrue(rendered.contains("origin=20"), rendered);
        assertTrue(rendered.contains("context_epoch=9"), rendered);
    }

    @Test
    void renderUsesOneHardBudgetAndPrioritizesCriticalEvidence() {
        SessionMemory memory = new SessionMemory();
        memory.accept(new SessionMemory.StateChanged("goal", "分析项目", "planner", "", 1));
        for (int i = 0; i < 20; i++) {
            memory.accept(new SessionMemory.ToolResultObserved(
                    "read_file", "{\"path\":\"src/F" + i + ".java\"}", "普通内容".repeat(80),
                    List.of(), "worker", "step-" + i, i + 2));
        }
        memory.accept(new SessionMemory.ToolResultObserved(
                "write_file", "{\"path\":\"src/Important.java\"}", "写入成功",
                List.of(), "worker", "critical", 100));

        String rendered = memory.render(SessionMemory.SessionView.WORKER, 600);

        assertTrue(MemoryEntry.estimateTokens(rendered) <= 620, "预算估算应保持在小幅标记误差内");
        assertTrue(rendered.contains("write_file"), "关键证据应先于普通读取证据进入 Prompt");
    }
}
