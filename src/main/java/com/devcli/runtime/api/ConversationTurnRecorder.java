package com.devcli.runtime.api;

import com.devcli.agent.Agent;
import com.devcli.event.RunEvent;
import com.devcli.event.RunEventSink;
import com.devcli.llm.LlmClient;
import com.devcli.runtime.RunCoordinator;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;

/** CLI 与一次性入口在执行期间追加事件；执行失败也保留已记录事实。 */
public final class ConversationTurnRecorder {
    private ConversationTurnRecorder() { }

    public static <T> T run(Agent agent, RuntimeThreadStore store, String threadId,
                            String mode, String input, String modelInput,
                            Callable<T> action, Function<T, String> output) throws Exception {
        String turnId = "turn_" + UUID.randomUUID().toString().replace("-", "");
        RunCoordinator coordinator = new RunCoordinator(store);
        coordinator.submitInteractive(turnId, threadId, store.activeBranchId(threadId), mode, input);
        coordinator.start(turnId);
        RuntimeEventPublisher journal = new RuntimeEventPublisher(store, threadId, turnId);
        RunEventSink previous = agent.getRunEventSink();
        agent.setRunEventSink(RunEventSink.composite(journal, previous));
        agent.setOriginalHistorySupplier(() -> store.originalConversationMessages(threadId));
        agent.setCompactionSourceCursorSupplier(() -> store.compactionSourceCursor(threadId));
        try {
            journal.emit(new RunEvent.TurnStarted(input));
            if (!"react".equals(mode)) {
                journal.emit(RunEvent.ModelContext.from(0, agent.getConversationHistory()));
                if (!"compact".equals(mode)) agent.appendConversationMessage(LlmClient.Message.user(modelInput));
            }
            T result = action.call();
            String response = output.apply(result);
            if ("compact".equals(mode)) {
                journal.emit(RunEvent.ModelContext.from(0, agent.getConversationHistory()));
            } else if (!"react".equals(mode)) {
                agent.appendConversationMessage(LlmClient.Message.assistant(response));
            }
            RunEvent.ExecutionState state = journal.state();
            if (state == RunEvent.ExecutionState.CANCELLED) {
                journal.emit(new RunEvent.TurnCompleted("cancelled"));
                coordinator.cancel(turnId, response);
            } else if (state == RunEvent.ExecutionState.FAILED || state == RunEvent.ExecutionState.BUDGET_EXCEEDED
                    || state == RunEvent.ExecutionState.ITERATION_LIMIT_REACHED) {
                journal.emit(new RunEvent.TurnFailed(response));
                coordinator.fail(turnId, response);
            } else {
                journal.emit(new RunEvent.TurnCompleted("completed"));
                coordinator.complete(turnId, response.isBlank() ? journal.assistantOutput() : response);
            }
            return result;
        } catch (RunEventSink.PersistenceFailure failure) {
            coordinator.fail(turnId, failure.getMessage());
            throw failure;
        } catch (Exception | Error failure) {
            journal.emit(new RunEvent.TurnFailed(failure.getMessage()));
            coordinator.fail(turnId, failure.getMessage());
            throw failure;
        } finally {
            agent.setRunEventSink(previous);
        }
    }
}
