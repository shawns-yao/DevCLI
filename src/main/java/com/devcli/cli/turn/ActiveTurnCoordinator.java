package com.devcli.cli.turn;

import java.util.Objects;
import java.util.Optional;

/** 协调活动轮次输入、排队顺序和当前轮次取消边界。 */
public final class ActiveTurnCoordinator {
    private final PromptQueue queue;

    public ActiveTurnCoordinator(int capacity) {
        this.queue = new PromptQueue(capacity);
    }

    public Submission submit(String input, Runnable cancelCurrent) {
        ActiveTurnInput.Parsed parsed = ActiveTurnInput.parse(input);
        return switch (parsed.action()) {
            case QUEUE -> fromEnqueue(parsed.action(), queue.enqueue(parsed.text()));
            case INTERRUPT -> {
                PromptQueue.EnqueueResult result = queue.enqueueFirst(parsed.text());
                if (result.accepted()) {
                    Objects.requireNonNull(cancelCurrent, "cancelCurrent").run();
                }
                yield fromEnqueue(parsed.action(), result);
            }
            case CANCEL -> {
                Objects.requireNonNull(cancelCurrent, "cancelCurrent").run();
                yield new Submission(parsed.action(), true, queue.size(), "");
            }
            case IGNORE -> new Submission(parsed.action(), false, queue.size(), "输入为空");
        };
    }

    public Optional<PromptQueue.Entry> poll() {
        return queue.poll();
    }

    public int size() {
        return queue.size();
    }

    private Submission fromEnqueue(ActiveTurnInput.Action action, PromptQueue.EnqueueResult result) {
        return new Submission(action, result.accepted(), queue.size(), result.reason());
    }

    public record Submission(ActiveTurnInput.Action action, boolean accepted, int queueSize, String reason) {
        public boolean cancelledCurrent() {
            return accepted && (action == ActiveTurnInput.Action.INTERRUPT
                    || action == ActiveTurnInput.Action.CANCEL);
        }
    }
}
