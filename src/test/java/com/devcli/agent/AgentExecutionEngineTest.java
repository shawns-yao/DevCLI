package com.devcli.agent;

import com.devcli.llm.GLMClient;
import com.devcli.llm.LlmClient;
import com.devcli.llm.SamplingRequestCoordinator;
import com.devcli.runtime.event.RunEvent;
import com.devcli.runtime.event.RunEventSink;
import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolRegistry;
import com.devcli.tool.ToolStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentExecutionEngineTest {

    @Test
    void doesNotCallModelWhenPreparationExhaustsTheSharedTokenBudget() {
        ScriptedClient client = new ScriptedClient(List.of());
        AgentBudget budget = new AgentBudget(100, 3, 10);
        RecordingDelegate delegate = new RecordingDelegate() {
            @Override public void beforeIteration(int iteration, AgentBudget current) {
                current.fork().recordTokens(100, 0);
            }
        };
        new AgentExecutionEngine<String>(client, budget).run(delegate);
        assertTrue(client.toolChoices.isEmpty());
        assertEquals(AgentBudget.ExitReason.TOKEN_BUDGET_EXCEEDED, budget.check());
    }

    @Test
    void ownsTheSharedLlmToolLoopAndMessageProtocol() {
        LlmClient.ToolCall call = new LlmClient.ToolCall(
                "call_1", new LlmClient.ToolCall.Function("read_file", "{\"path\":\"a.txt\"}"));
        ScriptedClient llm = new ScriptedClient(List.of(
                new LlmClient.ChatResponse("assistant", "reading", "reasoning", List.of(call), 10, 2),
                new LlmClient.ChatResponse("assistant", "done", null, null, 4, 1)
        ));
        AgentBudget budget = new AgentBudget(1_000, 3, 10);
        RecordingDelegate delegate = new RecordingDelegate();

        String result = new AgentExecutionEngine<String>(llm, budget).run(delegate);

        assertEquals("done", result);
        assertEquals(2, budget.iteration());
        assertEquals(14, budget.totalInputTokens());
        assertEquals(3, budget.totalOutputTokens());
        assertEquals(List.of("before:1", "tools:1", "before:2", "complete"), delegate.events);
        assertEquals(List.of("system", "assistant", "tool", "assistant"),
                delegate.history.stream().map(LlmClient.Message::role).toList());
        assertEquals(List.of(LlmClient.ToolChoice.REQUIRED, LlmClient.ToolChoice.AUTO), llm.toolChoices);
        assertEquals(List.of(RunEvent.ToolCalls.class, RunEvent.ToolResults.class),
                delegate.runEvents.stream()
                        .filter(event -> event instanceof RunEvent.ToolCalls
                                || event instanceof RunEvent.ToolResults)
                        .map(Object::getClass)
                        .toList());
        List<RunEvent.ModelContext> contexts = delegate.runEvents.stream()
                .filter(RunEvent.ModelContext.class::isInstance)
                .map(RunEvent.ModelContext.class::cast)
                .toList();
        assertEquals(2, contexts.size());
        assertEquals("SYSTEM_INTERNAL", contexts.getFirst().messages().getFirst().source());
        assertTrue(delegate.runEvents.stream().anyMatch(RunEvent.ModelUsage.class::isInstance));
        assertEquals(List.of(
                        RunEvent.ExecutionState.THINKING,
                        RunEvent.ExecutionState.TOOL_EXECUTING,
                        RunEvent.ExecutionState.TOOL_RESULTS_PAIRED,
                        RunEvent.ExecutionState.THINKING,
                        RunEvent.ExecutionState.COMPLETED),
                delegate.runEvents.stream()
                        .filter(RunEvent.ExecutionStateChanged.class::isInstance)
                        .map(RunEvent.ExecutionStateChanged.class::cast)
                        .map(RunEvent.ExecutionStateChanged::state)
                        .toList());
    }

    @Test
    void injectsDeterministicConflictInstructionBeforeReasoningContinues() {
        LlmClient.ToolCall call = new LlmClient.ToolCall(
                "call_1", new LlmClient.ToolCall.Function("list_dir", "{\"path\":\".\"}"));
        ScriptedClient llm = new ScriptedClient(List.of(
                new LlmClient.ChatResponse("assistant", "", null, List.of(call), 10, 2),
                new LlmClient.ChatResponse("assistant", "done", null, null, 4, 1)
        ));
        RecordingDelegate delegate = new RecordingDelegate();
        delegate.postToolInstruction = "当前状态已推翻旧记忆，禁止继续依赖旧记忆。";

        String result = new AgentExecutionEngine<String>(
                llm, new AgentBudget(1_000, 3, 10)).run(delegate);

        assertEquals("done", result);
        List<RunEvent.ModelContext> contexts = delegate.runEvents.stream()
                .filter(RunEvent.ModelContext.class::isInstance)
                .map(RunEvent.ModelContext.class::cast)
                .toList();
        assertEquals(2, contexts.size());
        assertTrue(contexts.get(1).messages().stream()
                .anyMatch(message -> message.content().contains("禁止继续依赖旧记忆")
                        && "SYSTEM_INTERNAL".equals(message.source())));
    }

    @Test
    void refreshesStaleContextAndEmitsTypedLifecycle() {
        LlmClient.ToolCall call = new LlmClient.ToolCall(
                "call_1", new LlmClient.ToolCall.Function("write_file", "{}"));
        ScriptedClient llm = new ScriptedClient(List.of(
                new LlmClient.ChatResponse("assistant", "", null, List.of(call), 1, 1),
                new LlmClient.ChatResponse("assistant", "done", null, null, 1, 1)
        ));
        RecordingDelegate delegate = new RecordingDelegate() {
            @Override
            public List<ToolRegistry.ToolExecutionResult> executeTools(
                    List<LlmClient.ToolCall> toolCalls, int iteration) {
                return List.of(new ToolRegistry.ToolExecutionResult(
                        "call_1", "write_file", "{}", "stale", 1,
                        ToolStatus.REJECTED, ToolErrorCode.STALE_CONTEXT, true, List.of()));
            }

            @Override
            public Map<String, String> refreshStaleContext() {
                return Map.of("Service.java", "class Service {}\n");
            }

            @Override
            public String contextScope() {
                return "step-1";
            }
        };

        assertEquals("done", new AgentExecutionEngine<String>(
                llm, new AgentBudget(100, 3, 10)).run(delegate));
        assertEquals(List.of(
                        RunEvent.ContextRefreshState.STALE_CONTEXT,
                        RunEvent.ContextRefreshState.REFRESHING_CONTEXT,
                        RunEvent.ContextRefreshState.RUNNING),
                delegate.runEvents.stream().filter(RunEvent.ContextRefresh.class::isInstance)
                        .map(RunEvent.ContextRefresh.class::cast)
                        .map(RunEvent.ContextRefresh::state).toList());
        assertTrue(delegate.history.stream().anyMatch(message ->
                message.content().contains("<refreshed_file path=\"Service.java\">")));
    }

    @Test
    void registersAndCleansSamplingRequestAroundEachModelCall() {
        SamplingRequestCoordinator coordinator = new SamplingRequestCoordinator();
        ScriptedClient llm = new ScriptedClient(List.of(
                new LlmClient.ChatResponse("assistant", "done", null, null, 1, 1)
        ), () -> assertEquals(1, coordinator.activeCount()));

        String result = new AgentExecutionEngine<String>(
                llm, new AgentBudget(100, 2, 1), null, coordinator).run(new RecordingDelegate());

        assertEquals("done", result);
        assertEquals(0, coordinator.activeCount());
    }

    @Test
    void canCompleteImmediatelyAfterSuccessfulToolResults() {
        LlmClient.ToolCall call = new LlmClient.ToolCall(
                "call_1", new LlmClient.ToolCall.Function("read_file", "{\"path\":\"a.txt\"}"));
        ScriptedClient llm = new ScriptedClient(List.of(
                new LlmClient.ChatResponse("assistant", "", "reasoning", List.of(call), 10, 2)
        ));
        AgentBudget budget = new AgentBudget(1_000, 3, 10);
        RecordingDelegate delegate = new RecordingDelegate(true);

        String result = new AgentExecutionEngine<String>(llm, budget).run(delegate);

        assertEquals("tool-complete", result);
        assertEquals(1, budget.iteration());
        assertEquals(List.of("before:1", "tools:1", "tool-complete"), delegate.events);
        assertEquals(List.of("system", "assistant", "tool"),
                delegate.history.stream().map(LlmClient.Message::role).toList());
        assertEquals(List.of(LlmClient.ToolChoice.REQUIRED), llm.toolChoices);
    }

    @Test
    void repeatToolAdvisorInjectsReminderAndLetsTheLoopContinue() {
        ScriptedClient llm = new ScriptedClient(List.of(
                toolCallResponse("read_file", "{\"path\":\"a.txt\"}"),
                toolCallResponse("read_file", "{\"path\":\"a.txt\"}"),
                toolCallResponse("read_file", "{\"path\":\"a.txt\"}"),
                toolCallResponse("read_file", "{\"path\":\"b.txt\"}"),
                new LlmClient.ChatResponse("assistant", "done", null, null, 1, 1)
        ));
        AgentBudget budget = new AgentBudget(1_000_000, 3, 10);
        SequenceDelegate delegate = new SequenceDelegate(List.of(
                toolResult("read_file", "{\"path\":\"a.txt\"}"),
                toolResult("read_file", "{\"path\":\"a.txt\"}"),
                toolResult("read_file", "{\"path\":\"a.txt\"}"),
                toolResult("read_file", "{\"path\":\"b.txt\"}")));

        String result = new AgentExecutionEngine<String>(llm, budget).run(delegate);

        assertEquals("done", result);
        // 第 3 次连续相同调用后注入温和提醒，循环没有被停滞检测踢出
        List<LlmClient.Message> reminders = delegate.history.stream()
                .filter(m -> "user".equals(m.role()) && m.content() != null
                        && m.content().contains("系统提醒"))
                .toList();
        assertEquals(1, reminders.size());
        assertTrue(reminders.get(0).content().contains("read_file"));
        assertEquals(LlmClient.MessageSource.PLUGIN, reminders.get(0).source());
        assertEquals("assistant", delegate.history.get(delegate.history.size() - 1).role());
    }

    @Test
    void repeatToolAdvisorDefersStagnationExitUntilThresholdsExhausted() {
        List<LlmClient.ChatResponse> responses = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            responses.add(toolCallResponse("read_file", "{\"path\":\"a.txt\"}"));
        }
        AgentBudget budget = new AgentBudget(1_000_000, 3, 10);
        RecordingDelegate delegate = new RecordingDelegate();

        String result = new AgentExecutionEngine<String>(new ScriptedClient(responses), budget).run(delegate);

        // 3/5/8 三次提醒注入后，第 9 次仍重复，停滞检测最终兜底
        assertEquals("budget", result);
        assertEquals(AgentBudget.ExitReason.STAGNATION_DETECTED, budget.check());
        long reminders = delegate.history.stream()
                .filter(m -> "user".equals(m.role()) && m.content() != null
                        && m.content().contains("系统提醒"))
                .count();
        assertEquals(3, reminders);
        assertTrue(delegate.runEvents.stream()
                .filter(RunEvent.CustomMessage.class::isInstance)
                .map(RunEvent.CustomMessage.class::cast)
                .anyMatch(event -> "tool_loop_guard".equals(event.messageType())
                        && "CIRCUIT_BREAKER".equals(event.attributes().get("action"))));
        assertTrue(delegate.runEvents.stream()
                .filter(RunEvent.FailureGuidance.class::isInstance)
                .map(RunEvent.FailureGuidance.class::cast)
                .anyMatch(event -> "BUDGET_EXHAUSTED".equals(event.category())
                        && event.actions().size() == 4));
        List<RunEvent.ExecutionState> terminalStates = delegate.runEvents.stream()
                .filter(RunEvent.ExecutionStateChanged.class::isInstance)
                .map(RunEvent.ExecutionStateChanged.class::cast)
                .map(RunEvent.ExecutionStateChanged::state)
                .filter(state -> state == RunEvent.ExecutionState.COMPLETED
                        || state == RunEvent.ExecutionState.FAILED
                        || state == RunEvent.ExecutionState.CANCELLED
                        || state == RunEvent.ExecutionState.BUDGET_EXCEEDED
                        || state == RunEvent.ExecutionState.ITERATION_LIMIT_REACHED)
                .toList();
        assertEquals(List.of(RunEvent.ExecutionState.BUDGET_EXCEEDED), terminalStates,
                "advisory 只能继续提醒，硬熔断必须产生唯一终态");
    }

    @Test
    void reconcilesMalformedDelegateResultsBeforeWritingToolMessages() {
        List<LlmClient.ToolCall> calls = List.of(
                new LlmClient.ToolCall("call_a",
                        new LlmClient.ToolCall.Function("read_file", "{\"path\":\"a.txt\"}")),
                new LlmClient.ToolCall("call_b",
                        new LlmClient.ToolCall.Function("list_dir", "{\"path\":\".\"}")));
        ScriptedClient llm = new ScriptedClient(List.of(
                new LlmClient.ChatResponse("assistant", "", "reasoning", calls, 1, 1),
                new LlmClient.ChatResponse("assistant", "done", null, null, 1, 1)));
        MalformedResultDelegate delegate = new MalformedResultDelegate();

        String result = new AgentExecutionEngine<String>(
                llm, new AgentBudget(1_000, 3, 10)).run(delegate);

        assertEquals("done", result);
        List<LlmClient.Message> toolMessages = delegate.history.stream()
                .filter(message -> "tool".equals(message.role()))
                .toList();
        assertEquals(List.of("call_a", "call_b"),
                toolMessages.stream().map(LlmClient.Message::toolCallId).toList());
        assertTrue(toolMessages.get(0).content().contains("未返回结果"));
        assertEquals("b", toolMessages.get(1).content());
        assertTrue(delegate.runEvents.stream()
                .filter(RunEvent.CustomMessage.class::isInstance)
                .map(RunEvent.CustomMessage.class::cast)
                .anyMatch(event -> "tool_result_pairing_anomaly".equals(event.messageType())
                        && "3".equals(event.attributes().get("count"))));
    }

    @Test
    void blocksFinalAnswerUntilReferencedFileHasSuccessfulReadEvidence() {
        String storedPath = ".devcli/context-inputs/large.txt";
        ScriptedClient llm = new ScriptedClient(List.of(
                new LlmClient.ChatResponse("assistant", "直接推理", null, null, 1, 1),
                toolCallResponse("read_file", "{\"path\":\"" + storedPath + "\"}"),
                new LlmClient.ChatResponse("assistant", "基于文件证据回答", null, null, 1, 1)
        ));
        AgentBudget budget = new AgentBudget(1_000, 5, 10);
        RecordingDelegate delegate = new RecordingDelegate();
        delegate.history.add(LlmClient.Message.user("""
                分析附件
                <file_reference original_path="large.txt"
                                stored_path=".devcli/context-inputs/large.txt"
                                sha256="abc" evidence_required="true">
                </file_reference>
                """));

        String result = new AgentExecutionEngine<String>(llm, budget).run(delegate);

        assertEquals("基于文件证据回答", result);
        assertEquals(3, budget.iteration());
        assertEquals(LlmClient.ToolChoice.required("read_file"), llm.toolChoices.get(1));
        assertTrue(delegate.history.stream()
                .filter(message -> message.source() == LlmClient.MessageSource.SYSTEM_INTERNAL)
                .anyMatch(message -> message.content().contains("必须先读取")
                        && message.content().contains(storedPath)));
    }

    @Test
    void allowsAnswerWithoutReadForReferenceMetadataQuestions() {
        List<String> metadataQuestions = List.of(
                "这个附件的文件名是什么？",
                "告诉我这个文件的路径和大小",
                "这个文件内容大小是多少？",
                "当前问题是什么？");

        for (String question : metadataQuestions) {
            ScriptedClient llm = new ScriptedClient(List.of(
                    new LlmClient.ChatResponse("assistant", "metadata answer", null, null, 1, 1)));
            AgentBudget budget = new AgentBudget(1_000, 5, 10);
            RecordingDelegate delegate = new RecordingDelegate();
            delegate.history.add(LlmClient.Message.user(question + """

                    <file_reference original_path="large.txt"
                                    stored_path=".devcli/context-inputs/large.txt"
                                    sha256="abc" evidence_required="true">
                    </file_reference>
                    """));

            String result = new AgentExecutionEngine<String>(llm, budget).run(delegate);

            assertEquals("metadata answer", result, question);
            assertEquals(1, budget.iteration(), question);
            assertFalse("read_file".equals(llm.toolChoices.getFirst().toolName()), question);
        }
    }

    @Test
    void requiresReadForContentLocationAndExactErrorQuestions() {
        List<String> evidenceQuestions = List.of(
                "请定位这个文件内容中的报错位置",
                "给出日志里精确的错误信息和行号");

        for (String question : evidenceQuestions) {
            ContextReferenceGuard guard = ContextReferenceGuard.fromHistory(List.of(
                    LlmClient.Message.user(question + """

                            <file_reference stored_path=".devcli/context-inputs/error.log"
                                            sha256="abc" evidence_required="true">
                            </file_reference>
                            """)));

            assertFalse(guard.isSatisfied(), question);
        }
    }

    @Test
    void contentFollowUpReusesMostRecentReferenceAcrossTurns() {
        ContextReferenceGuard.ReferenceRegistry registry = new ContextReferenceGuard.ReferenceRegistry();
        List<LlmClient.Message> history = List.of(
                LlmClient.Message.user("""
                        先保存这个附件
                        <file_reference stored_path=".devcli/context-inputs/error.log"
                                        sha256="abc" evidence_required="true">
                        </file_reference>
                        """),
                LlmClient.Message.assistant("已记录附件引用"),
                LlmClient.Message.user("里面具体是什么异常？"));

        ContextReferenceGuard guard = ContextReferenceGuard.fromHistory(history, registry);

        assertFalse(guard.isSatisfied());
        assertEquals("read_file", guard.toolChoice(LlmClient.ToolChoice.AUTO).toolName());
    }

    @Test
    void contentFollowUpReusesWholeMostRecentAttachmentBatch() {
        ContextReferenceGuard.ReferenceRegistry registry = new ContextReferenceGuard.ReferenceRegistry();
        List<LlmClient.Message> history = List.of(
                LlmClient.Message.user("""
                        <file_reference stored_path=".devcli/context-inputs/a.log" sha256="a" evidence_required="true"></file_reference>
                        <file_reference stored_path=".devcli/context-inputs/b.log" sha256="b" evidence_required="true"></file_reference>
                        """),
                LlmClient.Message.user("比较这些文件的具体错误"));

        ContextReferenceGuard guard = ContextReferenceGuard.fromHistory(history, registry);

        assertFalse(guard.isSatisfied());
        assertTrue(guard.retryInstruction().contains("a.log"));
        assertTrue(guard.retryInstruction().contains("b.log"));
    }

    @Test
    void metadataOnlyFollowUpDoesNotReuseEarlierReference() {
        ContextReferenceGuard.ReferenceRegistry registry = new ContextReferenceGuard.ReferenceRegistry();
        List<LlmClient.Message> history = List.of(
                LlmClient.Message.user("""
                        <file_reference stored_path=".devcli/context-inputs/error.log"
                                        sha256="abc" evidence_required="true">
                        </file_reference>
                        """),
                LlmClient.Message.user("这个文件大小是多少？"));

        ContextReferenceGuard guard = ContextReferenceGuard.fromHistory(history, registry);

        assertTrue(guard.isSatisfied());
    }

    @Test
    void failsClosedAfterReferencedFileCannotBeReadTwice() {
        String storedPath = ".devcli/context-inputs/missing.txt";
        ScriptedClient llm = new ScriptedClient(List.of(
                toolCallResponse("read_file", "{\"path\":\"" + storedPath + "\"}"),
                toolCallResponse("read_file", "{\"path\":\"" + storedPath + "\"}")));
        AgentBudget budget = new AgentBudget(1_000, 5, 10);
        RecordingDelegate delegate = new RecordingDelegate(ToolStatus.ERROR);
        delegate.history.add(LlmClient.Message.user("""
                请分析附件内容
                <file_reference stored_path=".devcli/context-inputs/missing.txt"
                                evidence_required="true">
                </file_reference>
                """));

        String result = new AgentExecutionEngine<String>(llm, budget).run(delegate);

        assertEquals("failed", result);
        assertEquals(2, budget.iteration());
        assertTrue(delegate.failure.getMessage().contains("附件证据不可用"));
        assertTrue(delegate.failure.getMessage().contains(storedPath));
    }

    @Test
    void wrongReadPathsCountTowardReferencedFileFailure() {
        ScriptedClient llm = new ScriptedClient(List.of(
                toolCallResponse("read_file", "{\"path\":\"wrong-a.txt\"}"),
                toolCallResponse("read_file", "{\"path\":\"wrong-b.txt\"}")));
        AgentBudget budget = new AgentBudget(1_000, 5, 10);
        RecordingDelegate delegate = new RecordingDelegate();
        delegate.history.add(LlmClient.Message.user("""
                请分析附件内容
                <file_reference stored_path=".devcli/context-inputs/required.txt"
                                evidence_required="true">
                </file_reference>
                """));

        String result = new AgentExecutionEngine<String>(llm, budget).run(delegate);

        assertEquals("failed", result);
        assertEquals(2, budget.iteration());
        assertTrue(delegate.failure.getMessage().contains("required.txt"));
    }

    @Test
    void failsClosedWhenReferencedSnapshotHashNoLongerMatches(@TempDir Path tempDir) throws Exception {
        Path stored = tempDir.resolve(".devcli/context-inputs/changed.txt");
        Files.createDirectories(stored.getParent());
        Files.writeString(stored, "changed content");
        String storedPath = ".devcli/context-inputs/changed.txt";
        ScriptedClient llm = new ScriptedClient(List.of(
                toolCallResponse("read_file", "{\"path\":\"" + storedPath + "\"}"),
                toolCallResponse("read_file", "{\"path\":\"" + storedPath + "\"}")));
        AgentBudget budget = new AgentBudget(1_000, 5, 10);
        RecordingDelegate delegate = new RecordingDelegate();
        delegate.history.add(LlmClient.Message.user("""
                请分析附件内容
                <file_reference stored_path=".devcli/context-inputs/changed.txt"
                                sha256="0000000000000000000000000000000000000000000000000000000000000000"
                                evidence_required="true">
                </file_reference>
                """));

        String result;
        try (com.devcli.runtime.RunContext ignored =
                     com.devcli.runtime.CancellationContext.startRunContext(tempDir)) {
            result = new AgentExecutionEngine<String>(llm, budget).run(delegate);
        }

        assertEquals("failed", result);
        assertEquals(2, budget.iteration());
        assertTrue(delegate.failure.getMessage().contains("附件证据不可用"));
        assertTrue(delegate.failure.getMessage().contains(storedPath));
    }

    private static LlmClient.ChatResponse toolCallResponse(String tool, String arguments) {
        LlmClient.ToolCall call = new LlmClient.ToolCall(
                "call_1", new LlmClient.ToolCall.Function(tool, arguments));
        return new LlmClient.ChatResponse("assistant", "", "reasoning", List.of(call), 10, 2);
    }

    private static ToolRegistry.ToolExecutionResult toolResult(String tool, String arguments) {
        return new ToolRegistry.ToolExecutionResult(
                "call_1", tool, arguments, "content", 1,
                ToolStatus.SUCCESS, ToolErrorCode.NONE, false, List.of());
    }

    private static final class SequenceDelegate implements AgentExecutionEngine.Delegate<String> {
        private final List<LlmClient.Message> history =
                new ArrayList<>(List.of(LlmClient.Message.system("system")));
        private final Iterator<ToolRegistry.ToolExecutionResult> results;

        private SequenceDelegate(List<ToolRegistry.ToolExecutionResult> results) {
            this.results = results.iterator();
        }

        @Override
        public List<LlmClient.Message> history() {
            return history;
        }

        @Override
        public List<LlmClient.Tool> toolDefinitions(int iteration) {
            return List.of();
        }

        @Override
        public LlmClient.StreamListener streamListener() {
            return LlmClient.StreamListener.NO_OP;
        }

        @Override
        public void beforeIteration(int iteration, AgentBudget budget) {
        }

        @Override
        public List<ToolRegistry.ToolExecutionResult> executeTools(
                List<LlmClient.ToolCall> toolCalls, int iteration) {
            return List.of(results.next());
        }

        @Override
        public String completed(LlmClient.ChatResponse response, AgentBudget budget) {
            return response.content();
        }

        @Override
        public String cancelled(AgentBudget budget) {
            return "cancelled";
        }

        @Override
        public String budgetExceeded(AgentBudget.ExitReason reason, AgentBudget budget) {
            return "budget";
        }

        @Override
        public String iterationLimitReached(AgentBudget budget) {
            return "limit";
        }

        @Override
        public String failed(IOException error, AgentBudget budget) {
            return "failed";
        }
    }

    private static class RecordingDelegate implements AgentExecutionEngine.Delegate<String> {
        private final List<LlmClient.Message> history = new ArrayList<>(List.of(LlmClient.Message.system("system")));
        private final List<String> events = new ArrayList<>();
        private final List<RunEvent> runEvents = new ArrayList<>();
        private final boolean completeAfterTools;
        private final ToolStatus readStatus;
        private String postToolInstruction = "";
        private IOException failure;

        private RecordingDelegate() {
            this(false);
        }

        private RecordingDelegate(boolean completeAfterTools) {
            this(completeAfterTools, ToolStatus.SUCCESS);
        }

        private RecordingDelegate(ToolStatus readStatus) {
            this(false, readStatus);
        }

        private RecordingDelegate(boolean completeAfterTools, ToolStatus readStatus) {
            this.completeAfterTools = completeAfterTools;
            this.readStatus = readStatus;
        }

        @Override
        public List<LlmClient.Message> history() {
            return history;
        }

        @Override
        public List<LlmClient.Tool> toolDefinitions(int iteration) {
            return List.of();
        }

        @Override
        public LlmClient.StreamListener streamListener() {
            return LlmClient.StreamListener.NO_OP;
        }

        @Override
        public RunEventSink eventSink() {
            return runEvents::add;
        }

        @Override
        public LlmClient.ToolChoice toolChoice(int iteration) {
            return iteration == 1 ? LlmClient.ToolChoice.REQUIRED : LlmClient.ToolChoice.AUTO;
        }

        @Override
        public void beforeIteration(int iteration, AgentBudget budget) {
            events.add("before:" + iteration);
        }

        @Override
        public List<ToolRegistry.ToolExecutionResult> executeTools(List<LlmClient.ToolCall> toolCalls,
                                                                   int iteration) {
            events.add("tools:" + iteration);
            return List.of(new ToolRegistry.ToolExecutionResult(
                    "call_1", "read_file", "{\"path\":\"a.txt\"}", "content",
                    1, readStatus,
                    readStatus == ToolStatus.SUCCESS ? ToolErrorCode.NONE : ToolErrorCode.EXECUTION_FAILED,
                    readStatus != ToolStatus.SUCCESS, List.of()));
        }

        @Override
        public String instructionAfterToolResults(
                LlmClient.ChatResponse response,
                List<ToolRegistry.ToolExecutionResult> toolResults,
                int iteration,
                AgentBudget budget) {
            String instruction = postToolInstruction;
            postToolInstruction = "";
            return instruction;
        }

        @Override
        public Optional<String> completedAfterToolResults(
                LlmClient.ChatResponse response,
                List<ToolRegistry.ToolExecutionResult> toolResults,
                int iteration,
                AgentBudget budget) {
            if (!completeAfterTools) {
                return Optional.empty();
            }
            events.add("tool-complete");
            return Optional.of("tool-complete");
        }

        @Override
        public String completed(LlmClient.ChatResponse response, AgentBudget budget) {
            events.add("complete");
            return response.content();
        }

        @Override
        public String cancelled(AgentBudget budget) {
            return "cancelled";
        }

        @Override
        public String budgetExceeded(AgentBudget.ExitReason reason, AgentBudget budget) {
            return "budget";
        }

        @Override
        public String iterationLimitReached(AgentBudget budget) {
            return "limit";
        }

        @Override
        public String failed(IOException error, AgentBudget budget) {
            failure = error;
            return "failed";
        }
    }

    private static final class MalformedResultDelegate implements AgentExecutionEngine.Delegate<String> {
        private final List<LlmClient.Message> history =
                new ArrayList<>(List.of(LlmClient.Message.system("system")));
        private final List<RunEvent> runEvents = new ArrayList<>();

        @Override
        public List<LlmClient.Message> history() {
            return history;
        }

        @Override
        public List<LlmClient.Tool> toolDefinitions(int iteration) {
            return List.of();
        }

        @Override
        public LlmClient.StreamListener streamListener() {
            return LlmClient.StreamListener.NO_OP;
        }

        @Override
        public RunEventSink eventSink() {
            return runEvents::add;
        }

        @Override
        public void beforeIteration(int iteration, AgentBudget budget) {
        }

        @Override
        public List<ToolRegistry.ToolExecutionResult> executeTools(
                List<LlmClient.ToolCall> toolCalls, int iteration) {
            return List.of(
                    new ToolRegistry.ToolExecutionResult(
                            "call_b", "list_dir", "{\"path\":\".\"}", "b", 1,
                            ToolStatus.SUCCESS, ToolErrorCode.NONE, false, List.of()),
                    new ToolRegistry.ToolExecutionResult(
                            "unknown", "grep_code", "{}", "unknown", 1,
                            ToolStatus.SUCCESS, ToolErrorCode.NONE, false, List.of()),
                    new ToolRegistry.ToolExecutionResult(
                            "call_b", "list_dir", "{\"path\":\".\"}", "duplicate", 1,
                            ToolStatus.SUCCESS, ToolErrorCode.NONE, false, List.of()));
        }

        @Override
        public String completed(LlmClient.ChatResponse response, AgentBudget budget) {
            return response.content();
        }

        @Override
        public String cancelled(AgentBudget budget) {
            return "cancelled";
        }

        @Override
        public String budgetExceeded(AgentBudget.ExitReason reason, AgentBudget budget) {
            return "budget";
        }

        @Override
        public String iterationLimitReached(AgentBudget budget) {
            return "limit";
        }

        @Override
        public String failed(IOException error, AgentBudget budget) {
            return "failed";
        }
    }

    private static final class ScriptedClient extends GLMClient {
        private final Iterator<LlmClient.ChatResponse> responses;
        private final List<LlmClient.ToolChoice> toolChoices = new ArrayList<>();
        private final Runnable beforeResponse;

        private ScriptedClient(List<LlmClient.ChatResponse> responses) {
            this(responses, () -> { });
        }

        private ScriptedClient(List<LlmClient.ChatResponse> responses, Runnable beforeResponse) {
            super("test-key");
            this.responses = responses.iterator();
            this.beforeResponse = beforeResponse;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            return chat(messages, tools, listener, LlmClient.ToolChoice.AUTO);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                 StreamListener listener, LlmClient.ToolChoice toolChoice) throws IOException {
            toolChoices.add(toolChoice);
            beforeResponse.run();
            if (!responses.hasNext()) {
                throw new IOException("script exhausted");
            }
            return responses.next();
        }
    }
}
