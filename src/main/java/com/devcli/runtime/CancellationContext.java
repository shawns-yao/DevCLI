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
    }
}
