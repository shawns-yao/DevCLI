package com.devcli.agent;

import com.devcli.plan.Task;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanTaskExecutionResultTest {

    @Test
    void summarizesLastTwoConclusionSentences() {
        Task task = new Task("task-1", "分析结果", Task.TaskType.ANALYSIS);

        PlanTaskExecutionResult result = PlanTaskExecutionResult.success(
                task,
                "第一句。 第二句。 最终结论。",
                true,
                List.of("src/A.java"));

        assertFalse(result.failed());
        assertTrue(result.streamedOutput());
        assertEquals("第二句。 最终结论。", result.resultSummary());
    }

    @Test
    void reportsPartialFilesWhenTaskFails() {
        Task task = new Task("task-2", "修改文件", Task.TaskType.FILE_WRITE);

        PlanTaskExecutionResult result = PlanTaskExecutionResult.failure(
                task,
                new IllegalStateException("编译失败"),
                List.of("src/A.java", "src/B.java"));

        assertTrue(result.failed());
        assertTrue(result.resultSummary().contains("已产生部分文件修改"));
        assertTrue(result.resultSummary().contains("src/A.java"));
        assertTrue(result.resultSummary().contains("编译失败"));
    }

    @Test
    void copiesModifiedFilesDefensively() {
        Task task = new Task("task-3", "读取文件", Task.TaskType.FILE_READ);
        List<String> modifiedFiles = new ArrayList<>(List.of("src/A.java"));

        PlanTaskExecutionResult result = PlanTaskExecutionResult.success(
                task, "完成", false, modifiedFiles);
        modifiedFiles.add("src/B.java");

        assertEquals(List.of("src/A.java"), result.modifiedFiles());
    }
}
