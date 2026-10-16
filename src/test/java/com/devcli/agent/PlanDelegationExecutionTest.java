package com.devcli.agent;

import com.devcli.event.RunEvent;
import com.devcli.memory.LongTermMemory;
import com.devcli.memory.MemoryManager;
import com.devcli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.devcli.agent.AgentDelegationTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** 模型替身下的计划执行适配集成测试，不代表真实模型能力验收。 */
class PlanDelegationExecutionTest {
    @TempDir Path project;

    @Test
    void textOnlyWriteStepCannotBecomeCompleted() throws Exception {
        try (var run = new PlanRun(new ScriptedClient(answer("implemented"), answer("implemented")), "FILE_WRITE")) {
            run.execute();
            assertEquals(AgentOrchestrator.StepStatus.FAILED, run.steps.getFirst().status());
            assertTrue(run.steps.getFirst().result().contains("缺少成功执行证据"));
            assertTrue(run.steps.getFirst().modifiedFiles().isEmpty());
        }
    }

    @Test
    void infrastructureFailureRetainsCandidateWithoutApplyingIt() throws Exception {
        var client = new ScriptedClient(call("write_file", "{\"path\":\"src/main/java/Deferred.java\",\"content\":\"public class Deferred {}\"}"), answer("finished"));
        try (var run = new PlanRun(client, "FILE_WRITE")) {
            run.orchestrator.setPreReviewVerifier(new PreReviewVerifier(30, request ->
                    com.devcli.tool.command.CommandExecutionService.Result.timedOut("Docker daemon unavailable")));
            run.execute();
            assertEquals(AgentOrchestrator.StepStatus.FAILED, run.steps.getFirst().status());
            assertFalse(Files.exists(project.resolve("src/main/java/Deferred.java")));
            assertTrue(run.checkpoint.hasDeferredPatch("s1"));
        }
    }

    @Test
    void finalReviewerFailureIsAdvisoryAfterHardCheckPasses() throws Exception {
        Files.createDirectories(project.resolve("src/main/java"));
        Files.writeString(project.resolve("src/main/java/Ready.java"), "public class Ready {}");
        var client = new ScriptedClient(call("list_dir", "{\"path\":\".\"}"), answer("checked"));
        client.beforeResponse = () -> {
            if (client.requests.size() >= 3) throw new java.io.IOException("reviewer unavailable");
        };
        try (var run = new PlanRun(client, "INTEGRATION")) {
            run.orchestrator.setPreReviewVerifier(new PreReviewVerifier(30, request ->
                    com.devcli.tool.command.CommandExecutionService.Result.completed(0, "ok")));
            run.execute();
            assertEquals(AgentOrchestrator.StepStatus.COMPLETED, run.steps.getFirst().status(), run.steps.getFirst().result());
            assertTrue(run.steps.getFirst().result().contains("final_hard_check"));
        }
    }

    private final class PlanRun implements AutoCloseable {
        final ToolRegistry registry = new ToolRegistry();
        final MemoryManager memory;
        final AgentOrchestrator orchestrator;
        final StepExecutionCoordinator coordinator;
        final AgentCheckpoint checkpoint;
        final ArrayList<AgentOrchestrator.ExecutionStep> steps;
        final String previousDirectory = System.getProperty("devcli.checkpoint.dir");

        PlanRun(ScriptedClient client, String type) throws Exception {
            System.setProperty("devcli.checkpoint.dir", project.resolve("checkpoints").toString());
            registry.setProjectPath(project.toString());
            memory = new MemoryManager(client, 2000, 32000, new LongTermMemory(project.resolve("memory")));
            orchestrator = new AgentOrchestrator(client, registry, memory, new PrintStream(new ByteArrayOutputStream()));
            String task = type.equals("INTEGRATION") ? "最终集成验收 Java 入口" : "实现当前步骤";
            var stateField = AgentOrchestrator.class.getDeclaredField("runState");
            stateField.setAccessible(true);
            var state = (OrchestrationRunState) stateField.get(orchestrator);
            state.beginNew(task);
            checkpoint = new AgentCheckpoint("delegation-" + java.util.UUID.randomUUID(), task);
            checkpoint.setPlanSteps(List.of(new AgentCheckpoint.PlanStep("s1", task, type, List.of())));
            var identitiesMethod = AgentOrchestrator.class.getDeclaredMethod("currentAgentIdentities");
            identitiesMethod.setAccessible(true);
            @SuppressWarnings("unchecked")
            var identities = (List<AgentCheckpoint.AgentIdentityRecord>) identitiesMethod.invoke(orchestrator);
            checkpoint.ensureAgentIdentities(identities);
            state.setCheckpoint(checkpoint);
            var field = AgentOrchestrator.class.getDeclaredField("stepExecutionCoordinator");
            field.setAccessible(true);
            coordinator = (StepExecutionCoordinator) field.get(orchestrator);
            steps = new ArrayList<>(List.of(AgentOrchestrator.ExecutionStep.pending("s1", task, type, List.of())));
        }

        void execute() { coordinator.execute(steps, null); }

        @Override public void close() {
            registry.close();
            memory.close();
            if (previousDirectory == null) System.clearProperty("devcli.checkpoint.dir");
            else System.setProperty("devcli.checkpoint.dir", previousDirectory);
        }
    }

