package com.devcli.cli.turn;

import java.util.Locale;

/** 将活动轮次输入归一为排队、立即执行或取消语义。 */
public final class ActiveTurnInput {
    private ActiveTurnInput() {
    }

    public static Parsed parse(String input) {
        String normalized = input == null ? "" : input.trim();
        if (normalized.isEmpty()) {
            return new Parsed(Action.IGNORE, "");
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.equals("/cancel")) {
            return new Parsed(Action.CANCEL, "");
        }
        if (lower.equals("/now")) {
            return new Parsed(Action.IGNORE, "");
        }
        if (lower.startsWith("/now ")) {
            String prompt = normalized.substring(5).trim();
            return prompt.isEmpty()
                    ? new Parsed(Action.IGNORE, "")
                    : new Parsed(Action.INTERRUPT, prompt);
        }
        return new Parsed(Action.QUEUE, normalized);
    }

    public enum Action {
        QUEUE,
        INTERRUPT,
        CANCEL,
        IGNORE
    }

    public record Parsed(Action action, String text) {
    }
}
