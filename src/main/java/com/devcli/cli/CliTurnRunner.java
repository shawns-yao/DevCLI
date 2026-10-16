package com.devcli.cli;

import com.devcli.cli.turn.ActiveTurnInput;
import com.devcli.cli.turn.TerminalInputCoordinator;
import com.devcli.cli.turn.TurnExecutionGuard;
import com.devcli.agent.AgentTurnInbox;
import com.devcli.render.Renderer;
import com.devcli.render.inline.InlineRenderer;
import com.devcli.concurrent.CancellationContext;
import com.devcli.concurrent.CancellationToken;
import com.devcli.concurrent.RunContext;
import org.jline.terminal.Terminal;
import org.jline.terminal.Attributes;
import org.jline.reader.LineReader;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;
import org.jline.utils.NonBlockingReader;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.devcli.cli.CliApplication.*;
import static com.devcli.cli.TerminalInputSupport.*;
import static com.devcli.cli.CliPlanReview.*;
import static com.devcli.cli.CliSettings.*;
import static com.devcli.cli.CliDisplay.*;

/** 单轮执行、输入转交和取消等待。 */
final class CliTurnRunner {
    private static final Logger log = LoggerFactory.getLogger(CliTurnRunner.class);

    static final long CANCEL_QUIESCE_TIMEOUT_SECONDS = 5;

    record TurnRunResult(String response, String draft, boolean executionQuiesced) {
        static TurnRunResult completed(String response) {
            return new TurnRunResult(response, "", true);
        }
    }

    static AgentTurnInbox.Item pollPendingAgentPrompt(AgentTurnInbox inbox) {
        return inbox.pollNext();
    }

