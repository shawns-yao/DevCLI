package com.devcli.agent;

import com.devcli.llm.LlmClient;
import com.devcli.render.Renderer;
import com.devcli.runtime.event.RunEvent;
import com.devcli.runtime.event.RunEventSink;
import com.devcli.util.AnsiStyle;
import com.devcli.util.TerminalMarkdownRenderer;

import java.io.PrintStream;
import java.util.Objects;
import java.util.function.Supplier;

/** ReAct、Plan task 与 SubAgent 共用的流式展示状态机。 */
final class AgentStreamPresenter implements LlmClient.StreamListener, RunEventSink {
    private enum Kind {
        REACT,
        LABELED
    }

    private final Kind kind;
    private final Renderer renderer;
    private final Supplier<PrintStream> output;
    private final String reasoningHeading;
    private final String contentHeading;
    private final String lateReasoningHeading;
    private final Runnable streamedCallback;
    private final boolean waitForReasoningLine;
    private final boolean preserveReasoningHeading;
    private final boolean doubleFinalNewline;

    private final StringBuilder pendingReasoning = new StringBuilder();
    private final StringBuilder visibleReasoning = new StringBuilder();
    private final StringBuilder lateReasoning = new StringBuilder();
    private TerminalMarkdownRenderer reasoningRenderer;
    private TerminalMarkdownRenderer contentRenderer;
    private boolean reasoningHeadingPrinted;
    private boolean reasoningStarted;
    private boolean contentStarted;
    private boolean thinkingQuotePrinted;
    private boolean streamedOutput;

    private AgentStreamPresenter(Kind kind, Renderer renderer, Supplier<PrintStream> output,
                                 String reasoningHeading, String contentHeading,
                                 String lateReasoningHeading, Runnable streamedCallback,
                                 boolean waitForReasoningLine,
                                 boolean preserveReasoningHeading,
                                 boolean doubleFinalNewline) {
        this.kind = kind;
        this.renderer = renderer;
        this.output = Objects.requireNonNull(output, "output");
        this.reasoningHeading = reasoningHeading;
        this.contentHeading = contentHeading;
        this.lateReasoningHeading = lateReasoningHeading;
        this.streamedCallback = streamedCallback == null ? () -> { } : streamedCallback;
        this.waitForReasoningLine = waitForReasoningLine;
        this.preserveReasoningHeading = preserveReasoningHeading;
        this.doubleFinalNewline = doubleFinalNewline;
    }

    static AgentStreamPresenter react() {
        return react(() -> System.out, null);
    }

    static AgentStreamPresenter react(PrintStream out) {
        return react(() -> out, null);
    }

    static AgentStreamPresenter react(Renderer renderer) {
        return react(renderer == null ? () -> System.out : renderer::stream, renderer);
    }

    private static AgentStreamPresenter react(Supplier<PrintStream> out, Renderer renderer) {
        return new AgentStreamPresenter(
                Kind.REACT, renderer, out,
                "思考过程", "", "补充思考", null,
                true, true, false);
    }

    static AgentStreamPresenter task(String taskId, PrintStream out, Runnable streamedCallback) {
        String id = Objects.toString(taskId, "");
        return labeled(out,
                "任务思考 [" + id + "]",
                "任务输出 [" + id + "]",
                "补充思考 [" + id + "]",
                streamedCallback);
    }

    static AgentStreamPresenter subAgent(String agentName, AgentRole role, PrintStream out) {
        String name = Objects.toString(agentName, "");
        String reasoning = switch (role) {
            case PLANNER -> "规划思考";
            case WORKER -> "执行思考";
            case REVIEWER -> "审查思考";
        };
        String content = switch (role) {
            case PLANNER -> "规划结果";
            case WORKER -> "执行输出";
            case REVIEWER -> "审查输出";
        };
        return labeled(out,
                reasoning + " [" + name + "]",
                content + " [" + name + "]",
                "补充思考 [" + name + "]",
                null);
    }

    private static AgentStreamPresenter labeled(PrintStream out, String reasoning,
                                                 String content, String late,
                                                 Runnable streamedCallback) {
        return new AgentStreamPresenter(
                Kind.LABELED, null, () -> out,
                reasoning, content, late, streamedCallback,
                false, false, true);
    }

    void beginThinking() {
        if (hasThinkingPanel()) {
            renderer.beginThinking("Thinking");
        }
    }

    void clearThinkingPanel() {
        if (hasThinkingPanel()) {
            renderer.endThinking();
            pendingReasoning.setLength(0);
        }
    }

    @Override
    public synchronized void emit(RunEvent event) {
        if (event instanceof RunEvent.ReasoningDelta reasoning) {
            onReasoningDelta(reasoning.content());
        } else if (event instanceof RunEvent.MessageDelta message) {
            onContentDelta(message.content());
        } else if (event instanceof RunEvent.ToolCalls toolCalls && renderer != null) {
            renderer.appendToolCallEvents(toolCalls.calls());
        }
    }

    @Override
    public synchronized void onReasoningDelta(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        if (contentStarted) {
            lateReasoning.append(delta);
            return;
        }
        if (kind == Kind.REACT) {
            visibleReasoning.append(delta);
        }
        if (hasThinkingPanel()) {
            pendingReasoning.append(delta);
            if (pendingReasoning.toString().isBlank()) {
                return;
            }
            renderer.appendThinking(pendingReasoning.toString());
            pendingReasoning.setLength(0);
            reasoningStarted = true;
            return;
        }
        if (!reasoningStarted) {
            pendingReasoning.append(delta);
            if (pendingReasoning.toString().isBlank()) {
                return;
            }
            if (waitForReasoningLine && !containsLineBreak(pendingReasoning)) {
                return;
            }
            printReasoningHeadingIfNeeded();
            reasoningRenderer = new TerminalMarkdownRenderer(out());
            reasoningRenderer.append(pendingReasoning.toString());
            pendingReasoning.setLength(0);
            reasoningStarted = true;
            markStreamed();
        } else {
            reasoningRenderer.append(delta);
        }
        out().flush();
    }

