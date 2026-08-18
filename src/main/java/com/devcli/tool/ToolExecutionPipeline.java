package com.devcli.tool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工具执行中间件管线。阶段顺序固定，同阶段按注册顺序执行。
 */
public final class ToolExecutionPipeline {

    public enum Stage {
        CANCELLATION,
        EXISTENCE,
        CAPABILITY,
        SKILL_PERMISSION,
        ARGUMENT_VALIDATION,
        HITL,
        AUDIT,
        POLICY,
        RESULT_CACHE,
        RESULT_GOVERNANCE
    }

    @FunctionalInterface
    public interface Middleware {
        ToolOutput execute(Context context, Chain chain);
    }

    @FunctionalInterface
    public interface Chain {
        ToolOutput proceed(Context context);
    }

    @FunctionalInterface
    public interface Terminal {
        ToolOutput execute(Context context);
    }

    public static final class Context {
        private final String name;
        private final String invocationId;
        private final ToolExecutionContext executionContext;
        private String argumentsJson;
        private final Map<String, Object> attributes = new HashMap<>();

        private Context(String name, String argumentsJson, String invocationId,
                        ToolExecutionContext executionContext) {
            this.name = name;
            this.argumentsJson = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
            this.invocationId = invocationId;
            this.executionContext = Objects.requireNonNull(executionContext, "executionContext");
        }

        public String name() {
            return name;
        }

        public String argumentsJson() {
            return argumentsJson;
        }

        public String invocationId() {
            return invocationId;
        }

        public ToolExecutionContext executionContext() {
            return executionContext;
        }

        public void replaceArguments(String argumentsJson) {
            this.argumentsJson = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
            attributes.clear();
        }

        public void putAttribute(String name, Object value) {
            if (name == null || name.isBlank()) {
                return;
            }
            if (value == null) {
                attributes.remove(name);
            } else {
                attributes.put(name, value);
            }
        }

        public <T> T attribute(String name, Class<T> type) {
            Object value = attributes.get(name);
            return type.isInstance(value) ? type.cast(value) : null;
        }
    }

    private record RegisteredMiddleware(Stage stage, long sequence, Middleware middleware) {
    }

    private final Terminal terminal;
    private final AtomicLong sequence = new AtomicLong();
    private final List<RegisteredMiddleware> middleware = new ArrayList<>();

    public ToolExecutionPipeline(Terminal terminal) {
        this.terminal = Objects.requireNonNull(terminal, "terminal");
    }

    public synchronized void register(Stage stage, Middleware item) {
        middleware.add(new RegisteredMiddleware(
                Objects.requireNonNull(stage, "stage"),
                sequence.getAndIncrement(),
                Objects.requireNonNull(item, "middleware")));
        middleware.sort(Comparator
                .comparing(RegisteredMiddleware::stage)
                .thenComparingLong(RegisteredMiddleware::sequence));
    }

    public ToolOutput execute(String name, String argumentsJson, String invocationId) {
        return execute(name, argumentsJson, invocationId,
                ToolExecutionContext.current(invocationId));
    }

    public ToolOutput execute(String name, String argumentsJson, String invocationId,
                              ToolExecutionContext executionContext) {
        List<RegisteredMiddleware> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(middleware);
        }
        return proceed(snapshot, 0, new Context(
                name, argumentsJson, invocationId, executionContext));
    }

    private ToolOutput proceed(List<RegisteredMiddleware> snapshot, int index, Context context) {
        if (index >= snapshot.size()) {
            return normalize(terminal.execute(context));
        }
        RegisteredMiddleware current = snapshot.get(index);
        return normalize(current.middleware().execute(context,
                nextContext -> proceed(snapshot, index + 1, nextContext)));
    }

    private static ToolOutput normalize(ToolOutput output) {
        return output == null ? ToolOutput.success("") : output;
    }
}
