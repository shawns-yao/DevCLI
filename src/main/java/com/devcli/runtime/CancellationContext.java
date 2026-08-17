package com.devcli.runtime;

import java.nio.file.Path;

/**
 * 当前线程绑定的运行上下文入口。
 *
 * <p>上下文只通过 InheritableThreadLocal 传播给运行期间创建的子线程，
 * 不再使用进程级全局回退，避免预先创建的后台线程读取其他任务的取消状态。</p>
 */
public final class CancellationContext {
    private static final InheritableThreadLocal<RunContext> LOCAL = new InheritableThreadLocal<>();
    private static final InheritableThreadLocal<CancellationToken> TOKEN_OVERRIDE =
            new InheritableThreadLocal<>();

    private CancellationContext() {
    }

    public static RunContext startRunContext(Path projectPath) {
        RunContext context = new RunContext(projectPath, LOCAL.get());
        LOCAL.set(context);
        return context;
    }

    /**
     * 兼容现有调用方。新代码应持有并关闭 {@link RunContext}。
     */
    public static CancellationToken startRun() {
        return startRunContext(Path.of(System.getProperty("user.dir"))).cancellationToken();
    }

    public static RunContext currentRun() {
        return LOCAL.get();
    }

    public static CancellationToken current() {
        CancellationToken override = TOKEN_OVERRIDE.get();
        if (override != null) {
            return override;
        }
        RunContext context = currentRun();
        return context == null ? null : context.cancellationToken();
    }

    public static boolean isCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        CancellationToken token = current();
        return token != null && token.isCancelled();
    }

    public static void throwIfCancelled() {
        CancellationToken token = current();
        if (token != null) {
            token.throwIfCancelled();
        } else if (Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException("线程已中断");
        }
    }

    /** 在当前线程及其新建子线程中临时使用工具调用自己的子令牌。 */
    public static TokenBinding bindToken(CancellationToken token) {
        CancellationToken previous = TOKEN_OVERRIDE.get();
        if (token == null) {
            TOKEN_OVERRIDE.remove();
        } else {
            TOKEN_OVERRIDE.set(token);
        }
        return new TokenBinding(previous);
    }

    public static final class TokenBinding implements AutoCloseable {
        private final CancellationToken previous;
        private boolean closed;

        private TokenBinding(CancellationToken previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                TOKEN_OVERRIDE.remove();
            } else {
                TOKEN_OVERRIDE.set(previous);
            }
        }
    }

    public static void clear(CancellationToken token) {
        RunContext context = currentRun();
        if (context != null && context.cancellationToken() == token) {
            clear(context);
        }
    }

    static void clear(RunContext context) {
        if (context == null || LOCAL.get() != context) {
            return;
        }
        RunContext previous = context.previous();
        if (previous == null) {
            LOCAL.remove();
        } else {
            LOCAL.set(previous);
        }
        TOKEN_OVERRIDE.remove();
    }

    /**
     * 将已有运行上下文绑定到另一个执行线程，返回该线程原先的上下文。
     * 调用方不得在这里关闭传入上下文，只负责在任务结束后恢复返回值。
     */
    static RunContext bind(RunContext context) {
        RunContext previous = LOCAL.get();
        if (context == null) {
            LOCAL.remove();
        } else {
            LOCAL.set(context);
        }
        return previous;
    }

    static void restore(RunContext context) {
        if (context == null) {
            LOCAL.remove();
        } else {
            LOCAL.set(context);
        }
    }
}