    @Test
    void deferredCheckpointPatchCanBeEditedInTheNewDelegationScope() throws Exception {
        String previous = System.getProperty("devcli.checkpoint.dir");
        System.setProperty("devcli.checkpoint.dir", project.resolve("checkpoints").toString());
        Files.writeString(project.resolve("result.txt"), "base");
        var client = new ScriptedClient(call("edit_file", "{\"path\":\"result.txt\",\"old_string\":\"draft\",\"new_string\":\"final\"}"), answer("finished"));
        try (var memory = new MemoryManager(client, 2000, 32000, new LongTermMemory(project.resolve("memory")));
             var registry = new ToolRegistry()) {
            registry.setProjectPath(project.toString());
            com.devcli.workspace.PatchSet pending;
            try (var workspace = com.devcli.workspace.WorkspaceExecutionSession.open(registry, "old-scope")) {
                Files.writeString(workspace.workspacePath().resolve("result.txt"), "draft");
                pending = workspace.patchSet();
            }
            var checkpoint = new AgentCheckpoint("plan-delegation-resume", "修正 result.txt");
            checkpoint.setPlanSteps(List.of(new AgentCheckpoint.PlanStep("s1", "修正 result.txt", "FILE_WRITE", List.of())));
            checkpoint.preserveDeferredPatch("s1", pending, "验证环境曾不可用");
            var orchestrator = new AgentOrchestrator(client, registry, memory, new PrintStream(new ByteArrayOutputStream()));
            var identitiesMethod = AgentOrchestrator.class.getDeclaredMethod("currentAgentIdentities");
            identitiesMethod.setAccessible(true);
            @SuppressWarnings("unchecked")
            var identities = (List<AgentCheckpoint.AgentIdentityRecord>) identitiesMethod.invoke(orchestrator);
            checkpoint.ensureAgentIdentities(identities);
            var stateField = AgentOrchestrator.class.getDeclaredField("runState");
            stateField.setAccessible(true);
            var state = (OrchestrationRunState) stateField.get(orchestrator);
            state.beginNew("修正 result.txt");
            state.setCheckpoint(checkpoint);
            var field = AgentOrchestrator.class.getDeclaredField("stepExecutionCoordinator");
            field.setAccessible(true);
            var steps = new ArrayList<>(List.of(AgentOrchestrator.ExecutionStep.pending("s1", "修正 result.txt", "FILE_WRITE", List.of())));
            ((StepExecutionCoordinator) field.get(orchestrator)).execute(steps, null);
            assertEquals(AgentOrchestrator.StepStatus.COMPLETED, steps.getFirst().status(), steps.getFirst().result());
            assertEquals("final", Files.readString(project.resolve("result.txt")));
            assertFalse(checkpoint.hasDeferredPatch("s1"));
        } finally {
            if (previous == null) System.clearProperty("devcli.checkpoint.dir");
            else System.setProperty("devcli.checkpoint.dir", previous);
        }
    }

    @Test
    void approvedStepsUseDelegationAndCommitOnlyAfterChildCompletes() throws Exception {
        var client = new ScriptedClient(call("write_file", "{\"path\":\"result.txt\",\"content\":\"done\"}"), answer("finished"));
        client.beforeResponse = () -> assertFalse(Files.exists(project.resolve("result.txt")), "子循环执行期间主项目尚未提交");
        try (var memory = new MemoryManager(client, 2000, 32000, new LongTermMemory(project.resolve("memory")));
             var registry = new ToolRegistry()) {
            registry.setProjectPath(project.toString());
            var orchestrator = new AgentOrchestrator(client, registry, memory, new PrintStream(new ByteArrayOutputStream()));
            List<RunEvent> events = new ArrayList<>();
            orchestrator.setAdditionalEventSink(events::add);
            var stateField = AgentOrchestrator.class.getDeclaredField("runState");
            stateField.setAccessible(true);
            ((OrchestrationRunState) stateField.get(orchestrator)).beginNew("创建 result.txt");
            var field = AgentOrchestrator.class.getDeclaredField("stepExecutionCoordinator");
            field.setAccessible(true);
            var coordinator = (StepExecutionCoordinator) field.get(orchestrator);
            coordinator.setRequireWorkerToolEvidence(true);
            var steps = new ArrayList<>(List.of(AgentOrchestrator.ExecutionStep.pending("s1", "创建 result.txt", "FILE_WRITE", List.of())));
            coordinator.execute(steps, null);
            assertEquals(AgentOrchestrator.StepStatus.COMPLETED, steps.getFirst().status(), steps.getFirst().result());
            assertEquals("done", Files.readString(project.resolve("result.txt")));
            assertTrue(steps.getFirst().result().contains("report_id"));
            assertTrue(steps.getFirst().artifact().summary().contains("tool=write_file"));
            assertFalse(steps.getFirst().artifact().summary().contains("finished"), "模型总结不能晋升为可信执行摘要");
            assertTrue(memory.workPlanForPrompt().contains("创建 result.txt"));
            assertTrue(events.stream().anyMatch(event -> event instanceof RunEvent.CustomMessage custom
                    && custom.messageType().equals("delegation.started")));
        }
    }
}
