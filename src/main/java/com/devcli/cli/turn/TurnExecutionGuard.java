package com.devcli.cli.turn;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 跟踪取消后执行线程是否已经真正退出，避免下一轮与旧轮次重叠。 */
public final class TurnExecutionGuard {
    private final CountDownLatch stopped = new CountDownLatch(1);

    public <T> Callable<T> wrap(Callable<T> task, Runnable onStopped) {
        Objects.requireNonNull(task, "task");
        Runnable completion = onStopped == null ? () -> { } : onStopped;
        return () -> {
            try {
                return task.call();
            } finally {
                try {
                    completion.run();
                } finally {
                    stopped.countDown();
                }
            }
        };
    }

    public boolean awaitStopped(Duration timeout) {
        Duration effective = timeout == null || timeout.isNegative() ? Duration.ZERO : timeout;
        try {
            return stopped.await(effective.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