    static TurnRunResult runWithCancelSupport(Terminal terminal,
                                                      LineReader lineReader,
                                                      Renderer renderer,
                                                      PrintStream out,
                                                      Path projectPath,
                                                      AgentTurnInbox inbox,
                                                      TerminalInputCoordinator terminalInput,
                                                      boolean acceptActiveTurnInput,
                                                      String initialDraft,
                                                      Callable<String> task) {
        RunContext runContext = CancellationContext.startRunContext(projectPath);
        CancellationToken token = runContext.cancellationToken();
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "devcli-agent-runner");
            thread.setDaemon(true);
            return thread;
        });
        AtomicBoolean taskCompleted = new AtomicBoolean(false);
        terminalInput.begin(taskCompleted::get);
        TurnExecutionGuard executionGuard = new TurnExecutionGuard();
        Future<String> future = executor.submit(executionGuard.wrap(task, () -> {
            taskCompleted.set(true);
            if (acceptActiveTurnInput) {
                terminalInput.wake();
            }
        }));
        Attributes original = null;
        String draft = initialDraft == null ? "" : initialDraft;
        Runnable cancelCurrent = () -> {
            token.cancel();
            future.cancel(true);
        };
        try {
            if (!acceptActiveTurnInput) {
                if (terminal != null) {
                    try {
                        original = terminal.enterRawMode();
                    } catch (Exception ignored) {
                        // 非交互终端保留任务执行，仅关闭按键取消能力。
                    }
                }
                while (!future.isDone()) {
                    terminalInput.serviceInteractions();
                    if (original != null && readEscCancel(terminal)) {
                        cancelCurrent.run();
                        return cancelledTurn("⏹️ 已请求取消当前任务。", executionGuard);
                    }
                    try {
                        return TurnRunResult.completed(future.get(150, TimeUnit.MILLISECONDS));
                    } catch (java.util.concurrent.TimeoutException ignored) {
                        // 继续监听 ESC。
                    }
                }
                return TurnRunResult.completed(future.get());
            }

            while (!future.isDone()) {
                terminalInput.serviceInteractions();
                if (future.isDone()) break;
                String activeInput;
                renderer.beforeInput();
                try {
                    var input = terminalInput.read(renderer.inputPrompt(), renderer.inputRightPrompt(), draft);
                    if (input.suspended()) {
                        draft = input.text();
                        continue;
                    }
                    activeInput = input.text();
                    draft = "";
                } catch (UserInterruptException e) {
                    if (taskCompleted.get()) {
                        draft = e.getPartialLine() == null ? "" : e.getPartialLine();
                        break;
                    }
                    cancelCurrent.run();
                    return cancelledTurn("⏹️ 已请求取消当前任务。", executionGuard);
                } catch (EndOfFileException e) {
                    cancelCurrent.run();
                    return cancelledTurn("⏹️ 已请求取消当前任务。", executionGuard);
                } finally {
                    renderer.afterInput();
                }
                if (renderer instanceof InlineRenderer inline) {
                    inline.clearAcceptedInput(activeInput);
                }
                ActiveTurnInput.Parsed parsed = ActiveTurnInput.parse(activeInput);
                AgentTurnInbox.EnqueueResult queued = switch (parsed.action()) {
                    case QUEUE -> inbox.enqueueFollowUp(parsed.text());
                    case INTERRUPT -> inbox.enqueueSteering(parsed.text());
                    case CANCEL -> new AgentTurnInbox.EnqueueResult(true, null, "", inbox.snapshot());
                    case IGNORE -> new AgentTurnInbox.EnqueueResult(false, null, "消息不能为空", inbox.snapshot());
                };
                if (!queued.accepted()) {
                    draft = activeInput;
                    if (!activeInput.isBlank()) out.println(parsed.action() == ActiveTurnInput.Action.IGNORE
                            ? "/now 需要提供立即执行的任务内容。" : "未加入队列：" + queued.reason());
                    continue;
                }
                if (parsed.action() == ActiveTurnInput.Action.QUEUE) {
                    out.println("已加入后续队列，当前等待 " + queued.snapshot().size() + " 项。");
                    continue;
                }
                if (parsed.action() == ActiveTurnInput.Action.INTERRUPT) {
                    cancelCurrent.run();
                    return cancelledTurn("⏹️ 已取消当前任务，将立即执行新任务。", executionGuard);
                }
                if (parsed.action() == ActiveTurnInput.Action.CANCEL) {
                    cancelCurrent.run();
                    return cancelledTurn("⏹️ 已请求取消当前任务。", executionGuard);
                }
            }
            return new TurnRunResult(future.get(), draft, true);
        } catch (CancellationException e) {
            return cancelledTurn("⏹️ 已取消当前任务。", executionGuard);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelCurrent.run();
            return cancelledTurn("⏹️ 已取消当前任务。", executionGuard);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String message = cause == null || cause.getMessage() == null ? "未知错误" : cause.getMessage();
            return TurnRunResult.completed("❌ 执行失败: " + message);
        } finally {
            terminalInput.end();
            if (terminal != null && original != null) {
                try {
                    terminal.setAttributes(original);
                } catch (Exception ignored) {
                }
            }
            runContext.close();
            executor.shutdownNow();
        }
    }

    static TurnRunResult cancelledTurn(String response, TurnExecutionGuard executionGuard) {
        boolean stopped = executionGuard.awaitStopped(Duration.ofSeconds(CANCEL_QUIESCE_TIMEOUT_SECONDS));
        return new TurnRunResult(response, "", stopped);
    }

    /**
     * 任务运行期间监听 ESC 按键。raw mode 下 ESC 字节是 0x1b（27）。
     *
     * 关键陷阱：方向键 / Home / End 等由 ESC + 控制序列组成（如 ESC[A），不能误判为单 ESC 取消。
     * 复用 {@link #readInputBurst} + {@link #classifyEscapeSequence}：
     * - STANDALONE_ESC（孤立的 ESC）→ 用户取消
     * - CONTROL_SEQUENCE / BRACKETED_PASTE / OTHER → 丢弃，不取消
     */
    static boolean readEscCancel(Terminal terminal) {
        if (terminal == null) {
            return false;
        }
        try {
            NonBlockingReader reader = terminal.reader();
            int next = reader.read(50);
            if (next == NonBlockingReader.READ_EXPIRED || next < 0) {
                return false;
            }
            String escTail = next == 27 ? readInputBurst(terminal, 80, 20, 120) : null;
            if (next != 27) {
                // 非 ESC 输入，drain 这一轮残余字节避免堆积，但不触发取消。
                while (true) {
                    int more = reader.read(1);
                    if (more == NonBlockingReader.READ_EXPIRED || more < 0) {
                        break;
                    }
                }
            }
            return decideEscCancel(next, escTail);
        } catch (Exception ignored) {
            // 监听是 best-effort；失败不能影响任务执行。
            return false;
        }
    }

    /**
     * ESC 取消判断的纯函数版（不依赖终端 IO，便于单测）。
     *
     * @param firstByte ESC=27 触发判断；其他字节直接返回 false
     * @param escTail  紧跟 ESC 之后的字节序列（不含 ESC 本身）；null / 空 → 单 ESC 取消
     */
    static boolean decideEscCancel(int firstByte, String escTail) {
        if (firstByte != 27) {
            return false;
        }
        return classifyEscapeSequence(escTail) == EscapeSequenceType.STANDALONE_ESC;
    }
}
