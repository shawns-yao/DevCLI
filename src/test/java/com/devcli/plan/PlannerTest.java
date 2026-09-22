package com.devcli.plan;

import com.devcli.llm.GLMClient;
import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerTest {

    @Test
    void createsMinimalPlanForSimpleGoalWithoutCallingLlm() throws Exception {
        Planner planner = new Planner(new FailingGLMClient());

        ExecutionPlan plan = planner.createPlan("列出当前目录的文件");

        assertEquals("直接执行简单任务：列出当前目录的文件", plan.getSummary());
        assertEquals(List.of("task_1"), plan.getExecutionOrder());
        Task task = plan.getTask("task_1");
        assertEquals(Task.TaskType.COMMAND, task.getType());
        assertEquals("列出当前目录的文件", task.getDescription());
    }

    @Test
    void delegatesComplexGoalToLlmPlannerPath() throws Exception {
        Planner planner = new Planner(new StubGLMClient("""
                {
                  "summary": "复杂任务",
                  "tasks": [
                    {
                      "id": "task_a",
                      "description": "先读取 pom.xml",
                      "type": "FILE_READ",
                      "dependencies": []
                    },
                    {
                      "id": "task_b",
                      "description": "再验证项目结构",
                      "type": "VERIFICATION",
                      "dependencies": ["task_a"]
                    }
                  ]
                }
                """));

        ExecutionPlan plan = planner.createPlan("先读取 pom.xml 然后验证项目结构");

        assertEquals("复杂任务", plan.getSummary());
        assertEquals(2, plan.getAllTasks().size());
        assertTrue(plan.getTask("task_2").getDependencies().contains("task_1"));
    }

    @Test
    void acceptsStepsKeyAsAliasForTasks() throws Exception {
        // 回归：编排层与模型常用 steps 命名，计划层不得只认 tasks 而判空计划
        Planner planner = new Planner(new StubGLMClient("""
                {
                  "summary": "steps 别名",
                  "steps": [
                    {
                      "id": "step_1",
                      "description": "定位迭代器源码",
                      "type": "FILE_READ",
                      "dependencies": []
                    },
                    {
                      "id": "step_2",
                      "description": "最小修复空栈",
                      "type": "FILE_WRITE",
                      "dependencies": ["step_1"]
                    }
                  ]
                }
                """));

        ExecutionPlan plan = planner.createPlan("先定位迭代器源码然后最小修复空栈问题");

        assertEquals("steps 别名", plan.getSummary());
        assertEquals(2, plan.getAllTasks().size());
        // 原始 step_1/step_2 重映射为 task_1/task_2，依赖同步映射
        assertEquals(List.of("task_1"), plan.getTask("task_2").getDependencies());
        assertEquals(Task.TaskType.FILE_WRITE, plan.getTask("task_2").getType());
    }

    @Test
    void replanIncludesStructuredTaskArtifacts() throws Exception {
        StubGLMClient client = new StubGLMClient("""
                {
                  "summary": "重规划",
                  "tasks": [
                    {
                      "id": "task_a",
                      "description": "基于当前文件继续修复启动流程",
                      "type": "FILE_WRITE",
                      "dependencies": []
                    }
                  ]
                }
                """);
        Planner planner = new Planner(client);
        ExecutionPlan failedPlan = new ExecutionPlan("plan_test", "实现配置加载并接入启动");
        Task completed = new Task("task_1", "实现配置加载", Task.TaskType.FILE_WRITE);
        completed.setModifiedFiles(List.of("src/main/java/com/devcli/ConfigLoader.java"));
        completed.setResultSummary("已实现配置加载并补充基础校验");
        completed.markCompleted("SHOULD_NOT_APPEAR_IN_REPLAN_PROMPT");
        failedPlan.addTask(completed);

        Task failed = new Task("task_2", "接入启动流程", Task.TaskType.FILE_WRITE, List.of("task_1"));
        failed.setModifiedFiles(List.of("src/main/java/com/devcli/Main.java"));
        failed.setResultSummary("启动流程已部分修改，后续需基于当前文件继续");
        failed.markFailed("编译失败，缺少构造参数");
        failedPlan.addTask(failed);
        assertTrue(failedPlan.computeExecutionOrder());

        planner.replan(failedPlan, "计划执行中有任务失败");

        String prompt = client.lastUserPrompt();
        assertTrue(prompt.contains("修改文件: src/main/java/com/devcli/ConfigLoader.java"));
        assertTrue(prompt.contains("结论: 已实现配置加载并补充基础校验"));
        assertTrue(prompt.contains("修改文件: src/main/java/com/devcli/Main.java"));
        assertTrue(prompt.contains("错误: 编译失败，缺少构造参数"));
        assertTrue(prompt.contains("新计划不得包含已完成的任务"));
        assertFalse(prompt.contains("SHOULD_NOT_APPEAR_IN_REPLAN_PROMPT"));
    }

    private static final class FailingGLMClient extends GLMClient {
        private FailingGLMClient() {
            super("test-key");
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            throw new IOException("simple goal should not call llm");
        }
    }

    private static final class StubGLMClient extends GLMClient {
        private final String content;
        private List<Message> lastMessages = List.of();

        private StubGLMClient(String content) {
            super("test-key");
            this.content = content;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            this.lastMessages = List.copyOf(messages);
            return new ChatResponse("assistant", content, null, 100, 20);
        }

        private String lastUserPrompt() {
            return lastMessages.stream()
                    .filter(message -> "user".equals(message.role()))
                    .reduce((first, second) -> second)
                    .map(Message::content)
                    .orElse("");
        }
    }
}
