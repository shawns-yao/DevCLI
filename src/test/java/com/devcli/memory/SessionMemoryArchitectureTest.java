package com.devcli.memory;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

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
        assertTrue(snapshot.attemptDigests().getFirst().contains("避免重复"));
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
}