    @Override
    public synchronized void onContentDelta(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        if (!contentStarted) {
            if (hasThinkingPanel()) {
                finishThinkingPanelAndPrintQuote();
            } else if (reasoningStarted && reasoningRenderer != null) {
                reasoningRenderer.finish();
                out().println();
            } else if (!pendingReasoning.toString().isBlank()) {
                printReasoningHeadingIfNeeded();
                TerminalMarkdownRenderer pendingRenderer = new TerminalMarkdownRenderer(out());
                pendingRenderer.append(pendingReasoning.toString());
                pendingRenderer.finish();
                out().println();
                pendingReasoning.setLength(0);
                reasoningStarted = true;
            }
            if (kind == Kind.REACT) {
                out().print(AnsiStyle.answerMarker() + " ");
            } else {
                out().println(AnsiStyle.section("🤖 " + contentHeading));
            }
            contentRenderer = new TerminalMarkdownRenderer(out());
            contentStarted = true;
            markStreamed();
        }
        contentRenderer.append(delta);
        out().flush();
    }

    synchronized boolean hasStreamedOutput() {
        return streamedOutput;
    }

    synchronized void resetBetweenIterations() {
        if (hasThinkingPanel()) {
            finishThinkingPanelAndPrintQuote();
        }
        if (reasoningRenderer != null) {
            reasoningRenderer.finish();
            reasoningRenderer = null;
        } else if (kind == Kind.REACT && !hasThinkingPanel()) {
            flushPendingReasoning();
        }
        if (contentRenderer != null) {
            contentRenderer.finish();
            contentRenderer = null;
        }
        flushLateReasoning();
        pendingReasoning.setLength(0);
        visibleReasoning.setLength(0);
        reasoningStarted = false;
        contentStarted = false;
        thinkingQuotePrinted = false;
        if (!preserveReasoningHeading) {
            reasoningHeadingPrinted = false;
        }
        if (streamedOutput) {
            out().println();
        }
    }

    synchronized void finish() {
        if (hasThinkingPanel()) {
            finishThinkingPanelAndPrintQuote();
        }
        if (reasoningRenderer != null) {
            reasoningRenderer.finish();
        } else if (kind == Kind.REACT && !hasThinkingPanel()) {
            flushPendingReasoning();
        }
        if (contentRenderer != null) {
            contentRenderer.finish();
        }
        flushLateReasoning();
        if (streamedOutput) {
            out().println(doubleFinalNewline ? "\n" : "");
        }
    }

    private void flushLateReasoning() {
        String late = lateReasoning.toString().trim();
        if (late.isEmpty()) {
            lateReasoning.setLength(0);
            return;
        }
        out().println();
        out().println(AnsiStyle.heading("🧠 " + lateReasoningHeading));
        TerminalMarkdownRenderer lateRenderer = new TerminalMarkdownRenderer(out());
        lateRenderer.append(late);
        lateRenderer.finish();
        lateReasoning.setLength(0);
        markStreamed();
    }

    private void flushPendingReasoning() {
        String pending = pendingReasoning.toString();
        if (pending.isBlank()) {
            pendingReasoning.setLength(0);
            return;
        }
        printReasoningHeadingIfNeeded();
        TerminalMarkdownRenderer pendingRenderer = new TerminalMarkdownRenderer(out());
        pendingRenderer.append(pending);
        pendingRenderer.finish();
        pendingReasoning.setLength(0);
        markStreamed();
    }

    private void finishThinkingPanelAndPrintQuote() {
        if (!hasThinkingPanel()) {
            return;
        }
        if (!pendingReasoning.toString().isBlank()) {
            renderer.appendThinking(pendingReasoning.toString());
        }
        renderer.endThinking();
        pendingReasoning.setLength(0);
        printThinkingQuoteIfNeeded();
    }

    private void printThinkingQuoteIfNeeded() {
        if (thinkingQuotePrinted) {
            return;
        }
        String reasoning = visibleReasoning.toString()
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
        if (reasoning.isEmpty()) {
            return;
        }
        out().println(AnsiStyle.thinking("Thinking..."));
        for (String line : reasoning.split("\\R+")) {
            String normalized = line.replaceAll("\\s+", " ").trim();
            if (!normalized.isEmpty()) {
                out().println(AnsiStyle.subtle("│ " + normalized));
            }
        }
        out().println();
        thinkingQuotePrinted = true;
        markStreamed();
    }

    private void printReasoningHeadingIfNeeded() {
        if (reasoningHeadingPrinted) {
            return;
        }
        out().println(AnsiStyle.heading("🧠 " + reasoningHeading));
        reasoningHeadingPrinted = true;
    }

    private void markStreamed() {
        streamedOutput = true;
        streamedCallback.run();
    }

    private boolean hasThinkingPanel() {
        return renderer != null && renderer.supportsThinkingPanel();
    }

    private PrintStream out() {
        PrintStream value = output.get();
        return value == null ? System.out : value;
    }

    private static boolean containsLineBreak(CharSequence content) {
        for (int index = 0; index < content.length(); index++) {
            char ch = content.charAt(index);
            if (ch == '\n' || ch == '\r') {
                return true;
            }
        }
        return false;
    }
}
